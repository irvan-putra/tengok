package com.vanipy.tengok;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.vanipy.tengok.midi.MidiFile;
import com.vanipy.tengok.midi.MidiPanel;

import java.io.File;
import java.io.IOException;

/**
 * Hosts the right panel for one file. Detection picks a panel; every panel
 * still fails safe with a readable message and an offer of the hex view.
 */
public class ViewerActivity extends Activity {
    private static final String TAG = "tengok";

    private Source source;
    private Sniffer.Kind kind;
    private byte[] head;
    private byte[] buffered;
    private LinearLayout root;
    private FrameLayout content;
    private TextView titleView;
    private MidiPanel midiPanel;
    private AudioPanel audioPanel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri uri = getIntent().getData();
        String fixturePath = BuildConfig.DEBUG ? getIntent().getStringExtra("fixture_path") : null;
        if (uri == null && fixturePath == null) {
            finish();
            return;
        }
        source = uri != null ? Source.forUri(this, uri) : new Source.FileSource(new File(fixturePath));

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1F2A5C);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));

        TextView back = new TextView(this);
        back.setText("‹ tengok");
        back.setTextSize(16);
        back.setTextColor(0xFFFFFFFF);
        back.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 12), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        titleView = new TextView(this);
        titleView.setText(source.name);
        titleView.setTextSize(16);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView details = new TextView(this);
        details.setText("Details");
        details.setTextSize(15);
        details.setTextColor(0xFFFFB300);
        details.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        details.setOnClickListener(v -> showDetails());
        bar.addView(details);

        root.addView(bar);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        render();
    }

    private void render() {
        try {
            head = source.head(8192);
        } catch (IOException e) {
            showError("tengok couldn't open this file", e.getMessage());
            return;
        }
        String declared = getIntent().getType();
        kind = Sniffer.sniff(source.name, declared, head);
        DiagLog.log(this, "VIEW kind=" + kind.name().toLowerCase(java.util.Locale.ROOT)
                + " size=" + source.size
                + " declared=" + (declared == null ? "none" : declared)
                + " scheme=" + (source.uri() == null ? "file" : source.uri().getScheme()));
        showKind(kind);
    }

    private void showKind(Sniffer.Kind k) {
        kind = k;
        releasePlayers();
        content.removeAllViews();
        switch (k) {
            case MIDI:
                showMidi();
                break;
            case IMAGE:
                content.addView(ImagePanel.build(this, source, head));
                break;
            case AUDIO:
                audioPanel = new AudioPanel();
                content.addView(audioPanel.build(this, source));
                break;
            case TEXT:
                showText();
                break;
            case HEX:
            default:
                content.addView(HexPanel.build(this, source));
                break;
        }
    }

    private void showMidi() {
        byte[] data;
        try {
            data = source.readAll(Source.MAX_BUFFERED_BYTES);
        } catch (IOException e) {
            showError("tengok couldn't read this MIDI file", e.getMessage());
            return;
        }
        MidiFile midi;
        try {
            midi = MidiFile.parse(data);
        } catch (MidiFile.ParseException e) {
            showError("tengok can't read this MIDI file", e.getMessage());
            return;
        }
        buffered = data;
        midiPanel = new MidiPanel(this, source, midi);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(midiPanel.view());
        content.addView(scroll);
    }

    private void showText() {
        try {
            TextPanel.Result r = TextPanel.decode(source);
            content.addView(TextPanel.build(this, r));
        } catch (IOException e) {
            showError("tengok couldn't read this file", e.getMessage());
        }
    }

    private void showError(String headline, String detail) {
        DiagLog.log(this, "ERR view: " + headline + (detail == null ? "" : " (" + detail + ")"));
        LinearLayout column = Ui.column(this);
        LinearLayout card = Ui.card(this);
        card.addView(Ui.line(this, headline));
        card.addView(Ui.muted(this, detail == null ? "" : detail));
        TextView hex = new TextView(this);
        hex.setText("Show as hex instead");
        hex.setTextSize(15);
        hex.setTextColor(0xFF1F2A5C);
        hex.setTypeface(Typeface.DEFAULT_BOLD);
        hex.setPadding(0, Ui.dp(this, 12), 0, 0);
        hex.setOnClickListener(v -> showKind(Sniffer.Kind.HEX));
        card.addView(hex);
        column.addView(card);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        content.removeAllViews();
        content.addView(scroll);
    }

    private void showDetails() {
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(source.name).append('\n');
        sb.append("Size: ").append(Meta.humanSize(source.size)).append('\n');
        sb.append("Shown as: ").append(kind.name().toLowerCase(java.util.Locale.ROOT)).append('\n');
        String declared = getIntent().getType();
        sb.append("Declared type: ").append(declared == null ? "none" : declared).append('\n');
        // The dialog and the background hash both run far from the file parser;
        // any failure here must degrade to a readable note, never a crash.
        Log.i(TAG, "TENGOK-DETAILS-OPEN");
        final AlertDialog dialog;
        try {
            dialog = new AlertDialog.Builder(this)
                    .setTitle("File details")
                    .setMessage(sb + "\nSHA-256: computing…")
                    .setPositiveButton("Close", null)
                    .show();
        } catch (RuntimeException e) {
            Log.e(TAG, "TENGOK-DETAILS-ERR dialog: " + e.getClass().getSimpleName());
            android.widget.Toast.makeText(this, sb.toString(),
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        TextView msg = dialog.findViewById(android.R.id.message);
        if (msg != null) {
            try {
                msg.setTextIsSelectable(true);
            } catch (RuntimeException e) {
                Log.e(TAG, "TENGOK-DETAILS-ERR selectable: " + e.getClass().getSimpleName());
            }
        }
        new Thread(() -> {
            String hash;
            try {
                if (BuildConfig.DEBUG && getIntent().getBooleanExtra("fail_details_hash", false)) {
                    throw new SecurityException("simulated grant failure");
                }
                // Hash the bytes already loaded for viewing when we have them:
                // a second provider read can be refused while MediaPlayer
                // holds the same Uri for playback.
                byte[] b = buffered;
                hash = b != null ? Meta.sha256(b) : Meta.sha256(source);
            } catch (Throwable t) {
                // includes SecurityException from a lapsed content grant; an
                // uncaught throw on this thread would kill the app
                String detail = t.getMessage() == null ? ""
                        : ": " + CrashLog.sanitize(t.getMessage());
                if (detail.length() > 140) detail = detail.substring(0, 140);
                hash = "couldn't compute (" + t.getClass().getSimpleName() + detail + ")";
                Log.e(TAG, "TENGOK-DETAILS-ERR hash: " + t.getClass().getSimpleName());
                DiagLog.log(this, "DETAILS-ERR hash: " + t.getClass().getSimpleName() + detail);
            }
            String h = hash;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                try {
                    if (dialog.isShowing()) {
                        dialog.setMessage(sb + "\nSHA-256:\n" + h);
                        Log.i(TAG, "TENGOK-DETAILS-OK");
                        DiagLog.log(this, h.length() == 64
                                ? "DETAILS-OK sha256=" + h.substring(0, 12) + "..."
                                : "DETAILS-OK fallback-shown");
                    }
                } catch (RuntimeException e) {
                    Log.e(TAG, "TENGOK-DETAILS-ERR update: " + e.getClass().getSimpleName());
                }
            });
        }).start();
    }

    private void releasePlayers() {
        if (midiPanel != null) {
            midiPanel.release();
            midiPanel = null;
        }
        if (audioPanel != null) {
            audioPanel.release();
            audioPanel = null;
        }
    }

    @Override
    protected void onDestroy() {
        releasePlayers();
        super.onDestroy();
    }
}
