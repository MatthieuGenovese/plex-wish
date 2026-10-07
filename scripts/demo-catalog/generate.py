#!/usr/bin/env python3
"""
Catalogue de démonstration (outil de développement, phase Polish) : ~1 300 animés inventés, ~1 700 saisons,
~28 000 épisodes, synopsis absents / très courts / très longs, titres longs, accents et noms japonais,
affiches de remplacement générées (formes géométriques, aucune image existante), distribution et progression.

Bibliothèque Python standard uniquement (lancé dans un conteneur python par scripts/demo-catalog.sh).
Sortie : <out>/demo.sql et <out>/posters/xx/<sha256>.png. Déterministe (graine fixe).

Jamais utilisé en production : rien ici n'entre dans les images Docker (contextes backend/ et web/), et
demo.sql refuse de s'exécuter sur une base qui contient de vrais fichiers (voir le garde-fou en tête).
"""
import hashlib
import math
import os
import random
import struct
import sys
import zlib
from datetime import datetime, timedelta, timezone

SEED = 20261007
ANIMES = 1300
ID_BASE = 1_000_000          # identifiants loin de ceux de l'application (pas de collision avec de vraies lignes)
# Identifiants « AniList » des comédiens inventés : numériques (format attendu par /api/people/{id}), bien au-delà des vrais.
DEMO_PROVIDER_ID = 9_900_000_000
NOW = datetime(2026, 10, 7, 12, 0, tzinfo=timezone.utc)

R = random.Random(SEED)

# ---------------------------------------------------------------------------------------------------------
# Vocabulaire inventé (aucun titre réel visé ; une coïncidence avec un vrai titre reste possible et sans objet)
# ---------------------------------------------------------------------------------------------------------
ROMAJI = ["Hoshi", "Kaze", "Yume", "Sora", "Tsuki", "Hikari", "Kage", "Mizu", "Hana", "Yoru", "Kiri", "Tori",
          "Umi", "Mori", "Ishi", "Koori", "Honoo", "Ame", "Yuki", "Kumo", "Tenshi", "Akuma", "Kitsune", "Ryuu",
          "Senpū", "Kōen", "Rōnin", "Shōnen", "Kyūden", "Tōkaidō", "Ōkami", "Jūnin", "Hōseki", "Yūrei"]
PARTS = ["no", "to", "ga", "wa"]
SUFFIX = ["Monogatari", "Densetsu", "Senki", "Gakuen", "Tensei", "Kiroku", "Nikki", "Sōshi", "Kitan", "Rhapsody",
          "Overdrive", "Chronicle", "Requiem", "Breaker", "Paradox", "Odyssey", "Gekijō", "Zero", "Δ", "R"]
FR_LONG = [
    "Le jour où la bibliothécaire de la tour d’ivoire décida de ne plus jamais rendre un seul livre",
    "Réincarné en distributeur de thé glacé dans une gare de province, je compte bien devenir le meilleur",
    "Ma voisine est une sorcière à la retraite et refuse obstinément de m’apprendre le moindre sort",
    "Comment survivre à la fin du monde quand on est délégué de classe et allergique aux chats",
    "L’épéiste qui avait peur du noir et la princesse qui collectionnait les cailloux",
    "Même si l’été ne revient pas, nous garderons les fenêtres ouvertes",
    "Cœur d’acier, âme de papier : la légende du forgeron œnologue",
    "Les dix-sept saisons de l’académie céleste (et pourquoi personne n’y obtient son diplôme)",
]
EN_TITLE = ["Starfall Academy", "Neon Tide", "The Quiet Blade", "Paper Moon Express", "Glass Garden", "Iron Lullaby",
            "Crimson Orbit", "Silent Harbor", "Velvet Circuit", "Ashen Crown", "Hollow Bell", "Tidebreaker"]
FR_TITLE = ["La Forêt des murmures", "Été éternel", "Le Château sous la pluie", "Les Gardiens de l’aube",
            "Écume", "Mélodie d’hiver", "L’Île aux lanternes", "Rêveries électriques", "Le Dernier Wagon"]
KANA = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん"
KANJI = "星風夢空月光影水花夜霧鳥海森石氷炎雨雪雲天使悪狐竜伝説物語学園記録日誌戦記零"

SENTENCES = [
    "Dans une ville où il pleut sans arrêt, une lycéenne découvre qu’elle peut arrêter le temps pendant trois secondes.",
    "Un ancien chevalier devenu boulanger tente de mener une vie tranquille, sans succès.",
    "Les élèves du club d’astronomie cherchent une étoile qui n’apparaît sur aucune carte.",
    "Au bout du monde, un phare s’allume chaque nuit pour des navires que personne n’a jamais vus.",
    "Leur amitié résistera-t-elle à un tournoi où le perdant oublie tout ?",
    "Une comédie douce sur la colocation, les nouilles instantanées et les rêves qu’on n’ose pas dire.",
    "Quand les esprits du quartier se mettent en grève, c’est à Haruto de négocier.",
    "Course-poursuite à travers des cités suspendues, trahisons et pilotes de génie.",
    "Chaque épisode raconte une journée différente de la même famille, à cent ans d’écart.",
    "Un mystère policier dans un train de nuit qui ne s’arrête jamais.",
    "Elle voulait juste ouvrir un café. Le café, lui, voulait conquérir le royaume.",
    "Une histoire d’apprentissage, d’échecs répétés et de victoires minuscules.",
]
EN_SENTENCES = [
    "A retired swordsman opens a noodle stand and finds that trouble still knows his address.",
    "In a city of floating islands, a courier who cannot fly carries messages no one else will deliver.",
    "Two rival bands share a single rehearsal room and a very small amount of patience.",
]
FAMILY = ["Satō", "Suzuki", "Takahashi", "Tanaka", "Watanabe", "Itō", "Yamamoto", "Nakamura", "Kobayashi", "Katō",
          "Yoshida", "Yamada", "Sasaki", "Yamaguchi", "Matsumoto", "Inoue", "Kimura", "Hayashi", "Shimizu", "Mori"]
GIVEN = ["Haruka", "Ren", "Yui", "Sōta", "Aoi", "Kaito", "Mio", "Riku", "Hina", "Yūto", "Sakura", "Daiki", "Akari",
         "Takumi", "Nanami", "Shō", "Rin", "Kenta", "Emi", "Kōji", "Ayane", "Jun", "Saki", "Tsubasa"]
EP_WORDS = ["Le début", "Une promesse", "La pluie", "Retour", "Le secret", "L’adieu", "Premier jour", "La tempête",
            "Le marché de nuit", "Rivalité", "Sous la lune", "Le pacte", "Fausse piste", "Le festival", "Épilogue"]


def kana(n):
    return "".join(R.choice(KANA) for _ in range(n))


def title_for(i):
    """Titres variés : romaji (macrons), mélanges, titres très longs, anglais, français, symboles."""
    k = R.random()
    if k < 0.45:
        t = f"{R.choice(ROMAJI)} {R.choice(PARTS)} {R.choice(SUFFIX)}"
        if R.random() < 0.2:
            t += f" {R.choice(['II', 'Ω', 'Next', '× ' + R.choice(ROMAJI), ': ' + R.choice(EN_TITLE)])}"
    elif k < 0.55:
        t = R.choice(FR_LONG)
    elif k < 0.72:
        t = R.choice(EN_TITLE) + R.choice(["", "", ": " + R.choice(ROMAJI) + " Arc", " (" + str(R.randint(1990, 2025)) + ")"])
    elif k < 0.82:
        t = R.choice(FR_TITLE)
    elif k < 0.9:
        t = f"{R.choice(ROMAJI)}⁄{R.choice(ROMAJI)}"
    else:
        t = f"{R.choice(ROMAJI)}!! {R.choice(SUFFIX)}?"
    return t


def synopsis(lang):
    k = R.random()
    pool = EN_SENTENCES if lang == "en" else SENTENCES
    if k < 0.25:
        return None
    if k < 0.45:
        return R.choice(pool)                                            # très court
    if k < 0.85:
        return " ".join(R.sample(pool, R.randint(2, min(4, len(pool)))))
    paras = [" ".join(R.sample(pool, min(3, len(pool)))) for _ in range(R.randint(3, 5))]   # très long
    return "\n\n".join(paras)


def episodes_per_season():
    k = R.random()
    if k < 0.36:
        return 12
    if k < 0.48:
        return 13
    if k < 0.74:
        return R.choice([24, 25, 26])
    if k < 0.86:
        return R.randint(1, 6)
    if k < 0.97:
        return R.randint(10, 30)
    return R.randint(40, 100)


def q(v):
    """Littéral SQL."""
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, datetime):
        return "'" + v.isoformat() + "'"
    return "'" + str(v).replace("'", "''") + "'"


# ---------------------------------------------------------------------------------------------------------
# Affiches générées : PNG (zlib + struct), aplats géométriques calculés par lignes (rapide en Python pur)
# ---------------------------------------------------------------------------------------------------------
W, H = 300, 450


def hsl(h, s, l):
    import colorsys
    r, g, b = colorsys.hls_to_rgb((h % 360) / 360, l, s)
    return bytes((int(r * 255), int(g * 255), int(b * 255)))


def poster_png(rnd):
    h0 = rnd.randint(0, 359)
    h1 = (h0 + rnd.choice([30, 60, 150, 200])) % 360
    sky = [hsl(h0 + (h1 - h0) * y / H * 0.3, 0.45, 0.18 + 0.32 * y / H) for y in range(H)]
    sun_c = hsl(h1, 0.7, 0.62)
    cx, cy, rad = rnd.randint(60, 240), rnd.randint(90, 220), rnd.randint(45, 95)
    layers = []
    for n in range(rnd.randint(2, 3)):
        base = 260 + n * 55 + rnd.randint(-20, 20)
        amp, freq, ph = rnd.randint(15, 45), rnd.uniform(0.01, 0.03), rnd.uniform(0, 6.28)
        heights = [int(base + amp * math.sin(x * freq + ph) + amp * 0.5 * math.sin(x * freq * 2.7)) for x in range(W)]
        layers.append((heights, hsl(h0 + 180 + n * 10, 0.35, 0.10 + 0.07 * (2 - n))))
    band = rnd.random() < 0.5
    band_c = hsl(h1 + 40, 0.5, 0.45)
    rows = []
    for y in range(H):
        row = bytearray(sky[y] * W)
        dy = y - cy
        if abs(dy) < rad:
            half = int(math.sqrt(rad * rad - dy * dy))
            x0, x1 = max(0, cx - half), min(W, cx + half)
            row[x0 * 3:x1 * 3] = sun_c * (x1 - x0)
        if band and 0 <= (y - 120) * 0.8 < W:
            x0 = int((y - 120) * 0.8)
            x1 = min(W, x0 + 14)
            row[x0 * 3:x1 * 3] = band_c * (x1 - x0)
        for heights, col in layers:
            # Colonnes sous la crête : segments contigus (la crête varie lentement).
            x = 0
            while x < W:
                if heights[x] <= y:
                    s = x
                    while x < W and heights[x] <= y:
                        x += 1
                    row[s * 3:x * 3] = col * (x - s)
                else:
                    x += 1
        rows.append(b"\x00" + bytes(row))
    raw = b"".join(rows)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


# ---------------------------------------------------------------------------------------------------------
def main(out):
    os.makedirs(out, exist_ok=True)
    sql = []
    w = sql.append
    w("-- Catalogue de démonstration (scripts/demo-catalog). NE PAS charger dans une vraie base.")
    w("\\set ON_ERROR_STOP on")
    w("BEGIN;")
    w("""DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM media_file WHERE relative_path NOT LIKE 'demo/%') THEN
    RAISE EXCEPTION 'Base non vide (vrais fichiers présents) : catalogue de démonstration refusé.';
  END IF;
END $$;""")
    # Rechargement : on repart de zéro pour les lignes de démonstration.
    w("DELETE FROM anime WHERE id >= %d;" % ID_BASE)
    w("DELETE FROM media_file WHERE relative_path LIKE 'demo/%';")
    w("DELETE FROM person WHERE id >= %d;" % ID_BASE)
    w("DELETE FROM cast_character WHERE id >= %d;" % ID_BASE)

    animes, seasons, files, episodes, posters = [], [], [], [], []
    seen_norm = set()
    sid = eid = fid = ID_BASE
    big = {7: 1100, 42: 720, 300: 500}   # séries fleuves en numérotation absolue (une saison)
    for i in range(ANIMES):
        aid = ID_BASE + i
        title = title_for(i)
        norm = title.lower()
        n = 2
        while norm in seen_norm:          # titres en double : suffixe (comme deux remakes)
            title = f"{title.split(' (')[0]} ({R.randint(1985, 2025)})" if n == 2 else f"{title} {n}"
            norm = title.lower()
            n += 1
        seen_norm.add(norm)
        lang = "en" if R.random() < 0.2 else "fr"
        alt = None
        if R.random() < 0.55:
            alt = "".join(R.choice(KANJI) for _ in range(R.randint(2, 5))) + kana(R.randint(0, 6))
        year = None if R.random() < 0.05 else R.randint(1983, 2026)
        has_meta = R.random() < 0.8
        created = NOW - timedelta(days=R.randint(0, 720), minutes=R.randint(0, 1440))
        syn = synopsis(lang)
        animes.append((aid, title, f"demo {aid} {norm}", alt, syn, year, "ANILIST" if has_meta else None,
                       ("fr" if lang == "fr" else "en") if syn else None, created))
        if R.random() < 0.65:
            png = poster_png(random.Random(SEED + aid))
            pid = hashlib.md5(f"demo-{aid}".encode()).hexdigest()
            sha = hashlib.sha256(png).hexdigest()
            rel = f"{sha[:2]}/{sha}.png"          # même rangement que les vraies affiches (PosterStore)
            os.makedirs(os.path.join(out, "posters", sha[:2]), exist_ok=True)
            with open(os.path.join(out, "posters", rel), "wb") as f:
                f.write(png)
            posters.append((aid, pid, rel, sha, len(png)))

        if i in big:
            plan = [(1, big[i])]
        else:
            k = R.random()
            count = 1 if k < 0.78 else 2 if k < 0.93 else R.randint(3, 4) if k < 0.985 else R.randint(5, 8)
            plan = [(s + 1, episodes_per_season()) for s in range(count)]
            if R.random() < 0.12:
                plan.append((0, R.randint(1, 6)))       # Spéciaux
        movie = len(plan) == 1 and plan[0][1] == 1
        container = R.choice(["mkv"] * 8 + ["mp4"] * 2 + ["avi"])
        for season_no, count in plan:
            sid += 1
            seasons.append((sid, aid, season_no))
            start = 1
            for e in range(start, start + count):
                fid += 1
                eid += 1
                rel = f"demo/{aid}/S{season_no:02d}E{e:04d}.{container}"
                seen = created + timedelta(minutes=e)
                files.append((fid, rel, os.path.basename(rel), R.randint(150, 1400) * 1_000_000, container, seen))
                t = R.random()
                ep_title = None if t < 0.4 else (R.choice(EP_WORDS) if t < 0.9 else
                                                 R.choice(EP_WORDS) + " : " + R.choice(FR_LONG).lower())
                dur = None if R.random() < 0.08 else (R.randint(85, 125) * 60 if movie else
                                                      R.randint(20, 26) * 60 + R.randint(0, 59) if season_no else
                                                      R.randint(8, 50) * 60)
                episodes.append((eid, sid, e, ep_title, dur, fid, seen))

    def insert(table, cols, rows, overriding=True, chunk=1000):
        for k in range(0, len(rows), chunk):
            part = rows[k:k + chunk]
            w(f"INSERT INTO {table} ({', '.join(cols)}) {'OVERRIDING SYSTEM VALUE ' if overriding else ''}VALUES")
            w(",\n".join("(" + ", ".join(q(v) for v in r) + ")" for r in part) + ";")

    insert("anime", ["id", "title", "normalized_title", "alternative_title", "synopsis", "year", "metadata_provider",
                     "synopsis_language", "created_at"], animes)
    insert("season", ["id", "anime_id", "season_number"], seasons)
    insert("media_file", ["id", "relative_path", "file_name", "file_size", "container", "kind", "available",
                          "first_seen_at", "last_seen_at", "last_modified"],
           [(f, r, n, s, c, "EPISODE", True, t, NOW, t) for (f, r, n, s, c, t) in files])
    insert("episode", ["id", "season_id", "episode_number", "title", "duration_seconds", "media_file_id", "created_at"],
           episodes)
    insert("anime_poster", ["anime_id", "status", "provider", "source_url", "public_id", "relative_path", "sha256",
                            "content_type", "bytes", "fetched_at"],
           [(a, "OK", "ANILIST", "demo:" + p, p, rel, sha, "image/png", size, NOW) for (a, p, rel, sha, size) in posters],
           overriding=False)

    # Distribution : 160 comédiens inventés, rôles pour ~500 animés.
    people = []
    for p in range(160):
        fam, giv = R.choice(FAMILY), R.choice(GIVEN)
        people.append((ID_BASE + p, "ANILIST", str(DEMO_PROVIDER_ID + p), f"{giv} {fam}", "".join(R.choice(KANJI) for _ in range(4)), NOW))
    insert("person", ["id", "provider", "provider_id", "name", "native_name", "fetched_at"], people, overriding=False)
    chars, casts = [], []
    cid = ID_BASE
    for (aid, *_rest) in R.sample(animes, 500):
        for pos in range(R.randint(3, 14)):
            cid += 1
            chars.append((cid, "ANILIST", str(DEMO_PROVIDER_ID + cid), f"{R.choice(GIVEN)} {R.choice(FAMILY)}",
                          kana(R.randint(3, 6)), NOW))
            person = None if R.random() < 0.06 else R.choice(people)[0]
            casts.append((aid, cid, "ja", person, "MAIN" if pos < 3 else "SUPPORTING", pos, "demo"))
    insert("cast_character", ["id", "provider", "provider_id", "name", "native_name", "fetched_at"], chars, overriding=False)
    insert("anime_cast", ["anime_id", "character_id", "language", "person_id", "role", "position", "source_id"], casts,
           overriding=False)

    # Progression pour chaque compte existant : 9 épisodes en cours, des débuts de séries vus, une série finie.
    by_season = {}
    for (e, s, num, _t, dur, _f, _c) in episodes:
        by_season.setdefault(s, []).append((e, num, dur or 1440))
    first_seasons = [s for (s, a, n) in seasons if n == 1]
    progress = []
    for s in R.sample(first_seasons, 30):
        eps = sorted(by_season[s], key=lambda x: x[1])
        done = R.randint(1, len(eps))
        for (e, _n, dur) in eps[:done]:
            progress.append((e, dur, dur, True, NOW - timedelta(days=R.randint(2, 60))))
        if done < len(eps) and len(progress) < 2000 and R.random() < 0.3:
            e, _n, dur = eps[done]
            progress.append((e, R.randint(60, dur - 120), dur, False, NOW - timedelta(hours=R.randint(1, 300))))
    for s in R.sample(first_seasons, 9):
        e, _n, dur = sorted(by_season[s], key=lambda x: x[1])[0]
        progress.append((e, R.randint(30, max(31, dur - 200)), dur, False, NOW - timedelta(minutes=R.randint(5, 5000))))
    uniq = {p[0]: p for p in progress}
    w("INSERT INTO playback_progress (user_id, episode_id, position_seconds, duration_seconds, completed, updated_at)")
    w("SELECT u.id, v.* FROM app_user u CROSS JOIN (VALUES")
    w(",\n".join("(" + ", ".join(q(x) for x in p) + "::timestamptz)" for p in uniq.values()))
    w(") AS v(episode_id, position_seconds, duration_seconds, completed, updated_at)")
    w("ON CONFLICT (user_id, episode_id) DO NOTHING;")
    # Genres AniList (S3) : 1 à 3 par animé ayant une fiche, valeurs AniList (anglais), comme en vrai.
    GENRES = ["Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music", "Mystery",
              "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller"]
    genre_rows = [(a[0], g) for a in animes if a[6] for g in R.sample(GENRES, R.randint(1, 3))]
    insert("anime_genre", ["anime_id", "genre"], genre_rows, overriding=False)
    w("UPDATE anime SET genres_fetched_at = now() WHERE id >= %d AND metadata_provider IS NOT NULL;" % ID_BASE)

    # Analyse ffprobe simulée (la vraie tâche est coupée dans la stack de démonstration) : MP4 lisible dans un
    # navigateur, MKV et AVI non ; AVI à remuxer pour Android. Sert au filtre « lisible dans le navigateur ».
    w("""INSERT INTO media_probe (media_file_id, probed_size, probed_modified, status, container, android_class,
         browser_playable, browser_reasons, duration_seconds)
       SELECT m.id, m.file_size, m.last_modified, 'OK', m.container,
              CASE WHEN m.container = 'avi' THEN 'REMUX' ELSE 'DIRECT' END, m.container = 'mp4',
              CASE WHEN m.container = 'mp4' THEN NULL ELSE 'conteneur ' || upper(m.container) END, e.duration_seconds
       FROM media_file m JOIN episode e ON e.media_file_id = m.id WHERE m.relative_path LIKE 'demo/%';""")
    w("COMMIT;")
    with open(os.path.join(out, "demo.sql"), "w", encoding="utf-8") as f:
        f.write("\n".join(sql) + "\n")
    print(f"{len(animes)} animés, {len(seasons)} saisons, {len(episodes)} épisodes, {len(posters)} affiches, "
          f"{len(people)} comédiens, {len(casts)} rôles, {len(uniq)} progressions par compte → {out}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "demo-catalog")
