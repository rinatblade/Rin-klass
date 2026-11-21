package com.rin.klass;

import android.util.Base64;
import android.webkit.CookieManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;

/**
 * Скачивание картинок и анимаций с сайта теми же куками, что у встроенного браузера
 * (чтобы работали и подарки, доступные только после входа).
 */
final class Fetcher {
    private Fetcher() {
    }

    static final int MAX_BYTES = 14 * 1024 * 1024;
    /** Решение «анимация это или нет» принимаем по первым байтам - дальше качаем только анимации. */
    private static final int PREFIX = 8192;

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    static final class Result {
        byte[] data;
        int kind;
        boolean animated;
        String error;
        byte[] head;
    }

    static boolean allowedUrl(String url) {
        try {
            URL u = new URL(url);
            String proto = u.getProtocol();
            if (!"http".equals(proto) && !"https".equals(proto)) {
                return false;
            }
            String h = u.getHost().toLowerCase(Locale.ROOT);
            if (h.isEmpty() || h.equals("localhost") || h.endsWith(".local") || h.endsWith(".internal")) {
                return false;
            }
            if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")
                    || h.startsWith("0.") || h.equals("[::1]")) {
                return false;
            }
            if (h.startsWith("172.")) {
                String[] p = h.split("\\.");
                if (p.length > 1) {
                    try {
                        int s = Integer.parseInt(p[1]);
                        if (s >= 16 && s <= 31) {
                            return false;
                        }
                    } catch (NumberFormatException ignored) {
                        // не IP-адрес
                    }
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static byte[] decodeDataUrl(String url) throws IOException {
        int comma = url.indexOf(',');
        if (comma < 0) {
            throw new IOException("Неверная ссылка data:");
        }
        String meta = url.substring(5, comma);
        String body = url.substring(comma + 1);
        if (meta.contains(";base64")) {
            return Base64.decode(body, Base64.DEFAULT);
        }
        return URLDecoder.decode(body, "ISO-8859-1").getBytes("ISO-8859-1");
    }

    static boolean isAnimated(byte[] d, int kind) {
        try {
            switch (kind) {
                case Media.GIF:
                    return GifDecoder.info(d).frames > 1;
                case Media.WEBP:
                    return WebpAnim.isAnimated(d);
                case Media.PNG:
                    return Media.isApng(d);
                case Media.VIDEO:
                    return true;
                default:
                    return false;
            }
        } catch (IOException e) {
            return false;
        }
    }

    static Result classify(byte[] d) {
        Result r = new Result();
        r.data = d;
        r.kind = Media.kind(d);
        r.animated = isAnimated(d, r.kind);
        return r;
    }

    /**
     * @param probeOnly true - только выяснить, анимация ли это (обычные картинки не докачиваем до конца)
     */
    static Result fetch(String pageUrl, String url, boolean probeOnly) {
        Result r = new Result();
        try {
            if (url.startsWith("data:")) {
                return classify(decodeDataUrl(url));
            }
            if (!allowedUrl(url)) {
                r.error = "Ссылка не поддерживается";
                return r;
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept",
                        "image/gif,image/webp,image/apng,image/png,image/*;q=0.8,video/*;q=0.6,*/*;q=0.5");
                if (pageUrl != null && !pageUrl.isEmpty()) {
                    c.setRequestProperty("Referer", pageUrl);
                }
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null && !cookie.isEmpty()) {
                    c.setRequestProperty("Cookie", cookie);
                }
                int code = c.getResponseCode();
                if (code >= 400) {
                    r.error = "Сайт ответил: " + code;
                    return r;
                }
                int len = c.getContentLength();
                if (len > MAX_BYTES) {
                    r.error = "Файл слишком большой";
                    return r;
                }
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream(len > 0 ? len : 65536);
                byte[] buf = new byte[16384];
                boolean decided = false;
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > MAX_BYTES) {
                        r.error = "Файл слишком большой";
                        return r;
                    }
                    if (probeOnly && !decided && bos.size() >= PREFIX) {
                        decided = true;
                        byte[] head = bos.toByteArray();
                        int kind = Media.kind(head);
                        boolean maybe;
                        switch (kind) {
                            case Media.GIF:
                            case Media.VIDEO:
                                maybe = true;
                                break;
                            case Media.WEBP:
                                maybe = WebpAnim.isAnimated(head);
                                break;
                            case Media.PNG:
                                maybe = Media.isApng(head);
                                break;
                            default:
                                maybe = false;
                        }
                        if (!maybe) {
                            r.kind = kind;
                            r.animated = false;
                            r.head = java.util.Arrays.copyOf(head, 16);
                            return r;
                        }
                    }
                }
                byte[] all = bos.toByteArray();
                Result done = classify(all);
                done.head = java.util.Arrays.copyOf(all, Math.min(16, all.length));
                return done;
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            r.error = e.getMessage() == null ? "Нет связи с сайтом" : e.getMessage();
            return r;
        } catch (RuntimeException e) {
            r.error = "Ошибка загрузки";
            return r;
        }
    }
}
