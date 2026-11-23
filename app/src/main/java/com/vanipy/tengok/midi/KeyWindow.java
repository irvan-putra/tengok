package com.vanipy.tengok.midi;

/**
 * Game keyboard geometry: how wide each key is and which slice of the board
 * is on screen. The default keeps a white key at least MIN_WHITE_DP wide -
 * on a phone the full song range would otherwise squash keys to a few
 * millimetres - and pans the board when the range is wider than the screen.
 * The precomputed camera (CameraPlan) may deliberately go below that floor
 * for a passage whose keys must ALL be visible at once: zooming out beats
 * jumping around, and the plan reports how small it went instead of hiding
 * it. Pure math, no Android types, so unit tests can pin the numbers an
 * emulator would only show indirectly.
 */
public final class KeyWindow {
    /** Default white-key width floor; the planned camera may go below it. */
    public static final float MIN_WHITE_DP = 44f;
    private static final int[] BLACK = {1, 3, 6, 8, 10};

    public final int lo;
    public final int hi;
    public final int viewW;
    public final float whiteW;

    public KeyWindow(int lo, int hi, int viewW, float density) {
        this(lo, hi, viewW, density, 0f);
    }

    /**
     * @param whiteWOverridePx a chosen white-key width, or <= 0 for the
     *                         default. An override can only ever zoom OUT:
     *                         it is capped at the default width.
     */
    public KeyWindow(int lo, int hi, int viewW, float density, float whiteWOverridePx) {
        this.lo = lo;
        this.hi = hi;
        this.viewW = viewW;
        float fit = viewW > 0 ? (float) viewW / whiteCount() : 0f;
        float def = Math.max(fit, MIN_WHITE_DP * density);
        this.whiteW = whiteWOverridePx > 0f ? Math.min(whiteWOverridePx, def) : def;
    }

    private KeyWindow(int lo, int hi, int viewW, float whiteW, boolean exact) {
        this.lo = lo;
        this.hi = hi;
        this.viewW = viewW;
        this.whiteW = whiteW;
    }

    /** A window at exactly this width, for camera planning and its tests. */
    public static KeyWindow exact(int lo, int hi, int viewW, float whiteW) {
        return new KeyWindow(lo, hi, viewW, whiteW, true);
    }

    public static boolean isBlack(int pitch) {
        int s = pitch % 12;
        for (int b : BLACK) if (s == b) return true;
        return false;
    }

    public static int whitesUpTo(int lo, int pitchInclusive) {
        int c = 0;
        for (int p = lo; p <= pitchInclusive; p++) if (!isBlack(p)) c++;
        return c;
    }

    public int whiteCount() {
        return whitesUpTo(lo, hi);
    }

    public float contentW() {
        return whiteW * whiteCount();
    }

    /** Leftmost valid scroll offset; 0 when the whole board fits the screen. */
    public float maxViewport() {
        return Math.max(0f, contentW() - viewW);
    }

    public float clampViewport(float vx) {
        return Math.min(Math.max(vx, 0f), maxViewport());
    }

    /** Left edge of a key, in board coordinates (before the viewport offset). */
    public float leftPx(int pitch) {
        if (!isBlack(pitch)) {
            int idx = whitesUpTo(lo, pitch) - 1;
            return idx * whiteW;
        }
        return centerPx(pitch) - blackW() / 2;
    }

    public float rightPx(int pitch) {
        if (!isBlack(pitch)) {
            return (whitesUpTo(lo, pitch)) * whiteW;
        }
        return centerPx(pitch) + blackW() / 2;
    }

    public float centerPx(int pitch) {
        if (!isBlack(pitch)) {
            return (whitesUpTo(lo, pitch) - 0.5f) * whiteW;
        }
        // a black key sits on the boundary after its preceding white key
        return whitesUpTo(lo, pitch - 1) * whiteW;
    }

    public float blackW() {
        return whiteW * 0.62f;
    }

    /**
     * The key at a board x position, or -1: what you touch is what you get.
     * Blacks win over whites, but only inside the black-key zone (the top
     * part of the strip where black keys are actually drawn); below it the
     * white keys own the full width. This is strict containment of the
     * touch point - the drawn geometry and the hit test can never disagree.
     * Nearest-key-center arbitration was evaluated against this in the unit
     * tests (KeyWindowTest): at reduced widths it would hand a visible band
     * of every white key next to a black key to that black key, dimming a
     * key the finger did not touch, so containment stays.
     */
    public int pitchAt(float boardX, boolean blackZone) {
        if (blackZone) {
            for (int p = lo; p <= hi; p++) {
                if (!isBlack(p)) continue;
                if (boardX >= leftPx(p) && boardX < rightPx(p)) return p;
            }
        }
        for (int p = lo; p <= hi; p++) {
            if (isBlack(p)) continue;
            if (boardX >= leftPx(p) && boardX < rightPx(p)) return p;
        }
        return -1;
    }

    /** True when every key between loPitch and hiPitch is fully on screen at vx. */
    public boolean rangeVisible(int loPitch, int hiPitch, float vx) {
        return leftPx(loPitch) - vx >= 0 && rightPx(hiPitch) - vx <= viewW;
    }

    /** Viewport offset that centers the pitch range, clamped to the board. */
    public float viewportCentering(int loPitch, int hiPitch) {
        float mid = (leftPx(loPitch) + rightPx(hiPitch)) / 2f;
        return clampViewport(mid - viewW / 2f);
    }
}
