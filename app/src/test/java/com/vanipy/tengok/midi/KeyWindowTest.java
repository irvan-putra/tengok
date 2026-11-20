package com.vanipy.tengok.midi;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the phone-keyboard geometry: keys never shrink below a fingertip, the
 * board pans instead, and the follow math keeps the next notes in view.
 * Screen numbers mimic a 1080 px wide phone at density 2.625.
 */
public class KeyWindowTest {
    private static final int W = 1080;
    private static final float D = 2.625f;

    @Test
    public void narrowRangeFillsScreenExactly() {
        KeyWindow kw = new KeyWindow(48, 60, W, D); // one octave: fits at fit-width
        assertEquals(8, kw.whiteCount());
        assertEquals(W / 8f, kw.whiteW, 0.001f);
        assertEquals(0f, kw.maxViewport(), 0.001f);
        assertEquals(0f, kw.clampViewport(500f), 0.001f);
    }

    @Test
    public void twoOctavesAlreadyBeatFitWidth() {
        // 15 white keys squeezed onto a phone would be ~27 dp: too small, so
        // the minimum wins and the board pans even for a two-octave song
        KeyWindow kw = new KeyWindow(48, 72, W, D);
        assertEquals(15, kw.whiteCount());
        assertEquals(KeyWindow.MIN_WHITE_DP * D, kw.whiteW, 0.001f);
        assertTrue(kw.maxViewport() > 0);
    }

    @Test
    public void wideRangeKeepsFingerSizedKeys() {
        KeyWindow kw = new KeyWindow(36, 83, W, D); // four octaves
        assertEquals(28, kw.whiteCount());
        assertEquals(KeyWindow.MIN_WHITE_DP * D, kw.whiteW, 0.001f);
        assertTrue("keys must beat squeezing the range onto the screen",
                kw.whiteW > W / 28f);
        assertTrue(kw.maxViewport() > 0);
    }

    @Test
    public void minWidthScalesWithDensity() {
        KeyWindow ldpi = new KeyWindow(36, 83, W, 1.0f);
        assertEquals(KeyWindow.MIN_WHITE_DP, ldpi.whiteW, 0.001f);
    }

    @Test
    public void clampStaysOnTheBoard() {
        KeyWindow kw = new KeyWindow(36, 83, W, D);
        assertEquals(0f, kw.clampViewport(-10f), 0.001f);
        assertEquals(kw.maxViewport(), kw.clampViewport(99999f), 0.001f);
        assertEquals(120f, kw.clampViewport(120f), 0.001f);
    }

    @Test
    public void visibilityFollowsTheViewport() {
        KeyWindow kw = new KeyWindow(36, 83, W, D);
        float vx = kw.viewportCentering(60, 64);
        assertTrue(kw.rangeVisible(60, 64, vx));
        assertFalse(kw.rangeVisible(60, 64, kw.maxViewport()));
        // the far end of the board shows the high notes instead
        assertTrue(kw.rangeVisible(76, 83, kw.maxViewport()));
    }

    @Test
    public void blackKeySitsOnTheWhiteBoundary() {
        KeyWindow kw = new KeyWindow(48, 72, W, D);
        assertEquals(kw.rightPx(60), kw.centerPx(61), 0.001f);
        assertEquals(kw.blackW(), kw.rightPx(61) - kw.leftPx(61), 0.001f);
    }

    @Test
    public void centeringClampsAtTheEdges() {
        KeyWindow kw = new KeyWindow(36, 83, W, D);
        assertEquals(0f, kw.viewportCentering(36, 40), 0.001f);
        assertEquals(kw.maxViewport(), kw.viewportCentering(80, 83), 0.001f);
    }
}
