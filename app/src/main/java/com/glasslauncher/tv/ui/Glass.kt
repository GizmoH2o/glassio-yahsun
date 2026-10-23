package com.glasslauncher.tv.ui

import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What every glass surface needs: the page backdrop, already blurred once for everybody. */
data class GlassEnv(
    /** Blurred backdrop (the "Glass blur" setting). */
    val bmp: ImageBitmap?,
    /** Unblurred backdrop, for the clear "Glass like glass" look. */
    val sharp: ImageBitmap?,
    /** Heavily blurred backdrop, for the Apps folder (like tvOS). */
    val heavy: ImageBitmap?,
    val rootW: Int, val rootH: Int,
    val tint: Int, val refractionDp: Int,
    /** 1 when "Glass like glass" surfaces may ripple; the ripple itself is read from [live]. */
    val wobble: Float = 0f,
    /**
     * 0..1, animated briefly after a move in the dock. Read only while drawing, so a ripple redraws the
     * glass without recomposing the screen.
     */
    val live: androidx.compose.runtime.State<Float>? = null,
    /** The previous backdrop, faded out underneath while the new one fades in (same timing as the page). */
    val prevBmp: ImageBitmap? = null,
    val prevSharp: ImageBitmap? = null,
    /** 0..1 progress of that crossfade; read only while drawing. */
    val mix: androidx.compose.runtime.State<Float>? = null,
    /** 0..1 while the dock is open: the whole page, glass included, turns blurred and dark. */
    val veil: androidx.compose.runtime.State<Float>? = null,
)

/** How dark the page gets under the open dock. */
const val VEIL_DIM = 0.62f

val LocalGlassEnv = compositionLocalOf { GlassEnv(null, null, null, 0, 0, 6, 14) }
/** 1.0 at 960dp wide (1080p TV at 2x); every size in the UI is multiplied by this. */
val LocalScale = staticCompositionLocalOf { 1f }

/**
 * Blurs the backdrop once on the CPU: shrink it hard (that alone averages pixels), then three box
 * passes. Every glass surface draws from this one small bitmap, so no surface needs its own GPU blur.
 * [strength] 0..80 (the "Glass blur" setting).
 */
fun blurredBackdrop(src: Bitmap, strength: Int): ImageBitmap {
    val w = (320 - strength * 3).coerceIn(48, 320)
    val h = max(8, w * src.height / max(1, src.width))
    val small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
    val r = if (strength <= 0) 0 else (1 + strength / 20).coerceAtMost(4)
    if (r > 0) {
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) { boxBlur(px, tmp, w, h, r); boxBlur(tmp, px, w, h, r, vertical = true) }
        small.setPixels(px, 0, w, 0, 0, w, h)
    }
    return small.asImageBitmap()
}

private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, vertical: Boolean = false) {
    val len = if (vertical) h else w
    val lines = if (vertical) w else h
    val div = 2 * r + 1
    for (line in 0 until lines) {
        var sa = 0; var sr = 0; var sg = 0; var sb = 0
        fun idx(i: Int) = if (vertical) i.coerceIn(0, len - 1) * w + line else line * w + i.coerceIn(0, len - 1)
        for (i in -r..r) { val c = src[idx(i)]; sa += c ushr 24; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
        for (i in 0 until len) {
            dst[idx(i)] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val o = src[idx(i - r)]; val n = src[idx(i + r + 1)]
            sa += (n ushr 24) - (o ushr 24); sr += ((n shr 16) and 255) - ((o shr 16) and 255)
            sg += ((n shr 8) and 255) - ((o shr 8) and 255); sb += (n and 255) - (o and 255)
        }
    }
}

/**
 * Liquid glass refraction on the GPU (AGSL, Android 13+). A rounded-rect signed distance field gives
 * every pixel its distance to the rim and the rim normal; inside the bezel band the backdrop is read
 * from further inside along that normal with a circular lens profile, so whatever is behind the glass
 * bends and stretches at the edges, with a slight red/blue split like real glass. No glow, no sheen:
 * the glass stays clean.
 */
private const val LIQUID_SKSL = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bezel;
uniform float strength;
uniform float wobble;
uniform float phase;

float sdRound(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 fc) {
    float2 c = size * 0.5;
    float2 p = fc - c;
    float d = sdRound(p, c, radius);
    float t = clamp(1.0 + d / bezel, 0.0, 1.0);
    // Liquid: after a move the whole body ripples and settles.
    float2 base = fc;
    if (wobble > 0.001) base += float2(sin(fc.y * 0.045 + phase), cos(fc.x * 0.032 + phase * 1.3)) * wobble * 7.0;
    float prof = 1.0 - sqrt(max(0.0, 1.0 - t * t));
    float disp = strength * prof;
    // Most pixels sit in the flat middle of the glass: one plain sample, no edge maths.
    if (disp < 0.35) return half4(content.eval(base).rgb, 1.0);
    float2 n = float2(sdRound(p + float2(1.0, 0.0), c, radius) - sdRound(p - float2(1.0, 0.0), c, radius),
                      sdRound(p + float2(0.0, 1.0), c, radius) - sdRound(p - float2(0.0, 1.0), c, radius));
    float nl = length(n);
    n = nl > 0.0001 ? n / nl : float2(0.0, 0.0);
    float4 cg = float4(content.eval(base - n * disp * 1.06));
    float r = float4(content.eval(base - n * disp)).r;
    float b = float4(content.eval(base - n * disp * 1.12)).b;
    return half4(half3(float3(r, cg.g, b)), 1.0);
}
"""

/** One compiled shader per surface; uniforms change, the program is never recompiled. */
/**
 * The program is compiled once for the whole app. Creating a RenderEffect copies the current uniform
 * values, so one shader object safely serves every surface (all effects are built on the main thread).
 */
private object SharedShader {
    private var shader: Any? = null
    fun get(): Any = shader ?: RuntimeShader(LIQUID_SKSL).also { shader = it }
}

private class Refractor {
    fun effect(w: Float, h: Float, radius: Float, bezel: Float, strength: Float, wobble: Float = 0f): androidx.compose.ui.graphics.RenderEffect? {
        if (Build.VERSION.SDK_INT < 33 || w < 2f || h < 2f || (strength < 0.5f && wobble < 0.01f)) return null
        val s = SharedShader.get() as RuntimeShader
        s.setFloatUniform("size", w, h)
        s.setFloatUniform("radius", min(radius, min(w, h) / 2f))
        s.setFloatUniform("bezel", max(1f, bezel))
        s.setFloatUniform("strength", strength)
        s.setFloatUniform("wobble", wobble)
        s.setFloatUniform("phase", (1f - wobble) * 14f)
        return RenderEffect.createRuntimeShaderEffect(s, "content").asComposeRenderEffect()
    }
}

/** Draws the (blurred) backdrop region that sits behind this element, darkened like the scene. */
private fun DrawScope.drawBackdropRegion(bmp: ImageBitmap, rootW: Int, rootH: Int, pos: Offset, alpha: Float = 1f, scrims: Boolean = true) {
    val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
    if (bw < 1 || bh < 1 || rootW <= 0 || rootH <= 0) return
    val scale = max(rootW / bw, rootH / bh)
    val ox = (rootW - bw * scale) / 2f; val oy = (rootH - bh * scale) / 2f
    val sx = (pos.x - ox) / scale; val sy = (pos.y - oy) / scale
    val sw = size.width / scale; val sh = size.height / scale
    val l = sx.coerceIn(0f, bw - 1f); val t = sy.coerceIn(0f, bh - 1f)
    val r = (sx + sw).coerceIn(l + 1f, bw); val b = (sy + sh).coerceIn(t + 1f, bh)
    drawImage(
        bmp,
        srcOffset = IntOffset(l.toInt(), t.toInt()),
        srcSize = IntSize((r - l).roundToInt().coerceAtLeast(1), (b - t).roundToInt().coerceAtLeast(1)),
        dstOffset = IntOffset(((l - sx) * scale).roundToInt(), ((t - sy) * scale).roundToInt()),
        dstSize = IntSize(((r - l) * scale).roundToInt().coerceAtLeast(1), ((b - t) * scale).roundToInt().coerceAtLeast(1)),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
    if (scrims) translate(-pos.x, -pos.y) {
        drawRect(
            Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.88f), 1f to Color.Transparent, startX = 0f, endX = rootW * 0.62f),
            topLeft = Offset.Zero, size = Size(rootW * 0.62f, rootH.toFloat()),
        )
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.80f), startY = rootH * 0.58f, endY = rootH.toFloat()),
            topLeft = Offset(0f, rootH * 0.58f), size = Size(rootW.toFloat(), rootH * 0.42f),
        )
    }
}

/** A liquid-glass surface: blurred backdrop + rim refraction ([refraction] dp, 0 = flat frosted glass). */
@Composable
fun LiquidSurface(
    modifier: Modifier = Modifier,
    radius: Dp,
    refraction: Dp,
    bezel: Dp,
    tint: Color = Color.Transparent,
    sharp: Boolean = false,
    wobble: Float = 0f,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val env = LocalGlassEnv.current
    val density = LocalDensity.current
    val pos = remember { mutableStateOf(Offset.Zero) }
    var sz by remember { mutableStateOf(IntSize.Zero) }
    val refractor = remember { Refractor() }
    val rPx = with(density) { radius.toPx() }
    val bPx = with(density) { bezel.toPx() }
    val fPx = with(density) { refraction.toPx() }
    val still = remember(sz, rPx, bPx, fPx) { refractor.effect(sz.width.toFloat(), sz.height.toFloat(), rPx, bPx, fPx, 0f) }
    val live = env.live
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .onGloballyPositioned { pos.value = it.positionInRoot(); if (it.size != sz) sz = it.size },
    ) {
        val bmp = if (sharp) env.sharp ?: env.bmp else env.bmp
        if (bmp != null) {
            Canvas(Modifier.matchParentSize().graphicsLayer {
                val w = if (wobble > 0f && live != null) live.value * wobble else 0f
                renderEffect = if (w < 0.01f) still else refractor.effect(size.width, size.height, rPx, bPx, fPx, w)
            }) {
                val m = env.mix?.value ?: 1f
                val prev = if (sharp) env.prevSharp ?: env.prevBmp else env.prevBmp
                if (m < 0.995f && prev != null) {
                    drawBackdropRegion(prev, env.rootW, env.rootH, pos.value, scrims = false)
                    drawBackdropRegion(bmp, env.rootW, env.rootH, pos.value, alpha = m)
                } else drawBackdropRegion(bmp, env.rootW, env.rootH, pos.value)
                val v = env.veil?.value ?: 0f
                if (v > 0.01f) {
                    env.heavy?.let { drawBackdropRegion(it, env.rootW, env.rootH, pos.value, alpha = v, scrims = false) }
                    drawRect(Color.Black.copy(alpha = v * VEIL_DIM))
                }
            }
        } else {
            Box(Modifier.matchParentSize().background(Color(0xFF1A1A22)))
        }
        if (tint.alpha > 0f) Box(Modifier.matchParentSize().background(tint))
        content()
    }
}

/** Large panel (dock, settings, folder) in one of the four looks: liquid | clear | amoled | bow. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    radius: Dp = 28.dp,
    style: String = "liquid",
    darken: Float = 0f,
    refract: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val env = LocalGlassEnv.current
    val shape = RoundedCornerShape(radius)
    when (style) {
        "amoled" -> Box(modifier.clip(shape).background(Color.Black.copy(alpha = 0.94f)).border(0.6.dp, Color.White.copy(alpha = 0.08f), shape), content = content)
        "bow" -> Box(modifier.clip(shape).background(Color(0xF2F2F2F5)), content = content)
        // Glass like glass: almost no frost or tint, strong rim bending, ripples after a move.
        "water", "water_still" -> LiquidSurface(
            modifier, radius, if (refract) (env.refractionDp * 1.5f).dp else 0.dp, (radius * 1.2f).coerceIn(12.dp, 34.dp),
            tint = Color.Black.copy(alpha = darken.coerceAtMost(0.9f)), sharp = true, wobble = if (refract && style == "water") env.wobble else 0f,
        ) {
            content()
            Box(Modifier.matchParentSize().border(0.6.dp, Color.White.copy(alpha = 0.16f), shape))
        }
        "clear" -> LiquidSurface(modifier, radius, 0.dp, 10.dp, tint = Color.White.copy(alpha = 0.06f)) {
            if (darken > 0f) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = darken)))
            content()
            Box(Modifier.matchParentSize().border(0.6.dp, Color.White.copy(alpha = 0.10f), shape))
        }
        else -> LiquidSurface(
            modifier, radius, if (refract) env.refractionDp.dp else 0.dp, (radius * 1.1f).coerceIn(10.dp, 30.dp),
            tint = Color.Black.copy(alpha = (0.14f + darken).coerceAtMost(0.9f)),
        ) {
            content()
            Box(Modifier.matchParentSize().border(0.6.dp, Color.White.copy(alpha = 0.10f), shape))
        }
    }
}

/** Kept for the screens that only need the clip. */
fun Modifier.roundClip(r: Dp) = this.clip(RoundedCornerShape(r))
