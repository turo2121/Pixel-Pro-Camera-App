package com.pixelraw.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.PowerManager
import android.util.Size
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {

    private lateinit var cam: CameraController
    private lateinit var tv: TextureView
    private lateinit var box: FrameLayout
    private lateinit var grid: GridView
    private lateinit var hud: TextView
    private lateinit var manualPanel: LinearLayout
    private lateinit var isoBar: SeekBar
    private lateinit var shutterBar: SeekBar
    private lateinit var focusBar: SeekBar
    private val lensButtons = LinkedHashMap<Float, Button>()
    private val refreshers = mutableListOf<() -> Unit>()
    private var surfaceReady = false
    private val amber = Color.parseColor("#FFB300")

    // Thermal guard: when the phone warms up, heavy CPU post-processing is switched off.
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
            cam.enhanceZoom = false
            toast("Phone is warm: zoom enhancement disabled")
            refreshers.forEach { it() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cam = CameraController(this) { msg -> runOnUiThread { toast(msg) } }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        tv = TextureView(this)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { surfaceReady = true; startCamera() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { surfaceReady = false; cam.stop(); return true }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        grid = GridView(this).apply { visibility = View.GONE }
        box = FrameLayout(this).apply {
            clipChildren = true
            addView(tv, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        root.addView(box, FrameLayout.LayoutParams(MATCH, dp(400), Gravity.TOP))

        hud = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 11f
            setPadding(dp(8), dp(4), dp(8), dp(4)); setBackgroundColor(0x66000000)
        }
        root.addView(hud, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP))

        // ---- lens row
        for (z in listOf(0.5f, 1f, 2f, 5f, 10f, 30f, 100f)) {
            lensButtons[z] = button(if (z < 1f) "0.5×" else "${z.toInt()}×") { setZoom(z) }
        }

        // ---- option toggles
        val auto = toggle({ if (cam.manual) "MANUAL" else "AUTO" }) {
            cam.manual = !cam.manual
            manualPanel.visibility = if (cam.manual) View.VISIBLE else View.GONE
            cam.updatePreview()
        }
        val fmt = toggle({ cam.saveMode.label }) {
            cam.saveMode = SaveMode.values()[(cam.saveMode.ordinal + 1) % SaveMode.values().size]
        }
        val flash = toggle({ if (cam.flashPulse) "STROBE ON" else "STROBE OFF" }) { cam.flashPulse = !cam.flashPulse }
        val look = toggle({ cam.look.label }) {
            val all = Look.values()
            cam.look = all[(cam.look.ordinal + 1) % all.size]
            applyLookToPreview()
        }
        val enh = toggle({ if (cam.enhanceZoom) "ZOOM+ ON" else "ZOOM+ OFF" }) { cam.enhanceZoom = !cam.enhanceZoom }
        val maxRes = toggle({ if (cam.maxRes) "MAX-RES ON" else "MAX-RES OFF" }) { toggleMaxRes() }
        val gridBtn = toggle({ if (grid.visibility == View.VISIBLE) "GRID ON" else "GRID OFF" }) {
            grid.visibility = if (grid.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        val selfie = button("SELFIE") { cam.front = !cam.front; cam.zoom = 1f; cam.stop(); startCamera() }

        // ---- manual sliders
        isoBar = bar { p ->
            val i = cam.info ?: return@bar
            cam.iso = logValue(p, i.isoMin.toDouble(), i.isoMax.toDouble()).roundToInt()
            cam.updatePreview(); refreshHud()
        }
        shutterBar = bar { p ->
            val i = cam.info ?: return@bar
            cam.exposureNs = logValue(p, i.expMin.toDouble(), i.expMax.toDouble()).toLong()
            cam.updatePreview(); refreshHud()
        }
        focusBar = bar { p ->
            val i = cam.info ?: return@bar
            cam.focusDiopters = p / 1000f * i.minFocus
            cam.updatePreview(); refreshHud()
        }
        manualPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), 0, dp(12), 0)
            addView(manualRow("ISO", isoBar))
            addView(manualRow("Shutter", shutterBar))
            addView(manualRow("Focus", focusBar))
        }

        val shutter = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(4), Color.parseColor("#888888"))
            }
            setOnClickListener { cam.capture() }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA000000.toInt())
            addView(row(*lensButtons.values.toTypedArray()))
            addView(row(auto, fmt, flash, look, enh, maxRes, gridBtn, selfie))
            addView(manualPanel)
            addView(shutter, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(6); bottomMargin = dp(10)
            })
        }
        root.addView(controls, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        getSystemService(PowerManager::class.java).addThermalStatusListener(mainExecutor, thermalListener)
        startCamera()
    }

    override fun onPause() {
        super.onPause()
        getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener)
        cam.stop()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        // onResume() restarts the camera right after this callback, so only report a refusal here.
        if (res.firstOrNull() != PackageManager.PERMISSION_GRANTED) toast("Camera permission is required")
    }

    // ------------------------------------------------------------------ camera glue

    private fun startCamera() {
        if (!surfaceReady) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        val st = tv.surfaceTexture ?: return
        try {
            cam.start(st) { size -> fitPreview(size) }
        } catch (e: Exception) {
            toast("Camera error: ${e.message}")
            return
        }
        applyLookToPreview()
        syncManualBars()
        setZoom(cam.zoom)
        refreshers.forEach { it() }
        refreshHud()
    }

    private fun fitPreview(size: Size) {
        // Portrait UI: the sensor buffer is landscape, so the view's aspect is height:width.
        val w = resources.displayMetrics.widthPixels
        val h = w * size.width / size.height
        box.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.TOP)
        box.requestLayout()
    }

    private fun applyLookToPreview() {
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cam.look.matrix()) }
        tv.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }

    private fun setZoom(z: Float) {
        cam.zoom = z
        val i = cam.info
        if (i != null) {
            // Beyond the hardware zoom limit the preview is cropped digitally; the JPEG is cropped + enhanced on save.
            val extra = (z / i.zoomMax).coerceAtLeast(1f)
            tv.scaleX = extra
            tv.scaleY = extra
        }
        cam.updatePreview()
        lensButtons.forEach { (v, b) -> b.setTextColor(if (v == z) amber else Color.WHITE) }
        refreshHud()
    }

    private fun toggleMaxRes() {
        val i = cam.info ?: return
        if (!i.hasMaxRes) {
            toast("This camera does not expose a max-resolution mode to apps")
            return
        }
        cam.maxRes = !cam.maxRes
        cam.reconfigure()
    }

    private fun syncManualBars() {
        val i = cam.info ?: return
        cam.iso = cam.iso.coerceIn(i.isoMin, i.isoMax)
        cam.exposureNs = cam.exposureNs.coerceIn(i.expMin, i.expMax)
        isoBar.progress = logPos(cam.iso.toDouble(), i.isoMin.toDouble(), i.isoMax.toDouble())
        shutterBar.progress = logPos(cam.exposureNs.toDouble(), i.expMin.toDouble(), i.expMax.toDouble())
        focusBar.progress = if (i.minFocus > 0f) (cam.focusDiopters / i.minFocus * 1000).toInt().coerceIn(0, 1000) else 0
    }

    private fun refreshHud() {
        val i = cam.info ?: return
        val mp = { s: Size? -> if (s == null) "n/a" else "%.1fMP".format(s.width * s.height / 1e6) }
        val focus = if (cam.focusDiopters < 0.05f) "∞" else "%.0fcm".format(100f / cam.focusDiopters)
        val exposure = if (cam.manual) "ISO ${cam.iso} · ${shutterLabel(cam.exposureNs)} · $focus" else "AUTO"
        val max = if (cam.maxRes && i.maxResRawSize != null) " · MAX ${mp(i.maxResRawSize)}" else ""
        hud.text = "%.1f× · %s\nJPG %s · RAW %s%s · %s".format(cam.zoom, exposure, mp(i.jpegSize), mp(i.rawSize), max, cam.look.label)
    }

    // ------------------------------------------------------------------ tiny UI helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun button(text: String, onClick: (Button) -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.WHITE)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply { setColor(0x55FFFFFF); cornerRadius = dp(16).toFloat() }
        setOnClickListener { onClick(this) }
    }

    private fun toggle(label: () -> String, action: () -> Unit): Button {
        val b = button(label()) { btn -> action(); btn.text = label(); refreshHud() }
        refreshers += { b.text = label() }
        return b
    }

    private fun row(vararg v: View): HorizontalScrollView {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        v.forEach { l.addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }) }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(l) }
    }

    private fun bar(onChange: (Int) -> Unit) = SeekBar(this).apply {
        max = 1000
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun manualRow(title: String, bar: SeekBar) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply { text = title; setTextColor(Color.WHITE); textSize = 12f },
            LinearLayout.LayoutParams(dp(64), WRAP))
        addView(bar, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    private fun logValue(p: Int, lo: Double, hi: Double) = lo * (hi / lo).pow(p / 1000.0)
    private fun logPos(v: Double, lo: Double, hi: Double) =
        if (hi <= lo) 0 else (1000 * ln(v / lo) / ln(hi / lo)).toInt().coerceIn(0, 1000)

    private fun shutterLabel(ns: Long): String {
        val s = ns / 1e9
        return if (s >= 0.5) "%.1fs".format(s) else "1/${(1 / s).roundToInt()}"
    }

    /** Rule-of-thirds grid. */
    private class GridView(c: Context) : View(c) {
        private val p = Paint().apply { color = 0x66FFFFFF; strokeWidth = 2f }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            for (k in 1..2) {
                canvas.drawLine(w * k / 3, 0f, w * k / 3, h, p)
                canvas.drawLine(0f, h * k / 3, w, h * k / 3, p)
            }
        }
    }
}
