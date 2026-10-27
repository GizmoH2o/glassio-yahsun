package com.glasslauncher.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A channel's country (US, UK, CA…) on a small glass pill, as Live TV draws it beside the name (LiveCountry):
 * nothing for "" (the tags off in Live TV's settings, or no country known).
 */
@Composable
fun CountryTag(code: String, s: Float, modifier: Modifier = Modifier) {
    if (code.isEmpty()) return
    val shape = RoundedCornerShape(50)
    Text(
        code, color = Color.White.copy(alpha = 0.9f), fontSize = (7 * s).sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (0.6f * s).sp, maxLines = 1,
        modifier = modifier.background(Color.White.copy(alpha = 0.14f), shape).border(0.6.dp, Color.White.copy(alpha = 0.2f), shape)
            .padding(horizontal = (5 * s).dp, vertical = (1 * s).dp),
    )
}
