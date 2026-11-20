package com.rin.klass;

import java.io.IOException;

/**
 * Декодер GIF на чистой Java (без Android): отдаёт полные кадры в ARGB.
 * Кадры отдаются по одному через {@link Sink}, чтобы не держать в памяти весь ролик.
 */
public final class GifDecoder {
    private GifDecoder() {
    }

    /** Получатель кадров. Массив argb переиспользуется - его нужно обработать сразу. */
    public interface Sink {
        /** @return false, чтобы остановить разбор. */
        boolean frame(int[] argb, int width, int height, int delayMs) throws IOException;
    }

    public static final class Info {
        public int width;
        public int height;
        public int frames;
    }

    private static final int MAX_SIDE = 4000;
    private static final int MAX_PIXELS = 16_000_000;

    private static final class R {
        final byte[] d;
        int p;

        R(byte[] d) {
            this.d = d;
        }

        int u8() throws IOException {
            if (p >= d.length) {
                throw new IOException("Файл GIF оборван");
            }
            return d[p++] & 0xFF;
        }

        int u16() throws IOException {
            int a = u8();
            return a | (u8() << 8);
        }

        void skip(int n) throws IOException {
            if (n < 0 || p + n > d.length) {
                throw new IOException("Файл GIF оборван");
            }
            p += n;
        }

        void skipSubBlocks() throws IOException {
            int n;
            while ((n = u8()) != 0) {
                skip(n);
            }
        }

        byte[] subBlocks() throws IOException {
            int start = p;
            int total = 0;
            int n;
            while ((n = u8()) != 0) {
                skip(n);
                total += n;
            }
            byte[] out = new byte[total];
            int q = start;
            int o = 0;
            while (true) {
                int len = d[q++] & 0xFF;
                if (len == 0) {
                    break;
                }
                System.arraycopy(d, q, out, o, len);
                q += len;
                o += len;
            }
            return out;
        }
    }

    public static boolean isGif(byte[] d) {
        return d != null && d.length > 10 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8';
    }

    /** Быстро считает кадры и размер, не распаковывая пиксели. */
    public static Info info(byte[] data) throws IOException {
        if (!isGif(data)) {
            throw new IOException("Это не GIF");
        }
        R r = new R(data);
        r.p = 6;
        Info info = new Info();
        info.width = r.u16();
        info.height = r.u16();
        int packed = r.u8();
        r.u8();
        r.u8();
        if ((packed & 0x80) != 0) {
            r.skip(3 * (2 << (packed & 7)));
        }
        while (r.p < data.length) {
            int b = r.u8();
            if (b == 0x3B) {
                break;
            } else if (b == 0x21) {
                r.u8();
                r.skipSubBlocks();
            } else if (b == 0x2C) {
                r.skip(8);
                int pk = r.u8();
                if ((pk & 0x80) != 0) {
                    r.skip(3 * (2 << (pk & 7)));
                }
                r.u8();
                r.skipSubBlocks();
                info.frames++;
            } else {
                break;
            }
        }
        return info;
    }

    /** Разбирает GIF и по очереди отдаёт кадры. Возвращает число отданных кадров. */
    public static int decode(byte[] data, Sink sink) throws IOException {
        if (!isGif(data)) {
            throw new IOException("Это не GIF");
        }
        R r = new R(data);
        r.p = 6;
        int w = r.u16();
        int h = r.u16();
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS) {
            throw new IOException("Слишком большой GIF: " + w + "x" + h);
        }
        int packed = r.u8();
        r.u8();
        r.u8();
        int[] gct = null;
        if ((packed & 0x80) != 0) {
            gct = palette(r, 2 << (packed & 7));
        }

        int[] canvas = new int[w * h];
        int[] backup = null;
        int prevDispose = 0;
        int px = 0;
        int py = 0;
        int pw = 0;
        int ph = 0;

        int delay = 100;
        int dispose = 0;
        int trans = -1;
        int count = 0;

        while (r.p < data.length) {
            int b = r.u8();
            if (b == 0x3B) {
                break;
            }
            if (b == 0x21) {
                int label = r.u8();
                if (label == 0xF9) {
                    int sz = r.u8();
                    int start = r.p;
                    r.skip(sz);
                    if (sz >= 4) {
                        int pk = data[start] & 0xFF;
                        delay = ((data[start + 1] & 0xFF) | ((data[start + 2] & 0xFF) << 8)) * 10;
                        dispose = (pk >> 2) & 7;
                        trans = (pk & 1) != 0 ? (data[start + 3] & 0xFF) : -1;
                    }
                    r.skipSubBlocks();
                } else {
                    r.skipSubBlocks();
                }
                continue;
            }
            if (b != 0x2C) {
                break;
            }

            int ix = r.u16();
            int iy = r.u16();
            int iw = r.u16();
            int ih = r.u16();
            int pk = r.u8();
            boolean interlaced = (pk & 0x40) != 0;
            int[] pal = gct;
            if ((pk & 0x80) != 0) {
                pal = palette(r, 2 << (pk & 7));
            }
            int minCode = r.u8();
            byte[] lzw = r.subBlocks();
            if (minCode < 1 || minCode > 11) {
                throw new IOException("Неверные данные GIF");
            }
            if (pal == null) {
                pal = new int[256];
                for (int i = 0; i < 256; i++) {
                    pal[i] = 0xFF000000 | (i << 16) | (i << 8) | i;
                }
            }

            // Действие предыдущего кадра (как его нужно убрать перед рисованием этого).
            if (prevDispose == 2) {
                clear(canvas, w, h, px, py, pw, ph);
            } else if (prevDispose == 3 && backup != null) {
                System.arraycopy(backup, 0, canvas, 0, canvas.length);
            }
            if (dispose == 3) {
                if (backup == null) {
                    backup = new int[canvas.length];
                }
                System.arraycopy(canvas, 0, backup, 0, canvas.length);
            }

            byte[] idx = lzwDecode(lzw, minCode, iw * ih);
            int[] rows = rowOrder(ih, interlaced);
            for (int row = 0; row < ih; row++) {
                int y = iy + rows[row];
                if (y < 0 || y >= h) {
                    continue;
                }
                int src = row * iw;
                for (int col = 0; col < iw; col++) {
                    int x = ix + col;
                    if (x < 0 || x >= w) {
                        continue;
                    }
                    int ci = idx[src + col] & 0xFF;
                    if (ci == trans) {
                        continue;
                    }
                    canvas[y * w + x] = ci < pal.length ? pal[ci] : 0xFF000000;
                }
            }

            count++;
            boolean go = sink.frame(canvas, w, h, delay);

            prevDispose = dispose;
            px = ix;
            py = iy;
            pw = iw;
            ph = ih;
            dispose = 0;
            trans = -1;
            delay = 100;
            if (!go) {
                break;
            }
        }
        return count;
    }

    private static void clear(int[] canvas, int w, int h, int x0, int y0, int rw, int rh) {
        for (int y = Math.max(0, y0); y < Math.min(h, y0 + rh); y++) {
            for (int x = Math.max(0, x0); x < Math.min(w, x0 + rw); x++) {
                canvas[y * w + x] = 0;
            }
        }
    }

    private static int[] palette(R r, int n) throws IOException {
        int[] p = new int[n];
        for (int i = 0; i < n; i++) {
            int rr = r.u8();
            int gg = r.u8();
            int bb = r.u8();
            p[i] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
        }
        return p;
    }

    private static int[] rowOrder(int h, boolean interlaced) {
        int[] rows = new int[h];
        if (!interlaced) {
            for (int i = 0; i < h; i++) {
                rows[i] = i;
            }
            return rows;
        }
        int n = 0;
        int[][] passes = {{0, 8}, {4, 8}, {2, 4}, {1, 2}};
        for (int[] ps : passes) {
            for (int y = ps[0]; y < h; y += ps[1]) {
                rows[n++] = y;
            }
        }
        return rows;
    }

    private static byte[] lzwDecode(byte[] data, int minCode, int pixelCount) {
        byte[] out = new byte[Math.max(pixelCount, 0)];
        if (pixelCount <= 0) {
            return out;
        }
        final int clear = 1 << minCode;
        final int eoi = clear + 1;
        short[] prefix = new short[4096];
        byte[] suffix = new byte[4096];
        byte[] stack = new byte[4097];
        for (int i = 0; i < clear; i++) {
            suffix[i] = (byte) i;
        }
        int codeSize = minCode + 1;
        int codeMask = (1 << codeSize) - 1;
        int available = clear + 2;
        int oldCode = -1;
        int first = 0;
        int top = 0;
        int datum = 0;
        int bits = 0;
        int pos = 0;
        int n = 0;

        while (n < pixelCount) {
            if (top == 0) {
                while (bits < codeSize) {
                    if (pos >= data.length) {
                        return out;
                    }
                    datum |= (data[pos++] & 0xFF) << bits;
                    bits += 8;
                }
                int code = datum & codeMask;
                datum >>>= codeSize;
                bits -= codeSize;
                if (code > available || code == eoi) {
                    break;
                }
                if (code == clear) {
                    codeSize = minCode + 1;
                    codeMask = (1 << codeSize) - 1;
                    available = clear + 2;
                    oldCode = -1;
                    continue;
                }
                if (oldCode == -1) {
                    stack[top++] = suffix[code];
                    oldCode = code;
                    first = code;
                    continue;
                }
                int inCode = code;
                if (code >= available) {
                    stack[top++] = (byte) first;
                    code = oldCode;
                }
                while (code >= clear) {
                    stack[top++] = suffix[code];
                    code = prefix[code];
                }
                first = suffix[code] & 0xFF;
                stack[top++] = (byte) first;
                if (available < 4096) {
                    prefix[available] = (short) oldCode;
                    suffix[available] = (byte) first;
                    available++;
                    if ((available & codeMask) == 0 && available < 4096) {
                        codeSize++;
                        codeMask = (1 << codeSize) - 1;
                    }
                }
                oldCode = inCode;
            }
            top--;
            out[n++] = stack[top];
        }
        return out;
    }
}
