package com.rin.klass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Делает из GIF анимированный стикер WhatsApp: WebP 512x512, до 10 секунд, до 500 КБ,
 * кадры не короче 8 мс. Если файл получается тяжёлым - снижает качество и убирает часть кадров.
 */
final class StickerMaker {
    private StickerMaker() {
    }

    static final int SIZE = 512;
    private static final int MAX_BYTES = 480 * 1024;
    private static final int MAX_MS = 9800;

    static byte[] make(byte[] gif) throws IOException {
        GifDecoder.Info info = GifDecoder.info(gif);
        if (info.frames < 1) {
            throw new IOException("В GIF нет кадров");
        }
        // Сначала снижаем качество картинки, кадры не трогаем - так анимация остаётся плавной.
        // Кадры прореживаем только если иначе никак не влезть в 500 КБ.
        int[] quality = {80, 68, 56, 46, 38, 30, 24, 18, 14, 30, 22, 16, 20, 14};
        int[] step = {1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 3, 3};
        byte[] last = null;
        for (int i = 0; i < quality.length; i++) {
            last = pass(gif, quality[i], step[i]);
            if (last.length <= MAX_BYTES) {
                return last;
            }
        }
        throw new IOException("Подарок слишком тяжёлый для стикера WhatsApp ("
                + (last == null ? 0 : last.length / 1024) + " КБ при пределе 500 КБ)");
    }

    private static byte[] pass(byte[] gif, final int quality, final int step) throws IOException {
        final List<byte[]> frames = new ArrayList<>();
        final List<Integer> delays = new ArrayList<>();
        final Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        final Canvas cv = new Canvas(out);
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        final Bitmap.CompressFormat fmt = Build.VERSION.SDK_INT >= 30
                ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
        final int[] idx = {0};
        final int[] total = {0};
        final byte[][] pending = {null};
        final int[] pendingDelay = {0};
        try {
            GifDecoder.decode(gif, new GifDecoder.Sink() {
                @Override
                public boolean frame(int[] argb, int w, int h, int delay) throws IOException {
                    int d = Math.min(Converter.fixDelay(delay), 3000);
                    if (total[0] > 0 && total[0] + d > MAX_MS) {
                        return false;
                    }
                    int i = idx[0]++;
                    if (i % step == 0) {
                        if (pending[0] != null) {
                            frames.add(pending[0]);
                            delays.add(pendingDelay[0]);
                        }
                        Bitmap src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        src.setPixels(argb, 0, w, 0, 0, w, h);
                        out.eraseColor(0);
                        float s = Math.min((float) SIZE / w, (float) SIZE / h);
                        float dw = w * s;
                        float dh = h * s;
                        RectF dst = new RectF((SIZE - dw) / 2f, (SIZE - dh) / 2f, (SIZE + dw) / 2f, (SIZE + dh) / 2f);
                        cv.drawBitmap(src, null, dst, paint);
                        src.recycle();
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        out.compress(fmt, quality, b);
                        pending[0] = b.toByteArray();
                        pendingDelay[0] = d;
                    } else {
                        pendingDelay[0] += d;
                    }
                    total[0] += d;
                    return true;
                }
            });
        } finally {
            out.recycle();
        }
        if (pending[0] != null) {
            frames.add(pending[0]);
            delays.add(pendingDelay[0]);
        }
        if (frames.isEmpty()) {
            throw new IOException("Не удалось получить кадры из GIF");
        }
        if (frames.size() == 1) {
            // WhatsApp ждёт анимацию минимум из двух кадров
            int half = Math.max(100, delays.get(0) / 2);
            frames.add(frames.get(0));
            delays.set(0, half);
            delays.add(half);
        }
        return WebpAnim.build(SIZE, SIZE, frames, delays);
    }
}
