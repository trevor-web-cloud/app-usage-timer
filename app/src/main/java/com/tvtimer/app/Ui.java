package com.tvtimer.app;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small helpers for building the TV UI in code (no layout XML, no AndroidX).
 * Everything is sized in dp, text is large, and focus is always obvious:
 * the focused control turns amber with dark text and scales up slightly.
 */
public final class Ui {

    public static final int COLOR_BG = Color.argb(245, 10, 16, 22);
    public static final int COLOR_PANEL = Color.rgb(30, 42, 54);
    public static final int COLOR_BTN = Color.rgb(38, 54, 70);
    public static final int COLOR_BTN_TEXT = Color.WHITE;
    public static final int COLOR_FOCUS_BG = Color.rgb(255, 193, 7);
    public static final int COLOR_FOCUS_TEXT = Color.rgb(20, 20, 20);
    public static final int COLOR_TEXT = Color.WHITE;
    public static final int COLOR_TEXT_DIM = Color.rgb(176, 190, 201);
    public static final int COLOR_ACCENT = Color.rgb(255, 193, 7);

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(android.view.Gravity.CENTER);
        return t;
    }

    /** A large D-pad friendly button with a clear focus style. */
    public static Button bigButton(Context c, String label) {
        final Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(22);
        b.setTextColor(COLOR_BTN_TEXT);
        b.setBackgroundColor(COLOR_BTN);
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        b.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12));
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    b.setBackgroundColor(COLOR_FOCUS_BG);
                    b.setTextColor(COLOR_FOCUS_TEXT);
                    b.setScaleX(1.05f);
                    b.setScaleY(1.05f);
                } else {
                    b.setBackgroundColor(COLOR_BTN);
                    b.setTextColor(COLOR_BTN_TEXT);
                    b.setScaleX(1.0f);
                    b.setScaleY(1.0f);
                }
            }
        });
        return b;
    }

    public static LinearLayout.LayoutParams rowParams(Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(c, 6), 0, dp(c, 6));
        return p;
    }

    public static LinearLayout.LayoutParams cellParams(Context c, int weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        p.setMargins(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
        return p;
    }
}
