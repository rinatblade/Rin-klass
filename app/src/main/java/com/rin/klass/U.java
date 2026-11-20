package com.rin.klass;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.widget.Toast;

import com.google.android.material.color.MaterialColors;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Небольшие общие помощники. */
public final class U {
    private U() {
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static int color(Context c, int attr, int fallback) {
        return MaterialColors.getColor(c, attr, fallback);
    }

    public static int primary(Context c) {
        return color(c, com.google.android.material.R.attr.colorPrimary, 0xFF6C4CF1);
    }

    public static int onSurface(Context c) {
        return color(c, com.google.android.material.R.attr.colorOnSurface, 0xFF222222);
    }

    public static int variant(Context c) {
        return color(c, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666);
    }

    public static int onPrimaryContainer(Context c) {
        return color(c, com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF222222);
    }

    public static int outlineVariant(Context c) {
        return color(c, com.google.android.material.R.attr.colorOutlineVariant, 0xFFCCCCCC);
    }

    public static int onPrimary(Context c) {
        return color(c, com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF);
    }

    public static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    public static void toastLong(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }

    /** Показывает диалог и сразу применяет к нему выбранный шрифт. */
    public static void show(Activity a, Dialog d) {
        d.show();
        if (d.getWindow() != null) {
            UiFont.apply(a, d.getWindow().getDecorView());
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    public static byte[] readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    /** Атомарная запись: сначала во временный файл, потом переименование. */
    public static void writeBytes(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(data);
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (!tmp.renameTo(f)) {
                throw new IOException("Не удалось сохранить файл " + f.getName());
            }
        }
    }

    public static String sha1(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha1(String s) {
        return sha1(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
