package com.vanipy.tengok.midi;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.vanipy.tengok.DiagLog;
import com.vanipy.tengok.Source;
import com.vanipy.tengok.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The MIDI screen: a summary, the instruments in each track, a piano roll,
 * and playback through the phone's built-in MIDI synth (MediaPlayer, fully
 * offline). If a device has no MIDI synth the panel says so instead of
 * pretending to play.
 */
public class MidiPanel {
    private static final String TAG = "tengok";

    private final Context ctx;
    private final Source source;
    private final MidiFile midi;
    private final LinearLayout root;
    private MediaPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PianoRollView roll;
    private HorizontalScrollView rollScroll;
    private SeekBar seek;
    private TextView clock;
    private Button playButton;
    private boolean prepared;
    private boolean released;
    private GameController game;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (released || player == null || !prepared) return;
            try {
                if (player.isPlaying()) {
                    int ms = player.getCurrentPosition();
                    Log.i(TAG, "TENGOK-MIDI-POS ms=" + ms);
                    updateClock(ms);
                    followCursor();
                }
            } catch (IllegalStateException ignored) {
            }
            handler.postDelayed(this, 500);
        }
    };

    public MidiPanel(Context ctx, Source source, MidiFile midi) {
        this.ctx = ctx;
        this.source = source;
        this.midi = midi;
        this.root = build();
    }

    public View view() {
        return root;
    }

    private LinearLayout build() {
        LinearLayout column = Ui.column(ctx);

        LinearLayout summary = Ui.card(ctx);
        summary.addView(Ui.line(ctx, "MIDI format " + midi.format + " · " + midi.tracks.size()
                + (midi.tracks.size() == 1 ? " track" : " tracks") + " · " + fmtTime(midi.durationSeconds)));
        StringBuilder meta = new StringBuilder();
        meta.append(midi.smpte ? "SMPTE timing" : midi.ticksPerQuarter + " ticks per quarter note");
        if (!midi.tempos.isEmpty()) {
            meta.append(" · ").append(tempoText());
        }
        meta.append(" · ").append(midi.timeSigNum).append("/").append(midi.timeSigDen);
        if (!midi.keySig.isEmpty()) meta.append(" · ").append(midi.keySig);
        summary.addView(Ui.muted(ctx, meta.toString()));
        summary.addView(Ui.muted(ctx, midi.notes.size() + (midi.notes.size() == 1 ? " note" : " notes")));
        column.addView(summary);

        for (MidiFile.Track t : midi.tracks) {
            if (t.noteCount == 0 && t.programs.isEmpty() && t.name.isEmpty()) continue;
            LinearLayout card = Ui.card(ctx);
            String title = "Track " + (t.index + 1) + (t.name.isEmpty() ? "" : " · " + t.name);
            card.addView(Ui.line(ctx, title));
            StringBuilder sb = new StringBuilder();
            if (t.programs.isEmpty()) {
                sb.append(t.usesChannel(9) ? "Percussion" : "no instrument changes");
            } else {
                for (int i = 0; i < t.programs.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(GmNames.instrument(t.programs.get(i)));
                }
            }
            card.addView(Ui.muted(ctx, sb.toString()));
            if (t.noteCount > 0) {
                card.addView(Ui.muted(ctx, t.noteCount + " notes · " + GmNames.pitch(t.minPitch)
                        + " to " + GmNames.pitch(t.maxPitch)));
            }
            column.addView(card);
        }

        if (!midi.notes.isEmpty()) {
            LinearLayout rollCard = Ui.card(ctx);
            roll = new PianoRollView(ctx);
            int rollHeight = (int) (180 * ctx.getResources().getDisplayMetrics().density);
            roll.setMidi(midi, rollHeight);
            HorizontalScrollView hsv = new HorizontalScrollView(ctx);
            rollScroll = hsv;
            hsv.addView(roll);
            rollCard.addView(hsv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (180 * ctx.getResources().getDisplayMetrics().density)));
            column.addView(rollCard);
        }

        LinearLayout transport = Ui.card(ctx);
        LinearLayout row = Ui.row(ctx);
        playButton = new Button(ctx);
        playButton.setText("Play");
        playButton.setEnabled(false);
        playButton.setOnClickListener(v -> togglePlay());
        row.addView(playButton);
        if (!midi.smpte && !midi.notes.isEmpty()) {
            Button gameButton = new Button(ctx);
            gameButton.setText("Game");
            gameButton.setOnClickListener(v -> startGame());
            row.addView(gameButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        clock = new TextView(ctx);
        clock.setText("0:00 / " + fmtTime(midi.durationSeconds));
        clock.setPadding(24, 0, 0, 0);
        row.addView(clock);
        transport.addView(row);
        seek = new SeekBar(ctx);
        seek.setMax(Math.max(1, (int) (midi.durationSeconds * 1000)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && prepared && player != null) {
                    try {
                        player.seekTo(progress);
                        updateClock(progress);
                    } catch (IllegalStateException ignored) {
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        transport.addView(seek);
        TextView synthNote = Ui.muted(ctx,
                "Plays on this phone's built-in MIDI synth. No files leave the phone.");
        transport.addView(synthNote);
        column.addView(transport);

        preparePlayer();
        // The viewer cards scroll; game mode must fill the viewport instead,
        // so the scroll wrapper lives here, not around the panel root.
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(column);
        wrap.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return wrap;
    }

    private String tempoText() {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        long lastBpm = -1;
        for (MidiFile.TempoChange t : midi.tempos) {
            long bpm = Math.round(60_000_000.0 / t.usPerQuarter);
            if (bpm != lastBpm) {
                if (shown > 0) sb.append(" then ");
                sb.append(bpm).append(" bpm");
                shown++;
                lastBpm = bpm;
            }
            if (shown >= 3) {
                sb.append("…");
                break;
            }
        }
        return sb.toString();
    }

    /** Default "your part": the note-bearing track with the highest average pitch (the melody). */
    private int defaultUserTrack() {
        int best = -1;
        double bestAvg = -1;
        for (MidiFile.Track t : midi.tracks) {
            if (t.noteCount == 0) continue;
            double sum = 0;
            for (MidiFile.Note n : midi.notes) if (n.track == t.index) sum += n.pitch;
            double avg = sum / t.noteCount;
            if (avg > bestAvg) {
                bestAvg = avg;
                best = t.index;
            }
        }
        return best;
    }

    private void startGame() {
        List<Integer> playable = new ArrayList<>();
        for (MidiFile.Track t : midi.tracks) if (t.noteCount > 0) playable.add(t.index);
        if (playable.isEmpty()) return;
        if (playable.size() == 1) {
            launchGame(playable.get(0));
            return;
        }
        String[] names = new String[playable.size()];
        for (int i = 0; i < playable.size(); i++) {
            MidiFile.Track t = midi.tracks.get(playable.get(i));
            names[i] = "Track " + (t.index + 1) + (t.name.isEmpty() ? "" : " · " + t.name)
                    + " (" + t.noteCount + " notes)";
        }
        int suggested = playable.indexOf(defaultUserTrack());
        new android.app.AlertDialog.Builder(ctx)
                .setTitle("Which part do you want to play?")
                .setSingleChoiceItems(names, suggested, (d, which) -> {
                    d.dismiss();
                    launchGame(playable.get(which));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void launchGame(int track) {
        if (player != null && prepared) {
            try { if (player.isPlaying()) player.pause(); } catch (IllegalStateException ignored) { }
        }
        game = new GameController(ctx, midi, track, this::exitGame);
        root.removeAllViews();
        root.addView(game.view(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        game.start();
    }

    private void exitGame() {
        if (game != null) {
            game.release();
            game = null;
        }
        // rebuild the viewer cards into the same container
        root.removeAllViews();
        LinearLayout rebuilt = build();
        while (rebuilt.getChildCount() > 0) {
            View child = rebuilt.getChildAt(0);
            rebuilt.removeView(child);
            root.addView(child);
        }
    }

    private void preparePlayer() {
        try {
            player = new MediaPlayer();
            Uri uri = source.uri();
            File local = source.localFile();
            if (uri != null) {
                player.setDataSource(ctx, uri);
            } else if (local != null) {
                player.setDataSource(local.getAbsolutePath());
            } else {
                throw new IllegalStateException("no playable source");
            }
            player.setOnPreparedListener(mp -> {
                prepared = true;
                playButton.setEnabled(true);
                seek.setMax(Math.max(1, mp.getDuration()));
                Log.i(TAG, "TENGOK-MIDI-PREPARED durationMs=" + mp.getDuration());
            });
            player.setOnCompletionListener(mp -> {
                playButton.setText("Play");
                updateClock(0);
                if (roll != null) roll.setCursor(-1);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "TENGOK-MIDI-ERROR what=" + what + " extra=" + extra);
                DiagLog.log(ctx, "PLAY-ERR midi what=" + what + " extra=" + extra);
                playButton.setEnabled(false);
                Toast.makeText(ctx, "This device could not play the MIDI file.", Toast.LENGTH_LONG).show();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "TENGOK-MIDI-ERROR " + e.getClass().getSimpleName());
            DiagLog.log(ctx, "PLAY-ERR midi: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()));
            playButton.setEnabled(false);
            Toast.makeText(ctx, "This device has no MIDI playback: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void togglePlay() {
        if (!prepared || player == null) return;
        try {
            if (player.isPlaying()) {
                player.pause();
                playButton.setText("Play");
                Log.i(TAG, "TENGOK-MIDI-PAUSED");
            } else {
                player.start();
                playButton.setText("Pause");
                Log.i(TAG, "TENGOK-MIDI-PLAYING");
                handler.removeCallbacks(ticker);
                handler.post(ticker);
            }
        } catch (IllegalStateException ignored) {
        }
    }

    /**
     * Keep the playhead visible while the song plays. Only corrects when the
     * line nears the right edge or leaves the left of the viewport, so a
     * manual scroll that still shows the line is left alone.
     */
    private void followCursor() {
        if (roll == null || rollScroll == null) return;
        float x = roll.cursorX();
        if (x < 0) return;
        int vw = rollScroll.getWidth();
        if (vw <= 0) return;
        int sx = rollScroll.getScrollX();
        if (x < sx + vw * 0.10f || x > sx + vw * 0.66f) {
            int target = Math.max(0, (int) (x - vw * 0.33f));
            if (target == sx) return;
            Log.i(TAG, "TENGOK-MIDI-FOLLOW sx=" + target + " cx=" + (int) x + " vw=" + vw);
            rollScroll.smoothScrollTo(target, 0);
        }
    }

    private void updateClock(int ms) {
        clock.setText(fmtTime(ms / 1000.0) + " / " + fmtTime(midi.durationSeconds));
        seek.setProgress(ms);
        if (roll != null) roll.setCursor(ms / 1000.0);
    }

    public static String fmtTime(double sec) {
        long s = Math.round(sec);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    public void release() {
        released = true;
        if (game != null) {
            game.release();
            game = null;
        }
        handler.removeCallbacks(ticker);
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }
}
