package com.rin.klass;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Запись области экрана в кадры (через PixelCopy - снимает то, что реально показано, в том числе
 * canvas, видео и Lottie). Работает с любой анимацией, формат файла не важен.
 */
final class Recorder {
    interface Done {
        /** Вызывается в главном потоке. frames == null - ошибка. */
        void done(List<Bitmap> frames, List<Integer> delays, String error);
    }

    private static final int INTERVAL = 70;
    private static final int MAX_FRAMES = 110;
    private static final int MAX_SIDE = 480;

    private final Activity act;
    private final Rect src;
    private final int outW;
    private final int outH;
    private final int durationMs;
    private final Done cb;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Bitmap> frames = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();
    private long t0;
    private boolean finished;

    private Recorder(Activity act, Rect src, int durationMs, Done cb) {
        this.act = act;
        this.src = src;
        this.durationMs = durationMs;
        this.cb = cb;
        float s = Math.min(1f, (float) MAX_SIDE / Math.max(src.width(), src.height()));
        this.outW = Math.max(1, Math.round(src.width() * s));
        this.outH = Math.max(1, Math.round(src.height() * s));
    }

    /** left/top/w/h - прямоугольник в координатах WebView, в пикселях экрана. */
    static void start(Activity act, View web, int left, int top, int w, int h, int durationMs, Done cb) {
        int[] loc = new int[2];
        web.getLocationInWindow(loc);
        Rect r = new Rect(loc[0] + left, loc[1] + top, loc[0] + left + w, loc[1] + top + h);
        Rect wv = new Rect(loc[0], loc[1], loc[0] + web.getWidth(), loc[1] + web.getHeight());
        if (!r.intersect(wv) || r.width() < 16 || r.height() < 16) {
            cb.done(null, null, "Анимация не видна на экране — прокрутите страницу так, чтобы она была целиком");
            return;
        }
        new Recorder(act, r, durationMs, cb).next();
    }

    private void next() {
        if (finished) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (t0 == 0) {
            t0 = now;
        }
        if (now - t0 >= durationMs || frames.size() >= MAX_FRAMES) {
            finish(null);
            return;
        }
        final Bitmap bmp = Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(act.getWindow(), src, bmp, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int result) {
                    if (finished) {
                        bmp.recycle();
                        return;
                    }
                    if (result != PixelCopy.SUCCESS) {
                        bmp.recycle();
                        finish(frames.size() >= 4 ? null : "Не удалось снять экран (код " + result + ")");
                        return;
                    }
                    long ts = SystemClock.uptimeMillis();
                    Bitmap keep = bmp;
                    if (bmp.getWidth() != outW || bmp.getHeight() != outH) {
                        keep = Bitmap.createScaledBitmap(bmp, outW, outH, true);
                        bmp.recycle();
                    }
                    frames.add(keep);
                    times.add(ts);
                    long wait = Math.max(0, ts + INTERVAL - SystemClock.uptimeMillis());
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            next();
                        }
                    }, wait);
                }
            }, h);
        } catch (IllegalArgumentException | IllegalStateException e) {
            bmp.recycle();
            finish("Не удалось снять экран");
        }
    }

    private void finish(String err) {
        if (finished) {
            return;
        }
        finished = true;
        if (err != null || frames.size() < 2) {
            for (Bitmap b : frames) {
                b.recycle();
            }
            cb.done(null, null, err != null ? err : "Получилось слишком мало кадров");
            return;
        }
        List<Integer> delays = new ArrayList<>();
        int n = frames.size();
        int[] d = new int[n - 1];
        for (int i = 0; i < n - 1; i++) {
            d[i] = (int) Math.max(40, Math.min(400, times.get(i + 1) - times.get(i)));
            delays.add(Math.round(d[i] / 10f) * 10);
        }
        int[] sorted = Arrays.copyOf(d, d.length);
        Arrays.sort(sorted);
        delays.add(Math.round(sorted[sorted.length / 2] / 10f) * 10);
        cb.done(frames, delays, null);
    }
}
