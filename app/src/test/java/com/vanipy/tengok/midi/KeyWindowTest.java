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

    @Test
    public void widthOverrideOnlyZoomsOut() {
        // the planned camera shrinks keys when a passage needs it; it never
        // inflates past the default
        KeyWindow kw = new KeyWindow(36, 83, W, D, 80f);
        assertEquals(80f, kw.whiteW, 0.001f);
        KeyWindow wider = new KeyWindow(36, 83, W, D, 99999f);
        assertEquals(KeyWindow.MIN_WHITE_DP * D, wider.whiteW, 0.001f);
        KeyWindow exact = KeyWindow.exact(48, 83, 800, 38f);
        assertEquals(38f, exact.whiteW, 0.001f);
    }

    @Test
    public void centerTapsAlwaysHitTheirOwnKey() {
        // the small-key question, measured: at the comfort width and below
        // it, a tap at a key's center maps to that key, black or white
        for (float w : new float[]{44f, 30f, 28f}) {
            KeyWindow kw = KeyWindow.exact(48, 83, 800, w);
            for (int p = 48; p <= 83; p++) {
                float c = kw.centerPx(p);
                if (KeyWindow.isBlack(p)) {
                    assertEquals("black " + p + " at " + w, p, kw.pitchAt(c, true));
                    // below the black zone the boundary belongs to the right white
                    assertEquals("under black " + p + " at " + w, p + 1, kw.pitchAt(c, false));
                } else {
                    assertEquals("white " + p + " at " + w, p, kw.pitchAt(c, true));
                    assertEquals("white low " + p + " at " + w, p, kw.pitchAt(c, false));
                }
            }
        }
    }

    @Test
    public void simultaneousNeighborsEachHitTheirOwnKey() {
        // a chord cluster at a shrunk width: two or three fingers on adjacent
        // key centers still land on their own keys
        KeyWindow kw = KeyWindow.exact(48, 83, 800, 30f);
        for (int p : new int[]{60, 64, 67}) { // C E G
            assertEquals(p, kw.pitchAt(kw.centerPx(p), true));
        }
        for (int p : new int[]{61, 63}) { // C# D#
            assertEquals(p, kw.pitchAt(kw.centerPx(p), true));
        }
        // black + adjacent white together, the worst boundary case
        assertEquals(61, kw.pitchAt(kw.centerPx(61), true));
        assertEquals(60, kw.pitchAt(kw.centerPx(60), true));
        assertEquals(62, kw.pitchAt(kw.centerPx(62), true));
    }

    @Test
    public void containmentMatchesTheDrawnKeys() {
        // Why hit-testing stays strict containment instead of nearest-center
        // arbitration: scan the black-key zone at 30px keys. Containment can
        // never disagree with what is drawn (a black key owns exactly its
        // drawn rect). Arbitration shrinks every black key's target from its
        // drawn rect (0.62*whiteW) to half a white width, so a tap ON the
        // drawn black near its edge scores the white beside it - and the
        // pressed key's dim must match what the tap scores. Pin the measured
        // size of that disagreement so the choice is evidence, not taste.
        KeyWindow kw = KeyWindow.exact(48, 83, 800, 30f);
        int disagree = 0;
        int blacks = 0;
        for (int p = 48; p <= 83; p++) {
            if (!KeyWindow.isBlack(p)) continue;
            blacks++;
            float bc = kw.centerPx(p);
            float half = kw.whiteW / 2f;
            for (float x = bc - half + 0.01f; x < bc + half; x += 0.25f) {
                if (nearestCenter(kw, x) != kw.pitchAt(x, true)) {
                    disagree++;
                }
            }
        }
        assertTrue(blacks > 0);
        // 30px keys: drawn black 18.6px, arbitrated 15px -> ~3.6px of disagreement
        int perBlack = (int) ((kw.blackW() - kw.whiteW / 2f) / 0.25f);
        assertTrue(perBlack > 0);
        assertEquals(perBlack * blacks, disagree, blacks * 2);
    }

    /** Test-only nearest-center arbitration, for the comparison above. */
    private static int nearestCenter(KeyWindow kw, float x) {
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (int p = kw.lo; p <= kw.hi; p++) {
            float d = Math.abs(kw.centerPx(p) - x);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }
}
