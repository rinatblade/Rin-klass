#!/usr/bin/env python3
"""Генерирует 20 цветовых тем: res/values/themes.xml и java/.../ThemeData.java.
Запуск вручную (результат лежит в репозитории): python3 tools/gen_themes.py"""
import os

# (id, название, основной цвет, второй цвет, тёмная?)
THEMES = [
    ('Violet', 'Фиолетовый', '6C4CF1', 'D6428C', False),
    ('Ocean', 'Океан', '0A7EA4', '00897B', False),
    ('Mint', 'Мята', '1A8F6E', '5C8D3A', False),
    ('Sunset', 'Закат', 'D9561A', 'C2306A', False),
    ('Rose', 'Роза', 'D83A74', '7F52C8', False),
    ('Lavender', 'Лаванда', '8559D6', '3F8FD2', False),
    ('Lemon', 'Лимон', '8A7A00', '2E7D32', False),
    ('Cherry', 'Вишня', 'B3203A', 'E0733A', False),
    ('Turquoise', 'Бирюза', '00838F', '5E35B1', False),
    ('Emerald', 'Изумруд', '2E7D32', '00838F', False),
    ('Raspberry', 'Малина', 'AD1457', '6A1B9A', False),
    ('Caramel', 'Карамель', '8D5524', 'D4851F', False),
    ('Sky', 'Небо', '1E6FD9', '00ACC1', False),
    ('Sakura', 'Сакура', 'C2557A', '7E57C2', False),
    ('Midnight', 'Полночь', 'B9A6FF', 'FFB0D2', True),
    ('Cosmos', 'Космос', '7FB2FF', 'C9A3FF', True),
    ('Neon', 'Неон', '39E6A0', 'FF5FA2', True),
    ('Coal', 'Уголь', 'E0E0E0', 'FFB74D', True),
    ('Bordeaux', 'Бордо', 'FF8FA3', 'FFCC80', True),
    ('Forest', 'Лес', '8FD18F', 'E6CF8A', True),
]


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def hx(c):
    return '%02X%02X%02X' % tuple(max(0, min(255, round(v))) for v in c)


def mix(a, b, t):
    """t - доля цвета b."""
    return tuple(a[i] * (1 - t) + b[i] * t for i in range(3))


WHITE = (255, 255, 255)
BLACK = (0, 0, 0)


def lum(c):
    def f(v):
        v /= 255
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2])


def on(c):
    return WHITE if lum(c) < 0.38 else (20, 20, 24)


def palette(p, s, dark):
    P, S = rgb(p), rgb(s)
    if not dark:
        return {
            'colorPrimary': P, 'colorOnPrimary': on(P),
            'colorPrimaryContainer': mix(P, WHITE, 0.80), 'colorOnPrimaryContainer': mix(P, BLACK, 0.72),
            'colorSecondary': S, 'colorOnSecondary': on(S),
            'colorSecondaryContainer': mix(S, WHITE, 0.80), 'colorOnSecondaryContainer': mix(S, BLACK, 0.72),
            'android:colorBackground': mix(P, WHITE, 0.95), 'colorOnBackground': mix(P, BLACK, 0.88),
            'colorSurface': mix(P, WHITE, 0.98), 'colorOnSurface': mix(P, BLACK, 0.88),
            'colorSurfaceVariant': mix(P, WHITE, 0.86), 'colorOnSurfaceVariant': mix(P, BLACK, 0.65),
            'colorOutline': mix(mix(P, BLACK, 0.65), WHITE, 0.45), 'colorOutlineVariant': mix(P, WHITE, 0.75),
            'colorSurfaceContainerLow': mix(P, WHITE, 0.965), 'colorSurfaceContainer': mix(P, WHITE, 0.92),
            'colorSurfaceContainerHigh': mix(P, WHITE, 0.89), 'colorSurfaceContainerHighest': mix(P, WHITE, 0.86),
        }
    return {
        'colorPrimary': P, 'colorOnPrimary': mix(P, BLACK, 0.82),
        'colorPrimaryContainer': mix(P, BLACK, 0.55), 'colorOnPrimaryContainer': mix(P, WHITE, 0.78),
        'colorSecondary': S, 'colorOnSecondary': mix(S, BLACK, 0.82),
        'colorSecondaryContainer': mix(S, BLACK, 0.55), 'colorOnSecondaryContainer': mix(S, WHITE, 0.78),
        'android:colorBackground': mix(P, BLACK, 0.93), 'colorOnBackground': mix(P, WHITE, 0.88),
        'colorSurface': mix(P, BLACK, 0.91), 'colorOnSurface': mix(P, WHITE, 0.88),
        'colorSurfaceVariant': mix(P, BLACK, 0.80), 'colorOnSurfaceVariant': mix(P, WHITE, 0.70),
        'colorOutline': mix(mix(P, WHITE, 0.55), (128, 128, 128), 0.5), 'colorOutlineVariant': mix(P, BLACK, 0.72),
        'colorSurfaceContainerLow': mix(P, BLACK, 0.90), 'colorSurfaceContainer': mix(P, BLACK, 0.87),
        'colorSurfaceContainerHigh': mix(P, BLACK, 0.83), 'colorSurfaceContainerHighest': mix(P, BLACK, 0.79),
    }


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    xml = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Создано tools/gen_themes.py - руками не править -->', '<resources>', '',
           '    <style name="Base.Theme.Klass.Light" parent="Theme.Material3.Light.NoActionBar">',
           '        <item name="android:windowLightStatusBar">true</item>',
           '        <item name="android:windowLightNavigationBar">true</item>',
           '    </style>', '',
           '    <style name="Base.Theme.Klass.Dark" parent="Theme.Material3.Dark.NoActionBar">',
           '        <item name="android:windowLightStatusBar">false</item>',
           '        <item name="android:windowLightNavigationBar">false</item>',
           '    </style>', '']
    names, prim, back, styles = [], [], [], []
    for tid, label, p, s, dark in THEMES:
        pal = palette(p, s, dark)
        parent = 'Base.Theme.Klass.Dark' if dark else 'Base.Theme.Klass.Light'
        xml.append('    <style name="Theme.Klass.%s" parent="%s">' % (tid, parent))
        for k, v in pal.items():
            xml.append('        <item name="%s">#%s</item>' % (k, hx(v)))
        bg = hx(pal['android:colorBackground'])
        xml.append('        <item name="android:statusBarColor">#%s</item>' % bg)
        xml.append('        <item name="android:navigationBarColor">#%s</item>' % hx(pal['colorSurfaceContainer']))
        xml.append('        <item name="android:windowBackground">#%s</item>' % bg)
        xml.append('    </style>')
        xml.append('')
        names.append(label)
        prim.append(p)
        back.append(bg)
        styles.append(tid)
    xml.append('</resources>')
    with open(os.path.join(root, 'app/src/main/res/values/themes.xml'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(xml) + '\n')

    j = ['package com.rin.klass;', '', '/** Создано tools/gen_themes.py - руками не править. */',
         'final class ThemeData {', '    private ThemeData() {', '    }', '']
    j.append('    static final String[] NAMES = {' + ', '.join('"%s"' % n for n in names) + '};')
    j.append('    static final int[] PRIMARY = {' + ', '.join('0xFF' + c for c in prim) + '};')
    j.append('    static final int[] BACK = {' + ', '.join('0xFF' + c for c in back) + '};')
    j.append('    static final int[] STYLES = {')
    for tid in styles:
        j.append('            R.style.Theme_Klass_%s,' % tid)
    j.append('    };')
    j.append('}')
    with open(os.path.join(root, 'app/src/main/java/com/rin/klass/ThemeData.java'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(j) + '\n')
    print('тем:', len(THEMES))


if __name__ == '__main__':
    main()
