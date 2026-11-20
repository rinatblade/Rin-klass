package com.rin.klass;

import java.util.ArrayList;
import java.util.List;

/** Набор стикеров. */
public class Pack {
    /** Ограничения WhatsApp: от 3 до 30 стикеров в наборе. */
    public static final int MIN = 3;
    public static final int MAX = 30;

    public static final String TRAY_FILE = "tray.png";

    public String id;
    public String name;
    public String publisher;
    /** Увеличивается при каждом изменении - WhatsApp по нему понимает, что кэш устарел. */
    public int version = 1;
    public final List<Item> stickers = new ArrayList<>();

    public static class Item {
        public String file;
        public String emoji;

        public Item(String file, String emoji) {
            this.file = file;
            this.emoji = emoji;
        }
    }
}
