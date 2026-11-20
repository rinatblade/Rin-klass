package com.rin.klass;

/** Определение формата файла по первым байтам (не по расширению и не по заголовкам сервера). */
public final class Media {
    private Media() {
    }

    public static final int OTHER = 0;
    public static final int GIF = 1;
    public static final int WEBP = 2;
    public static final int PNG = 3;
    public static final int JPEG = 4;
    public static final int VIDEO = 5;

    public static int kind(byte[] d) {
        if (d == null || d.length < 12) {
            return OTHER;
        }
        if (GifDecoder.isGif(d)) {
            return GIF;
        }
        if (WebpAnim.isWebp(d)) {
            return WEBP;
        }
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return PNG;
        }
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        if (d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            return VIDEO; // mp4 / mov / 3gp
        }
        if ((d[0] & 0xFF) == 0x1A && (d[1] & 0xFF) == 0x45 && (d[2] & 0xFF) == 0xDF && (d[3] & 0xFF) == 0xA3) {
            return VIDEO; // webm / mkv
        }
        return OTHER;
    }

    public static String label(int kind) {
        switch (kind) {
            case GIF:
                return "gif";
            case WEBP:
                return "webp";
            case PNG:
                return "png";
            case JPEG:
                return "jpeg";
            case VIDEO:
                return "video";
            default:
                return "other";
        }
    }

    /** PNG с анимацией (APNG): чанк acTL встречается до первого IDAT. */
    public static boolean isApng(byte[] d) {
        if (kind(d) != PNG) {
            return false;
        }
        int p = 8;
        while (p + 8 <= d.length) {
            int size = ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            if (size < 0) {
                return false;
            }
            if (d[p + 4] == 'a' && d[p + 5] == 'c' && d[p + 6] == 'T' && d[p + 7] == 'L') {
                return true;
            }
            if (d[p + 4] == 'I' && d[p + 5] == 'D' && d[p + 6] == 'A' && d[p + 7] == 'T') {
                return false;
            }
            p += 12 + size;
        }
        return false;
    }
}
