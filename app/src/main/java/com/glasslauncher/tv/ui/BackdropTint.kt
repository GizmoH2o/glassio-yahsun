package com.glasslauncher.tv.ui

/**
 * The page's colour from a title's backdrop (Settings › Look & motion › Colour from the picture): the average of the
 * picture's colourful parts, the more colourful the more they count, darkened until white text reads on it at any
 * strength. Null, for the usual neutral page, when too little of the picture has any colour.
 */
object BackdropTint {
    /** The tint's brightest channel, out of 255. */
    private const val TOP = 44f
    /** Darker than this a pixel's colour can't be told; greyer than this it has none. */
    private const val MIN_BRIGHT = 40
    private const val MIN_COLOUR = 0.25f
    /** How much of the picture has to have colour. */
    private const val MIN_SHARE = 0.08f

    /** [pixels] are ARGB; the result is opaque ARGB. */
    fun of(pixels: IntArray): Int? {
        var red = 0f; var green = 0f; var blue = 0f
        var colourful = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val hi = maxOf(r, g, b)
            if (hi < MIN_BRIGHT) continue
            val colour = (hi - minOf(r, g, b)) / hi.toFloat()
            if (colour < MIN_COLOUR) continue
            // By their share of the brightest channel, so a dim part counts as much as a bright one of that colour.
            red += r * colour / hi; green += g * colour / hi; blue += b * colour / hi
            colourful++
        }
        if (colourful == 0 || colourful < pixels.size * MIN_SHARE) return null
        val scale = TOP / maxOf(red, green, blue)
        fun part(v: Float) = Math.round(v * scale)
        return (0xFF shl 24) or (part(red) shl 16) or (part(green) shl 8) or part(blue)
    }

    /** From a picture, read small (off the main thread). */
    fun of(picture: android.graphics.Bitmap): Int? = runCatching {
        val soft = if (picture.config == android.graphics.Bitmap.Config.HARDWARE) picture.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else picture
        val small = android.graphics.Bitmap.createScaledBitmap(soft, 32, 18, true)
        of(IntArray(32 * 18).also { small.getPixels(it, 0, 32, 0, 0, 32, 18) })
    }.getOrNull()
}
