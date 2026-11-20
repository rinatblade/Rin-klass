package com.rin.klass;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/** Сохранение GIF в галерею телефона: альбом «Rin-klass» (Pictures/Rin-klass). */
final class Gallery {
    private Gallery() {
    }

    static final String FOLDER = "Rin-klass";

    /** До Android 10 для записи в общую папку нужно разрешение. */
    static boolean needsPermission(Context c) {
        return Build.VERSION.SDK_INT < 29
                && ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
    }

    static boolean exported(Context c, String id) {
        return Prefs.raw(c).getStringSet("exported", new HashSet<String>()).contains(id);
    }

    static void markExported(Context c, String id) {
        Set<String> s = new HashSet<>(Prefs.raw(c).getStringSet("exported", new HashSet<String>()));
        s.add(id);
        Prefs.raw(c).edit().putStringSet("exported", s).apply();
    }

    static void save(Context c, File gif) throws IOException {
        String name = "klass_" + System.currentTimeMillis() + ".gif";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/gif");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER);
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                throw new IOException("Галерея не приняла файл");
            }
            try (OutputStream out = r.openOutputStream(uri); InputStream in = new FileInputStream(gif)) {
                if (out == null) {
                    throw new IOException("Галерея не приняла файл");
                }
                copy(in, out);
            } catch (IOException e) {
                r.delete(uri, null, null);
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            r.update(uri, done, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("Не удалось создать папку Pictures/" + FOLDER);
            }
            File dst = new File(dir, name);
            try (OutputStream out = new FileOutputStream(dst); InputStream in = new FileInputStream(gif)) {
                copy(in, out);
            }
            MediaScannerConnection.scanFile(c, new String[]{dst.getPath()}, new String[]{"image/gif"}, null);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }
}
