package com.vanipy.tengok.midi;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The sound for keys the player strikes: a short decaying sine tone
 * synthesized in memory at strike time. No audio assets, no permissions,
 * works anywhere AudioTrack works. Velocity sets loudness, so a soft tap
 * (or a soft key press from a real keyboard) sounds soft.
 */
public final class TonePlayer {
    private static final int SAMPLE_RATE = 22050;
    private static final float DURATION_S = 0.9f;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private volatile boolean released;

    public void strike(int pitch, int velocity) {
        if (released) return;
        final short[] pcm = synth(pitch, velocity);
        pool.execute(() -> play(pcm));
    }

    static short[] synth(int pitch, int velocity) {
        int n = (int) (SAMPLE_RATE * DURATION_S);
        short[] pcm = new short[n];
        double freq = 440.0 * Math.pow(2.0, (pitch - 69) / 12.0);
        double amp = 0.45 * Math.max(0.05, velocity / 127.0) * Short.MAX_VALUE;
        double phase = 0;
        double step = 2 * Math.PI * freq / SAMPLE_RATE;
        for (int i = 0; i < n; i++) {
            double env = Math.exp(-4.0 * i / (double) n);
            pcm[i] = (short) (Math.sin(phase) * amp * env);
            phase += step;
        }
        return pcm;
    }

    private void play(short[] pcm) {
        if (released) return;
        AudioTrack at = null;
        try {
            at = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(pcm.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            at.write(pcm, 0, pcm.length);
            at.play();
            long alive = (long) (DURATION_S * 1000) + 100;
            Thread.sleep(alive);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // a device without audio out just stays silent
        } finally {
            if (at != null) {
                try { at.stop(); } catch (RuntimeException ignored) { }
                at.release();
            }
        }
    }

    public void release() {
        released = true;
        pool.shutdownNow();
    }
}
