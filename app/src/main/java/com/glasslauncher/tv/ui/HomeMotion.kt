package com.glasslauncher.tv.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Home's own speed for the motion measured from a streaming app (the big picture's title change, a poster card
 * opening, its trailer coming in). Compose follows the TV's animation speed (Developer options › Animator duration
 * scale), and some TVs ship at 0.5x, which ran all of it twice as fast as measured. Settings › Home › Follow the TV's
 * animation speed puts it back on the TV's; animations turned off on the TV (0x) stay off either way.
 */
private object OwnSpeed : MotionDurationScale {
    override val scaleFactor = 1f
}

/** Runs [block] with its animations at their own durations, unless [follow] (the TV's speed) or the TV's are off. */
internal suspend fun <T> atOwnSpeed(follow: Boolean, block: suspend () -> T): T {
    val tv = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    return if (follow || tv == 0f) block() else withContext(OwnSpeed) { block() }
}

/** animateFloatAsState, at Home's own speed (above) unless [follow]. */
@Composable
internal fun animateAtOwnSpeed(target: Float, spec: AnimationSpec<Float>, follow: Boolean): State<Float> {
    val anim = remember { Animatable(target) }
    val latest by rememberUpdatedState(spec)
    LaunchedEffect(target, follow) { atOwnSpeed(follow) { anim.animateTo(target, latest) } }
    return anim.asState()
}

/** The TV's animation speed, for the View animations that follow it by themselves: 1 when it can't be read. */
internal fun tvAnimationScale(ctx: Context): Float =
    runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

/** [ms] for a View animation to last [ms] at the TV's speed [scale] (0: off, left as it is). Pure. */
internal fun ownViewDuration(ms: Long, scale: Float): Long = if (scale <= 0f) ms else (ms / scale).toLong()
