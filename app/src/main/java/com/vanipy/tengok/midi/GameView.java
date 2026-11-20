package com.vanipy.tengok.midi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The falling-notes game view. Notes of the player's track drop toward an
 * on-screen keyboard; the player taps the matching key as each note lands
 * (or plays it on a connected MIDI keyboard - same strike path). Velocity
 * shades the notes, timings stay exactly as written, and nothing is
 * quantized: a gently rolled chord arrives rolled.
 *
 * Keys keep a finger-sized minimum width (KeyWindow.MIN_WHITE_DP). When the
 * song's range is wider than the screen, the board pans: it follows the next
 * notes by itself, and a sideways drag on the keys looks around without
 * striking. A press strikes as soon as it is clearly not a drag (a short
 * grace, or release - whichever comes first) and the press time is carried
 * through either way, so judgment and sparks land during a hold, and
 * panning never costs timing.
 */
public final class GameView extends View {
    private static final String TAG = "tengok";
    private static final int[] CHANNEL_COLORS = {
            0xFF3F6FD8, 0xFF3DA35D, 0xFFD8913F, 0xFF9B59D0,
            0xFFD84848, 0xFF3FA8A0, 0xFFC45BA8, 0xFF8A8F3D,
            0xFF6E7FD8, 0xFF4D9E4D, 0xFFB87333, 0xFF7A5CD0,
            0xFFD05858, 0xFF2F9E8E, 0xFFB84FA0, 0xFF999933};
    /** Pending notes starting within this far after the next one steer the pan. */
    private static final double FOLLOW_WINDOW_MS = 1500;
    /** A manual drag holds the board still for this long before follow resumes. */
    private static final long MANUAL_HOLD_MS = 2500;
    /** A press held this long without turning into a pan strikes in place, so
     *  the judgment and sparks land while the finger is still down. A drag
     *  that passes the pan slop first cancels the pending strike, so a real
     *  look-around drag still never strikes. */
    private static final long STRIKE_GRACE_MS = 90;
    /** While a judged hit's key stays held, fresh sparks re-emit at this pace. */
    private static final long HELD_SPARK_MS = 110;
    /** Follow glide: minimum sweep duration and peak speed in screens/second. */
    private static final float FOLLOW_MIN_MS = 320f;
    private static final float FOLLOW_PEAK_SCREENS_PER_SEC = 0.9f;
    /** Keep this much slack at the board edges and start gliding early. */
    private static final float FOLLOW_MARGIN_DP = 18f;

    public interface StrikeListener {
        /** delayMs is how long ago the finger landed (0 for a MIDI key). */
        void onStrike(int pitch, long delayMs);
    }

    public interface SkipListener {
        void onSkip();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final List<MidiFile.Note> notes = new ArrayList<>();
    private GameScoring scoring;
    private StrikeListener listener;

    private int loPitch = 48;
    private int hiPitch = 72;
    private double nowMs;
    private double gapHintSec = -1;
    private SkipListener skipListener;

    private float viewportX;
    private boolean viewportInit;
    private long manualHoldUntil;
    private float downX;
    private float downY;
    private float lastX;
    private float maxDx;
    private boolean panning;
    /** Key under the finger right now: the visible "your tap registered". */
    private int pressedPitch = -1;
    private final Runnable strikeGrace = this::strikeGraceNow;
    private boolean struckThisGesture;
    /** Judged hit whose key is still held: sparks keep coming until release. */
    private int heldSparkPitch = -1;
    private int heldSparkColor;
    private long lastHeldSparkMs;
    private float tweenFrom;
    private float tweenDest;
    private long tweenStartMs;
    private long tweenDurMs;
    /** Last judged strike, flashed briefly on its key in the grade's color. */
    private int flashPitch = -1;
    private int flashGrade = 0;
    private long flashUntilMs = 0;
    private static final long FLASH_MS = 350;
    /** Judged-hit celebration: a short spark burst just above the hit line,
     *  where the striking finger does not cover it. Only judged hits spark;
     *  a plain registered tap keeps the quiet dim/grey flash, so sparks
     *  always mean "that one scored". */
    private static final long SPARK_MS = 480;
    private static final int SPARK_MAX = 72;
    private static final int SPARKS_PER_HIT = 12;
    /** x, y, vx (px/ms), vy (px/ms), born uptimeMs, color. */
    private final ArrayList<float[]> sparks = new ArrayList<>();
    private final Random sparkRng = new Random();
    private int candidatePitch = -1;
    private long downEventMs;
    private final int touchSlop;

    private int keysLoggedW = -1;
    private int keysLoggedH = -1;
    private int keysLoggedX = -1;
    private int keysLoggedY = -1;
    private int keysLoggedVx = -1;

    public GameView(Context ctx) {
        super(ctx);
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
    }

    public void setSong(MidiFile midi, GameScoring scoring, int track) {
        this.scoring = scoring;
        notes.clear();
        int lo = 127, hi = 0;
        for (int i = 0; i < scoring.total(); i++) {
            MidiFile.Note n = scoring.judgedNote(i);
            notes.add(n);
            if (n.pitch < lo) lo = n.pitch;
            if (n.pitch > hi) hi = n.pitch;
        }
        if (lo > hi) { lo = 60; hi = 72; }
        // pad to whole octaves so the keyboard starts and ends on C
        loPitch = Math.max(0, (lo / 12) * 12 - 12);
        hiPitch = Math.min(127, ((hi / 12) + 2) * 12 - 1);
        viewportInit = false;
        viewportX = 0;
        manualHoldUntil = 0;
        keysLoggedW = -1;
        invalidate();
    }

    /**
     * Forces the next draw to re-emit the key-geometry line even when nothing
     * moved. Debug-log contract: a round's wait can engage with the viewport
     * already centered, and a harness that cleared its logs between rounds
     * would otherwise have no fresh geometry to read.
     */
    public void relogKeys() {
        keysLoggedW = -1;
        invalidate();
    }

    public void setStrikeListener(StrikeListener l) {
        listener = l;
    }

    public void setSkipListener(SkipListener l) {
        skipListener = l;
    }

    /** Seconds until the next note during a long empty stretch; negative hides the hint. */
    public void setGapHint(double sec) {
        gapHintSec = sec;
    }

    public void setNow(double ms) {
        nowMs = ms;
        updateFollow(false);
        invalidate();
    }

    private KeyWindow keyWindow() {
        return new KeyWindow(loPitch, hiPitch, getWidth(),
                getResources().getDisplayMetrics().density);
    }

    /**
     * Keeps the next cluster of pending notes on screen. The board only moves
     * when those notes would fall outside the visible slice, so it stays
     * still through ordinary passages; motion itself is eased, not snapped.
     */
    private void updateFollow(boolean snap) {
        if (getWidth() == 0 || scoring == null) return;
        KeyWindow kw = keyWindow();
        if (kw.maxViewport() <= 0) {
            viewportX = 0;
            return;
        }
        if (SystemClock.uptimeMillis() < manualHoldUntil) {
            tweenStartMs = 0; // a frozen tween must not resume with a stale clock
            return;
        }
        double first = Double.MAX_VALUE;
        for (int i = 0; i < notes.size(); i++) {
            if (scoring.stateOf(i) == GameScoring.PENDING) {
                first = notes.get(i).startSec * 1000.0;
                break;
            }
        }
        if (first == Double.MAX_VALUE) { tweenStartMs = 0; return; }
        int lo = 127, hi = 0;
        for (int i = 0; i < notes.size(); i++) {
            if (scoring.stateOf(i) != GameScoring.PENDING) continue;
            MidiFile.Note n = notes.get(i);
            if (n.startSec * 1000.0 > first + FOLLOW_WINDOW_MS) continue;
            if (n.pitch < lo) lo = n.pitch;
            if (n.pitch > hi) hi = n.pitch;
        }
        if (lo > hi) { tweenStartMs = 0; return; }
        float margin = getResources().getDisplayMetrics().density * FOLLOW_MARGIN_DP;
        if (!snap && tweenStartMs == 0
                && kw.leftPx(lo) - viewportX >= margin
                && kw.rightPx(hi) - viewportX <= getWidth() - margin) {
            // Cluster comfortable and no sweep in flight: stay still. A
            // running sweep is NOT frozen here: it keeps gliding to its
            // target so it eases out at the center instead of cutting to a
            // dead stop at the margin.
            return;
        }
        float target = kw.viewportCentering(lo, hi);
        float diff = target - viewportX;
        if (snap) {
            viewportX = target;
            tweenFrom = tweenDest = target;
            tweenStartMs = 0;
            return;
        }
        if (Math.abs(diff) < 1f) {
            viewportX = target;
            tweenDest = target;
            tweenStartMs = 0;
            return;
        }
        // Fixed-duration smoothstep glide: zero speed at both ends, so the
        // board sweeps to the next cluster without a visible lurch, however
        // far away that cluster sits. A retarget restarts the sweep from
        // wherever the board is now.
        long tick = SystemClock.uptimeMillis();
        if (tweenStartMs == 0
                || Math.abs(target - tweenDest)
                        > getResources().getDisplayMetrics().density * 4f) {
            tweenFrom = viewportX;
            tweenDest = target;
            tweenStartMs = tick;
            tweenDurMs = (long) Math.max(FOLLOW_MIN_MS, 1.5f * Math.abs(target - viewportX)
                    / (getWidth() * FOLLOW_PEAK_SCREENS_PER_SEC) * 1000f);
        }
        float t = Math.min(1f, (tick - tweenStartMs) / (float) tweenDurMs);
        float e = t * t * (3f - 2f * t);
        viewportX = tweenFrom + (tweenDest - tweenFrom) * e;
        if (t >= 1f) tweenStartMs = 0; // sweep complete: idle until retriggered
    }

    /** Keyboard strip height, always derived from the current laid-out height. */
    private int keyboardH() {
        return (int) (getHeight() * 0.18f);
    }

    /** Horizontal rect of a key, in view coordinates (viewport applied). */
    RectF keyRect(int pitch) {
        KeyWindow kw = keyWindow();
        float top = getHeight() - keyboardH();
        float bottom = KeyWindow.isBlack(pitch) ? top + keyboardH() * 0.6f : getHeight();
        return new RectF(kw.leftPx(pitch) - viewportX, top,
                kw.rightPx(pitch) - viewportX, bottom);
    }

    /** The key at a keyboard-strip point, blacks winning over whites, or -1. */
    private int pitchAt(float x, float y) {
        for (int p = loPitch; p <= hiPitch; p++) {
            if (!KeyWindow.isBlack(p)) continue;
            if (keyRect(p).contains(x, y)) return p;
        }
        for (int p = loPitch; p <= hiPitch; p++) {
            if (KeyWindow.isBlack(p)) continue;
            if (keyRect(p).contains(x, y)) return p;
        }
        return -1;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec));
    }

    private static int rankColor(String rank) {
        switch (rank) {
            case "SSS":
            case "SS":
            case "S":
                return 0xFFFFB300;
            case "A":
                return 0xFF3F6FD8;
            case "B":
                return 0xFF3DA35D;
            case "C":
                return 0xFF8A90A2;
            default:
                return 0xFFB0B4C0;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        int kb = keyboardH();
        float hitY = h - kb;
        double pxPerSec = (h - kb) / 3.0; // ~3 seconds of lookahead
        if (!viewportInit && w > 0) {
            viewportInit = true;
            updateFollow(true);
        }

        paint.setColor(0xFFFBFBFE);
        canvas.drawRect(0, 0, w, h, paint);

        // falling notes of the player's track, velocity-shaded like the viewer.
        // Judged state stays visible: struck notes keep sinking into the piano
        // behind the keys, misses fall gray, so scrubbing back along the
        // timeline shows the round as it stands.
        for (int i = 0; i < notes.size(); i++) {
            MidiFile.Note n = notes.get(i);
            int state = scoring != null ? scoring.stateOf(i) : GameScoring.PENDING;
            double y1 = hitY - (n.startSec * 1000.0 - nowMs) / 1000.0 * pxPerSec;
            double y0 = hitY - (n.endSec * 1000.0 - nowMs) / 1000.0 * pxPerSec;
            if (y1 < 0 || y0 > hitY) continue;
            RectF kr = keyRect(n.pitch);
            if (kr.right < 0 || kr.left > w) continue;
            if (state == GameScoring.MISS) {
                paint.setColor(0xFF9AA0B0);
                paint.setAlpha(90);
            } else {
                paint.setColor(CHANNEL_COLORS[n.channel & 15]);
                paint.setAlpha(80 + (n.velocity * 175 / 127));
            }
            rect.set(kr.left + 1, (float) Math.max(0, y0), kr.right - 1, (float) Math.min(hitY, y1));
            canvas.drawRoundRect(rect, 6, 6, paint);
        }
        paint.setAlpha(255);

        // hit line
        paint.setColor(0xFF1F2A5C);
        canvas.drawRect(0, hitY - 2, w, hitY, paint);

        // long empty stretch: say what is coming instead of looking broken
        if (gapHintSec > 0) {
            String hint = "next note in " + (long) Math.ceil(gapHintSec) + " s - tap to jump";
            paint.setColor(0xFF8A90A2);
            paint.setTextSize(getResources().getDisplayMetrics().density * 15);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(hint, w / 2.0f, hitY * 0.4f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        // the live rank, big and quiet, top right of the lane
        if (scoring != null && scoring.judged() > 0) {
            String rank = scoring.rankLive();
            float density = getResources().getDisplayMetrics().density;
            paint.setColor(rankColor(rank));
            paint.setAlpha(70);
            paint.setFakeBoldText(true);
            paint.setTextSize((h - kb) * 0.24f);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(rank, w - 16 * density, (h - kb) * 0.3f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setFakeBoldText(false);
            paint.setAlpha(255);
        }

        // keyboard: whites, then blacks on top
        KeyWindow kw = keyWindow();
        for (int p = loPitch; p <= hiPitch; p++) {
            if (KeyWindow.isBlack(p)) continue;
            RectF kr = keyRect(p);
            if (kr.right < 0 || kr.left > w) continue;
            paint.setColor(0xFFFFFFFF);
            canvas.drawRect(kr, paint);
            paint.setColor(0xFFB9BECB);
            canvas.drawRect(kr.left, kr.top, kr.left + 1, kr.bottom, paint);
        }
        for (int p = loPitch; p <= hiPitch; p++) {
            if (!KeyWindow.isBlack(p)) continue;
            RectF kr = keyRect(p);
            if (kr.right < 0 || kr.left > w) continue;
            paint.setColor(0xFF22242E);
            canvas.drawRect(kr, paint);
        }

        // input feedback: the key under the finger dims while held; a judged
        // strike flashes its key green (Perfect), amber (Good) or grey
        // (registered, but no pending note there), fading over FLASH_MS.
        long nowUp = SystemClock.uptimeMillis();
        for (int p = loPitch; p <= hiPitch; p++) {
            RectF kr = keyRect(p);
            if (kr.right < 0 || kr.left > w) continue;
            boolean black = KeyWindow.isBlack(p);
            if (p == pressedPitch) {
                paint.setColor(black ? 0x66FFFFFF : 0x33000000);
                canvas.drawRect(kr, paint);
            }
            if (p == flashPitch && nowUp < flashUntilMs) {
                int base;
                if (flashGrade == GameScoring.PERFECT) base = 0xFF3DA35D;
                else if (flashGrade == GameScoring.GOOD) base = 0xFFFFB300;
                else base = 0xFF8A90A2;
                int a = (int) (110 * (flashUntilMs - nowUp) / (float) FLASH_MS);
                paint.setColor((a << 24) | (base & 0x00FFFFFF));
                canvas.drawRect(kr, paint);
                postInvalidateDelayed(30); // keep the fade animating
            }
        }

        // judged-hit sparks above the hit line; physics integrate on the
        // wall clock so frame rate changes smoothness, never positions.
        if (!sparks.isEmpty()) {
            float d = getResources().getDisplayMetrics().density;
            for (int i = sparks.size() - 1; i >= 0; i--) {
                float[] s = sparks.get(i);
                float age = nowUp - s[4];
                if (age > SPARK_MS) { sparks.remove(i); continue; }
                float t = age / (float) SPARK_MS;
                float x = s[0] + s[2] * age;
                float y = s[1] + s[3] * age + 0.0005f * d * age * age;
                paint.setColor(((int) ((1 - t) * 255) << 24) | ((int) s[5] & 0x00FFFFFF));
                canvas.drawCircle(x, y, (3.4f - 1.4f * t) * d, paint);
            }
            if (!sparks.isEmpty()) postInvalidateDelayed(30);
        }
        // a held judged key keeps a gentle sparkle going until release
        if (heldSparkPitch >= 0) {
            if (pressedPitch != heldSparkPitch) {
                heldSparkPitch = -1;
            } else {
                if (nowUp - lastHeldSparkMs >= HELD_SPARK_MS) {
                    lastHeldSparkMs = nowUp;
                    spawnSparks(heldSparkPitch, heldSparkColor, 3);
                }
                postInvalidateDelayed(30);
            }
        }
        // scoring readout lives in the panel text under this view.

        int[] loc = new int[2];
        getLocationOnScreen(loc);
        if (w > 0 && (w != keysLoggedW || h != keysLoggedH
                || loc[0] != keysLoggedX || loc[1] != keysLoggedY
                || (int) viewportX != keysLoggedVx)) {
            keysLoggedW = w;
            keysLoggedH = h;
            keysLoggedX = loc[0];
            keysLoggedY = loc[1];
            keysLoggedVx = (int) viewportX;
            StringBuilder sb = new StringBuilder("TENGOK-GAME-KEYS");
            for (int p = loPitch; p <= hiPitch; p++) {
                RectF kr = keyRect(p);
                sb.append(' ').append(p).append(':')
                        .append((int) (loc[0] + kr.left)).append(',')
                        .append((int) (loc[0] + kr.right)).append(',')
                        .append((int) (loc[1] + (kr.top + kr.bottom) / 2));
            }
            sb.append(" | w=").append(w).append(" h=").append(h)
                    .append(" loc=").append(loc[0]).append(',').append(loc[1])
                    .append(" kb=").append(keyboardH())
                    .append(" lo=").append(loPitch).append(" hi=").append(hiPitch)
                    .append(" vx=").append((int) viewportX)
                    .append(" kwd=").append((int) (kw.whiteW
                            / getResources().getDisplayMetrics().density));
            Log.i(TAG, sb.toString());
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = e.getX();
                float y = e.getY();
                downX = lastX = x;
                downY = y;
                maxDx = 0;
                panning = false;
                downEventMs = e.getEventTime();
                candidatePitch = -1;
                struckThisGesture = false;
                if (y < getHeight() - keyboardH()) {
                    // the lane itself: only meaningful while a skip is on offer
                    if (gapHintSec > 0 && skipListener != null) skipListener.onSkip();
                } else {
                    candidatePitch = pitchAt(x, y);
                    if (candidatePitch >= 0) {
                        pressedPitch = candidatePitch; // immediate "registered"
                        postDelayed(strikeGrace, STRIKE_GRACE_MS);
                        invalidate();
                    }
                    // A real finger rolls a little vertically during a tap. Do
                    // not let a scrolling parent steal the stream mid-tap and
                    // turn it into a CANCEL - the keyboard owns this touch.
                    android.view.ViewParent parent = getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (Math.abs(e.getY() - downY) > touchSlop * 3) {
                    candidatePitch = -1; // a deliberate vertical drag is not a tap
                    if (pressedPitch >= 0) { pressedPitch = -1; invalidate(); }
                }
                KeyWindow kw = keyWindow();
                if (kw.maxViewport() <= 0) return true;
                float x = e.getX();
                maxDx = Math.max(maxDx, Math.abs(x - downX));
                if (!panning && maxDx > touchSlop) {
                    panning = true; // a drag pans; the candidate survives small wobble
                    removeCallbacks(strikeGrace); // but a real drag never strikes
                }
                if (panning) {
                    viewportX = kw.clampViewport(viewportX - (x - lastX));
                    tweenFrom = tweenDest = viewportX;
                    tweenStartMs = 0;
                    lastX = x;
                    manualHoldUntil = SystemClock.uptimeMillis() + MANUAL_HOLD_MS;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                // A real tap wobbles. Forgive a quick gesture whose finger
                // never strayed far: still the strike the user meant. A true
                // look-around drag (sustained displacement) never strikes.
                removeCallbacks(strikeGrace);
                if (!struckThisGesture && candidatePitch >= 0
                        && (!panning || (maxDx < touchSlop * 2.5f
                                && e.getEventTime() - downEventMs < 300))) {
                    strike(candidatePitch, e.getEventTime() - downEventMs);
                }
                candidatePitch = -1;
                pressedPitch = -1;
                heldSparkPitch = -1;
                panning = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(strikeGrace);
                candidatePitch = -1;
                pressedPitch = -1;
                heldSparkPitch = -1;
                panning = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    /** The controller judged a strike: flash the key in the grade's color.
     *  grade PENDING means the tap registered but matched no pending note. */
    public void showStrikeGrade(int pitch, int grade) {
        flashPitch = pitch;
        flashGrade = grade;
        flashUntilMs = SystemClock.uptimeMillis() + FLASH_MS;
        if (grade == GameScoring.PERFECT || grade == GameScoring.GOOD) {
            int color = grade == GameScoring.PERFECT ? 0xFFFFD84D : 0xFF9BE15D;
            spawnSparks(pitch, color, SPARKS_PER_HIT);
            if (pressedPitch == pitch) {
                // still held: keep sparkling until the finger lifts
                heldSparkPitch = pitch;
                heldSparkColor = color;
                lastHeldSparkMs = SystemClock.uptimeMillis();
                postInvalidateDelayed(30);
            }
        }
        invalidate();
    }

    private void spawnSparks(int pitch, int color, int count) {
        RectF kr = keyRect(pitch);
        float d = getResources().getDisplayMetrics().density;
        long born = SystemClock.uptimeMillis();
        for (int i = 0; i < count; i++) {
            // a tight column at the border between the falling notes and the
            // piano: inside the struck key's width, just above its top
            float x = kr.left + kr.width() * (0.25f + sparkRng.nextFloat() * 0.5f);
            float y = kr.top - 2 * d;
            float vx = (sparkRng.nextFloat() - 0.5f) * 0.04f * d;
            float vy = -(0.06f + sparkRng.nextFloat() * 0.10f) * d;
            sparks.add(new float[]{x, y, vx, vy, born, color});
        }
        while (sparks.size() > SPARK_MAX) sparks.remove(0);
        postInvalidateDelayed(30);
    }

    /** Grace elapsed with the finger still down and no pan: strike now, so a
     *  held key judges and sparks during the hold, not only at release. */
    private void strikeGraceNow() {
        if (struckThisGesture || panning || candidatePitch < 0) return;
        struckThisGesture = true;
        strike(candidatePitch, SystemClock.uptimeMillis() - downEventMs);
    }

    private void strike(int pitch, long delayMs) {
        Log.i(TAG, "TENGOK-GAME-TAP pitch=" + pitch);
        if (listener != null) listener.onStrike(pitch, delayMs);
    }
}
