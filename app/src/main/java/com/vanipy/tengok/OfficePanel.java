package com.vanipy.tengok;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shows the text OfficeText pulled out of a .docx, .xlsx or .pptx. */
public final class OfficePanel {
    private OfficePanel() {
    }

    public static View build(Context ctx, OfficeText.Result r, Runnable showZip, Runnable showHex) {
        LinearLayout column = Ui.column(ctx);
        LinearLayout head = Ui.card(ctx);
        head.addView(Ui.line(ctx, r.format));
        head.addView(Ui.muted(ctx, "Text preview, not the real layout. Nothing is executed, saved or sent anywhere."));
        for (String n : r.notes) head.addView(Ui.muted(ctx, n));
        if (r.truncated) head.addView(Ui.muted(ctx, "Stopped at a safety limit; later content is not shown."));
        if (showZip != null) {
            Button zip = new Button(ctx);
            zip.setText("Show ZIP contents");
            zip.setOnClickListener(v -> showZip.run());
            head.addView(zip);
        }
        Button hex = new Button(ctx);
        hex.setText("Show raw bytes");
        hex.setOnClickListener(v -> showHex.run());
        head.addView(hex);
        column.addView(head);
        boolean sheet = r.format.startsWith("Excel");
        for (OfficeText.Section s : r.sections) {
            LinearLayout card = Ui.card(ctx);
            card.addView(Ui.line(ctx, s.title));
            TextView body = new TextView(ctx);
            body.setText(s.text);
            body.setTextSize(sheet ? 13 : 15);
            body.setTextColor(0xFF1B2340);
            if (sheet) body.setTypeface(Typeface.MONOSPACE);
            body.setTextIsSelectable(true);
            body.setPadding(0, Ui.dp(ctx, 8), 0, 0);
            card.addView(body);
            column.addView(card);
        }
        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(column);
        return scroll;
    }
}
