package com.rin.klass;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Скачанные подарки внутри приложения: файлы GIF, имя - отпечаток содержимого (дубли не копятся). */
final class GiftStore {
    private GiftStore() {
    }

    static final class Gift {
        final File file;
        final String id;
        final long time;

        Gift(File file) {
            this.file = file;
            String n = file.getName();
            this.id = n.endsWith(".gif") ? n.substring(0, n.length() - 4) : n;
            this.time = file.lastModified();
        }
    }

    static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), "gifts");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static List<Gift> list(Context c) {
        List<Gift> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) {
            return out;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".gif")) {
                out.add(new Gift(f));
            }
        }
        return out;
    }

    /** Сохраняет GIF; isNew[0] = false, если такой подарок уже был. */
    static synchronized Gift add(Context c, byte[] gif, boolean[] isNew) throws IOException {
        String id = U.sha1(gif).substring(0, 16);
        File f = new File(dir(c), id + ".gif");
        if (f.exists()) {
            isNew[0] = false;
            return new Gift(f);
        }
        U.writeBytes(f, gif);
        isNew[0] = true;
        return new Gift(f);
    }

    static void delete(Gift g) {
        //noinspection ResultOfMethodCallIgnored
        g.file.delete();
    }

    /** Чистит кэш проверенных анимаций, чтобы он не рос бесконечно. */
    static void trimProbeCache(Context c) {
        File d = new File(c.getCacheDir(), "probe");
        File[] files = d.listFiles();
        if (files == null) {
            return;
        }
        long now = System.currentTimeMillis();
        List<File> keep = new ArrayList<>();
        for (File f : files) {
            if (now - f.lastModified() > 24L * 3600 * 1000) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } else {
                keep.add(f);
            }
        }
        if (keep.size() > 60) {
            Collections.sort(keep, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (int i = 0; i < keep.size() - 60; i++) {
                //noinspection ResultOfMethodCallIgnored
                keep.get(i).delete();
            }
        }
    }
}
