package com.vanipy.tengok.midi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The game camera, planned once when a song loads instead of improvised
 * while notes fall. The plan answers one question - "what does the board
 * show at song time t?" - so playback, scrubbing, pausing and wait-mode
 * freezes all read the same precomputed answer and the board never has to
 * catch up mid-phrase.
 *
 * Rules, in order of importance:
 *
 * 1. The board never moves while a finger could be down. A note keeps its
 *    key "busy" from BUSY_LEAD_MS before its onset (a player pre-positions)
 *    until BUSY_TAIL_MS after its end (the strike grace plus hold tails).
 *    Overlapping busy intervals merge into segments; inside a segment the
 *    camera is a single static frame.
 * 2. Zoom out before moving sideways. Each segment's frame fits its whole
 *    required pitch range (every key pressed or held anywhere in the
 *    segment) on screen at once: the widest white-key width up to the
 *    song's default that shows them all. Only when that range does not fit
 *    at the default width do the keys shrink - exactly the "don't zoom out
 *    unless all needed keys must show" rule. There is no hard floor that
 *    would re-introduce panning: a dense no-gap passage is a whole-passage
 *    fit problem, and frames smaller than the 44dp comfort size are
 *    reported in the plan stats instead of being hidden.
 * 3. Sideways movement happens only inside the gaps between busy segments,
 *    where by construction nothing is or can be held. A glide must arrive
 *    ARRIVE_EARLY_MS before the next segment starts. When a gap is too
 *    short for that, the two adjacent frames merge into one zoomed-out
 *    frame covering both ranges - again zoom instead of motion - so the
 *    plan never falls back to moving the board during play.
 *
 * The padding, arrival and glide constants are starting hypotheses, not
 * measured human limits; the unit tests pin the behaviors they produce on
 * synthetic songs so a retune is a deliberate diff, not a silent drift.
 *
 * Pure math, no Android types, so the unit tests pin the numbers an
 * emulator would only show indirectly. The web game runs the same planner
 * ported in docs/js/game-core.js; the numbers must match.
 */
public final class CameraPlan {
    /** Busy starts this far before a note's onset (anticipation), ms. */
    public static final double BUSY_LEAD_MS = 300;
    /** Busy ends this far after a note's end (strike grace plus holds), ms. */
    public static final double BUSY_TAIL_MS = 150;
    /** A glide must arrive this far before the next busy segment, ms. */
    public static final double ARRIVE_EARLY_MS = 500;
    /** Gap glide feel: minimum sweep duration and peak speed, screens/second. */
    public static final double GLIDE_MIN_MS = 320;
    public static final double GLIDE_PEAK_SCREENS_PER_SEC = 0.9;

    private static final class Group {
        double startMs, endMs;
        int loPitch, hiPitch;
        float whiteW, vx;
    }

    private static final class Glide {
        double startMs, endMs;
        float fromVx, fromW, toVx, toW;
    }

    private final int lo;
    private final int hi;
    private final int viewW;
    private final float defaultWhiteW;
    private final ArrayList<Group> groups = new ArrayList<>();
    private final ArrayList<Glide> glides = new ArrayList<>();
    private int segmentCount;
    private float minWhiteW;

    /**
     * @param notes          the judged (player) notes of the song
     * @param lo             board's lowest pitch
     * @param hi             board's highest pitch
     * @param viewW          viewport width in pixels
     * @param defaultWhiteW  the song's default white-key width in pixels
     *                       (KeyWindow's: the fit width, at least the 44dp
     *                       finger comfort size)
     */
    public CameraPlan(List<MidiFile.Note> notes, int lo, int hi, int viewW,
                      float defaultWhiteW) {
        this.lo = lo;
        this.hi = hi;
        this.viewW = viewW;
        this.defaultWhiteW = defaultWhiteW;
        this.minWhiteW = defaultWhiteW;
        build(notes);
    }

    private void build(List<MidiFile.Note> notes) {
        ArrayList<double[]> busy = new ArrayList<>(); // startMs, endMs, pitch
        for (MidiFile.Note n : notes) {
            busy.add(new double[]{n.startSec * 1000.0 - BUSY_LEAD_MS,
                    n.endSec * 1000.0 + BUSY_TAIL_MS, n.pitch});
        }
        Collections.sort(busy, new Comparator<double[]>() {
            @Override public int compare(double[] a, double[] b) {
                return Double.compare(a[0], b[0]);
            }
        });
        ArrayList<Group> segs = new ArrayList<>();
        for (double[] iv : busy) {
            Group last = segs.isEmpty() ? null : segs.get(segs.size() - 1);
            if (last == null || iv[0] > last.endMs) {
                Group g = new Group();
                g.startMs = iv[0];
                g.endMs = iv[1];
                g.loPitch = g.hiPitch = (int) iv[2];
                segs.add(g);
            } else {
                last.endMs = Math.max(last.endMs, iv[1]);
                last.loPitch = Math.min(last.loPitch, (int) iv[2]);
                last.hiPitch = Math.max(last.hiPitch, (int) iv[2]);
            }
        }
        segmentCount = segs.size();
        groups.addAll(segs);
        for (Group g : groups) computeFrame(g);
        // A glide that cannot finish inside its gap becomes a wider frame:
        // merge the two neighbors and refit the union. Scan until stable.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i + 1 < groups.size(); i++) {
                Group a = groups.get(i);
                Group b = groups.get(i + 1);
                double gap = b.startMs - a.endMs;
                if (gap - ARRIVE_EARLY_MS < glideMs(a, b)) {
                    a.endMs = b.endMs;
                    a.loPitch = Math.min(a.loPitch, b.loPitch);
                    a.hiPitch = Math.max(a.hiPitch, b.hiPitch);
                    computeFrame(a);
                    groups.remove(i + 1);
                    changed = true;
                    break;
                }
            }
        }
        for (int i = 0; i + 1 < groups.size(); i++) {
            Group a = groups.get(i);
            Group b = groups.get(i + 1);
            if (Math.abs(b.vx - a.vx) < 0.5f && Math.abs(b.whiteW - a.whiteW) < 0.25f) {
                continue; // identical frames: nothing to animate
            }
            Glide g = new Glide();
            g.endMs = b.startMs - ARRIVE_EARLY_MS;
            g.startMs = Math.max(a.endMs, g.endMs - glideMs(a, b));
            g.fromVx = a.vx;
            g.fromW = a.whiteW;
            g.toVx = b.vx;
            g.toW = b.whiteW;
            glides.add(g);
        }
        for (Group g : groups) minWhiteW = Math.min(minWhiteW, g.whiteW);
    }

    /** One group's frame: widest width up to the default that fits its range. */
    private void computeFrame(Group g) {
        int whites = KeyWindow.whitesUpTo(g.loPitch, g.hiPitch);
        float w = defaultWhiteW;
        if (whites > 0 && w * whites > viewW) w = viewW / (float) whites;
        KeyWindow kw = KeyWindow.exact(lo, hi, viewW, w);
        g.whiteW = w;
        g.vx = kw.viewportCentering(g.loPitch, g.hiPitch);
    }

    /** Glide duration for a hop between two frames, the follow feel it replaces. */
    private double glideMs(Group a, Group b) {
        if (viewW <= 0) return GLIDE_MIN_MS;
        return Math.max(GLIDE_MIN_MS, 1.5 * Math.abs(b.vx - a.vx)
                / (viewW * GLIDE_PEAK_SCREENS_PER_SEC) * 1000.0);
    }

    /**
     * The planned camera at a song time: out[0] = viewport offset px,
     * out[1] = white-key width px. A pure function of song time, so pause,
     * scrub and wait-mode freezes need no special cases. Glides animate
     * with the same smoothstep the old follow used: zero speed at both ends.
     */
    public void valueAt(double songMs, float[] out) {
        if (groups.isEmpty()) {
            out[0] = 0f;
            out[1] = defaultWhiteW;
            return;
        }
        for (int i = 0; i < glides.size(); i++) {
            Glide g = glides.get(i);
            if (songMs < g.startMs) {
                out[0] = groups.get(i).vx;
                out[1] = groups.get(i).whiteW;
                return;
            }
            if (songMs <= g.endMs) {
                double u = g.endMs <= g.startMs ? 1.0
                        : (songMs - g.startMs) / (g.endMs - g.startMs);
                double e = u * u * (3.0 - 2.0 * u);
                out[0] = (float) (g.fromVx + (g.toVx - g.fromVx) * e);
                out[1] = (float) (g.fromW + (g.toW - g.fromW) * e);
                return;
            }
        }
        Group last = groups.get(groups.size() - 1);
        out[0] = last.vx;
        out[1] = last.whiteW;
    }

    /** Busy segments before short-gap frame merging. */
    public int segmentCount() {
        return segmentCount;
    }

    /** Static frames after merging; groups - 1 >= glides always. */
    public int groupCount() {
        return groups.size();
    }

    public int glideCount() {
        return glides.size();
    }

    /** Glide i's window, for tests: {startMs, endMs}. */
    public double[] glideWindow(int i) {
        Glide g = glides.get(i);
        return new double[]{g.startMs, g.endMs};
    }

    /** Narrowest white-key width any frame uses, px. */
    public float minWhiteW() {
        return minWhiteW;
    }

    /** Frames whose keys are narrower than wPx (the honest small-key report). */
    public int framesBelow(float wPx) {
        int n = 0;
        for (Group g : groups) if (g.whiteW < wPx - 1e-3f) n++;
        return n;
    }
}
