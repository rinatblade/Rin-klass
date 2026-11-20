package com.rin.klass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.media.MediaMetadataRetriever;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/** Превращает любую анимацию (GIF, анимированный WebP, APNG, короткое видео) в обычный GIF. */
final class Converter {
    private Converter() {
    }

    private static final int MAX_FRAMES = 300;
    private static final int MAX_SIDE = 640;

    static byte[] toGif(Context c, byte[] data, int kind) throws IOException {
        switch (kind) {
            case Media.GIF:
                return data;
            case Media.WEBP:
                return webpToGif(data);
            case Media.PNG:
                if (Media.isApng(data)) {
                    return apngToGif(data);
                }
                throw new IOException("Это обычная картинка, не анимация");
            case Media.VIDEO:
                return videoToGif(c, data);
            default:
                throw new IOException("Этот формат не поддерживается");
        }
    }

    /** Задержка кадра: как в браузерах, слишком малые значения считаются 100 мс. */
    static int fixDelay(int ms) {
        return ms <= 10 ? 100 : ms;
    }

    // ------------------------------------------------------------------ вывод в GIF

    private static final class GifOut {
        final int maxSide;
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GifEncoder enc;
        int w;
        int h;
        int[] px;

        GifOut(int maxSide) {
            this.maxSide = maxSide;
        }

        void add(Bitmap src, int delay) throws IOException {
            float s = Math.min(1f, (float) maxSide / Math.max(src.getWidth(), src.getHeight()));
            Bitmap b = src;
            if (s < 1f) {
                b = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * s)),
                        Math.max(1, Math.round(src.getHeight() * s)), true);
            }
            if (enc == null) {
                w = b.getWidth();
                h = b.getHeight();
                px = new int[w * h];
                enc = new GifEncoder(bos, w, h);
            } else if (b.getWidth() != w || b.getHeight() != h) {
                Bitmap f = Bitmap.createScaledBitmap(b, w, h, true);
                if (b != src) {
                    b.recycle();
                }
                b = f;
            }
            b.getPixels(px, 0, w, 0, 0, w, h);
            enc.addFrame(px, delay);
            if (b != src) {
                b.recycle();
            }
        }

        byte[] finish() throws IOException {
            if (enc == null) {
                throw new IOException("В анимации нет кадров");
            }
            enc.finish();
            return bos.toByteArray();
        }
    }

    private static Paint clearPaint() {
        Paint p = new Paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        return p;
    }

    /** Готовые кадры (например, запись экрана) в GIF. */
    static byte[] framesToGif(List<Bitmap> frames, List<Integer> delays) throws IOException {
        GifOut out = new GifOut(480);
        for (int i = 0; i < frames.size(); i++) {
            out.add(frames.get(i), Math.max(20, delays.get(i)));
        }
        return out.finish();
    }

    // ------------------------------------------------------------------ WebP

    private static byte[] webpToGif(byte[] data) throws IOException {
        WebpAnim.Info info = WebpAnim.parse(data);
        int w = info.canvasWidth;
        int h = info.canvasHeight;
        if (w <= 0 || h <= 0 || (long) w * h > 16_000_000L) {
            throw new IOException("Слишком большая анимация");
        }
        Bitmap canvas = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(canvas);
        Paint over = new Paint(Paint.FILTER_BITMAP_FLAG);
        Paint clear = clearPaint();
        GifOut out = new GifOut(MAX_SIDE);
        WebpAnim.Frame prev = null;
        int count = 0;
        try {
            for (WebpAnim.Frame f : info.frames) {
                if (count++ >= MAX_FRAMES) {
                    break;
                }
                if (prev != null && prev.disposeToBackground) {
                    cv.drawRect(prev.x, prev.y, prev.x + prev.width, prev.y + prev.height, clear);
                }
                Bitmap fb = BitmapFactory.decodeByteArray(f.standalone, 0, f.standalone.length);
                if (fb == null) {
                    throw new IOException("Не удалось прочитать кадр WebP");
                }
                if (f.noBlend) {
                    cv.drawRect(f.x, f.y, f.x + f.width, f.y + f.height, clear);
                }
                cv.drawBitmap(fb, f.x, f.y, over);
                fb.recycle();
                out.add(canvas, fixDelay(f.durationMs));
                prev = f;
            }
            return out.finish();
        } finally {
            canvas.recycle();
        }
    }

    // ------------------------------------------------------------------ APNG

    private static final class ApngFrame {
        int w;
        int h;
        int x;
        int y;
        int delayMs;
        int dispose;
        int blend;
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
    }

    private static int be32(byte[] d, int p) {
        return ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    private static int be16(byte[] d, int p) {
        return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
    }

    private static void putBe32(ByteArrayOutputStream o, int v) {
        o.write((v >> 24) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void chunk(ByteArrayOutputStream o, String type, byte[] body) {
        putBe32(o, body.length);
        byte[] t = new byte[]{(byte) type.charAt(0), (byte) type.charAt(1), (byte) type.charAt(2), (byte) type.charAt(3)};
        o.write(t, 0, 4);
        o.write(body, 0, body.length);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(body);
        putBe32(o, (int) crc.getValue());
    }

    private static byte[] framePng(ApngFrame f, byte[] ihdr, byte[] extra) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0, 8);
        byte[] h = ihdr.clone();
        h[0] = (byte) (f.w >> 24);
        h[1] = (byte) (f.w >> 16);
        h[2] = (byte) (f.w >> 8);
        h[3] = (byte) f.w;
        h[4] = (byte) (f.h >> 24);
        h[5] = (byte) (f.h >> 16);
        h[6] = (byte) (f.h >> 8);
        h[7] = (byte) f.h;
        chunk(o, "IHDR", h);
        o.write(extra, 0, extra.length);
        chunk(o, "IDAT", f.data.toByteArray());
        chunk(o, "IEND", new byte[0]);
        return o.toByteArray();
    }

    private static byte[] apngToGif(byte[] d) throws IOException {
        byte[] ihdr = null;
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        List<ApngFrame> frames = new ArrayList<>();
        ApngFrame cur = null;
        ApngFrame idatOwner = null;
        boolean idatSeen = false;
        int p = 8;
        while (p + 12 <= d.length) {
            int len = be32(d, p);
            int body = p + 8;
            if (len < 0 || body + len + 4 > d.length) {
                break;
            }
            String type = new String(d, p + 4, 4, "ISO-8859-1");
            if (type.equals("IHDR") && len == 13) {
                ihdr = new byte[13];
                System.arraycopy(d, body, ihdr, 0, 13);
            } else if (type.equals("PLTE") || type.equals("tRNS")) {
                extra.write(d, p, 12 + len);
            } else if (type.equals("fcTL") && len >= 26) {
                cur = new ApngFrame();
                cur.w = be32(d, body + 4);
                cur.h = be32(d, body + 8);
                cur.x = be32(d, body + 12);
                cur.y = be32(d, body + 16);
                int num = be16(d, body + 20);
                int den = be16(d, body + 22);
                cur.delayMs = fixDelay(Math.round(num * 1000f / (den == 0 ? 100 : den)));
                cur.dispose = d[body + 24] & 0xFF;
                cur.blend = d[body + 25] & 0xFF;
                frames.add(cur);
            } else if (type.equals("IDAT")) {
                if (!idatSeen) {
                    idatSeen = true;
                    idatOwner = cur;
                }
                if (idatOwner != null) {
                    idatOwner.data.write(d, body, len);
                }
            } else if (type.equals("fdAT") && len > 4) {
                if (cur != null) {
                    cur.data.write(d, body + 4, len - 4);
                }
            } else if (type.equals("IEND")) {
                break;
            }
            p = body + len + 4;
        }
        if (ihdr == null || frames.isEmpty()) {
            throw new IOException("Не удалось прочитать APNG");
        }
        int w = be32(ihdr, 0);
        int h = be32(ihdr, 4);
        if (w <= 0 || h <= 0 || (long) w * h > 16_000_000L) {
            throw new IOException("Слишком большая анимация");
        }
        byte[] ex = extra.toByteArray();
        Bitmap canvas = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(canvas);
        Paint over = new Paint(Paint.FILTER_BITMAP_FLAG);
        Paint clear = clearPaint();
        GifOut out = new GifOut(MAX_SIDE);
        int count = 0;
        try {
            for (ApngFrame f : frames) {
                if (count++ >= MAX_FRAMES) {
                    break;
                }
                byte[] png = framePng(f, ihdr, ex);
                Bitmap fb = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (fb == null) {
                    throw new IOException("Не удалось прочитать кадр APNG");
                }
                Bitmap backup = null;
                if (f.dispose == 2) {
                    backup = canvas.copy(Bitmap.Config.ARGB_8888, false);
                }
                if (f.blend == 0) {
                    cv.drawRect(f.x, f.y, f.x + f.w, f.y + f.h, clear);
                }
                cv.drawBitmap(fb, f.x, f.y, over);
                fb.recycle();
                out.add(canvas, f.delayMs);
                if (f.dispose == 1) {
                    cv.drawRect(f.x, f.y, f.x + f.w, f.y + f.h, clear);
                } else if (f.dispose == 2 && backup != null) {
                    cv.drawRect(0, 0, w, h, clear);
                    cv.drawBitmap(backup, 0, 0, null);
                }
                if (backup != null) {
                    backup.recycle();
                }
            }
            return out.finish();
        } finally {
            canvas.recycle();
        }
    }

    // ------------------------------------------------------------------ видео

    private static byte[] videoToGif(Context c, byte[] data) throws IOException {
        File tmp = new File(c.getCacheDir(), "video_" + System.nanoTime() + ".bin");
        U.writeBytes(tmp, data);
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(tmp.getPath());
            long dur = 0;
            String ds = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (ds != null) {
                try {
                    dur = Long.parseLong(ds);
                } catch (NumberFormatException ignored) {
                    dur = 0;
                }
            }
            if (dur <= 0) {
                dur = 3000;
            }
            long maxMs = Math.min(dur, 8000);
            int n = (int) Math.max(1, Math.min(96, maxMs / 80));
            GifOut out = new GifOut(480);
            int added = 0;
            for (int i = 0; i < n; i++) {
                long t = (long) i * maxMs / n * 1000L;
                Bitmap b = mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST);
                if (b == null) {
                    continue;
                }
                out.add(b, 80);
                b.recycle();
                added++;
            }
            if (added == 0) {
                throw new IOException("Не удалось прочитать видео");
            }
            return out.finish();
        } catch (RuntimeException e) {
            throw new IOException("Не удалось прочитать видео");
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
                // уже освобождён
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
