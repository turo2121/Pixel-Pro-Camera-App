package com.pixelraw.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

enum class SaveMode(val label: String) { JPEG("JPG"), RAW("DNG"), BOTH("JPG+DNG") }

/**
 * Camera2 engine. Everything here uses public Android APIs: it exposes what the Pixel's camera HAL
 * offers to third-party apps (manual sensor controls, RAW_SENSOR, zoom ratio, flash, OIS) and
 * picks the largest output size the device advertises.
 */
class CameraController(private val ctx: Context, private val onStatus: (String) -> Unit) {

    class Info(
        val isoMin: Int, val isoMax: Int,
        val expMin: Long, val expMax: Long,
        val minFocus: Float,
        val zoomMin: Float, val zoomMax: Float,
        val jpegSize: Size, val rawSize: Size?, val maxResRawSize: Size?,
        val hasOis: Boolean, val hasFlash: Boolean, val sensorOrientation: Int
    ) {
        val hasMaxRes: Boolean get() = maxResRawSize != null
    }

    // ---- user settings (written by the UI) ----
    var front = false
    var manual = false
    var iso = 100
    var exposureNs = 8_000_000L          // 1/125 s
    var focusDiopters = 0f               // 0 = infinity
    var zoom = 1f                        // total requested zoom (can exceed the hardware max)
    var flashPulse = false
    var saveMode = SaveMode.BOTH
    var maxRes = false
    var enhanceZoom = true
    var look = Look.VIVID_SKIN

    var info: Info? = null
        private set

    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var chars: CameraCharacteristics? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var maxRawReader: ImageReader? = null
    @Volatile private var pending: Pending? = null

    // ------------------------------------------------------------------ lifecycle

    @SuppressLint("MissingPermission")
    fun start(texture: SurfaceTexture, onPreviewSize: (Size) -> Unit) {
        stop()
        val t = HandlerThread("camera").also { it.start() }
        thread = t
        handler = Handler(t.looper)

        val id = pickCamera()
        val c = manager.getCameraCharacteristics(id)
        chars = c
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val preview = choosePreview(map)
        texture.setDefaultBufferSize(preview.width, preview.height)
        onPreviewSize(preview)
        previewSurface = Surface(texture)
        info = buildInfo(c, map)

        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { device = d; createSession() }
            override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
            override fun onError(d: CameraDevice, error: Int) {
                d.close(); device = null; onStatus("Camera error $error")
            }
        }, handler)
    }

    fun stop() {
        try { session?.close() } catch (ignored: Exception) {}
        session = null
        device?.close(); device = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
        maxRawReader?.close(); maxRawReader = null
        previewSurface?.release(); previewSurface = null
        pending?.raw?.close(); pending = null
        thread?.quitSafely(); thread = null
        handler = null
    }

    fun reconfigure() {
        handler?.post {
            try { session?.close() } catch (ignored: Exception) {}
            session = null
            createSession()
        }
    }

    // ------------------------------------------------------------------ setup helpers

    private fun pickCamera(): String {
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        // Prefer the logical multi-camera (widest zoom range) so 0.5x..30x works through CONTROL_ZOOM_RATIO.
        return manager.cameraIdList
            .filter { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
            .maxByOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f }
            ?: error("No camera found")
    }

    private fun choosePreview(map: StreamConfigurationMap): Size {
        val sizes = map.getOutputSizes(SurfaceTexture::class.java).toList()
        return sizes.filter { it.width * 3 == it.height * 4 && it.width <= 1920 }.maxByOrNull { it.width }
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: sizes.first()
    }

    private fun largest(s: Array<Size>?): Size? = s?.maxByOrNull { it.width.toLong() * it.height }

    private fun buildInfo(c: CameraCharacteristics, map: StreamConfigurationMap): Info {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val hasRaw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val hasUhr = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val maxMap = if (hasUhr) c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) else null
        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        return Info(
            isoMin = iso?.lower ?: 100, isoMax = iso?.upper ?: 100,
            expMin = exp?.lower ?: 1_000_000L, expMax = exp?.upper ?: 1_000_000L,
            minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            zoomMin = zr?.lower ?: 1f, zoomMax = zr?.upper ?: 1f,
            jpegSize = largest(map.getOutputSizes(ImageFormat.JPEG)) ?: Size(4000, 3000),
            rawSize = if (hasRaw) largest(map.getOutputSizes(ImageFormat.RAW_SENSOR)) else null,
            maxResRawSize = if (hasRaw) largest(maxMap?.getOutputSizes(ImageFormat.RAW_SENSOR)) else null,
            hasOis = ois?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
            hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        )
    }

    private fun createSession(useMax: Boolean = maxRes && info?.hasMaxRes == true) {
        val d = device ?: return
        val i = info ?: return
        val h = handler ?: return
        jpegReader?.close(); rawReader?.close(); maxRawReader?.close()

        jpegReader = ImageReader.newInstance(i.jpegSize.width, i.jpegSize.height, ImageFormat.JPEG, 2).also {
            it.setOnImageAvailableListener({ r -> onJpeg(r) }, h)
        }
        rawReader = i.rawSize?.let { s ->
            ImageReader.newInstance(s.width, s.height, ImageFormat.RAW_SENSOR, 2).also {
                it.setOnImageAvailableListener({ r -> onRaw(r, false) }, h)
            }
        }
        maxRawReader = if (useMax) i.maxResRawSize?.let { s ->
            ImageReader.newInstance(s.width, s.height, ImageFormat.RAW_SENSOR, 1).also {
                it.setOnImageAvailableListener({ r -> onRaw(r, true) }, h)
            }
        } else null

        val outs = mutableListOf(
            OutputConfiguration(previewSurface ?: return),
            OutputConfiguration(jpegReader!!.surface)
        )
        rawReader?.let { outs += OutputConfiguration(it.surface) }
        maxRawReader?.let {
            outs += OutputConfiguration(it.surface).apply {
                addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            }
        }

        val executor = Executor { r -> h.post(r) }
        val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; updatePreview() }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (useMax) {
                        maxRes = false
                        onStatus("Max-res mode rejected by the camera HAL; using standard sizes")
                        createSession(false)
                    } else onStatus("Camera session failed")
                }
            })
        d.createCaptureSession(cfg)
    }

    // ------------------------------------------------------------------ requests

    private fun applyCommon(b: CaptureRequest.Builder, withZoom: Boolean = true) {
        val i = info ?: return
        if (withZoom) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.coerceIn(i.zoomMin, i.zoomMax))
        if (manual) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(i.isoMin, i.isoMax))
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs.coerceIn(i.expMin, i.expMax))
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters.coerceIn(0f, i.minFocus))
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (i.hasOis) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
    }

    fun updatePreview() {
        val s = session ?: return
        val d = device ?: return
        val surface = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            applyCommon(b)
            b.addTarget(surface)
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (e: Exception) {
            onStatus("Preview error: ${e.message}")
        }
    }

    /** "Pure" JPEG: no extra sharpening, minimal noise reduction (only if the HAL allows it). */
    private fun pureProcessing(b: CaptureRequest.Builder) {
        val c = chars ?: return
        if (c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.contains(CameraMetadata.EDGE_MODE_OFF) == true) {
            b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        }
        if (c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                ?.contains(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) == true) {
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
        }
    }

    fun capture() {
        val s = session ?: return
        val d = device ?: return
        val i = info ?: return
        if (pending != null) { onStatus("Still saving the previous shot…"); return }

        val wantRaw = saveMode != SaveMode.JPEG
        val wantJpeg = saveMode != SaveMode.RAW
        val useMax = wantRaw && maxRawReader != null
        if (wantRaw && !useMax && rawReader == null) { onStatus("RAW is not available on this camera"); return }

        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            applyCommon(b, withZoom = !useMax)
            if (flashPulse && i.hasFlash && !front) {
                b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)   // one full flash pulse during exposure
            }
            if (useMax) {
                // Max-res mode: DNG only (JPEG and preview use the default pixel mode and can't share this request)
                b.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                b.addTarget(maxRawReader!!.surface)
            } else {
                if (wantJpeg) {
                    b.set(CaptureRequest.JPEG_ORIENTATION, i.sensorOrientation)
                    b.set(CaptureRequest.JPEG_QUALITY, 97.toByte())
                    if (look == Look.NONE) pureProcessing(b)
                    b.addTarget(jpegReader!!.surface)
                }
                if (wantRaw) b.addTarget(rawReader!!.surface)
            }
            val extra = if (useMax) 1f else (zoom / i.zoomMax).coerceAtLeast(1f)
            val p = Pending(wantJpeg && !useMax, wantRaw, useMax, extra, look, enhanceZoom)
            pending = p
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) { p.result = result; p.tryFinish() }

                override fun onCaptureFailed(
                    session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure
                ) { p.abort(); onStatus("Capture failed") }
            }, handler)
        } catch (e: Exception) {
            pending = null
            onStatus("Capture error: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ image delivery + saving

    private fun onJpeg(r: ImageReader) {
        val img = r.acquireNextImage() ?: return
        val p = pending
        if (p == null || !p.needJpeg) { img.close(); return }
        val buf = img.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        img.close()
        p.jpeg = bytes
        p.tryFinish()
    }

    private fun onRaw(r: ImageReader, max: Boolean) {
        val img = r.acquireNextImage() ?: return
        val p = pending
        if (p == null || !p.needRaw || p.maxRes != max) { img.close(); return }
        p.raw = img
        p.tryFinish()
    }

    private fun exifOrientation() = when (info?.sensorOrientation) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun save(name: String, mime: String, write: (OutputStream) -> Unit) {
        val r = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PixelRAW")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        r.openOutputStream(uri)?.use(write)
        r.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }

    private inner class Pending(
        val needJpeg: Boolean, val needRaw: Boolean, val maxRes: Boolean,
        val extraZoom: Float, val look: Look, val enhance: Boolean
    ) {
        var result: TotalCaptureResult? = null
        var jpeg: ByteArray? = null
        var raw: Image? = null

        fun tryFinish() {
            val res = result ?: return
            if (needJpeg && jpeg == null) return
            if (needRaw && raw == null) return
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            try {
                jpeg?.let { bytes ->
                    val out = Processing.process(bytes, extraZoom, look, enhance)
                    save("PXR_$stamp.jpg", "image/jpeg") { it.write(out) }
                }
                raw?.let { img ->
                    save("PXR_$stamp.dng", "image/x-adobe-dng") { os ->
                        val dng = DngCreator(chars!!, res)
                        try {
                            dng.setOrientation(exifOrientation())
                            dng.writeImage(os, img)
                        } finally { dng.close() }
                    }
                }
                onStatus("Saved PXR_$stamp")
            } catch (e: Throwable) {
                onStatus("Save failed: ${e.message}")
            } finally {
                raw?.close()
                pending = null
            }
        }

        fun abort() { raw?.close(); pending = null }
    }
}
