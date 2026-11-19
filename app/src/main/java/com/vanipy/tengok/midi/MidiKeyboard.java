package com.vanipy.tengok.midi;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiManager;
import android.media.midi.MidiReceiver;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;

/**
 * Real-keyboard input for game mode over the Android MIDI service
 * (USB; the service also surfaces Bluetooth devices another app already
 * opened). Needs no runtime permission: the system MIDI service owns the
 * USB device. Absent hardware or absent MIDI support simply means no
 * keyboard, never an error the player has to see.
 */
public final class MidiKeyboard {
    private static final String TAG = "tengok";

    public interface Listener {
        /** A physical key went down on channel-agnostic input. */
        void onKey(int pitch, int velocity);
    }

    private final MidiManager manager;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MidiDevice device;
    private MidiOutputPort port;

    public MidiKeyboard(Context ctx, Listener listener) {
        this.listener = listener;
        this.manager = ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MIDI)
                ? (MidiManager) ctx.getSystemService(Context.MIDI_SERVICE)
                : null;
    }

    public boolean available() {
        return manager != null;
    }

    /** Opens the first connected MIDI device that offers an input port. */
    public void open() {
        if (manager == null || device != null) return;
        MidiDeviceInfo[] devices = manager.getDevices();
        for (MidiDeviceInfo info : devices) {
            if (info.getOutputPortCount() < 1) continue; // keyboard keys leave through an output port
            manager.openDevice(info, d -> onOpened(d, info), handler);
            return; // one keyboard at a time is plenty
        }
    }

    private void onOpened(MidiDevice d, MidiDeviceInfo info) {
        if (d == null) return;
        device = d;
        try {
            port = d.openOutputPort(0);
            if (port != null) {
                port.connect(new Receiver());
                Log.i(TAG, "TENGOK-GAME-MIDI keyboard connected: " + info.getType());
            }
        } catch (RuntimeException e) {
            Log.i(TAG, "TENGOK-GAME-MIDI open failed");
            close();
        }
    }

    private final class Receiver extends MidiReceiver {
        Receiver() {
            super(3);
        }

        @Override
        public void onSend(byte[] msg, int offset, int count, long timestamp) {
            for (int i = offset; i + 2 < offset + count; i++) {
                int b = msg[i] & 0xFF;
                int type = b & 0xF0;
                if (type == 0x90 || type == 0x80) {
                    int pitch = msg[i + 1] & 0xFF;
                    int vel = msg[i + 2] & 0xFF;
                    if (type == 0x90 && vel > 0) {
                        handler.post(() -> listener.onKey(pitch, vel));
                    }
                    i += 2;
                } else if (type == 0xC0 || type == 0xD0) {
                    i += 1;
                } else if ((b & 0x80) != 0) {
                    i += 2; // other channel messages: 2 data bytes
                }
            }
        }
    }

    public void close() {
        if (port != null) {
            try { port.close(); } catch (IOException ignored) { }
            port = null;
        }
        if (device != null) {
            try { device.close(); } catch (IOException ignored) { }
            device = null;
        }
    }
}
