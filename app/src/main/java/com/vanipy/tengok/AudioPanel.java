package com.vanipy.tengok;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;

/** Simple audio player for the common formats, fully on-device. */
public class AudioPanel {
    private static final String TAG = "tengok";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private boolean prepared;
    private boolean released;
    private Button play;
    private SeekBar seek;
    private TextView clock;
    private int durationMs = 1;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (released || player == null || !prepared) return;
            try {
                if (player.isPlaying()) updateClock(player.getCurrentPosition());
            } catch (IllegalStateException ignored) {
            }
            handler.postDelayed(this, 500);
        }
    };

    public View build(Context ctx, Source source) {
        LinearLayout column = Ui.column(ctx);
        LinearLayout card = Ui.card(ctx);
        card.addView(Ui.line(ctx, source.name));
        card.addView(Ui.muted(ctx, "audio · plays on this device, nothing is uploaded"));
        play = new Button(ctx);
        play.setText("Play");
        play.setEnabled(false);
        play.setOnClickListener(v -> toggle());
        card.addView(play);
        seek = new SeekBar(ctx);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && prepared && player != null) {
                    try {
                        player.seekTo(progress);
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
        card.addView(seek);
        clock = new TextView(ctx);
        clock.setText("0:00");
        card.addView(clock);
        column.addView(card);
        prepare(ctx, source);
        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(column);
        return scroll;
    }

    private void prepare(Context ctx, Source source) {
        try {
            player = new MediaPlayer();
            android.media.MediaDataSource memory = source.mediaDataSource();
            Uri uri = source.uri();
            File local = source.localFile();
            if (memory != null) player.setDataSource(memory);
            else if (uri != null) player.setDataSource(ctx, uri);
            else if (local != null) player.setDataSource(local.getAbsolutePath());
            else throw new IllegalStateException("no playable source");
            player.setOnPreparedListener(mp -> {
                prepared = true;
                durationMs = Math.max(1, mp.getDuration());
                seek.setMax(durationMs);
                play.setEnabled(true);
                clock.setText("0:00 / " + fmt(durationMs));
            });
            player.setOnCompletionListener(mp -> play.setText("Play"));
            player.setOnErrorListener((mp, what, extra) -> {
                clock.setText("this device could not play this audio");
                play.setEnabled(false);
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            clock.setText("this device could not play this audio");
        }
    }

    private void toggle() {
        if (!prepared || player == null) return;
        try {
            if (player.isPlaying()) {
                player.pause();
                play.setText("Play");
            } else {
                player.start();
                play.setText("Pause");
                handler.removeCallbacks(ticker);
                handler.post(ticker);
            }
        } catch (IllegalStateException ignored) {
        }
    }

    private void updateClock(int ms) {
        clock.setText(fmt(ms) + " / " + fmt(durationMs));
        seek.setProgress(ms);
    }

    static String fmt(int ms) {
        long s = ms / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    public void release() {
        released = true;
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
