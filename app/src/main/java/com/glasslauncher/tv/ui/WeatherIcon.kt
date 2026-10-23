package com.glasslauncher.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Small monochrome weather glyphs in the SF Symbols spirit (sun.max, cloud, cloud.rain …). */
@Composable
fun WeatherIcon(kind: String, modifier: Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val w = size.minDimension
        val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round)
        fun sun(c: Offset, r: Float) {
            drawCircle(color, r, c, style = stroke)
            for (i in 0 until 8) {
                val a = i * PI / 4
                drawLine(color, c + Offset((cos(a) * r * 1.55f).toFloat(), (sin(a) * r * 1.55f).toFloat()),
                    c + Offset((cos(a) * r * 2.05f).toFloat(), (sin(a) * r * 2.05f).toFloat()), stroke.width, StrokeCap.Round)
            }
        }
        fun cloud(dy: Float = 0f, fill: Boolean = false) {
            val p = Path().apply {
                moveTo(w * 0.22f, w * 0.72f + dy)
                cubicTo(w * 0.05f, w * 0.72f + dy, w * 0.05f, w * 0.46f + dy, w * 0.24f, w * 0.46f + dy)
                cubicTo(w * 0.26f, w * 0.26f + dy, w * 0.52f, w * 0.20f + dy, w * 0.60f, w * 0.38f + dy)
                cubicTo(w * 0.78f, w * 0.30f + dy, w * 0.95f, w * 0.44f + dy, w * 0.86f, w * 0.60f + dy)
                cubicTo(w * 0.98f, w * 0.66f + dy, w * 0.92f, w * 0.72f + dy, w * 0.80f, w * 0.72f + dy)
                close()
            }
            drawPath(p, color, style = if (fill) Fill else stroke)
        }
        when (kind) {
            "sun" -> sun(center, w * 0.2f)
            "moon" -> {
                val outer = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.18f, w * 0.18f), Size(w * 0.64f, w * 0.64f))) }
                val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.38f, w * 0.06f), Size(w * 0.56f, w * 0.56f))) }
                drawPath(Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, outer, bite), color)
            }
            "partly" -> { sun(Offset(w * 0.36f, w * 0.34f), w * 0.12f); cloud(w * 0.08f) }
            "cloud" -> cloud()
            "fog" -> { cloud(-w * 0.12f); for (i in 0..1) drawLine(color, Offset(w * 0.2f, w * (0.78f + i * 0.12f)), Offset(w * 0.8f, w * (0.78f + i * 0.12f)), stroke.width, StrokeCap.Round) }
            "drizzle", "rain" -> {
                cloud(-w * 0.14f)
                val n = if (kind == "rain") 3 else 2
                for (i in 0 until n) {
                    val x = w * (0.32f + i * 0.18f)
                    drawLine(color, Offset(x, w * 0.68f), Offset(x - w * 0.06f, w * 0.88f), stroke.width, StrokeCap.Round)
                }
            }
            "snow" -> { cloud(-w * 0.14f); for (i in 0 until 3) drawCircle(color, w * 0.045f, Offset(w * (0.32f + i * 0.18f), w * 0.8f)) }
            "storm" -> {
                cloud(-w * 0.14f)
                val p = Path().apply {
                    moveTo(w * 0.52f, w * 0.6f); lineTo(w * 0.4f, w * 0.8f); lineTo(w * 0.52f, w * 0.8f); lineTo(w * 0.44f, w * 0.97f)
                }
                drawPath(p, color, style = stroke)
            }
            else -> cloud()
        }
    }
}

