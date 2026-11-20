package com.rin.klass;

import android.app.Activity;
import android.content.Context;

/** Выбор цветовой темы приложения (20 штук, список в ThemeData). */
public final class ThemeManager {
    private ThemeManager() {
    }

    public static int count() {
        return ThemeData.NAMES.length;
    }

    public static String name(int i) {
        return ThemeData.NAMES[i];
    }

    public static int primary(int i) {
        return ThemeData.PRIMARY[i];
    }

    public static int index(Context c) {
        return Prefs.theme(c);
    }

    /** Вызывать в onCreate ДО super.onCreate(). */
    public static void apply(Activity a) {
        a.setTheme(ThemeData.STYLES[index(a)]);
    }
}
