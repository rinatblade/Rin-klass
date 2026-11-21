package com.rin.klass;

import android.content.Context;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * «Ловец подарков»: страница присылает адреса картинок, здесь проверяется, анимация ли это,
 * и по нажатию на подарок он скачивается, превращается в GIF и сохраняется.
 */
final class Catcher {
    interface Host {
        /** Открыта ли сейчас страница Одноклассников (только с неё принимаем команды). */
        boolean onOkPage();

        void js(String script);

        void working(String msg);

        void failed(String msg, String url);

        /** Вызывается из фонового потока. */
        void saved(GiftStore.Gift g, boolean isNew, String url);

        /** Страница просит записать область экрана (координаты в CSS-пикселях окна страницы). */
        void startRecord(double left, double top, double width, double height, double dpr);

        /** Ручной режим записи завершён (выбрана область). */
        void pickModeOff();
    }

    private static final int YES = 1;
    private static final int NO = 2;
    private static final int PROBING = 3;

    private final Context app;
    private final Host host;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final ConcurrentHashMap<String, Integer> state = new ConcurrentHashMap<>();
    private final File cacheDir;
    private volatile int sample = 0;

    Catcher(Context c, Host host) {
        this.app = c.getApplicationContext();
        this.host = host;
        this.cacheDir = new File(app.getCacheDir(), "probe");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
    }

    Object bridge() {
        return new Bridge();
    }

    void forget() {
        state.clear();
    }

    private File cacheFile(String url) {
        return new File(cacheDir, U.sha1(url));
    }

    private void reply(String url, boolean yes) {
        host.js("window.__klass&&window.__klass.result(" + JSONObject.quote(url) + "," + yes + ")");
    }

    private void probe(final String url, final String page) {
        if (!host.onOkPage() || url == null || url.isEmpty()) {
            return;
        }
        Integer s = state.get(url);
        if (s != null) {
            if (s != PROBING) {
                reply(url, s == YES);
            }
            return;
        }
        if (state.size() > 3000) {
            state.clear();
        }
        state.put(url, PROBING);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Fetcher.Result r = Fetcher.fetch(page, url, true);
                finishProbe(url, r);
            }
        });
    }

    private void finishProbe(String url, Fetcher.Result r) {
        boolean yes = r.animated && r.data != null;
        if (yes) {
            try {
                U.writeBytes(cacheFile(url), r.data);
            } catch (IOException ignored) {
                // без кэша просто скачаем заново при нажатии
            }
            Diag.log("Анимация найдена: " + Media.label(r.kind) + " " + (r.data.length / 1024) + " КБ  "
                    + Diag.shortUrl(url));
        } else if (r.error != null) {
            Diag.log("Не проверить (" + r.error + "): " + Diag.shortUrl(url));
        } else if (r.head != null && url.contains("getGift") && sample++ < 40) {
            StringBuilder hex = new StringBuilder();
            for (byte b : r.head) {
                hex.append(String.format("%02x", b & 0xFF));
            }
            int len = r.data == null ? -1 : r.data.length;
            Diag.log("Не анимация: " + Media.label(r.kind) + (len >= 0 ? " " + len + " Б" : "")
                    + " байты=" + hex + "  " + Diag.shortUrl(url));
        }
        state.put(url, yes ? YES : NO);
        reply(url, yes);
    }

    private void probeData(final String url, String b64) {
        if (!host.onOkPage()) {
            return;
        }
        try {
            final byte[] d = Base64.decode(b64, Base64.DEFAULT);
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    finishProbe(url, Fetcher.classify(d));
                }
            });
        } catch (IllegalArgumentException e) {
            state.put(url, NO);
        }
    }

    private void pick(final String url, final String page, final byte[] given) {
        if (!host.onOkPage()) {
            return;
        }
        host.working("Скачиваю подарок…");
        pool.execute(new Runnable() {
            @Override
            public void run() {
                process(url, page, given);
            }
        });
    }

    private void process(String url, String page, byte[] given) {
        try {
            byte[] data = given;
            if (data == null) {
                File cf = cacheFile(url);
                if (cf.exists()) {
                    data = U.readFile(cf);
                }
            }
            if (data == null) {
                Fetcher.Result r = Fetcher.fetch(page, url, false);
                if (r.data == null) {
                    fail(url, r.error == null ? "Не удалось скачать" : r.error);
                    return;
                }
                data = r.data;
            }
            int kind = Media.kind(data);
            if (!Fetcher.isAnimated(data, kind)) {
                fail(url, "Это обычная картинка, не анимация");
                return;
            }
            byte[] gif = Converter.toGif(app, data, kind);
            boolean[] isNew = new boolean[1];
            GiftStore.Gift g = GiftStore.add(app, gif, isNew);
            Diag.log("Сохранён подарок " + g.id + " (" + Media.label(kind) + " → gif, " + (gif.length / 1024) + " КБ)");
            host.saved(g, isNew[0], url);
        } catch (IOException e) {
            fail(url, e.getMessage() == null ? "Не получилось скачать" : e.getMessage());
        } catch (OutOfMemoryError e) {
            fail(url, "Подарок слишком большой для телефона");
        } catch (RuntimeException e) {
            fail(url, "Ошибка: " + e);
        }
    }

    private void fail(String url, String msg) {
        Diag.log("Ошибка: " + msg + "  " + Diag.shortUrl(url));
        host.failed(msg, url);
    }

    /** Методы, которые видит страница (вызываются из потока WebView). */
    private final class Bridge {
        @JavascriptInterface
        public void probe(String url, String page) {
            Catcher.this.probe(url, page);
        }

        @JavascriptInterface
        public void record(double l, double t, double w, double h, double dpr) {
            if (host.onOkPage()) {
                host.startRecord(l, t, w, h, dpr);
            }
        }

        @JavascriptInterface
        public void pickModeOff() {
            host.pickModeOff();
        }

        @JavascriptInterface
        public void log(String s) {
            if (s != null && s.length() < 300) {
                Diag.log("На странице: " + s);
            }
        }

        @JavascriptInterface
        public void probeData(String url, String b64) {
            Catcher.this.probeData(url, b64);
        }

        @JavascriptInterface
        public void pick(String url, String page) {
            Catcher.this.pick(url, page, null);
        }

        @JavascriptInterface
        public void pickData(String url, String page, String b64) {
            try {
                Catcher.this.pick(url, page, Base64.decode(b64, Base64.DEFAULT));
            } catch (IllegalArgumentException e) {
                fail(url, "Не удалось прочитать подарок");
            }
        }
    }
}
