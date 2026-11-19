package com.vanipy.tengok;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Home screen and the front door: files arrive from the system picker, from
 * other apps (VIEW / SEND), or, in debug builds, from the fixture screen.
 */
public class MainActivity extends Activity {
    private static final int REQ_OPEN = 41;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        Uri incoming = extractUri(intent);
        if (incoming != null) {
            openViewer(incoming);
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

    private void showAbout() {
        showingAbout = true;
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

        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(v -> showHome());
        column.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK && data != null && data.getData() != null) {
            openViewer(data.getData());
        } else if (requestCode == REQ_OPEN) {
            Toast.makeText(this, "No file chosen", Toast.LENGTH_SHORT).show();
        }
    }

    private void openViewer(Uri uri) {
        Intent view = new Intent(this, ViewerActivity.class);
        view.setData(uri);
        startActivity(view);
    }
}
