package com.rin.klass;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Шрифты приложения (все с кириллицей). Список лежит в assets/fonts/index.txt,
 * строки вида: файл|название|вес (вес больше 0 - ось wght у вариативных шрифтов).
 * Нулевой пункт - системный шрифт по умолчанию.
 */
public final class FontManager {
    private static final class Entry {
        final String file;
        final String label;
        final int wght;

        Entry(String file, String label, int wght) {
            this.file = file;
            this.label = label;
            this.wght = wght;
        }
    }

    private static List<Entry> entries;
    private static Typeface[] cache;

    private FontManager() {
    }

    private static synchronized void init(Context c) {
        if (entries != null) {
            return;
        }
        List<Entry> list = new ArrayList<>();
        list.add(new Entry("", "Системный — по умолчанию", 0));
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getApplicationContext().getAssets().open("fonts/index.txt"), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\\|");
                if (p.length < 2) {
                    continue;
                }
                int w = 0;
                if (p.length > 2) {
                    try {
                        w = Integer.parseInt(p[2].trim());
                    } catch (NumberFormatException ignored) {
                        // оставляем 0
                    }
                }
                list.add(new Entry(p[0].trim(), p[1].trim(), w));
            }
        } catch (IOException ignored) {
            // останется только системный шрифт
        }
        entries = list;
        cache = new Typeface[list.size()];
    }

    public static int count(Context c) {
        init(c);
        return entries.size();
    }

    public static String label(Context c, int index) {
        init(c);
        return entries.get(clamp(index)).label;
    }

    public static String file(Context c, int index) {
        init(c);
        return entries.get(clamp(index)).file;
    }

    private static int clamp(int i) {
        return Math.max(0, Math.min(i, entries.size() - 1));
    }

    /** Индекс выбранного шрифта; пока не выбран - «Nunito», а если его нет - системный. */
    public static int selected(Context c) {
        init(c);
        String f = Prefs.font(c);
        if (f != null) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).file.equals(f)) {
                    return i;
                }
            }
            return 0;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).file.startsWith("nunito")) {
                return i;
            }
        }
        return 0;
    }

    /** Шрифт по номеру; для системного (0) - null, чтобы оставить оформление Material. */
    public static Typeface get(Context c, int index) {
        init(c);
        int i = clamp(index);
        if (entries.get(i).file.isEmpty()) {
            return null;
        }
        synchronized (FontManager.class) {
            if (cache[i] == null) {
                cache[i] = load(c.getApplicationContext(), entries.get(i));
            }
            return cache[i];
        }
    }

    public static Typeface current(Context c) {
        return get(c, selected(c));
    }

    private static Typeface load(Context c, Entry e) {
        String path = "fonts/" + e.file;
        Typeface t = null;
        if (e.wght > 0 && Build.VERSION.SDK_INT >= 26) {
            try {
                t = new Typeface.Builder(c.getAssets(), path)
                        .setFontVariationSettings("'wght' " + e.wght)
                        .build();
            } catch (Exception ignored) {
                t = null;
            }
        }
        if (t == null) {
            try {
                t = Typeface.createFromAsset(c.getAssets(), path);
            } catch (Exception ignored) {
                t = null;
            }
        }
        return t != null ? t : Typeface.DEFAULT;
    }
}
