package com.vanipy.tengok;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import java.io.File;

/**
 * Simple video player for the common formats, fully on-device.
 *
 * Real recordings are not constant frame rate. Bidik's screen captures
 * stamp every frame with wall-clock time (intervals wander either side of
 * the advertised rate and the first frame can hold for a beat), write the
 * MP4 index (moov) at the END of the file behind a 64-bit mdat size, start
 * the audio a few hundred milliseconds after the video, and leave silent
 * recordings with no audio track at all. A player that assumes a constant
 * rate or an index up front plays those files at the wrong speed or not at
 * all. Media3's extractor reads the sample table wherever the muxer wrote
 * it and renders by each frame's own timestamp, so these files play at
 * exactly the speed they were recorded, sound on its own offset.
 */
public class VideoPanel {
    private static final String TAG = "tengok";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private boolean prepared;
    private boolean released;
    private TextureView surface;
    private Button play;
    private SeekBar seek;
    private TextView clock;
    private TextView note;
    private int durationMs = 1;
    private int videoWidth;
    private int videoHeight;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (released || player == null || !prepared) return;
            try {
                if (player.isPlaying()) {
                    int pos = (int) Math.min(Integer.MAX_VALUE, player.getCurrentPosition());
                    updateClock(pos);
                    Log.i(TAG, "TENGOK-VIDEO-POS ms=" + pos);
                }
            } catch (IllegalStateException ignored) {
            }
            handler.postDelayed(this, 500);
        }
    };

    public View build(Context ctx, Source source) {
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);

        FrameLayout stage = new FrameLayout(ctx);
        stage.setBackgroundColor(Color.BLACK);
        surface = new TextureView(ctx);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        sp.gravity = Gravity.CENTER;
        stage.addView(surface, sp);
        // The surface starts filling the stage; once the real frame size is
        // known it is letterboxed to fit, and every layout pass re-fits it.
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> fitStage());
        column.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout card = Ui.card(ctx);
        card.addView(Ui.line(ctx, source.name));
        note = Ui.muted(ctx, "video · plays on this device, nothing is uploaded");
        card.addView(note);
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
        card.addView(seek);
        clock = new TextView(ctx);
        clock.setText("0:00");
        card.addView(clock);
        column.addView(card);
        prepare(ctx, source);
        return column;
    }

    /** Letterbox the surface inside the stage, honoring the pixel aspect. */
    private void fitStage() {
        if (surface == null || videoWidth <= 0 || videoHeight <= 0) return;
        View parent = (View) surface.getParent();
        if (parent == null) return;
        int pw = parent.getWidth();
        int ph = parent.getHeight();
        if (pw <= 0 || ph <= 0) return;
        double scale = Math.min(pw / (double) videoWidth, ph / (double) videoHeight);
        ViewGroup.LayoutParams lp = surface.getLayoutParams();
        lp.width = Math.max(1, (int) (videoWidth * scale));
        lp.height = Math.max(1, (int) (videoHeight * scale));
        surface.setLayoutParams(lp);
    }

    private void prepare(Context ctx, Source source) {
        try {
            player = new ExoPlayer.Builder(ctx).build();
            player.setVideoTextureView(surface);
            Uri uri = source.uri();
            File local = source.localFile();
            if (uri != null) player.setMediaItem(MediaItem.fromUri(uri));
            else if (local != null) player.setMediaItem(MediaItem.fromUri(Uri.fromFile(local)));
            else throw new IllegalStateException("no playable source");
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        prepared = true;
                        long d = player.getDuration();
                        durationMs = (d == C.TIME_UNSET || d <= 0)
                                ? 1 : (int) Math.min(Integer.MAX_VALUE, d);
                        seek.setMax(durationMs);
                        play.setEnabled(true);
                        clock.setText("0:00 / " + AudioPanel.fmt(durationMs));
                        String tracks = currentTracksLabel();
                        note.setText(hasAudio()
                                ? "video · plays on this device, nothing is uploaded"
                                : "video · silent file, no audio track · on this device only");
                        Log.i(TAG, "TENGOK-VIDEO-READY durMs=" + durationMs + " tracks=" + tracks);
                        DiagLog.log(ctx, "VIDEO-READY dur=" + durationMs + " tracks=" + tracks);
                    } else if (state == Player.STATE_ENDED) {
                        play.setText("Play");
                    }
                }

                @Override
                public void onVideoSizeChanged(VideoSize size) {
                    videoWidth = Math.max(1, Math.round(size.width * size.pixelWidthHeightRatio));
                    videoHeight = Math.max(1, size.height);
                    Log.i(TAG, "TENGOK-VIDEO-SIZE w=" + videoWidth + " h=" + videoHeight);
                    fitStage();
                }

                @Override
                public void onPlayerError(PlaybackException e) {
                    prepared = false;
                    clock.setText("this device could not play this video");
                    play.setEnabled(false);
                    Log.e(TAG, "TENGOK-VIDEO-ERR " + e.getErrorCodeName());
                    DiagLog.log(ctx, "ERR video op=play ex=" + e.getErrorCodeName());
                }
            });
            player.prepare();
        } catch (Exception e) {
            clock.setText("this device could not play this video");
            DiagLog.log(ctx, "ERR video op=prepare ex=" + e.getClass().getName());
        }
    }

    private boolean hasAudio() {
        if (player == null) return true;
        Tracks tracks = player.getCurrentTracks();
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() == C.TRACK_TYPE_AUDIO && g.length > 0) return true;
        }
        return false;
    }

    private String currentTracksLabel() {
        boolean video = false;
        boolean audio = false;
        if (player != null) {
            for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
                if (g.length == 0) continue;
                if (g.getType() == C.TRACK_TYPE_VIDEO) video = true;
                if (g.getType() == C.TRACK_TYPE_AUDIO) audio = true;
            }
        }
        return (video ? "v" : "") + (audio ? "a" : "");
    }

    private void toggle() {
        if (!prepared || player == null) return;
        try {
            if (player.isPlaying()) {
                player.pause();
                play.setText("Play");
            } else {
                player.play();
                play.setText("Pause");
                handler.removeCallbacks(ticker);
                handler.post(ticker);
            }
        } catch (IllegalStateException ignored) {
        }
    }

    private void updateClock(int ms) {
        clock.setText(AudioPanel.fmt(ms) + " / " + AudioPanel.fmt(durationMs));
        seek.setProgress(ms);
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
