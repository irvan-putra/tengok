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
    private ConvertPanel convertPanel;

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
        DiagLog.watch(source.name);
        if (uri != null) Recents.record(this, uri, source.name);

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
            if (BuildConfig.DEBUG && getIntent().getBooleanExtra("fail_open_grant", false)) {
                throw new SecurityException("simulated lost grant");
            }
            head = source.head(8192);
        } catch (SecurityException e) {
            // The read grant is gone: Android's per-app persisted-grant budget
            // was full when the file was first opened (so nothing persisted),
            // or the provider revoked it. Fail safe, never crash, and point at
            // the only fix - picking the file again. No hex fallback: every
            // further read of this Uri would fail the same way.
            showAccessLost(e);
            return;
        } catch (IOException e) {
            showError("tengok couldn't open this file", "open", e);
            return;
        }
        String declared = getIntent().getType();
        kind = Sniffer.sniff(source.name, declared, head);
        DiagLog.log(this, "VIEW kind=" + kind.name().toLowerCase(java.util.Locale.ROOT)
                + " size=" + source.size
                + " declared=" + DiagLog.mime(declared)
                + " scheme=" + (source.uri() == null ? "file" : source.uri().getScheme()));
        showKind(kind);
    }

    private void showKind(Sniffer.Kind k) {
        kind = k;
        releasePlayers();
        if (convertPanel != null) {
            convertPanel.cancelRunning();
            convertPanel = null;
        }
        content.removeAllViews();
        switch (k) {
            case MIDI:
                showMidi();
                break;
            case IMAGE:
                addWithConvert(ImagePanel.build(this, source, head), k);
                break;
            case AUDIO:
                audioPanel = new AudioPanel();
                addWithConvert(audioPanel.build(this, source), k);
                break;
            case TEXT:
                showText();
                break;
            case VIDEO:
                // No video player yet; the bytes stay inspectable and the
                // convert card offers MP4 re-encoding.
                addWithConvert(HexPanel.build(this, source), k);
                break;
            case HEX:
            default:
                content.addView(HexPanel.build(this, source));
                break;
        }
    }

    /** Panel on top, Convert card docked below when the kind has targets. */
    private void addWithConvert(android.view.View panelView, Sniffer.Kind k) {
        convertPanel = new ConvertPanel(this, source, k, head);
        android.view.View card = convertPanel.build();
        if (card == null) {
            content.addView(panelView);
            return;
        }
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(panelView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        wrap.addView(card);
        content.addView(wrap);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (convertPanel != null) convertPanel.onDestination(requestCode, resultCode, data);
    }

    private void showMidi() {
        byte[] data;
        try {
            data = source.readAll(Source.MAX_BUFFERED_BYTES);
        } catch (IOException e) {
            showError("tengok couldn't read this MIDI file", "read", e);
            return;
        }
        MidiFile midi;
        try {
            midi = MidiFile.parse(data);
        } catch (MidiFile.ParseException e) {
            showError("tengok can't read this MIDI file", "parse", e);
            return;
        }
        buffered = data;
        midiPanel = new MidiPanel(this, source, midi);
        // the panel wraps its own viewer cards in a ScrollView; the root must
        // stay viewport-sized so game mode can fill exactly the screen
        content.addView(midiPanel.view());
    }

    private void showText() {
        try {
            TextPanel.Result r = TextPanel.decode(source);
            addWithConvert(TextPanel.build(this, r), Sniffer.Kind.TEXT);
        } catch (IOException e) {
            showError("tengok couldn't read this file", "read", e);
        }
    }

    private void showAccessLost(SecurityException e) {
        DiagLog.log(this, "ERR view op=open ex=" + e.getClass().getName());
        LinearLayout column = Ui.column(this);
        LinearLayout card = Ui.card(this);
        card.addView(Ui.line(this, "tengok no longer has access to this file"));
        card.addView(Ui.muted(this,
                "Android forgot the read access tengok was granted. "
                + "Pick the file again from the home screen to keep viewing it. "
                + "The file itself is exactly where it was."));
        column.addView(card);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        content.removeAllViews();
        content.addView(scroll);
    }

    private void showError(String headline, String op, Throwable cause) {
        // On-screen the user sees the real message (their own phone, their
        // own file); the shareable log records only the exception class.
        String detail = cause == null ? null : cause.getMessage();
        DiagLog.log(this, "ERR view op=" + op + " ex="
                + (cause == null ? "unknown" : cause.getClass().getName()));
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
                        : ": " + DiagLog.scrub(t.getMessage());
                if (detail.length() > 140) detail = detail.substring(0, 140);
                hash = "couldn't compute (" + t.getClass().getSimpleName() + detail + ")";
                Log.e(TAG, "TENGOK-DETAILS-ERR hash: " + t.getClass().getSimpleName());
                DiagLog.log(this, "DETAILS-ERR op=hash ex=" + t.getClass().getName());
            }
            String h = hash;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                try {
                    if (dialog.isShowing()) {
                        dialog.setMessage(sb + "\nSHA-256:\n" + h);
                        Log.i(TAG, "TENGOK-DETAILS-OK");
                        DiagLog.log(this, h.length() == 64
                                ? "DETAILS-OK"
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
        if (convertPanel != null) {
            convertPanel.cancelRunning();
            convertPanel = null;
        }
        super.onDestroy();
    }
}
