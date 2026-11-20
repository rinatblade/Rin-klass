package com.rin.klass;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Отправка набора в WhatsApp через официальный интерфейс стикеров. */
public final class WhatsAppHelper {
    public static final String ACTION_ENABLE_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK";
    public static final String PKG = "com.whatsapp";
    public static final String PKG_BUSINESS = "com.whatsapp.w4b";

    private WhatsAppHelper() {
    }

    public static String authority(Context c) {
        return c.getPackageName() + ".stickercontentprovider";
    }

    public static boolean isInstalled(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static Intent buildIntent(Context c, Pack pack, String whatsappPackage) {
        Intent i = new Intent();
        i.setAction(ACTION_ENABLE_PACK);
        i.setPackage(whatsappPackage);
        i.putExtra("sticker_pack_id", pack.id);
        i.putExtra("sticker_pack_authority", authority(c));
        i.putExtra("sticker_pack_name", pack.name);
        return i;
    }
}
