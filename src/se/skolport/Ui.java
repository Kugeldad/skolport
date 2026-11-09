package se.skolport;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/** Small helpers for a high-contrast, animation-free UI that works on e-ink. */
final class Ui {
    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    private static GradientDrawable shape(Context c, int fill, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(c, strokeDp), Color.BLACK);
        g.setCornerRadius(dp(c, 10));
        return g;
    }

    /** Outlined button that inverts (white on black) while pressed. */
    static Button button(Context c, String label, float textSp, int strokeDp) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_pressed}, shape(c, Color.BLACK, strokeDp));
        bg.addState(new int[] {}, shape(c, Color.WHITE, strokeDp));
        b.setBackground(bg);
        b.setTextColor(new ColorStateList(
                new int[][] {{android.R.attr.state_pressed}, {}},
                new int[] {Color.WHITE, Color.BLACK}));
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        int h = dp(c, 14);
        b.setPadding(h, 0, h, 0);
        return b;
    }

    static TextView text(Context c, String s, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(Color.BLACK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }
}
