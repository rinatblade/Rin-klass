#!/usr/bin/env python3
"""Скачивает шрифты для оформления приложения (с кириллицей) из репозитория Google Fonts (лицензия OFL/Apache/UFL),
проверяет, что в каждом есть весь русский алфавит, и собирает assets/fonts/index.txt.

Запускается в GitHub Actions перед сборкой (на машине сборки есть интернет)."""
import json
import os
import sys
import urllib.request

from fontTools.ttLib import TTFont

OUT = 'app/src/main/assets/fonts'
MAX_DOWNLOADED = 60
MAX_BYTES = 700_000
MIN_TOTAL = 20

# (папка в google/fonts, название в приложении)
CANDIDATES = [
    # --- страшные / мрачные
    ('rubikwetpaint', 'Кровавые подтёки — ужастик'),
    ('rubikglitch', 'Глитч — сломанный экран'),
    ('rubikburned', 'Обгоревший — после пожара'),
    ('rubikbeastly', 'Зверь — когтистый'),
    ('rubikdirt', 'Грязь — подвал'),
    ('rubikdistressed', 'Потёртый — старый плакат'),
    ('rubikspraypaint', 'Граффити-спрей'),
    ('rubikmoonrocks', 'Лунные камни'),
    ('rubikbrokenfax', 'Сломанный факс'),
    ('rubikmaze', 'Лабиринт'),
    ('rubikmicrobe', 'Микроб'),
    ('rubikpuddles', 'Лужи'),
    ('rubikstorm', 'Шторм'),
    ('ruslandisplay', 'Руслан — готика и сказки'),
    ('ponomar', 'Пономарь — церковный'),
    ('stalinistone', 'Агитплакат — СССР'),
    ('seymourone', 'Сеймур — мультяшный триллер'),
    # --- смешные / мультяшные
    ('underdog', 'Комикс — разговорный пузырь'),
    ('pangolin', 'Детский рисунок'),
    ('marmelad', 'Мармелад'),
    ('rubikbubbles', 'Пузыри'),
    ('rubikdoodleshadow', 'Дудл с тенью'),
    ('rubikdoodletriangles', 'Дудл-треугольники'),
    ('rubikscribble', 'Каракули'),
    ('rubikmarkerhatch', 'Штриховка маркером'),
    ('rubikgemstones', 'Самоцветы'),
    ('oi', 'Ой — толстяк'),
    ('kellyslab', 'Келли — плакат'),
    ('russoone', 'Руссо — бодрый'),
    ('comfortaa', 'Круглый и добрый'),
    ('nunito', 'Нунито — мягкий'),
    ('neucha', 'Неуча — почерк'),
    ('caveat', 'Рукопись — быстрая'),
    ('caveatbrush', 'Рукопись — кисть'),
    ('badscript', 'Беглый почерк'),
    ('marckscript', 'Письмо пером'),
    ('kolkerbrush', 'Мазок кистью'),
    ('comforterbrush', 'Росчерк кистью'),
    ('pattaya', 'Паттайя — вывеска'),
    ('lobster', 'Лобстер — ретро-вывеска'),
    ('lobstertwo', 'Лобстер 2 — ретро'),
    ('pacifico', 'Пацифика — пляж'),
    ('amaticsc', 'Аматик — худые буквы'),
    ('greatvibes', 'Каллиграфия'),
    # --- кино / игры / фантастика
    ('bebasneue', 'Кино-титры'),
    ('anton', 'Афиша боевика'),
    ('oswald', 'Газетный заголовок'),
    ('yesevaone', 'Афиша мелодрамы'),
    ('pressstart2p', '8-бит — приставка'),
    ('pixelifysans', 'Пиксельная игра'),
    ('vt323', 'Терминал — хакер'),
    ('tektur', 'Робот — техно'),
    ('jura', 'Фантастика — Юра'),
    ('exo2', 'Космос — Экзо'),
    ('play', 'Игровой интерфейс'),
    ('unbounded', 'Безграничный — футуризм'),
    ('delagothicone', 'Аниме — жирный'),
    ('rubikmonoone', 'Моно-плакат'),
    ('rubikvinyl', 'Винил'),
    ('rubik80sfade', 'Диско 80-х'),
    ('rubikglitchpop', 'Глитч-поп'),
    ('rubikiso', 'Изометрия'),
    ('rubikmaps', 'Карта сокровищ'),
    ('rubiklines', 'Линии'),
    ('rubikpixels', 'Пиксели'),
    # --- классика / элегантные
    ('playfairdisplay', 'Глянцевый журнал'),
    ('philosopher', 'Философ'),
    ('forum', 'Форум — античность'),
    ('alice', 'Алиса — сказка'),
    ('oranienbaum', 'Ораниенбаум — афиша'),
    ('podkova', 'Подкова'),
    ('cormorantunicase', 'Корморант — изящный'),
    ('tenorsans', 'Тенор'),
    ('kurale', 'Курале'),
    ('arsenal', 'Арсенал'),
    ('alumnisans', 'Алюмни — спорт'),
    ('firasansextracondensed', 'Узкий — титры'),
    ('yanonekaffeesatz', 'Кофейня'),
    ('montserratalternates', 'Монсеррат — модный'),
    ('gabriela', 'Габриэла'),
    ('cuprum', 'Купрум'),
    ('ibmplexmono', 'Печатная — моно'),
    ('ysabeau', 'Исабо'),
]

REQUIRED = set('АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя')
TOKEN = os.environ.get('GITHUB_TOKEN')


def fetch(url, binary=False):
    req = urllib.request.Request(url, headers={'User-Agent': 'rin-klass-ci'})
    if TOKEN and 'api.github.com' in url:
        req.add_header('Authorization', 'Bearer ' + TOKEN)
        req.add_header('Accept', 'application/vnd.github+json')
    with urllib.request.urlopen(req, timeout=90) as r:
        data = r.read()
    return data if binary else json.loads(data)


def list_dir(folder):
    for lic in ('ofl', 'apache', 'ufl'):
        try:
            items = fetch(f'https://api.github.com/repos/google/fonts/contents/{lic}/{folder}')
        except Exception:
            continue
        if isinstance(items, list):
            return items
    return None


def choose(items):
    best = None
    best_score = 10 ** 9
    for it in items:
        name = it['name']
        if not name.lower().endswith('.ttf'):
            continue
        score = 0
        if 'Italic' in name:
            score += 100
        if '[' in name:
            score += 5
        if '-Regular' in name:
            score -= 10
        if score < best_score:
            best, best_score = it, score
    return best


def main():
    os.makedirs(OUT, exist_ok=True)
    lines = [l.rstrip('\n') for l in open('tools/fonts_local.txt', encoding='utf-8') if l.strip()]
    local_count = len(lines)
    ok, bad = [], []

    for folder, label in CANDIDATES:
        if len(ok) >= MAX_DOWNLOADED:
            break
        try:
            items = list_dir(folder)
            if not items:
                bad.append((folder, 'нет папки в репозитории'))
                continue
            it = choose(items)
            if not it:
                bad.append((folder, 'нет .ttf'))
                continue
            if it.get('size', 0) > MAX_BYTES:
                bad.append((folder, f"слишком большой ({it['size']} Б)"))
                continue
            data = fetch(it['download_url'], binary=True)
            path = os.path.join(OUT, folder + '.ttf')
            with open(path, 'wb') as f:
                f.write(data)
            font = TTFont(path, lazy=True)
            cmap = font.getBestCmap() or {}
            missing = [c for c in REQUIRED if ord(c) not in cmap]
            if missing:
                os.remove(path)
                bad.append((folder, 'нет кириллицы: ' + ''.join(sorted(missing))[:12]))
                continue
            wght = 0
            if 'fvar' in font:
                for ax in font['fvar'].axes:
                    if ax.axisTag == 'wght':
                        wght = int(max(ax.minValue, min(ax.maxValue, 700)))
            lines.append(f'{folder}.ttf|{label}|{wght}')
            ok.append((folder, it['name'], wght))
        except Exception as e:  # сеть, битый файл и т.п. - просто пропускаем шрифт
            bad.append((folder, f'ошибка: {e}'))

    with open(os.path.join(OUT, 'index.txt'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')

    print(f'=== ШРИФТЫ: своих {local_count}, скачано {len(ok)}, всего {len(lines)} ===')
    for folder, name, w in ok:
        print(f'  OK   {folder:26s} {name} wght={w}')
    for folder, why in bad:
        print(f'  SKIP {folder:26s} {why}')
    total_mb = sum(os.path.getsize(os.path.join(OUT, x)) for x in os.listdir(OUT)) / 1e6
    print(f'размер шрифтов: {total_mb:.1f} МБ')
    if len(lines) < MIN_TOTAL:
        print(f'ВНИМАНИЕ: шрифтов меньше {MIN_TOTAL}: получилось {len(lines)} (сборка продолжается)')


if __name__ == '__main__':
    main()
