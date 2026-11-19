package com.vanipy.tengok;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Browse one folder the user picked with the system folder picker. Access is
 * exactly what the picker granted, kept with a persisted read permission, and
 * strictly read-only: tengok can look, never change, move or delete. Android
 * itself decides which roots the picker may offer.
 */
public class ExplorerActivity extends Activity {
    private static final int REQ_TREE = 43;
    private static final int MAX_ROWS = 500;

    private final List<Uri> docStack = new ArrayList<>();
    private final List<String> nameStack = new ArrayList<>();
    private LinearLayout listColumn;
    private TextView titleView;
    private Uri tree;

    private static final class Entry {
        String docId;
        String name;
        String mime;
        long size;
        boolean isDir;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String t = getIntent().getStringExtra("tree");
        if (t == null) t = prefs().getString("explorer_tree", null);
        if (t == null) {
            finish();
            return;
        }
        tree = Uri.parse(t);

        LinearLayout root = new LinearLayout(this);
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
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back);

        titleView = new TextView(this);
        titleView.setTextSize(16);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView change = new TextView(this);
        change.setText("Change");
        change.setTextSize(15);
        change.setTextColor(0xFFFFB300);
        change.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        change.setOnClickListener(v -> pickTree());
        bar.addView(change);

        root.addView(bar);
        listColumn = new LinearLayout(this);
        listColumn.setOrientation(LinearLayout.VERTICAL);
        listColumn.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 24));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(listColumn);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        String rootName = getIntent().getStringExtra("tree_name");
        if (rootName == null || rootName.isEmpty()) rootName = treeName(tree);
        docStack.add(rootDoc);
        nameStack.add(rootName);
        reload();
    }

    /** Best-effort folder label from the tree id, e.g. primary:Download/x -> x. */
    private static String treeName(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int colon = id.indexOf(':');
            String path = colon >= 0 ? id.substring(colon + 1) : id;
            int slash = path.lastIndexOf('/');
            String leaf = slash >= 0 ? path.substring(slash + 1) : path;
            return leaf.isEmpty() ? "picked folder" : leaf;
        } catch (RuntimeException e) {
            return "picked folder";
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("tengok", MODE_PRIVATE);
    }

    private void pickTree() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(pick, REQ_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri picked = data.getData();
            getContentResolver().takePersistableUriPermission(
                    picked, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            tree = picked;
            prefs().edit().putString("explorer_tree", picked.toString()).apply();
            docStack.clear();
            nameStack.clear();
            docStack.add(DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree)));
            nameStack.add(treeName(tree));
            reload();
        }
    }

    @Override
    public void onBackPressed() {
        if (docStack.size() > 1) {
            docStack.remove(docStack.size() - 1);
            nameStack.remove(nameStack.size() - 1);
            reload();
        } else {
            super.onBackPressed();
        }
    }

    private void reload() {
        titleView.setText(nameStack.get(nameStack.size() - 1));
        listColumn.removeAllViews();
        TextView loading = Ui.muted(this, "reading folder…");
        listColumn.addView(loading);
        final Uri current = docStack.get(docStack.size() - 1);
        new Thread(() -> {
            List<Entry> entries = new ArrayList<>();
            String error = null;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getDocumentId(current));
            try (Cursor c = getContentResolver().query(children,
                    new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE},
                    null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        Entry e = new Entry();
                        e.docId = c.getString(0);
                        e.name = c.getString(1);
                        e.mime = c.getString(2);
                        e.size = c.isNull(3) ? -1 : c.getLong(3);
                        e.isDir = Document.MIME_TYPE_DIR.equals(e.mime);
                        if (e.name == null || e.name.isEmpty()) e.name = "(unnamed)";
                        entries.add(e);
                    }
                }
            } catch (SecurityException se) {
                error = "access to this folder expired - tap Change above to pick it again";
            } catch (RuntimeException re) {
                error = "this folder couldn't be read: " + re.getClass().getSimpleName();
            }
            entries.sort((a, b) -> a.isDir != b.isDir ? (a.isDir ? -1 : 1)
                    : a.name.compareToIgnoreCase(b.name));
            String err = error;
            runOnUiThread(() -> render(entries, err));
        }).start();
    }

    private void render(List<Entry> entries, String error) {
        if (isFinishing() || isDestroyed()) return;
        listColumn.removeAllViews();
        if (error != null) {
            LinearLayout card = Ui.card(this);
            card.addView(Ui.line(this, "Folder not readable"));
            card.addView(Ui.muted(this, error));
            listColumn.addView(card);
            return;
        }
        if (entries.isEmpty()) {
            LinearLayout card = Ui.card(this);
            card.addView(Ui.muted(this, "empty folder"));
            listColumn.addView(card);
        }
        int shown = Math.min(entries.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            final Entry e = entries.get(i);
            LinearLayout card = Ui.card(this);
            card.addView(Ui.line(this, (e.isDir ? "folder  " : "") + e.name));
            String detail = e.isDir ? "folder"
                    : Meta.humanSize(e.size) + (e.mime == null || e.mime.isEmpty() ? "" : " · " + e.mime);
            card.addView(Ui.muted(this, detail));
            card.setOnClickListener(v -> open(e));
            listColumn.addView(card);
        }
        if (entries.size() > shown) {
            listColumn.addView(Ui.muted(this,
                    "+ " + (entries.size() - shown) + " more - this folder is too big to list fully"));
        }
        listColumn.addView(Ui.muted(this,
                "read-only - tengok can open files here, never change or delete them"));
    }

    private void open(Entry e) {
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, e.docId);
        if (e.isDir) {
            docStack.add(doc);
            nameStack.add(e.name);
            reload();
            return;
        }
        Intent view = new Intent(this, ViewerActivity.class);
        view.setDataAndType(doc, e.mime);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(view);
    }
}
