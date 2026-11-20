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
 * striking. A tap still strikes at the instant the finger lands - the press
 * time is carried through the tap check, so panning never costs timing.
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
        if (SystemClock.uptimeMillis() < manualHoldUntil) return;
        double first = Double.MAX_VALUE;
        for (int i = 0; i < notes.size(); i++) {
            if (scoring.stateOf(i) == GameScoring.PENDING) {
                first = notes.get(i).startSec * 1000.0;
                break;
            }
        }
        if (first == Double.MAX_VALUE) return;
        int lo = 127, hi = 0;
        for (int i = 0; i < notes.size(); i++) {
            if (scoring.stateOf(i) != GameScoring.PENDING) continue;
            MidiFile.Note n = notes.get(i);
            if (n.startSec * 1000.0 > first + FOLLOW_WINDOW_MS) continue;
            if (n.pitch < lo) lo = n.pitch;
            if (n.pitch > hi) hi = n.pitch;
        }
        if (lo > hi) return;
        if (kw.rangeVisible(lo, hi, viewportX)) return;
        float target = kw.viewportCentering(lo, hi);
        float diff = target - viewportX;
        viewportX = (snap || Math.abs(diff) < 1f) ? target : viewportX + diff * 0.25f;
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
        // Judged state stays visible: played notes are gone, misses fall gray,
        // so scrubbing back along the timeline shows the round as it stands.
        for (int i = 0; i < notes.size(); i++) {
            MidiFile.Note n = notes.get(i);
            int state = scoring != null ? scoring.stateOf(i) : GameScoring.PENDING;
            if (state == GameScoring.PERFECT || state == GameScoring.GOOD) continue;
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

        // judged-note feedback: brief tint over the key area is handled by alpha notes;
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
                if (y < getHeight() - keyboardH()) {
                    // the lane itself: only meaningful while a skip is on offer
                    if (gapHintSec > 0 && skipListener != null) skipListener.onSkip();
                } else {
                    candidatePitch = pitchAt(x, y);
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
                }
                KeyWindow kw = keyWindow();
                if (kw.maxViewport() <= 0) return true;
                float x = e.getX();
                maxDx = Math.max(maxDx, Math.abs(x - downX));
                if (!panning && maxDx > touchSlop) {
                    panning = true; // a drag pans; the candidate survives small wobble
                }
                if (panning) {
                    viewportX = kw.clampViewport(viewportX - (x - lastX));
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
                if (candidatePitch >= 0
                        && (!panning || (maxDx < touchSlop * 2.5f
                                && e.getEventTime() - downEventMs < 300))) {
                    strike(candidatePitch, e.getEventTime() - downEventMs);
                }
                candidatePitch = -1;
                panning = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                candidatePitch = -1;
                panning = false;
                return true;
            default:
                return true;
        }
    }

    private void strike(int pitch, long delayMs) {
        Log.i(TAG, "TENGOK-GAME-TAP pitch=" + pitch);
        if (listener != null) listener.onStrike(pitch, delayMs);
    }
}
