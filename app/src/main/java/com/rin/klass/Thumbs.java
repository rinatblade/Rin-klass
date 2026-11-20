package com.rin.klass;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.ImageDecoder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.rin.klass.WebpAnim.Frame;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Миниатюры (первый кадр) для сеток и показ живой анимации в просмотре. */
final class Thumbs {
    private Thumbs() {
    }

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(28 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Первый кадр GIF в уменьшенном виде. */
    static Bitmap gifFirstFrame(File f, int maxSide) throws IOException {
        byte[] d = U.readFile(f);
        final Bitmap[] holder = new Bitmap[1];
        GifDecoder.decode(d, new GifDecoder.Sink() {
            @Override
            public boolean frame(int[] argb, int w, int h, int delay) {
                holder[0] = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
                return false;
            }
        });
        Bitmap b = holder[0];
        if (b == null) {
            throw new IOException("Пустой GIF");
        }
        return scaleDown(b, maxSide);
    }

    /** Первый кадр анимированного WebP-стикера. */
    static Bitmap stickerFirstFrame(File f) {
        try {
            byte[] d = U.readFile(f);
            WebpAnim.Info info = WebpAnim.parse(d);
            Frame fr = info.frames.get(0);
            Bitmap part = BitmapFactory.decodeByteArray(fr.standalone, 0, fr.standalone.length);
            if (part == null) {
                return null;
            }
            if (part.getWidth() == info.canvasWidth && part.getHeight() == info.canvasHeight) {
                return part;
            }
            Bitmap full = Bitmap.createBitmap(info.canvasWidth, info.canvasHeight, Bitmap.Config.ARGB_8888);
            new android.graphics.Canvas(full).drawBitmap(part, fr.x, fr.y, null);
            part.recycle();
            return full;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Bitmap scaleDown(Bitmap b, int maxSide) {
        int m = Math.max(b.getWidth(), b.getHeight());
        if (m <= maxSide) {
            return b;
        }
        float s = (float) maxSide / m;
        Bitmap r = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)),
                Math.max(1, Math.round(b.getHeight() * s)), true);
        b.recycle();
        return r;
    }

    /** Миниатюра в ImageView: в фоне, с кэшем. */
    static void load(final ImageView iv, final File f, final boolean sticker) {
        final String key = f.getPath() + "|" + f.lastModified();
        iv.setTag(key);
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        iv.setImageDrawable(null);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap b;
                try {
                    b = sticker ? stickerFirstFrame(f) : gifFirstFrame(f, 300);
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    b = null;
                }
                if (b == null) {
                    return;
                }
                final Bitmap fb = b;
                CACHE.put(key, fb);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (key.equals(iv.getTag())) {
                            iv.setImageBitmap(fb);
                        }
                    }
                });
            }
        });
    }

    /** Живая анимация (Android 9+); на старых версиях - просто первый кадр. */
    static void showAnimated(final ImageView iv, final File f, final boolean sticker) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                Drawable d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f));
                iv.setImageDrawable(d);
                if (d instanceof AnimatedImageDrawable) {
                    AnimatedImageDrawable a = (AnimatedImageDrawable) d;
                    a.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                    a.start();
                }
                return;
            } catch (IOException | RuntimeException e) {
                // покажем первый кадр
            }
        }
        load(iv, f, sticker);
    }
}
