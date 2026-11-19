package com.vanipy.tengok;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small programmatic-UI helpers so every screen shares one look. */
public final class Ui {
    private Ui() {
    }

    public static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    public static LinearLayout column(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 12));
        return l;
    }

    public static LinearLayout row(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return l;
    }

    /** A white rounded card with a soft outline. */
    public static LinearLayout card(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(ctx, 14));
        bg.setStroke(Math.max(1, dp(ctx, 1)), 0xFFE3E5EE);
        l.setBackground(bg);
        l.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(ctx, 8), 0, 0);
        l.setLayoutParams(lp);
        return l;
    }

    public static TextView line(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(0xFF1B2340);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView muted(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(0xFF5A6072);
        t.setPadding(0, dp(ctx, 2), 0, 0);
        return t;
    }

    public static View spacer(Context ctx, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(ctx, dp)));
        return v;
    }
}
