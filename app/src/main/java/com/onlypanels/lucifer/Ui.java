package com.onlypanels.lucifer;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Colours and small helpers for building screens. Black and purple theme. */
final class Ui {
    static final int BG = 0xFF09080D;
    static final int SURFACE = 0xFF16131D;
    static final int SURFACE_2 = 0xFF221D2C;
    static final int PURPLE = 0xFF8B3DFF;
    static final int PURPLE_DARK = 0xFF4C1D95;
    static final int PURPLE_SOFT = 0xFFC4A5FF;
    static final int TEXT = 0xFFF3F0F8;
    static final int MUTED = 0xFF9C94AB;
    static final int LINE = 0xFF2C2638;
    static final int RED = 0xFFFF6B7A;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static void darkBars(Activity a) {
        Window w = a.getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static LinearLayout vbox(Context c, int padDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, padDp);
        l.setPadding(p, p, p, p);
        return l;
    }

    static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable outline(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = rounded(c, fill, radiusDp);
        d.setStroke(dp(c, 1), stroke);
        return d;
    }

    static LinearLayout.LayoutParams matchWrap(Context c, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, topMarginDp);
        return lp;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = vbox(c, 16);
        l.setBackground(rounded(c, SURFACE, 16));
        l.setLayoutParams(matchWrap(c, 12));
        return l;
    }

    static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(primary ? 0xFFFFFFFF : PURPLE_SOFT);
        b.setBackground(primary ? rounded(c, PURPLE, 12) : outline(c, SURFACE, PURPLE_DARK, 12));
        b.setStateListAnimator(null);
        b.setLayoutParams(matchWrap(c, 10));
        b.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        return b;
    }

    /** Round icon button for the top bar and message bar. */
    static TextView iconButton(Context c, String symbol, String description) {
        TextView t = text(c, symbol, 20, TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setContentDescription(description);
        int s = dp(c, 44);
        t.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        t.setBackground(rounded(c, SURFACE_2, 22));
        return t;
    }

    static View gap(Context c, int dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, dp), dp(c, dp)));
        return v;
    }

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern CODE = Pattern.compile("`([^`\\n]+)`");

    /** Light formatting for replies: **bold**, `code`, # headings and bullet lists. */
    static CharSequence markdown(String src) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        boolean inCode = false;
        String[] lines = src.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("```")) { inCode = !inCode; continue; }
            int start = out.length();
            if (inCode) {
                out.append(line);
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(0xFF0F0D14), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                Matcher h = Pattern.compile("^(#{1,6})\\s+(.*)").matcher(line);
                if (h.find()) {
                    out.append(h.group(2).replace("**", ""));
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(h.group(1).length() <= 2 ? 1.2f : 1.08f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    String l = line.replaceFirst("^(\\s*)[*-]\\s+", "$1•  ");
                    inline(out, l);
                }
            }
            if (i < lines.length - 1) out.append('\n');
        }
        return out;
    }

    private static void inline(SpannableStringBuilder out, String line) {
        int pos = 0;
        Matcher b = BOLD.matcher(line);
        while (b.find()) {
            code(out, line.substring(pos, b.start()));
            int s = out.length();
            out.append(b.group(1));
            out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            pos = b.end();
        }
        code(out, line.substring(pos));
    }

    private static void code(SpannableStringBuilder out, String part) {
        int pos = 0;
        Matcher m = CODE.matcher(part);
        while (m.find()) {
            out.append(part, pos, m.start());
            int s = out.length();
            out.append(m.group(1));
            out.setSpan(new TypefaceSpan("monospace"), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new BackgroundColorSpan(0xFF0F0D14), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            pos = m.end();
        }
        out.append(part.substring(pos));
    }

    static String size(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format(java.util.Locale.UK, "%.1f GB", bytes / 1e9);
        return String.format(java.util.Locale.UK, "%.0f MB", bytes / 1e6);
    }
}
