package com.rin.klass;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Работа с контейнером анимированного WebP на чистой Java (без Android):
 * {@link #build} склеивает отдельные статичные WebP-кадры в одну анимацию,
 * {@link #parse} разбирает анимацию обратно на кадры.
 */
public final class WebpAnim {
    private WebpAnim() {
    }

    public static final class Frame {
        public int x;
        public int y;
        public int width;
        public int height;
        public int durationMs;
        /** true - кадр рисуется поверх без смешивания (заменяет пиксели). */
        public boolean noBlend;
        /** true - после кадра область очищается до прозрачного. */
        public boolean disposeToBackground;
        /** Самостоятельный WebP-файл с этим кадром (можно отдать BitmapFactory). */
        public byte[] standalone;
    }

    public static final class Info {
        public int canvasWidth;
        public int canvasHeight;
        public int loopCount;
        public final List<Frame> frames = new ArrayList<>();
    }

    // ------------------------------------------------------------------ чтение

    private static int le16(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
    }

    private static int le24(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8) | ((d[p + 2] & 0xFF) << 16);
    }

    private static long le32(byte[] d, int p) {
        return (d[p] & 0xFFL) | ((d[p + 1] & 0xFFL) << 8) | ((d[p + 2] & 0xFFL) << 16) | ((d[p + 3] & 0xFFL) << 24);
    }

    private static boolean tag(byte[] d, int p, String t) {
        return d[p] == t.charAt(0) && d[p + 1] == t.charAt(1) && d[p + 2] == t.charAt(2) && d[p + 3] == t.charAt(3);
    }

    public static boolean isWebp(byte[] d) {
        return d != null && d.length >= 16 && tag(d, 0, "RIFF") && tag(d, 8, "WEBP");
    }

    /** true, если это анимированный WebP (флаг анимации в VP8X). */
    public static boolean isAnimated(byte[] d) {
        if (!isWebp(d) || d.length < 30) {
            return false;
        }
        return tag(d, 12, "VP8X") && (d[20] & 0x02) != 0;
    }

    /** Размер холста из VP8X, либо размер кадра из VP8/VP8L; {0,0}, если не удалось. */
    public static int[] dimensions(byte[] d) {
        if (!isWebp(d) || d.length < 30) {
            return new int[]{0, 0};
        }
        if (tag(d, 12, "VP8X")) {
            return new int[]{le24(d, 24) + 1, le24(d, 27) + 1};
        }
        if (tag(d, 12, "VP8 ") && d.length >= 30) {
            return new int[]{le16(d, 26) & 0x3FFF, le16(d, 28) & 0x3FFF};
        }
        if (tag(d, 12, "VP8L") && d.length >= 25) {
            long b = le32(d, 21);
            return new int[]{(int) (b & 0x3FFF) + 1, (int) ((b >> 14) & 0x3FFF) + 1};
        }
        return new int[]{0, 0};
    }

    public static Info parse(byte[] d) throws IOException {
        if (!isWebp(d)) {
            throw new IOException("Это не WebP");
        }
        Info info = new Info();
        int p = 12;
        int end = (int) Math.min(d.length, 8 + le32(d, 4));
        while (p + 8 <= end) {
            int size = (int) le32(d, p + 4);
            int body = p + 8;
            if (size < 0 || body + size > d.length) {
                throw new IOException("Файл WebP оборван");
            }
            if (tag(d, p, "VP8X")) {
                info.canvasWidth = le24(d, body + 4) + 1;
                info.canvasHeight = le24(d, body + 7) + 1;
            } else if (tag(d, p, "ANIM")) {
                info.loopCount = le16(d, body + 4);
            } else if (tag(d, p, "ANMF")) {
                if (size < 16) {
                    throw new IOException("Повреждённый кадр WebP");
                }
                Frame f = new Frame();
                f.x = le24(d, body) * 2;
                f.y = le24(d, body + 3) * 2;
                f.width = le24(d, body + 6) + 1;
                f.height = le24(d, body + 9) + 1;
                f.durationMs = le24(d, body + 12);
                int flags = d[body + 15] & 0xFF;
                f.noBlend = (flags & 2) != 0;
                f.disposeToBackground = (flags & 1) != 0;
                f.standalone = standalone(d, body + 16, body + size, f.width, f.height);
                info.frames.add(f);
            }
            p = body + size + (size & 1);
        }
        if (info.frames.isEmpty()) {
            throw new IOException("В WebP нет анимации");
        }
        if (info.canvasWidth == 0) {
            info.canvasWidth = info.frames.get(0).width;
            info.canvasHeight = info.frames.get(0).height;
        }
        return info;
    }

    /** Собирает самостоятельный WebP из подчанков кадра (ALPH/VP8/VP8L). */
    private static byte[] standalone(byte[] d, int from, int to, int w, int h) throws IOException {
        boolean alpha = false;
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        int p = from;
        while (p + 8 <= to) {
            int size = (int) le32(d, p + 4);
            int len = 8 + size + (size & 1);
            if (size < 0 || p + 8 + size > to) {
                throw new IOException("Повреждённый кадр WebP");
            }
            if (tag(d, p, "ALPH")) {
                alpha = true;
            } else if (tag(d, p, "VP8L") && size >= 5 && ((d[p + 8 + 4] >> 4) & 1) != 0) {
                alpha = true;
            }
            if (tag(d, p, "ALPH") || tag(d, p, "VP8 ") || tag(d, p, "VP8L")) {
                int avail = Math.min(len, to - p);
                chunks.write(d, p, avail);
                if (avail < len) {
                    chunks.write(0);
                }
            }
            p += len;
        }
        byte[] body = chunks.toByteArray();
        if (body.length == 0) {
            throw new IOException("В кадре WebP нет изображения");
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int riffSize = 4 + 18 + body.length;
        o.write(new byte[]{'R', 'I', 'F', 'F'});
        w32(o, riffSize);
        o.write(new byte[]{'W', 'E', 'B', 'P'});
        vp8x(o, alpha, false, w, h);
        o.write(body);
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ запись

    private static void w32(ByteArrayOutputStream o, long v) {
        o.write((int) (v & 0xFF));
        o.write((int) ((v >> 8) & 0xFF));
        o.write((int) ((v >> 16) & 0xFF));
        o.write((int) ((v >> 24) & 0xFF));
    }

    private static void w24(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
    }

    private static void vp8x(ByteArrayOutputStream o, boolean alpha, boolean animated, int w, int h) {
        o.write(new byte[]{'V', 'P', '8', 'X'}, 0, 4);
        w32(o, 10);
        int flags = (alpha ? 0x10 : 0) | (animated ? 0x02 : 0);
        o.write(flags);
        o.write(0);
        o.write(0);
        o.write(0);
        w24(o, w - 1);
        w24(o, h - 1);
    }

    /**
     * Склеивает кадры в анимацию.
     *
     * @param frames    полные WebP-файлы кадров (результат Bitmap.compress), все размера width x height
     * @param durations длительность каждого кадра в миллисекундах
     */
    public static byte[] build(int width, int height, List<byte[]> frames, List<Integer> durations)
            throws IOException {
        if (frames.isEmpty() || frames.size() != durations.size()) {
            throw new IOException("Нет кадров для анимации");
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        boolean anyAlpha = false;
        for (int i = 0; i < frames.size(); i++) {
            byte[] f = frames.get(i);
            if (!isWebp(f)) {
                throw new IOException("Кадр не в формате WebP");
            }
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            int p = 12;
            int end = (int) Math.min(f.length, 8 + le32(f, 4));
            while (p + 8 <= end) {
                int size = (int) le32(f, p + 4);
                if (size < 0 || p + 8 + size > f.length) {
                    throw new IOException("Кадр WebP оборван");
                }
                boolean copy = false;
                if (tag(f, p, "ALPH")) {
                    anyAlpha = true;
                    copy = true;
                } else if (tag(f, p, "VP8L")) {
                    if (size >= 5 && ((f[p + 8 + 4] >> 4) & 1) != 0) {
                        anyAlpha = true;
                    }
                    copy = true;
                } else if (tag(f, p, "VP8 ")) {
                    copy = true;
                }
                if (copy) {
                    payload.write(f, p, 8 + size);
                    if ((size & 1) != 0) {
                        payload.write(0);
                    }
                }
                p += 8 + size + (size & 1);
            }
            if (payload.size() == 0) {
                throw new IOException("В кадре нет изображения");
            }
            int dur = Math.max(1, Math.min(durations.get(i), 0xFFFFFF));
            ByteArrayOutputStream anmf = new ByteArrayOutputStream();
            w24(anmf, 0);
            w24(anmf, 0);
            w24(anmf, width - 1);
            w24(anmf, height - 1);
            w24(anmf, dur);
            // Не смешивать с предыдущим кадром (бит 1) и очищать холст после кадра (бит 0):
            // так прозрачные места не «просвечивают» прошлыми кадрами ни в одном декодере.
            anmf.write(0x03);
            anmf.write(payload.toByteArray(), 0, payload.size());
            body.write(new byte[]{'A', 'N', 'M', 'F'}, 0, 4);
            w32(body, anmf.size());
            body.write(anmf.toByteArray(), 0, anmf.size());
            if ((anmf.size() & 1) != 0) {
                body.write(0);
            }
        }

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        vp8x(head, anyAlpha, true, width, height);
        head.write(new byte[]{'A', 'N', 'I', 'M'}, 0, 4);
        w32(head, 6);
        w32(head, 0);   // фон: прозрачный
        head.write(0);  // бесконечный цикл
        head.write(0);
        o.write(new byte[]{'R', 'I', 'F', 'F'}, 0, 4);
        w32(o, 4 + head.size() + body.size());
        o.write(new byte[]{'W', 'E', 'B', 'P'}, 0, 4);
        o.write(head.toByteArray(), 0, head.size());
        o.write(body.toByteArray(), 0, body.size());
        return o.toByteArray();
    }
}
