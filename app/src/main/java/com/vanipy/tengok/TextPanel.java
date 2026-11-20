package com.vanipy.tengok;

import android.content.Context;
import android.graphics.Typeface;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Plain-text screen: UTF-8 when it really is, Latin-1 as the honest fallback. */
public class TextPanel {
    private static final long MAX_CHARS_BYTES = 1024 * 1024; // show at most 1 MB of text

    public static class Result {
        public String text;
        public String charset;
        public boolean truncated;
        public long bytesShown;
    }

    public static Result decode(Source source) throws IOException {
        byte[] raw = source.readAll(MAX_CHARS_BYTES + 1);
        Result r = new Result();
        r.truncated = raw.length > MAX_CHARS_BYTES;
        r.bytesShown = Math.min(raw.length, MAX_CHARS_BYTES);
        byte[] shown = r.truncated ? java.util.Arrays.copyOf(raw, (int) MAX_CHARS_BYTES) : raw;
        try {
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            r.text = dec.decode(ByteBuffer.wrap(shown)).toString();
            r.charset = "UTF-8";
        } catch (CharacterCodingException e) {
            r.text = new String(shown, StandardCharsets.ISO_8859_1);
            r.charset = "Latin-1 (not valid UTF-8)";
        }
        return r;
    }

    public static View build(Context ctx, Result r) {
        LinearLayout column = Ui.column(ctx);
        LinearLayout card = Ui.card(ctx);
        String caption = "text · " + r.charset + " · " + r.bytesShown + " bytes"
                + (r.truncated ? " · showing the first 1 MB only" : "");
        card.addView(Ui.muted(ctx, caption));
        TextView body = new TextView(ctx);
        body.setText(r.text);
        body.setTextSize(14);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextColor(0xFF1B2340);
        body.setTextIsSelectable(true);
        body.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        card.addView(body);
        column.addView(card);
        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(column);
        return scroll;
    }
}
