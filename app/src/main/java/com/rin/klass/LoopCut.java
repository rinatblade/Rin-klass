package com.rin.klass;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

/** Если записанная анимация успела пройти полный круг - обрезает запись ровно по кругу, чтобы GIF зацикливался без скачка. */
final class LoopCut {
    private LoopCut() {
    }

    static final class Result {
        List<Bitmap> frames;
        List<Integer> delays;
    }

    private static int[] sig(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 20, 20, true);
        int[] px = new int[400];
        s.getPixels(px, 0, 20, 0, 0, 20, 20);
        if (s != b) {
            s.recycle();
        }
        int[] out = new int[400];
        for (int i = 0; i < 400; i++) {
            int p = px[i];
            out[i] = (((p >> 16) & 255) * 30 + ((p >> 8) & 255) * 59 + (p & 255) * 11) / 100;
        }
        return out;
    }

    private static double diff(int[] a, int[] b) {
        long s = 0;
        for (int i = 0; i < a.length; i++) {
            s += Math.abs(a[i] - b[i]);
        }
        return s / (double) a.length;
    }

    static Result cut(List<Bitmap> frames, List<Integer> delays) {
        Result r = new Result();
        r.frames = frames;
        r.delays = delays;
        int n = frames.size();
        if (n < 14) {
            return r;
        }
        int[][] sig = new int[n][];
        for (int i = 0; i < n; i++) {
            sig[i] = sig(frames.get(i));
        }
        double motion = diff(sig[0], sig[1]);
        int minK = Math.max(8, n / 5);
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int k = minK; k < n; k++) {
            double d = diff(sig[0], sig[k]);
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        if (best > 0 && bestD <= Math.max(2.5, motion * 0.6)) {
            List<Bitmap> f2 = new ArrayList<>(frames.subList(0, best));
            List<Integer> d2 = new ArrayList<>(delays.subList(0, best));
            for (int i = best; i < n; i++) {
                frames.get(i).recycle();
            }
            r.frames = f2;
            r.delays = d2;
        }
        return r;
    }
}
