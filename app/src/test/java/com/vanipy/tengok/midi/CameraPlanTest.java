package com.vanipy.tengok.midi;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the precomputed game camera: zoom out before moving, move only
 * inside no-press gaps, never under a held finger, and say honestly how
 * small the keys got. Numbers mirror docs/tests/run-tests.mjs exactly:
 * an 800 px viewport and a 44 px default white key (density 1).
 */
public class CameraPlanTest {
    private static final int W = 800;
    private static final float DEF = 44f;
    private static final int LO = 48;
    private static final int HI = 83; // three octaves: 21 white keys

    private static MidiFile.Note note(int pitch, double startSec, double endSec) {
        MidiFile.Note n = new MidiFile.Note();
        n.pitch = pitch;
        n.startSec = startSec;
        n.endSec = endSec;
        return n;
    }

    private static List<MidiFile.Note> notes(MidiFile.Note... ns) {
        return new ArrayList<>(Arrays.asList(ns));
    }

    private static float[] at(CameraPlan p, double songMs) {
        float[] out = new float[2];
        p.valueAt(songMs, out);
        return out;
    }

    @Test
    public void emptySongIsStaticDefault() {
        CameraPlan p = new CameraPlan(notes(), LO, HI, W, DEF);
        assertEquals(0, p.segmentCount());
        assertEquals(0, p.groupCount());
        assertEquals(0, p.glideCount());
        assertEquals(0f, at(p, 0)[0], 0.001f);
        assertEquals(DEF, at(p, 12345)[1], 0.001f);
        assertEquals(DEF, p.minWhiteW(), 0.001f);
    }

    @Test
    public void singlePassageKeepsDefaultWidth() {
        // two adjacent notes merge into one busy segment; C4..E4 fit easily
        CameraPlan p = new CameraPlan(notes(
                note(60, 1.0, 2.0), note(64, 2.0, 3.0)), LO, HI, W, DEF);
        assertEquals(1, p.segmentCount());
        assertEquals(1, p.groupCount());
        assertEquals(0, p.glideCount());
        assertEquals(DEF, p.minWhiteW(), 0.001f);
        assertEquals(0, p.framesBelow(DEF));
        // centered on C4..E4: the range sits left of center, clamped to 0
        KeyWindow kw = KeyWindow.exact(LO, HI, W, DEF);
        assertEquals(kw.viewportCentering(60, 64), at(p, 1500)[0], 0.001f);
        assertEquals(DEF, at(p, 1500)[1], 0.001f);
        // one static frame for the whole song, before during and after
        assertEquals(at(p, 1500)[0], at(p, 0)[0], 0.001f);
        assertEquals(at(p, 1500)[0], at(p, 99999)[0], 0.001f);
    }

    @Test
    public void longGapGlidesInsideTheGapOnly() {
        CameraPlan p = new CameraPlan(notes(
                note(48, 0.0, 1.0), note(83, 10.0, 11.0)), LO, HI, W, DEF);
        assertEquals(2, p.segmentCount());
        assertEquals(2, p.groupCount());
        assertEquals(1, p.glideCount());
        double[] gw = p.glideWindow(0);
        // segment 1 busy [-300, 1150], segment 2 busy [9700, 11150]:
        // the glide arrives 500 ms before the next segment...
        assertEquals(9700.0 - CameraPlan.ARRIVE_EARLY_MS, gw[1], 0.001);
        // ...takes at least the minimum sweep...
        assertTrue(gw[1] - gw[0] >= CameraPlan.GLIDE_MIN_MS - 0.001);
        // ...and starts no earlier than the first segment's end
        assertTrue(gw[0] >= 1150.0 - 0.001);
        // before, mid and after the glide
        KeyWindow kw = KeyWindow.exact(LO, HI, W, DEF);
        float vxA = kw.viewportCentering(48, 48);
        float vxB = kw.viewportCentering(83, 83);
        assertEquals(vxA, at(p, 5000)[0], 0.001f);
        assertEquals(vxB, at(p, 9500)[0], 0.001f);
        float mid = at(p, (gw[0] + gw[1]) / 2)[0];
        assertEquals((vxA + vxB) / 2f, mid, 0.5f); // smoothstep midpoint
        // the sweep is monotonic: the board never doubles back
        float prev = vxA;
        for (double t = gw[0]; t <= gw[1]; t += 20) {
            float v = at(p, t)[0];
            assertTrue("monotonic at " + t, v >= prev - 0.001f);
            prev = v;
        }
    }

    @Test
    public void shortGapMergesIntoOneZoomedFrame() {
        // 550 ms gap: too short for a 500 ms arrival buffer plus a 320 ms
        // sweep, so instead of moving the camera zooms out to see both
        CameraPlan p = new CameraPlan(notes(
                note(48, 0.0, 1.0), note(83, 2.0, 3.0)), LO, HI, W, DEF);
        assertEquals(2, p.segmentCount());
        assertEquals(1, p.groupCount());
        assertEquals(0, p.glideCount());
        float fitW = W / 21f; // the whole three-octave board at once
        assertEquals(fitW, at(p, 0)[1], 0.001f);
        assertEquals(fitW, at(p, 2000)[1], 0.001f);
        assertEquals(fitW, at(p, 99999)[1], 0.001f);
        assertEquals(fitW, p.minWhiteW(), 0.001f);
        assertEquals(1, p.framesBelow(44f)); // honestly below the comfort size
        assertEquals(0, p.framesBelow(38f));
        // and the whole range really is visible: nothing left to pan to
        KeyWindow kw = KeyWindow.exact(LO, HI, W, fitW);
        assertTrue(kw.rangeVisible(48, 83, at(p, 1000)[0]));
    }

    @Test
    public void denseNoGapPassageIsAWholePassageFit() {
        // 36 back-to-back chromatic eighth notes: no gap anywhere, so the
        // whole passage is one static zoomed frame and the board never moves
        List<MidiFile.Note> walk = new ArrayList<>();
        for (int i = 0; i < 36; i++) walk.add(note(48 + i, i * 0.25, i * 0.25 + 0.25));
        CameraPlan p = new CameraPlan(walk, LO, HI, W, DEF);
        assertEquals(1, p.segmentCount());
        assertEquals(1, p.groupCount());
        assertEquals(0, p.glideCount());
        float fitW = W / 21f;
        assertEquals(fitW, p.minWhiteW(), 0.001f);
        float[] first = at(p, 0);
        for (double t = 0; t <= 9500; t += 250) {
            float[] v = at(p, t);
            assertEquals("vx static at " + t, first[0], v[0], 0.001f);
            assertEquals("width static at " + t, first[1], v[1], 0.001f);
        }
        KeyWindow kw = KeyWindow.exact(LO, HI, W, first[1]);
        assertTrue(kw.rangeVisible(48, 83, first[0]));
    }

    @Test
    public void heldPadKeepsTheWholeUnionVisible() {
        // a held pad note spans the whole passage: far melody notes during
        // the hold must not move the board - one static union frame
        CameraPlan p = new CameraPlan(notes(
                note(48, 0.0, 10.0),
                note(76, 2.0, 2.5), note(79, 4.0, 4.5), note(83, 6.0, 6.5)),
                LO, HI, W, DEF);
        assertEquals(1, p.segmentCount());
        assertEquals(1, p.groupCount());
        assertEquals(0, p.glideCount());
        KeyWindow kw = KeyWindow.exact(LO, HI, W, at(p, 3000)[1]);
        assertTrue(kw.rangeVisible(48, 83, at(p, 3000)[0]));
    }

    @Test
    public void glidesNeverOverlapBusyTime() {
        // three separated phrases: every glide window must sit strictly
        // between busy intervals (lead and tail padding included)
        List<MidiFile.Note> song = notes(
                note(48, 0.0, 1.0), note(83, 10.0, 11.0), note(60, 20.0, 21.0));
        CameraPlan p = new CameraPlan(song, LO, HI, W, DEF);
        assertEquals(3, p.segmentCount());
        assertEquals(3, p.groupCount());
        assertEquals(2, p.glideCount());
        for (int i = 0; i < p.glideCount(); i++) {
            double[] gw = p.glideWindow(i);
            for (MidiFile.Note n : song) {
                double b0 = n.startSec * 1000.0 - CameraPlan.BUSY_LEAD_MS;
                double b1 = n.endSec * 1000.0 + CameraPlan.BUSY_TAIL_MS;
                assertTrue("glide [" + gw[0] + "," + gw[1] + "] overlaps busy ["
                                + b0 + "," + b1 + "]",
                        gw[1] <= b0 || gw[0] >= b1);
            }
        }
    }

    @Test
    public void zoomRidesTheGlide() {
        // a narrow phrase, then a cluster spanning the whole board: the
        // gap glide carries the zoom change smoothly, inside the gap
        List<MidiFile.Note> song = new ArrayList<>();
        song.add(note(60, 0.0, 1.0)); // one key
        for (int i = 0; i < 36; i++) song.add(note(48 + i, 10.0, 11.0)); // whole-board cluster
        CameraPlan p = new CameraPlan(song, LO, HI, W, DEF);
        assertEquals(2, p.groupCount());
        assertEquals(1, p.glideCount());
        double[] gw = p.glideWindow(0);
        float wA = at(p, gw[0] - 1)[1];
        float wB = at(p, gw[1] + 1)[1];
        assertEquals(DEF, wA, 0.001f);
        assertEquals(W / 21f, wB, 0.01f);
        float midW = at(p, (gw[0] + gw[1]) / 2)[1];
        assertTrue("zoom interpolates mid-glide", midW < wA && midW > wB);
    }
}
