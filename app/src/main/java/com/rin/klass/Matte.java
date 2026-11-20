package com.rin.klass;

import android.graphics.Bitmap;

import java.util.List;

/**
 * Убирает однотонный фон вокруг записанной анимации (заливка от краёв кадра),
 * чтобы подарок можно было превратить в стикер с прозрачным фоном.
 */
final class Matte {
    private Matte() {
    }

    private static final int TOL = 26;
    private static final int FRINGE = 56;

    static void apply(List<Bitmap> frames) {
        if (frames.isEmpty()) {
            return;
        }
        Bitmap f0 = frames.get(0);
        int w = f0.getWidth();
        int h = f0.getHeight();
        int[] px = new int[w * h];
        f0.getPixels(px, 0, w, 0, 0, w, h);
        int[] corners = {px[0], px[w - 1], px[(h - 1) * w], px[w * h - 1]};
        for (int i = 1; i < 4; i++) {
            if (chDiff(corners[0], corners[i]) > 30) {
                return; // фон неоднородный - не трогаем
            }
        }
        int r = 0;
        int g = 0;
        int b = 0;
        for (int c : corners) {
            r += (c >> 16) & 255;
            g += (c >> 8) & 255;
            b += c & 255;
        }
        int bg = 0xFF000000 | ((r / 4) << 16) | ((g / 4) << 8) | (b / 4);
        for (Bitmap f : frames) {
            process(f, bg);
        }
    }

    private static int chDiff(int a, int b) {
        int dr = Math.abs(((a >> 16) & 255) - ((b >> 16) & 255));
        int dg = Math.abs(((a >> 8) & 255) - ((b >> 8) & 255));
        int db = Math.abs((a & 255) - (b & 255));
        return Math.max(dr, Math.max(dg, db));
    }

    private static void process(Bitmap f, int bg) {
        int w = f.getWidth();
        int h = f.getHeight();
        int[] px = new int[w * h];
        f.getPixels(px, 0, w, 0, 0, w, h);
        boolean[] gone = new boolean[w * h];
        int[] queue = new int[w * h];
        int qh = 0;
        int qt = 0;
        for (int x = 0; x < w; x++) {
            qt = seed(px, gone, queue, qt, x, bg);
            qt = seed(px, gone, queue, qt, (h - 1) * w + x, bg);
        }
        for (int y = 1; y < h - 1; y++) {
            qt = seed(px, gone, queue, qt, y * w, bg);
            qt = seed(px, gone, queue, qt, y * w + w - 1, bg);
        }
        int removed = qt;
        while (qh < qt) {
            int p = queue[qh++];
            int x = p % w;
            int y = p / w;
            if (x > 0) {
                qt = seed(px, gone, queue, qt, p - 1, bg);
            }
            if (x < w - 1) {
                qt = seed(px, gone, queue, qt, p + 1, bg);
            }
            if (y > 0) {
                qt = seed(px, gone, queue, qt, p - w, bg);
            }
            if (y < h - 1) {
                qt = seed(px, gone, queue, qt, p + w, bg);
            }
        }
        removed = qt;
        if (removed == 0 || removed > (long) w * h * 92 / 100) {
            return; // фона нет или убрали бы почти всё - оставляем как есть
        }
        // тонкая кайма вдоль границы: полупрозрачные пиксели с цветом, близким к фону
        boolean[] fringe = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (gone[i]) {
                    continue;
                }
                boolean near = (x > 0 && gone[i - 1]) || (x < w - 1 && gone[i + 1])
                        || (y > 0 && gone[i - w]) || (y < h - 1 && gone[i + w]);
                if (near && chDiff(px[i], bg) <= FRINGE) {
                    fringe[i] = true;
                }
            }
        }
        for (int i = 0; i < px.length; i++) {
            if (gone[i] || fringe[i]) {
                px[i] = 0;
            }
        }
        f.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static int seed(int[] px, boolean[] gone, int[] queue, int qt, int idx, int bg) {
        if (!gone[idx] && chDiff(px[idx], bg) <= TOL) {
            gone[idx] = true;
            queue[qt++] = idx;
        }
        return qt;
    }
}
