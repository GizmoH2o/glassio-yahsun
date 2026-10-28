package com.glasslauncher.tv.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** One blurred rounded shape drawn behind every card (scaled): a soft shadow that never bands on the TV. */
internal object CardShadow {
    const val PAD = 0.12f
    private var img: ImageBitmap? = null
    fun get(): ImageBitmap = img ?: run {
        val w = 320; val h = 180; val pad = (w * PAD).toInt()
        val b = android.graphics.Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, android.graphics.Bitmap.Config.ARGB_8888)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.DITHER_FLAG).apply {
            color = android.graphics.Color.argb(170, 0, 0, 0)
            maskFilter = android.graphics.BlurMaskFilter(pad * 0.8f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        android.graphics.Canvas(b).drawRoundRect(pad + 6f, pad + 10f, pad + w - 6f, pad + h.toFloat(), 18f, 18f, p)
        b.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { b.recycle(); it.prepareToDraw() }.asImageBitmap().also { img = it }
    }
}
