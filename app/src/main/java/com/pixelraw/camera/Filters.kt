package com.pixelraw.camera

import android.graphics.ColorMatrix

/** Colour looks. Used for the live preview (GPU) and baked into JPEGs only; DNG files are never touched. */
enum class Look(val label: String) {
    NONE("Pure"),
    VIVID_SKIN("Vivid skin"),
    CINEMATIC("Cinematic"),
    VINTAGE("Vintage"),
    BW_FLAT("B&W flat"),
    BW_CONTRAST("B&W contrast");

    fun matrix(): ColorMatrix = when (this) {
        NONE -> ColorMatrix()
        // slightly livelier colour + slightly warmer + very slightly stronger contrast
        VIVID_SKIN -> chain(sat(1.08f), warm(1.03f, 0.98f), contrast(1.06f))
        CINEMATIC -> chain(sat(0.92f), tealOrange(), contrast(1.12f))
        VINTAGE -> chain(sat(0.80f), warm(1.08f, 0.90f), contrast(0.92f), lift(14f))
        BW_FLAT -> chain(sat(0f), contrast(0.82f), lift(10f))
        BW_CONTRAST -> chain(sat(0f), contrast(1.45f))
    }
}

private fun sat(s: Float) = ColorMatrix().apply { setSaturation(s) }

private fun contrast(c: Float): ColorMatrix {
    val t = (1f - c) * 127.5f
    return ColorMatrix(floatArrayOf(
        c, 0f, 0f, 0f, t,
        0f, c, 0f, 0f, t,
        0f, 0f, c, 0f, t,
        0f, 0f, 0f, 1f, 0f))
}

private fun warm(r: Float, b: Float) = ColorMatrix(floatArrayOf(
    r, 0f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f, 0f,
    0f, 0f, b, 0f, 0f,
    0f, 0f, 0f, 1f, 0f))

private fun lift(o: Float) = ColorMatrix(floatArrayOf(
    1f, 0f, 0f, 0f, o,
    0f, 1f, 0f, 0f, o,
    0f, 0f, 1f, 0f, o,
    0f, 0f, 0f, 1f, 0f))

private fun tealOrange() = ColorMatrix(floatArrayOf(
    1.06f, 0f, -0.02f, 0f, 4f,
    0f, 1.00f, 0.02f, 0f, 0f,
    -0.04f, 0.04f, 0.96f, 0f, 6f,
    0f, 0f, 0f, 1f, 0f))

private fun chain(vararg m: ColorMatrix) = ColorMatrix().apply { m.forEach { postConcat(it) } }
