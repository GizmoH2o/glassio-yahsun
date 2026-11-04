package com.glasslauncher.tv.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import com.glasslauncher.tv.R

/**
 * Inter: the open typeface closest to Apple's SF Pro (SF itself may only ship on Apple devices).
 * Letters it lacks, such as Arabic, fall back to the system font automatically.
 */
val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Apple TV's secondary text (synopsis, metadata): soft grey rather than white. */
val AppleSecondary = androidx.compose.ui.graphics.Color(0xFFB3B3B8)

/**
 * Home's accent (Settings › Home › Accent colour): LIVE, NEW, the progress bars, the FILM / SERIES line, +N. Netflix's
 * red unless another is picked.
 */
val LocalAccent = androidx.compose.runtime.staticCompositionLocalOf { androidx.compose.ui.graphics.Color(0xFFE50914) }

/** The accent colours to pick from: their names and colours, the first the default. */
val ACCENTS = listOf(
    "red" to ("Netflix red" to 0xFFE50914), "apple" to ("Apple blue" to 0xFF0A84FF), "prime" to ("Prime blue" to 0xFF00A8E1),
    "disney" to ("Disney teal" to 0xFF19C3B4), "green" to ("Spotify green" to 0xFF1DB954), "purple" to ("Purple" to 0xFF8A5AF0),
    "orange" to ("Orange" to 0xFFFF8A00), "pink" to ("Pink" to 0xFFFF3D7F),
)

/** Text sizes (Settings › Home › Text size): their names and how much larger than normal. */
val TEXT_SIZES = listOf("small" to ("Small" to 0.9f), "normal" to ("Normal" to 1f), "large" to ("Large" to 1.12f), "larger" to ("Larger" to 1.25f))

/**
 * Glassio's lettering, and the accent colour and text size picked in Settings › Home ([accent] a key of ACCENTS,
 * [textSize] of TEXT_SIZES): every sp of the screens inside grows or shrinks by it.
 */
@Composable
fun GlassType(accent: String = "red", textSize: String = "normal", reduceMotion: Boolean = false, highContrast: Boolean = false, content: @Composable () -> Unit) {
    val color = androidx.compose.ui.graphics.Color((ACCENTS.firstOrNull { it.first == accent } ?: ACCENTS[0]).second.second)
    val scale = (TEXT_SIZES.firstOrNull { it.first == textSize } ?: TEXT_SIZES[1]).second.second
    val d = androidx.compose.ui.platform.LocalDensity.current
    CompositionLocalProvider(
        LocalAccent provides color,
        LocalReduceMotion provides reduceMotion,
        LocalHighContrast provides highContrast,
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale * scale),
        LocalTextStyle provides LocalTextStyle.current.copy(
            fontFamily = Inter,
            // The TV draws apps at 1080p and scales them up to 4K: Regular strokes go soft, Medium stays crisp.
            fontWeight = FontWeight.Medium,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        ),
        content = content,
    )
}
