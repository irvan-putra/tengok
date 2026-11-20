package com.vanipy.tengok;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Home screen and the front door: files arrive from the system picker, from
 * other apps (VIEW / SEND), or, in debug builds, from the fixture screen.
 */
public class MainActivity extends Activity {
    private static final int REQ_OPEN = 41;
    private static final int REQ_TREE = 42;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(getApplicationContext());
        Intent intent = getIntent();
        if (BuildConfig.DEBUG && intent != null
                && intent.getBooleanExtra("debug_crash_test", false)) {
            throw new IllegalStateException("tengok debug crash test");
        }
        Uri incoming = extractUri(intent);
        if (incoming != null) {
            openViewer(incoming, intent.getType());
            finish();
            return;
        }
        showHome();
    }

    private static Uri extractUri(Intent intent) {
        if (intent == null || intent.getAction() == null) return null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) return intent.getData();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            return u;
        }
        return null;
    }

    private boolean showingAbout;
    private boolean homeVisible;

    @Override
    protected void onResume() {
        super.onResume();
        // reopen count and list order change while a file is open; refresh on return
        if (homeVisible && !showingAbout) showHome();
    }

    @Override
    public void onBackPressed() {
        if (showingAbout) {
            showHome();
        } else {
            super.onBackPressed();
        }
    }

    private void showHome() {
        showingAbout = false;
        homeVisible = true;
        LinearLayout column = Ui.column(this);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(Ui.dp(this, 24), Ui.dp(this, 48), Ui.dp(this, 24), Ui.dp(this, 24));

        TextView title = new TextView(this);
        title.setText("tengok");
        title.setTextSize(40);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1F2A5C);
        title.setGravity(Gravity.CENTER);
        column.addView(title);

        TextView sub = new TextView(this);
        sub.setText("peek into any file · version " + BuildConfig.VERSION_NAME);
        sub.setTextSize(15);
        sub.setTextColor(0xFF5A6072);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 32));
        column.addView(sub);

        String crash = CrashLog.read(this);
        if (crash != null) {
            LinearLayout crashCard = Ui.card(this);
            crashCard.addView(Ui.line(this, "tengok crashed last time"));
            crashCard.addView(Ui.muted(this,
                    "Sharing the details helps fix it - nothing is sent by itself, "
                    + "and the full trace stays on this phone."));
            for (String line : crash.split("\\n")) {
                if (line.contains("Exception") || line.contains("Error")) {
                    crashCard.addView(Ui.muted(this, line.trim()));
                    break;
                }
            }
            Button share = new Button(this);
            share.setText("Share crash details");
            share.setOnClickListener(v -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "tengok crash report");
                send.putExtra(Intent.EXTRA_TEXT, CrashLog.shareableReport(this));
                startActivity(Intent.createChooser(send, null));
            });
            crashCard.addView(share);
            Button dismiss = new Button(this);
            dismiss.setText("Dismiss");
            dismiss.setOnClickListener(v -> {
                CrashLog.clear(this);
                showHome();
            });
            crashCard.addView(dismiss);
            column.addView(crashCard);
        }

        Button open = new Button(this);
        open.setText("Open a file");
        open.setTextSize(17);
        open.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            startActivityForResult(pick, REQ_OPEN);
        });
        column.addView(open, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button browse = new Button(this);
        browse.setText("Browse a folder");
        browse.setTextSize(17);
        browse.setOnClickListener(v -> browseFolder());
        LinearLayout.LayoutParams browseLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        browseLp.setMargins(0, Ui.dp(this, 12), 0, 0);
        column.addView(browse, browseLp);

        List<Recents.Entry> recents = Recents.list(this);
        if (!recents.isEmpty()) {
            TextView head = new TextView(this);
            head.setText("Recent files");
            head.setTextSize(17);
            head.setTypeface(Typeface.DEFAULT_BOLD);
            head.setTextColor(0xFF1B2340);
            head.setPadding(0, Ui.dp(this, 24), 0, 0);
            column.addView(head);
            for (Recents.Entry e : recents) {
                LinearLayout card = Ui.card(this);
                LinearLayout row = Ui.row(this);
                TextView name = Ui.line(this, e.name);
                name.setMaxLines(1);
                name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                row.addView(name, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView rm = new TextView(this);
                rm.setText("Remove");
                rm.setTextSize(14);
                rm.setTextColor(0xFFB86E00);
                rm.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), 0, Ui.dp(this, 4));
                rm.setOnClickListener(v -> confirmRemoveRecent(e));
                row.addView(rm);
                card.addView(row);
                card.setOnClickListener(v -> openViewer(Uri.parse(e.uri), null));
                column.addView(card);
            }
            TextView note = new TextView(this);
            note.setText("Remove only clears this list - the file itself is never touched.");
            note.setTextSize(13);
            note.setTextColor(0xFF8A8FA3);
            note.setPadding(0, Ui.dp(this, 8), 0, 0);
            column.addView(note);
        }

        TextView or = new TextView(this);
        or.setText("or tap a file in any app and choose tengok");
        or.setTextSize(14);
        or.setTextColor(0xFF5A6072);
        or.setGravity(Gravity.CENTER);
        or.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 32));
        column.addView(or);

        Button about = new Button(this);
        about.setText("What tengok can open");
        about.setOnClickListener(v -> showAbout());
        column.addView(about, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView privacy = new TextView(this);
        privacy.setText("Offline. No accounts. No permissions. Files never leave this phone.");
        privacy.setTextSize(13);
        privacy.setTextColor(0xFF8A8FA3);
        privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(0, Ui.dp(this, 40), 0, 0);
        column.addView(privacy);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);
    }

    private void confirmRemoveRecent(Recents.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle("Remove from recent files?")
                .setMessage("\"" + e.name + "\" stays exactly where it is; "
                        + "only its entry in this list is cleared. tengok never deletes files.")
                .setPositiveButton("Remove", (d, w) -> {
                    Recents.remove(this, e.uri);
                    showHome();
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    private void showAbout() {
        showingAbout = true;
        homeVisible = false;
        LinearLayout column = Ui.column(this);
        TextView title = new TextView(this);
        title.setText("What tengok can open");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1B2340);
        title.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8));
        column.addView(title);

        String[] blocks = {
                "MIDI music (.mid, .midi, .rmi)",
                "Summary, instruments, a piano roll of every note, and playback on this phone's built-in synth.",
                "Images (.png, .jpg, .webp, .gif, .bmp, .heic on newer Android)",
                "Pinch to zoom. Animated GIFs play.",
                "Audio (.mp3, .ogg, .flac, .wav, .m4a, .aac)",
                "Simple on-device player.",
                "Text (.txt, .log, .md, .json, .csv, source code, and friends)",
                "Shown as plain text, UTF-8 or Latin-1.",
                "Everything else",
                "A paged hex view plus the file's details (type, size, SHA-256), so no file is a dead end.",
                "Not yet, honestly",
                "PDF, video, office documents, SVG and archives are on the roadmap; today they open in the hex/details view.",
        };
        for (int i = 0; i < blocks.length; i += 2) {
            LinearLayout card = Ui.card(this);
            card.addView(Ui.line(this, blocks[i]));
            card.addView(Ui.muted(this, blocks[i + 1]));
            column.addView(card);
        }

        LinearLayout diag = Ui.card(this);
        diag.addView(Ui.line(this, "Something wrong?"));
        diag.addView(Ui.muted(this,
                "tengok keeps a small diagnostic log on this phone: which kind of file was opened "
                + "(type and size, never its name), which step failed, no file contents. "
                + "It leaves the phone only if you share it."));
        Button share = new Button(this);
        share.setText("Share diagnostics");
        share.setOnClickListener(v -> {
            String log = DiagLog.read(this).trim();
            if (log.isEmpty()) log = "(no diagnostics yet)";
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "tengok diagnostics " + BuildConfig.VERSION_NAME);
            send.putExtra(Intent.EXTRA_TEXT, "tengok diagnostics\n" + log);
            startActivity(Intent.createChooser(send, null));
        });
        diag.addView(share);
        column.addView(diag);

        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(v -> showHome());
        column.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);
    }

    private void browseFolder() {
        String saved = getSharedPreferences("tengok", MODE_PRIVATE).getString("explorer_tree", null);
        if (saved != null) {
            startActivity(new Intent(this, ExplorerActivity.class));
            return;
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(pick, REQ_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK && data != null && data.getData() != null) {
            openViewer(data.getData(), data.getType());
        } else if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            getContentResolver().takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getSharedPreferences("tengok", MODE_PRIVATE).edit()
                    .putString("explorer_tree", tree.toString()).apply();
            startActivity(new Intent(this, ExplorerActivity.class));
        } else if (requestCode == REQ_OPEN) {
            Toast.makeText(this, "No file chosen", Toast.LENGTH_SHORT).show();
        }
    }

    private void openViewer(Uri uri, String declaredType) {
        Intent view = new Intent(this, ViewerActivity.class);
        view.setDataAndType(uri, declaredType);
        if (BuildConfig.DEBUG) {
            view.putExtra("fail_details_hash",
                    getIntent().getBooleanExtra("fail_details_hash", false));
        }
        startActivity(view);
    }
}
