package com.rin.klass;

import android.content.Context;
import android.content.SharedPreferences;

/** Настройки приложения. */
public final class Prefs {
    private Prefs() {
    }

    public static SharedPreferences raw(Context c) {
        return c.getApplicationContext().getSharedPreferences("klass", Context.MODE_PRIVATE);
    }

    /** Главный выключатель «Ловца подарков». */
    public static boolean catcherOn(Context c) {
        return raw(c).getBoolean("catcher", true);
    }

    public static void setCatcherOn(Context c, boolean on) {
        raw(c).edit().putBoolean("catcher", on).apply();
    }

    /** Держать приложение в фоне (служба с уведомлением), пока «Ловец» включён. */
    public static boolean keepBg(Context c) {
        return raw(c).getBoolean("keepBg", true);
    }

    public static void setKeepBg(Context c, boolean on) {
        raw(c).edit().putBoolean("keepBg", on).apply();
    }

    public static int theme(Context c) {
        int i = raw(c).getInt("theme", 0);
        return (i >= 0 && i < ThemeData.NAMES.length) ? i : 0;
    }

    public static void setTheme(Context c, int i) {
        raw(c).edit().putInt("theme", i).apply();
    }

    /** Файл выбранного шрифта; null - пользователь ещё не выбирал. */
    public static String font(Context c) {
        return raw(c).getString("font", null);
    }

    public static void setFont(Context c, String file) {
        raw(c).edit().putString("font", file).apply();
    }

    public static String publisher(Context c) {
        return raw(c).getString("publisher", "Rin-klass");
    }

    public static void setPublisher(Context c, String s) {
        raw(c).edit().putString("publisher", s).apply();
    }

    public static String lastUrl(Context c) {
        return raw(c).getString("lastUrl", "https://ok.ru/");
    }

    public static void setLastUrl(Context c, String url) {
        raw(c).edit().putString("lastUrl", url).apply();
    }

    public static String packId(Context c) {
        return raw(c).getString("packId", null);
    }

    public static void setPackId(Context c, String id) {
        raw(c).edit().putString("packId", id).apply();
    }

    public static boolean askedNotifications(Context c) {
        return raw(c).getBoolean("askedNotif", false);
    }

    public static void setAskedNotifications(Context c) {
        raw(c).edit().putBoolean("askedNotif", true).apply();
    }
}
