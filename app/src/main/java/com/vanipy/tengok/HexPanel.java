package com.vanipy.tengok;

import android.content.Context;
import android.graphics.Typeface;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * The universal fallback: a paged hex dump. Pages are read straight from the
 * file at their offset, so a multi-GB file opens as fast as a tiny one.
 */
public class HexPanel {
    static final int PAGE = 4096;

    public static String formatPage(byte[] data, int len, long base) {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < len; row += 16) {
            sb.append(String.format(Locale.ROOT, "%08x  ", base + row));
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                int idx = row + i;
                if (idx < len) {
                    int v = data[idx] & 0xFF;
                    sb.append(String.format(Locale.ROOT, "%02x ", v));
                    ascii.append(v >= 0x20 && v < 0x7F ? (char) v : '.');
                } else {
                    sb.append("   ");
                }
                if (i == 7) sb.append(' ');
            }
            sb.append(' ').append(ascii).append('\n');
        }
        if (len == 0) sb.append("(empty file)\n");
        return sb.toString();
    }

    public static View build(Context ctx, Source source) {
        LinearLayout column = Ui.column(ctx);
        LinearLayout card = Ui.card(ctx);

        long size = source.size;
        card.addView(Ui.muted(ctx, "hex view · " + (size >= 0 ? size + " bytes" : "unknown size")
                + " · this format has no special viewer yet"));

        TextView body = new TextView(ctx);
        body.setTextSize(12);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextColor(0xFF1B2340);
        card.addView(body);

        LinearLayout nav = Ui.row(ctx);
        Button prev = new Button(ctx);
        prev.setText("◀ Previous");
        Button next = new Button(ctx);
        next.setText("Next ▶");
        TextView where = new TextView(ctx);
        where.setPadding(Ui.dp(ctx, 12), 0, 0, 0);
        nav.addView(prev);
        nav.addView(next);
        nav.addView(where);
        card.addView(nav);
        column.addView(card);

        final long[] page = {0};
        long pages = size >= 0 ? Math.max(1, (size + PAGE - 1) / PAGE) : -1;

        Runnable show = () -> {
            try {
                byte[] buf = new byte[PAGE];
                int len = readAt(source, page[0] * PAGE, buf);
                body.setText(formatPage(buf, len, page[0] * PAGE));
                where.setText("page " + (page[0] + 1) + (pages > 0 ? " / " + pages : ""));
                prev.setEnabled(page[0] > 0);
                next.setEnabled(len == PAGE);
            } catch (IOException e) {
                body.setText("could not read: " + e.getMessage());
            }
        };
        prev.setOnClickListener(v -> {
            if (page[0] > 0) {
                page[0]--;
                show.run();
            }
        });
        next.setOnClickListener(v -> {
            page[0]++;
            show.run();
        });
        show.run();

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(column);
        return scroll;
    }

    private static int readAt(Source source, long offset, byte[] buf) throws IOException {
        ParcelFileDescriptor fd = source.openFd();
        if (fd != null) {
            try (ParcelFileDescriptor pfd = fd;
                 java.io.FileInputStream in = new java.io.FileInputStream(pfd.getFileDescriptor())) {
                in.getChannel().position(offset);
                int off = 0, r;
                while (off < buf.length && (r = in.read(buf, off, buf.length - off)) >= 0) off += r;
                return off;
            }
        }
        try (InputStream in = source.open()) {
            long left = offset;
            while (left > 0) {
                long skipped = in.skip(left);
                if (skipped <= 0) {
                    if (in.read() < 0) return 0;
                    skipped = 1;
                }
                left -= skipped;
            }
            int off = 0, r;
            while (off < buf.length && (r = in.read(buf, off, buf.length - off)) >= 0) off += r;
            return off;
        }
    }
}
