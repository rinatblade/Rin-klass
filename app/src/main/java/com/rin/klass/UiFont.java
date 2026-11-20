package com.rin.klass;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.TextView;

/** Применяет выбранный пользователем шрифт ко всем надписям на экране. */
public final class UiFont {
    private UiFont() {
    }

    public static void apply(Context c, View root) {
        Typeface tf = FontManager.current(c);
        if (tf == null || root == null) {
            return;
        }
        walk(root, tf);
    }

    private static void walk(View v, Typeface tf) {
        if (v instanceof WebView) {
            return;
        }
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            Typeface old = t.getTypeface();
            int style = old == null ? Typeface.NORMAL : old.getStyle();
            t.setTypeface(tf, style);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), tf);
            }
        }
    }
}
