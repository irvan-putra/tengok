package com.vanipy.tengok.midi;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;


import com.vanipy.tengok.DiagLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * Runs one round of the game: rebuilds the song without the player's track
 * (expression intact), plays that as backing, judges taps/keys against the
 * original notes, and reports the score at the end. "Wait for me" mode
 * pauses the song at the next note until the player strikes it - the
 * melody-practice idea, for any MIDI file on the phone.
 */
public final class GameController {
    private static final String TAG = "tengok";
    /** Runway left before a note when the game jumps forward, ms of song time. */
    private static final double RUNWAY_MS = 2000;
    /** Gaps longer than this offer a skip instead of an empty lane. */
    private static final double GAP_HINT_MS = 4000;

    public interface ExitListener {
        void onExitGame();
    }

    private final Context ctx;
    private final MidiFile midi;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinearLayout root;
    private final GameView gameView;
    private final TextView status;
    private final TextView scoreLine;
    private final SeekBar timeline;
    private final TextView timelineClock;
    private final Button playPause;
    private final Button speedButton;
    private final Button waitButton;
    private final TonePlayer tones = new TonePlayer();
    private final MidiKeyboard keyboard;
    private ExitListener exitListener;

    private int userTrack;
    private double speed = 1.0;
    private boolean waitMode;
    private boolean waiting;
    private GameScoring scoring;
    private MediaPlayer backing;
    private boolean prepared;
    private boolean released;
    private boolean finished;
    private double frozenMs;
    private int hintForNote = -1;
    private boolean scrubbing;
    private String totalText = "";

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            tick();
            handler.postDelayed(this, 33);
        }
    };

    public GameController(Context ctx, MidiFile midi, int userTrack, ExitListener exitListener) {
        this.ctx = ctx;
        this.midi = midi;
        this.userTrack = userTrack;
        this.exitListener = exitListener;
        this.keyboard = new MidiKeyboard(ctx, this::onKeyboardKey);

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        playPause = new Button(ctx);
        playPause.setText("play");
        speedButton = new Button(ctx);
        speedButton.setText("1.0x");
        waitButton = new Button(ctx);
        waitButton.setText("wait for me: off");
        Button exit = new Button(ctx);
        exit.setText("exit game");
        bar.addView(playPause, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(speedButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(waitButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(exit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        status = new TextView(ctx);
        status.setPadding(24, 8, 24, 0);
        root.addView(status);

        // the same horizontal song timeline as normal playback, so position is
        // visible even when no notes are falling; dragging scrubs the round
        LinearLayout seekRow = new LinearLayout(ctx);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        timeline = new SeekBar(ctx);
        timeline.setMax(Math.max(1, (int) (midi.durationSeconds * 1000)));
        timeline.setContentDescription("song timeline");
        seekRow.addView(timeline, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        timelineClock = new TextView(ctx);
        timelineClock.setPadding(24, 0, 24, 0);
        seekRow.addView(timelineClock);
        root.addView(seekRow);
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                scrubbing = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                scrubbing = false;
                scrubTo(sb.getProgress());
            }
        });

        gameView = new GameView(ctx);
        root.addView(gameView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        scoreLine = new TextView(ctx);
        scoreLine.setPadding(24, 0, 24, 16);
        root.addView(scoreLine);

        gameView.setStrikeListener(this::onStrike);
        gameView.setSkipListener(this::onSkipToNext);
        playPause.setOnClickListener(v -> togglePlay());
        speedButton.setOnClickListener(v -> cycleSpeed());
        waitButton.setOnClickListener(v -> toggleWait());
        exit.setOnClickListener(v -> exit());

        if (midi.smpte) {
            status.setText("This song uses SMPTE timing, which game mode does not support yet.");
            playPause.setEnabled(false);
            speedButton.setEnabled(false);
        } else {
            status.setText(trackLabel());
        }
        keyboard.open();
    }

    private String trackLabel() {
        MidiFile.Track t = midi.tracks.get(userTrack);
        String name = t.name.isEmpty() ? "track " + (userTrack + 1) : t.name;
        return "Your part: " + name + " (" + t.noteCount + " notes)"
                + (keyboard.available() ? " - tap keys below or play a connected MIDI keyboard"
                                        : " - tap the keys below");
    }

    public View view() {
        return root;
    }

    private void setupRound() {
        releaseBacking();
        scoring = new GameScoring(midi, userTrack);
        gameView.setSong(midi, scoring, userTrack);
        finished = false;
        waiting = false;
        frozenMs = 0;
        hintForNote = -1;
        totalText = MidiPanel.fmtTime(midi.durationSeconds);
        timeline.setProgress(0);
        timelineClock.setText("0:00 / " + totalText);
        // numbers only, no file or track names: lets a shared diagnostic log say
        // whether a blank lane was a late entrance or something wrong in the times
        Log.i(TAG, "TENGOK-GAME-NOTES track=" + userTrack + " count=" + scoring.total()
                + " firstMs=" + (int) scoring.firstStartMs()
                + " lastMs=" + (int) scoring.lastStartMs()
                + " badTimes=" + scoring.badTimeCount());
        DiagLog.log(ctx, "GAME-NOTES count=" + scoring.total()
                + " firstMs=" + (int) scoring.firstStartMs()
                + " lastMs=" + (int) scoring.lastStartMs()
                + " badTimes=" + scoring.badTimeCount());
        byte[] bytes = MidiWriter.write(midi, Collections.singleton(userTrack), speed);
        File f = new File(ctx.getCacheDir(), "tengok-game-backing.mid");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        } catch (IOException e) {
            status.setText("Could not prepare the backing track.");
            return;
        }
        backing = new MediaPlayer();
        try {
            backing.setDataSource(f.getAbsolutePath());
            backing.setOnPreparedListener(mp -> {
                prepared = true;
                Log.i(TAG, "TENGOK-GAME-READY track=" + userTrack + " speed=" + speed);
            });
            backing.setOnCompletionListener(mp -> finishRound());
            backing.prepareAsync();
        } catch (IOException | RuntimeException e) {
            status.setText("This phone could not play the backing track.");
        }
        updateScoreLine();
    }

    /** DEBUG-ONLY: adb-reachable chord injection for the emulator smoke
     *  test (a synthetic MotionEvent dispatch, not real hardware input).
     *  Registered only in debug builds, only while the game runs. */
    private android.content.BroadcastReceiver chordReceiver;

    public void start() {
        if (com.vanipy.tengok.BuildConfig.DEBUG && chordReceiver == null) {
            chordReceiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, android.content.Intent i) {
                    if (gameView != null) {
                        gameView.debugInjectChord(i.getIntExtra("a", -1),
                                i.getIntExtra("b", -1));
                    }
                }
            };
            android.content.IntentFilter f =
                    new android.content.IntentFilter("com.vanipy.tengok.debug.CHORD");
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(chordReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(chordReceiver, f);
            }
        }
        setupRound();
        handler.post(ticker);
    }

    private void togglePlay() {
        if (finished) {
            // a done round restarts deliberately from the top on play
            setupRound();
            handler.postDelayed(this::togglePlay, 400);
            return;
        }
        if (!prepared || backing == null) return;
        try {
            if (waiting) {
                waiting = false;
                backing.start();
            } else if (backing.isPlaying()) {
                backing.pause();
                playPause.setText("play");
            } else {
                backing.start();
            }
            if (backing.isPlaying()) playPause.setText("pause");
        } catch (IllegalStateException ignored) {
        }
    }

    private void cycleSpeed() {
        speed = speed >= 1.0 ? 0.75 : speed >= 0.75 ? 0.5 : 1.0;
        speedButton.setText(String.format(Locale.US, "%.2gx", speed));
        boolean wasPlaying = prepared && backing != null && backing.isPlaying();
        setupRound();
        if (wasPlaying) handler.postDelayed(this::togglePlay, 400);
    }

    private void toggleWait() {
        waitMode = !waitMode;
        waitButton.setText(waitMode ? "wait for me: on" : "wait for me: off");
    }

    private void onStrike(int pitch, long delayMs) {
        strike(pitch, 100, delayMs);
    }

    private void onKeyboardKey(int pitch, int velocity) {
        strike(pitch, velocity, 0);
    }

    private void strike(int pitch, int velocity, long delayMs) {
        if (released || scoring == null) return;
        tones.strike(pitch, velocity);
        // a screen tap reports when the finger landed, not when it lifted:
        // judging happens on the landing time so a careful tap is not taxed
        // for its own length (a frozen wait-mode clock stays at the freeze)
        double now = clockMs();
        if (!waiting) now = Math.max(0, now - delayMs);
        int grade = scoring.strike(pitch, now);
        gameView.showStrikeGrade(pitch, grade);
        Log.i(TAG, "TENGOK-GAME-STRIKE pitch=" + pitch + " grade=" + grade + " ms=" + (int) now);
        if (waiting && waitMode) {
            waiting = false;
            if (backing != null) {
                try { backing.start(); } catch (IllegalStateException ignored) { }
            }
        }
        updateScoreLine();
    }

    /**
     * Song time runs on the written score: the backing file's tempo map is
     * scaled by 1/speed, so a media position p corresponds to score time
     * p*speed. View, scoring and freeze logic all live on the score clock,
     * which keeps the falling notes on the music at every speed.
     */
    private double clockMs() {
        if (waiting) return frozenMs;
        if (backing == null || !prepared) return 0;
        try {
            return backing.getCurrentPosition() * speed;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    /** Moves the backing so the score clock reads songMs, and logs it. */
    private void seekSongMs(double songMs, String marker) {
        if (backing == null || !prepared) return;
        int mediaMs = (int) Math.max(0, Math.round(songMs / speed));
        try {
            backing.seekTo(mediaMs);
            Log.i(TAG, "TENGOK-GAME-" + marker + " mediaMs=" + mediaMs
                    + " songMs=" + (int) songMs + " speed=" + speed);
        } catch (IllegalStateException ignored) {
        }
    }

    /**
     * Drags on the song timeline, in score ms. Judging stays monotonic: notes
     * already judged keep their verdict, and pending notes the scrub passes
     * over are missed as the clock crosses them - the same honesty as the
     * lane's tap-to-jump. A scrub while frozen simply unfreezes there.
     */
    private void scrubTo(int songMs) {
        if (released || scoring == null || !prepared) return;
        waiting = false;
        gameView.setGapHint(-1);
        hintForNote = -1;
        seekSongMs(songMs, "SCRUB");
    }

    /** Lane tap while a long gap is announced: jump to just before the next note. */
    private void onSkipToNext() {
        if (released || scoring == null || waiting || finished) return;
        int next = scoring.nextPendingIndex();
        if (next < 0) return;
        double nextMs = scoring.noteStartMs(next);
        if (nextMs - clockMs() <= GAP_HINT_MS) return;
        seekSongMs(nextMs - RUNWAY_MS, "SKIP");
        gameView.setGapHint(-1);
        hintForNote = -1;
    }

    private void tick() {
        if (scoring == null) return;
        double now = clockMs();
        if (!scrubbing) {
            timeline.setProgress((int) Math.min(now, timeline.getMax()));
            timelineClock.setText(MidiPanel.fmtTime(now / 1000.0) + " / " + totalText);
        }
        int next = scoring.nextPendingIndex();
        double nextMs = next < 0 ? -1 : scoring.noteStartMs(next);
        // wait mode: freeze the clock just before the next unplayed note
        if (waitMode && !waiting && prepared && backing != null && backing.isPlaying()
                && nextMs >= 0 && now >= nextMs - 30) {
            waiting = true;
            frozenMs = nextMs;
            try { backing.pause(); } catch (IllegalStateException ignored) { }
            playPause.setText("play");
            Log.i(TAG, "TENGOK-GAME-WAIT pitch=" + scoring.notePitch(next));
            gameView.relogKeys(); // fresh geometry for the harness even when the wait moved nothing
        }
        // long empty stretch ahead (a late entrance or a mid-song rest): say so
        // on the lane and offer a tap-to-jump, instead of looking broken
        if (!waiting && nextMs >= 0 && nextMs - now > GAP_HINT_MS) {
            gameView.setGapHint((nextMs - now) / 1000.0);
            if (hintForNote != next) {
                hintForNote = next;
                Log.i(TAG, "TENGOK-GAME-HINT nextSongMs=" + (int) nextMs + " nowMs=" + (int) now);
            }
        } else {
            gameView.setGapHint(-1);
            hintForNote = -1;
        }
        if (!waiting) {
            scoring.tick(now);
        }
        gameView.setNow(waiting ? frozenMs : now);
        if (scoring.finished() && !finished) {
            finishRound();
        }
        if (finished) return;
        updateScoreLine();
    }

    private void updateScoreLine() {
        if (scoring == null) return;
        scoreLine.setText(String.format(Locale.US,
                "perfect %d · good %d · miss %d · streak %d · rank %s",
                scoring.perfect(), scoring.good(), scoring.miss(), scoring.streak(),
                scoring.rankLive()));
    }

    private void finishRound() {
        if (finished || scoring == null) return;
        finished = true;
        if (backing != null) {
            try { if (backing.isPlaying()) backing.pause(); } catch (IllegalStateException ignored) { }
        }
        playPause.setText("play");
        scoreLine.setText(String.format(Locale.US,
                "done! perfect %d · good %d · miss %d · best streak %d · accuracy %d%% · score %d · rank %s",
                scoring.perfect(), scoring.good(), scoring.miss(), scoring.bestStreak(),
                scoring.accuracy(), scoring.score(), scoring.rankFinal()));
        Log.i(TAG, "TENGOK-GAME-DONE score=" + scoring.score() + " accuracy=" + scoring.accuracy()
                + " rank=" + scoring.rankFinal());
    }

    private void exit() {
        release();
        if (exitListener != null) exitListener.onExitGame();
    }

    private void releaseBacking() {
        prepared = false;
        if (backing != null) {
            try { backing.reset(); } catch (RuntimeException ignored) { }
            backing.release();
            backing = null;
        }
    }

    public void release() {
        released = true;
        if (chordReceiver != null) {
            try { ctx.unregisterReceiver(chordReceiver); }
            catch (IllegalArgumentException ignored) { }
            chordReceiver = null;
        }
        handler.removeCallbacksAndMessages(null);
        releaseBacking();
        tones.release();
        keyboard.close();
    }
}
