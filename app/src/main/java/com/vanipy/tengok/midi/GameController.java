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
import android.widget.TextView;


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

        gameView = new GameView(ctx);
        root.addView(gameView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        scoreLine = new TextView(ctx);
        scoreLine.setPadding(24, 0, 24, 16);
        root.addView(scoreLine);

        gameView.setStrikeListener(this::onStrike);
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

    public void start() {
        setupRound();
        handler.post(ticker);
    }

    private void togglePlay() {
        if (!prepared || backing == null || finished) return;
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

    private void onStrike(int pitch) {
        strike(pitch, 100);
    }

    private void onKeyboardKey(int pitch, int velocity) {
        strike(pitch, velocity);
    }

    private void strike(int pitch, int velocity) {
        if (released || scoring == null) return;
        tones.strike(pitch, velocity);
        double now = clockMs();
        int grade = scoring.strike(pitch, now);
        Log.i(TAG, "TENGOK-GAME-STRIKE pitch=" + pitch + " grade=" + grade + " ms=" + (int) now);
        if (waiting && waitMode) {
            waiting = false;
            if (backing != null) {
                try { backing.start(); } catch (IllegalStateException ignored) { }
            }
        }
        updateScoreLine();
    }

    private double clockMs() {
        if (waiting) return frozenMs;
        if (backing == null || !prepared) return 0;
        try {
            return backing.getCurrentPosition();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    private void tick() {
        if (scoring == null) return;
        double now = clockMs();
        // wait mode: freeze the clock just before the next unplayed note
        if (waitMode && !waiting && prepared && backing != null && backing.isPlaying()) {
            for (int i = 0; i < scoring.total(); i++) {
                if (scoring.stateOf(i) != GameScoring.PENDING) continue;
                double start = scoring.noteStartMs(i);
                if (start >= 0 && now >= start - 30) {
                    waiting = true;
                    frozenMs = start;
                    try { backing.pause(); } catch (IllegalStateException ignored) { }
                    playPause.setText("play");
                    Log.i(TAG, "TENGOK-GAME-WAIT pitch=" + scoring.notePitch(i));
                }
                break;
            }
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
                "perfect %d · good %d · miss %d · streak %d",
                scoring.perfect(), scoring.good(), scoring.miss(), scoring.streak()));
    }

    private void finishRound() {
        if (finished || scoring == null) return;
        finished = true;
        if (backing != null) {
            try { if (backing.isPlaying()) backing.pause(); } catch (IllegalStateException ignored) { }
        }
        playPause.setText("play");
        scoreLine.setText(String.format(Locale.US,
                "done! perfect %d · good %d · miss %d · best streak %d · accuracy %d%% · score %d",
                scoring.perfect(), scoring.good(), scoring.miss(), scoring.bestStreak(),
                scoring.accuracy(), scoring.score()));
        Log.i(TAG, "TENGOK-GAME-DONE score=" + scoring.score() + " accuracy=" + scoring.accuracy());
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
        handler.removeCallbacksAndMessages(null);
        releaseBacking();
        tones.release();
        keyboard.close();
    }
}
