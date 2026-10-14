package com.pixelraw.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * JPEG post-processing. Runs only when needed:
 *  - digital zoom beyond the hardware limit (crop + upscale + mild unsharp mask)
 *  - a colour look other than "Pure"
 * If neither applies, the original JPEG bytes are saved untouched.
 */
object Processing {

    fun process(jpeg: ByteArray, extraZoom: Float, look: Look, enhance: Boolean): ByteArray {
        val zoomed = extraZoom > 1.001f
        if (!zoomed && look == Look.NONE) return jpeg

        val orientation = ExifInterface(ByteArrayInputStream(jpeg))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        var bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)

        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            val m = Matrix().apply { postRotate(deg) }
            bmp = replace(bmp, Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true))
        }

        if (zoomed) {
            val w = bmp.width
            val h = bmp.height
            val cw = (w / extraZoom).toInt().coerceAtLeast(32)
            val ch = (h / extraZoom).toInt().coerceAtLeast(32)
            val crop = Bitmap.createBitmap(bmp, (w - cw) / 2, (h - ch) / 2, cw, ch)
            val up = Bitmap.createScaledBitmap(crop, w, h, true)
            if (up !== crop) crop.recycle()
            bmp = replace(bmp, up)
            if (enhance && w.toLong() * h <= 20_000_000L) bmp = replace(bmp, unsharp(bmp))
        }

        if (look != Look.NONE) bmp = replace(bmp, applyLook(bmp, look))

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 97, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun replace(old: Bitmap, new: Bitmap): Bitmap {
        if (old !== new) old.recycle()
        return new
    }

    private fun applyLook(src: Bitmap, look: Look): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(look.matrix()) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** Unsharp mask: sharpens existing edges. It cannot recover detail the sensor never captured. */
    private fun unsharp(src: Bitmap, amount: Float = 0.6f): Bitmap {
        val w = src.width
        val h = src.height
        val small = Bitmap.createScaledBitmap(src, w / 3, h / 3, true)
        val blur = Bitmap.createScaledBitmap(small, w, h, true)
        if (small !== blur) small.recycle()
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        blur.getPixels(b, 0, w, 0, 0, w, h)
        blur.recycle()
        for (i in a.indices) {
            val p = a[i]
            val q = b[i]
            val pr = (p shr 16) and 255
            val pg = (p shr 8) and 255
            val pb = p and 255
            val r = clamp(pr + amount * (pr - ((q shr 16) and 255)))
            val g = clamp(pg + amount * (pg - ((q shr 8) and 255)))
            val bl = clamp(pb + amount * (pb - (q and 255)))
            a[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    private fun clamp(v: Float): Int = if (v < 0f) 0 else if (v > 255f) 255 else v.toInt()
}
