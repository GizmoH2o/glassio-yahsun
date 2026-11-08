package com.glasslauncher.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Slow zoom, as measured from a streaming app's recording: steady from the moment a picture comes in, up to a limit. */
class SlowZoomTest {
    @Test fun `it starts at its own size`() {
        assertEquals(1f, slowZoomOf(1_000, 1_000, HERO_ZOOM_PER_S), 0f)
    }

    @Test fun `the big picture grows about half a percent a second, steadily`() {
        assertEquals(1.0047f, slowZoomOf(0, 1_000, HERO_ZOOM_PER_S), 1e-4f)
        assertEquals(1.0376f, slowZoomOf(0, 8_000, HERO_ZOOM_PER_S), 1e-4f)
        // The same growth in every second: no easing.
        val a = slowZoomOf(0, 1_000, HERO_ZOOM_PER_S) - slowZoomOf(0, 0, HERO_ZOOM_PER_S)
        val b = slowZoomOf(0, 7_000, HERO_ZOOM_PER_S) - slowZoomOf(0, 6_000, HERO_ZOOM_PER_S)
        assertEquals(a, b, 1e-5f)
    }

    @Test fun `an open card grows about 1_8 percent a second`() {
        assertEquals(1.072f, slowZoomOf(0, 4_000, CARD_ZOOM_PER_S), 1e-4f)
    }

    @Test fun `it stops at its limit`() {
        assertEquals(SLOW_ZOOM_MAX, slowZoomOf(0, 600_000, HERO_ZOOM_PER_S), 0f)
        assertEquals(SLOW_ZOOM_MAX, slowZoomOf(0, 60_000, CARD_ZOOM_PER_S), 0f)
    }

    @Test fun `a time before it came in counts as its start`() {
        assertEquals(1f, slowZoomOf(5_000, 4_000, CARD_ZOOM_PER_S), 0f)
    }
}

/** Home's own speed (HomeMotion): a View animation lasts as long as meant at any TV animation speed. */
class OwnViewDurationTest {
    @Test fun `at the TV's 0_5x speed it is asked for twice as long`() { assertEquals(600L, ownViewDuration(300, 0.5f)) }
    @Test fun `at 1x it is as it is`() { assertEquals(300L, ownViewDuration(300, 1f)) }
    @Test fun `animations off on the TV stay off`() { assertEquals(300L, ownViewDuration(300, 0f)) }
}
