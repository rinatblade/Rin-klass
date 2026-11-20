package com.rin.klass;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Pattern;

/** Журнал для экрана «Диагностика»: что приложение увидело на сайте и что с этим сделало. */
public final class Diag {
    private Diag() {
    }

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final LinkedHashSet<String> NET = new LinkedHashSet<>();
    private static final Pattern MEDIA = Pattern.compile(
            "(?i)\\.(gif|webp|apng|mp4|webm|json|tgs|lottie|svga)(\\?|#|$)|gift|present|podar|anim");
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public static synchronized void log(String s) {
        LINES.addLast(TIME.format(new Date()) + "  " + s);
        while (LINES.size() > 250) {
            LINES.removeFirst();
        }
    }

    /** Запрос страницы к сети: запоминаем только похожие на картинки/анимации. */
    public static synchronized void net(String url) {
        if (url == null || url.startsWith("data:") || !MEDIA.matcher(url).find()) {
            return;
        }
        if (url.length() > 160) {
            url = url.substring(0, 160) + "…";
        }
        NET.add(url);
        while (NET.size() > 120) {
            NET.remove(NET.iterator().next());
        }
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== События ===\n");
        if (LINES.isEmpty()) {
            sb.append("(пока пусто)\n");
        }
        for (String l : LINES) {
            sb.append(l).append('\n');
        }
        sb.append("\n=== Запросы сайта (картинки/анимации) ===\n");
        if (NET.isEmpty()) {
            sb.append("(пока пусто)\n");
        }
        for (String u : NET) {
            sb.append(u).append('\n');
        }
        return sb.toString();
    }

    public static String shortUrl(String u) {
        if (u == null) {
            return "";
        }
        return u.length() > 90 ? u.substring(0, 90) + "…" : u;
    }
}
