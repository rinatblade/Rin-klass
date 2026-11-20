package com.rin.klass;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Хранилище наборов: у каждого набора своя папка с pack.json, tray.png и анимированными стикерами .webp. */
public final class PackStore {
    private final File root;

    public PackStore(Context c) {
        root = new File(c.getApplicationContext().getFilesDir(), "packs");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
    }

    public File dir(String id) {
        return new File(root, id);
    }

    /** Файл внутри папки набора; null, если имя пытается выйти за её пределы. */
    public File file(String id, String name) {
        if (id == null || name == null) {
            return null;
        }
        File d = dir(id);
        File f = new File(d, name);
        try {
            if (!f.getCanonicalPath().startsWith(d.getCanonicalPath() + File.separator)) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return f;
    }

    public List<Pack> loadAll() {
        List<Pack> out = new ArrayList<>();
        File[] dirs = root.listFiles();
        if (dirs == null) {
            return out;
        }
        Arrays.sort(dirs);
        for (File d : dirs) {
            if (d.isDirectory()) {
                Pack p = load(d.getName());
                if (p != null) {
                    out.add(p);
                }
            }
        }
        Collections.reverse(out); // новые сверху
        return out;
    }

    public Pack load(String id) {
        if (id == null) {
            return null;
        }
        File f = file(id, "pack.json");
        if (f == null || !f.exists()) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(new String(U.readFile(f), "UTF-8"));
            Pack p = new Pack();
            p.id = o.getString("id");
            p.name = o.optString("name", "Подарки");
            p.publisher = o.optString("publisher", "Rin-klass");
            p.version = o.optInt("version", 1);
            JSONArray arr = o.optJSONArray("stickers");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.getJSONObject(i);
                    p.stickers.add(new Pack.Item(s.getString("file"), s.optString("emoji", "🎁")));
                }
            }
            return p;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    public synchronized void save(Pack p) throws IOException {
        try {
            JSONObject o = new JSONObject();
            o.put("id", p.id);
            o.put("name", p.name);
            o.put("publisher", p.publisher);
            o.put("version", p.version);
            JSONArray arr = new JSONArray();
            for (Pack.Item it : p.stickers) {
                JSONObject s = new JSONObject();
                s.put("file", it.file);
                s.put("emoji", it.emoji);
                arr.put(s);
            }
            o.put("stickers", arr);
            //noinspection ResultOfMethodCallIgnored
            dir(p.id).mkdirs();
            U.writeBytes(new File(dir(p.id), "pack.json"), o.toString().getBytes("UTF-8"));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    public Pack create(String name, String publisher) throws IOException {
        Pack p = new Pack();
        p.id = "rk_" + System.currentTimeMillis();
        p.name = name;
        p.publisher = publisher;
        save(p);
        return p;
    }

    public synchronized void delete(String id) {
        File d = dir(id);
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        d.delete();
    }

    /** Добавляет готовый анимированный WebP (512x512, до 500 КБ) в набор. */
    public synchronized Pack addSticker(String packId, byte[] animatedWebp, String emoji) throws IOException {
        Pack p = load(packId);
        if (p == null) {
            throw new IOException("Набор не найден");
        }
        if (p.stickers.size() >= Pack.MAX) {
            throw new IOException("В наборе не может быть больше " + Pack.MAX + " стикеров");
        }
        String name = "s_" + System.currentTimeMillis() + ".webp";
        //noinspection ResultOfMethodCallIgnored
        dir(packId).mkdirs();
        U.writeBytes(new File(dir(packId), name), animatedWebp);
        p.stickers.add(new Pack.Item(name, emoji));
        p.version++;
        writeTray(p);
        save(p);
        return p;
    }

    public synchronized Pack removeSticker(String packId, String fileName) throws IOException {
        Pack p = load(packId);
        if (p == null) {
            return null;
        }
        for (int i = 0; i < p.stickers.size(); i++) {
            if (p.stickers.get(i).file.equals(fileName)) {
                p.stickers.remove(i);
                break;
            }
        }
        File f = file(packId, fileName);
        if (f != null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        p.version++;
        writeTray(p);
        save(p);
        return p;
    }

    public synchronized void rename(String packId, String name, String publisher) throws IOException {
        Pack p = load(packId);
        if (p == null) {
            return;
        }
        p.name = name;
        p.publisher = publisher;
        p.version++;
        save(p);
    }

    /** Иконка набора 96x96 PNG (до 50 КБ) - первый кадр первого стикера. */
    private void writeTray(Pack p) throws IOException {
        File tray = new File(dir(p.id), Pack.TRAY_FILE);
        if (p.stickers.isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            tray.delete();
            return;
        }
        File first = file(p.id, p.stickers.get(0).file);
        Bitmap src = first == null ? null : Thumbs.stickerFirstFrame(first);
        if (src == null) {
            return;
        }
        Bitmap small = Bitmap.createScaledBitmap(src, 96, 96, true);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.PNG, 100, bos);
        U.writeBytes(tray, bos.toByteArray());
    }
}
