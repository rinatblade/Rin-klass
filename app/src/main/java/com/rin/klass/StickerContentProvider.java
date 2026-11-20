package com.rin.klass;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Провайдер, из которого WhatsApp забирает наборы стикеров.
 * Формат (колонки, адреса) задан WhatsApp:
 * metadata, metadata/{id}, stickers/{id}, stickers_asset/{id}/{файл}.
 */
public class StickerContentProvider extends ContentProvider {
    private static final int CODE_METADATA = 1;
    private static final int CODE_METADATA_SINGLE = 2;
    private static final int CODE_STICKERS = 3;
    private static final int CODE_ASSET = 4;

    private static final String[] PACK_COLUMNS = {
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_icon",
            "android_play_store_link",
            "ios_app_download_link",
            "sticker_pack_publisher_email",
            "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website",
            "image_data_version",
            "whatsapp_will_not_cache_stickers",
            "animated_sticker_pack"
    };

    private static final String[] STICKER_COLUMNS = {
            "sticker_file_name",
            "sticker_emoji",
            "sticker_accessibility_text"
    };

    private final UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);
    private PackStore store;
    private String authority;

    @Override
    public boolean onCreate() {
        authority = getContext().getPackageName() + ".stickercontentprovider";
        matcher.addURI(authority, "metadata", CODE_METADATA);
        matcher.addURI(authority, "metadata/*", CODE_METADATA_SINGLE);
        matcher.addURI(authority, "stickers/*", CODE_STICKERS);
        matcher.addURI(authority, "stickers_asset/*/*", CODE_ASSET);
        store = new PackStore(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        switch (matcher.match(uri)) {
            case CODE_METADATA:
                return packsCursor(store.loadAll());
            case CODE_METADATA_SINGLE: {
                Pack p = store.load(uri.getLastPathSegment());
                List<Pack> list = p == null ? new ArrayList<Pack>() : Collections.singletonList(p);
                return packsCursor(list);
            }
            case CODE_STICKERS:
                return stickersCursor(store.load(uri.getLastPathSegment()));
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
    }

    private Cursor packsCursor(List<Pack> packs) {
        MatrixCursor c = new MatrixCursor(PACK_COLUMNS);
        for (Pack p : packs) {
            c.addRow(new Object[]{
                    p.id,
                    p.name,
                    p.publisher,
                    Pack.TRAY_FILE,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    String.valueOf(p.version),
                    0,
                    1 // анимированный набор
            });
        }
        return c;
    }

    private Cursor stickersCursor(Pack p) {
        MatrixCursor c = new MatrixCursor(STICKER_COLUMNS);
        if (p != null) {
            for (Pack.Item it : p.stickers) {
                c.addRow(new Object[]{it.file, it.emoji, ""});
            }
        }
        return c;
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        if (matcher.match(uri) != CODE_ASSET) {
            return null;
        }
        List<String> seg = uri.getPathSegments();
        if (seg.size() < 3) {
            throw new FileNotFoundException("Bad URI: " + uri);
        }
        File f = store.file(seg.get(1), seg.get(2));
        if (f == null || !f.exists()) {
            throw new FileNotFoundException("No file for " + uri);
        }
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        return new AssetFileDescriptor(pfd, 0, f.length());
    }

    @Override
    public String getType(Uri uri) {
        switch (matcher.match(uri)) {
            case CODE_METADATA:
                return "vnd.android.cursor.dir/vnd." + authority + ".metadata";
            case CODE_METADATA_SINGLE:
                return "vnd.android.cursor.item/vnd." + authority + ".metadata";
            case CODE_STICKERS:
                return "vnd.android.cursor.dir/vnd." + authority + ".stickers";
            case CODE_ASSET: {
                String name = uri.getLastPathSegment();
                return name != null && name.endsWith(".png") ? "image/png" : "image/webp";
            }
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Not supported");
    }
}
