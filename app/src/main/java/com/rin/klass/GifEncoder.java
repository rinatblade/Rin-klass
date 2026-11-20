package com.rin.klass;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Кодировщик GIF на чистой Java: своя палитра (медианное сечение) для каждого кадра,
 * поддержка прозрачности, зацикливание. Без зависимостей от Android.
 */
public final class GifEncoder {
    private final OutputStream out;
    private final int width;
    private final int height;
    private boolean headerWritten;

    public GifEncoder(OutputStream out, int width, int height) {
        this.out = out;
        this.width = width;
        this.height = height;
    }

    private void u16(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private void header() throws IOException {
        out.write(new byte[]{'G', 'I', 'F', '8', '9', 'a'});
        u16(width);
        u16(height);
        out.write(0x70); // без глобальной палитры
        out.write(0);
        out.write(0);
        // бесконечное повторение
        out.write(new byte[]{0x21, (byte) 0xFF, 0x0B, 'N', 'E', 'T', 'S', 'C', 'A', 'P', 'E', '2', '.', '0',
                0x03, 0x01, 0x00, 0x00, 0x00});
        headerWritten = true;
    }

    /** Добавляет кадр: argb размером width*height (не премультиплицированный). */
    public void addFrame(int[] argb, int delayMs) throws IOException {
        if (!headerWritten) {
            header();
        }
        if (argb.length < width * height) {
            throw new IOException("Кадр меньше холста");
        }
        Quant q = quantize(argb, width * height);
        byte[] idx = new byte[width * height];
        for (int i = 0; i < idx.length; i++) {
            int p = argb[i];
            if ((p >>> 24) < 128) {
                idx[i] = 0;
            } else {
                int bin = ((p >> 19) & 31) << 10 | ((p >> 11) & 31) << 5 | ((p >> 3) & 31);
                idx[i] = (byte) (q.map[bin] + q.offset);
            }
        }

        int cs = Math.max(2, Math.round(delayMs / 10f));
        out.write(0x21);
        out.write(0xF9);
        out.write(4);
        out.write(((q.hasTrans ? 2 : 1) << 2) | (q.hasTrans ? 1 : 0));
        u16(cs);
        out.write(0); // прозрачный цвет - индекс 0
        out.write(0);

        out.write(0x2C);
        u16(0);
        u16(0);
        u16(width);
        u16(height);
        out.write(0x87); // локальная палитра на 256 цветов
        out.write(q.palette);
        out.write(8);
        new Lzw(out).encode(idx);
        out.write(0);
    }

    public void finish() throws IOException {
        if (!headerWritten) {
            header();
        }
        out.write(0x3B);
        out.flush();
    }

    // ------------------------------------------------------------------ палитра

    private static final class Quant {
        final byte[] palette = new byte[768];
        final int[] map = new int[32768];
        boolean hasTrans;
        int offset;
    }

    private static Quant quantize(int[] px, int n) {
        int[] count = new int[32768];
        int[] sr = new int[32768];
        int[] sg = new int[32768];
        int[] sb = new int[32768];
        boolean trans = false;
        for (int i = 0; i < n; i++) {
            int p = px[i];
            if ((p >>> 24) < 128) {
                trans = true;
                continue;
            }
            int r = (p >> 16) & 255;
            int g = (p >> 8) & 255;
            int b = p & 255;
            int bin = (r >> 3) << 10 | (g >> 3) << 5 | (b >> 3);
            count[bin]++;
            sr[bin] += r;
            sg[bin] += g;
            sb[bin] += b;
        }
        Quant q = new Quant();
        q.hasTrans = trans;
        q.offset = trans ? 1 : 0;
        int maxColors = trans ? 255 : 256;

        int m = 0;
        for (int i = 0; i < 32768; i++) {
            if (count[i] > 0) {
                m++;
            }
        }
        int[] bins = new int[m];
        m = 0;
        for (int i = 0; i < 32768; i++) {
            if (count[i] > 0) {
                bins[m++] = i;
            }
        }

        // Ящики: [start, end) в массиве bins.
        int[] bs = new int[256];
        int[] be = new int[256];
        int boxes = 0;
        if (m > 0) {
            bs[0] = 0;
            be[0] = m;
            boxes = 1;
        }
        while (boxes < maxColors) {
            int best = -1;
            long bestPop = 0;
            for (int i = 0; i < boxes; i++) {
                if (be[i] - bs[i] < 2) {
                    continue;
                }
                long pop = 0;
                for (int k = bs[i]; k < be[i]; k++) {
                    pop += count[bins[k]];
                }
                if (pop > bestPop) {
                    bestPop = pop;
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            int s = bs[best];
            int e = be[best];
            int minR = 31, maxR = 0, minG = 31, maxG = 0, minB = 31, maxB = 0;
            for (int k = s; k < e; k++) {
                int bin = bins[k];
                int r = bin >> 10;
                int g = (bin >> 5) & 31;
                int b = bin & 31;
                minR = Math.min(minR, r);
                maxR = Math.max(maxR, r);
                minG = Math.min(minG, g);
                maxG = Math.max(maxG, g);
                minB = Math.min(minB, b);
                maxB = Math.max(maxB, b);
            }
            int rr = maxR - minR;
            int gg = maxG - minG;
            int bb = maxB - minB;
            int shift = (rr >= gg && rr >= bb) ? 10 : (gg >= bb ? 5 : 0);
            int[] keys = new int[e - s];
            for (int k = s; k < e; k++) {
                int bin = bins[k];
                keys[k - s] = (((bin >> shift) & 31) << 15) | bin;
            }
            Arrays.sort(keys);
            for (int k = s; k < e; k++) {
                bins[k] = keys[k - s] & 0x7FFF;
            }
            long half = bestPop / 2;
            long acc = 0;
            int cut = s + 1;
            for (int k = s; k < e - 1; k++) {
                acc += count[bins[k]];
                cut = k + 1;
                if (acc >= half) {
                    break;
                }
            }
            bs[boxes] = cut;
            be[boxes] = e;
            be[best] = cut;
            boxes++;
        }

        for (int i = 0; i < boxes; i++) {
            long c = 0;
            long r = 0;
            long g = 0;
            long b = 0;
            for (int k = bs[i]; k < be[i]; k++) {
                int bin = bins[k];
                c += count[bin];
                r += sr[bin];
                g += sg[bin];
                b += sb[bin];
            }
            int pi = (i + q.offset) * 3;
            if (c > 0) {
                q.palette[pi] = (byte) (r / c);
                q.palette[pi + 1] = (byte) (g / c);
                q.palette[pi + 2] = (byte) (b / c);
            }
            for (int k = bs[i]; k < be[i]; k++) {
                q.map[bins[k]] = i;
            }
        }
        return q;
    }

    // ------------------------------------------------------------------ LZW

    private static final class Lzw {
        private static final int HSIZE = 8192;
        private final OutputStream out;
        private final byte[] block = new byte[255];
        private int blockLen;
        private int curAccum;
        private int curBits;
        private int nBits;
        private int maxCode;
        private int freeEnt;
        private boolean clearFlag;
        private final int initBits = 9;
        private final int clearCode = 256;
        private final int eofCode = 257;
        private final int[] hkey = new int[HSIZE];
        private final int[] hval = new int[HSIZE];

        Lzw(OutputStream out) {
            this.out = out;
        }

        void encode(byte[] pix) throws IOException {
            nBits = initBits;
            maxCode = (1 << nBits) - 1;
            freeEnt = clearCode + 2;
            clearFlag = false;
            Arrays.fill(hkey, -1);
            output(clearCode);
            int ent = pix[0] & 0xFF;
            for (int i = 1; i < pix.length; i++) {
                int c = pix[i] & 0xFF;
                int key = (c << 12) | ent;
                int h = (key * 0x9E3779B1) >>> 19;
                boolean found = false;
                while (hkey[h] != -1) {
                    if (hkey[h] == key) {
                        ent = hval[h];
                        found = true;
                        break;
                    }
                    h = (h + 1) & (HSIZE - 1);
                }
                if (found) {
                    continue;
                }
                output(ent);
                ent = c;
                if (freeEnt < 4096) {
                    hkey[h] = key;
                    hval[h] = freeEnt++;
                } else {
                    Arrays.fill(hkey, -1);
                    freeEnt = clearCode + 2;
                    clearFlag = true;
                    output(clearCode);
                }
            }
            output(ent);
            output(eofCode);
            flushBlock();
        }

        private void output(int code) throws IOException {
            curAccum |= code << curBits;
            curBits += nBits;
            while (curBits >= 8) {
                byteOut(curAccum & 0xFF);
                curAccum >>>= 8;
                curBits -= 8;
            }
            if (freeEnt > maxCode || clearFlag) {
                if (clearFlag) {
                    nBits = initBits;
                    maxCode = (1 << nBits) - 1;
                    clearFlag = false;
                } else {
                    nBits++;
                    maxCode = nBits == 12 ? 4096 : (1 << nBits) - 1;
                }
            }
            if (code == eofCode) {
                while (curBits > 0) {
                    byteOut(curAccum & 0xFF);
                    curAccum >>>= 8;
                    curBits -= 8;
                }
            }
        }

        private void byteOut(int b) throws IOException {
            block[blockLen++] = (byte) b;
            if (blockLen >= 254) {
                flushBlock();
            }
        }

        private void flushBlock() throws IOException {
            if (blockLen > 0) {
                out.write(blockLen);
                out.write(block, 0, blockLen);
                blockLen = 0;
            }
        }
    }
}
