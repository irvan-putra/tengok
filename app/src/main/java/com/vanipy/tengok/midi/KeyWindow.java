package com.vanipy.tengok.midi;

/**
 * Game keyboard geometry: how wide each key is and which slice of the board
 * is on screen. On a phone the full song range would squash keys to a few
 * millimetres, so a white key never gets narrower than MIN_WHITE_DP; when the
 * range is wider than the screen, the board pans (auto-follow plus drag)
 * instead of shrinking. Pure math, no Android types, so unit tests can pin
 * the numbers an emulator would only show indirectly.
 */
public final class KeyWindow {
    /** Smallest usable white-key width; below this a fingertip covers two keys. */
    public static final float MIN_WHITE_DP = 44f;
    private static final int[] BLACK = {1, 3, 6, 8, 10};

    public final int lo;
    public final int hi;
    public final int viewW;
    public final float whiteW;

    public KeyWindow(int lo, int hi, int viewW, float density) {
        this.lo = lo;
        this.hi = hi;
        this.viewW = viewW;
        float fit = viewW > 0 ? (float) viewW / whiteCount() : 0f;
        this.whiteW = Math.max(fit, MIN_WHITE_DP * density);
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
