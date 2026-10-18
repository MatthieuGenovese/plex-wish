# Remux à la volée (Android et navigateur) — conception

Statut : **validée le 2026-10-18** (sous-titres en image : perte acceptée, à recompter). **L1 (serveur) réalisée** (§13). Essai préalable fait le 2026-10-18 sur le PC (§2).

## 0. Résumé et décisions à valider

**Besoin** (installation de Julien, ~27 900 fichiers) : à chaque épisode à remuxer, l'utilisateur attend
« Préparation de l'épisode… » ; les copies s'accumulent dans un cache. Or presque toute la bibliothèque ne demande qu'un
remux (changer de conteneur sans ré-encoder), et le transcodage est rare.

**Principe** : le serveur fabrique les segments HLS **pendant la lecture**, avec un ffmpeg par lecture qui recopie les
données sans ré-encoder. La lecture démarre tout de suite, le seek marche partout, et rien ne s'accumule sur le disque.
Le **transcodage vidéo** (lecteur web, classe W3) reste préparé à l'avance et mis en cache, comme aujourd'hui : à la
volée, il ne suivrait pas sur le NAS.

| # | Décision | Proposé |
|---|---|---|
| L-D1 | Format | **HLS, segments MP4 fragmentés (fMP4)**, une playlist pour la vidéo et une par piste audio. MPEG-TS est écarté (§2.2). |
| L-D2 | Découpage | Segments de **6 s au moins**, coupés sur les images clés d'origine, calculés à l'avance à partir d'un **index des images clés** gardé en base (§4). |
| L-D3 | Production | **Un seul ffmpeg par lecture**, relancé à l'endroit voulu en cas de saut. Le serveur découpe sa sortie, corrige les horodatages et sépare les pistes (§3). |
| L-D4 | Stockage | Quelques segments d'avance dans un **dossier temporaire par lecture**, supprimé à la fin de la lecture (environ 30 Mo par lecture). Pas de cache. |
| L-D5 | Son | Copié tel quel s'il est lisible. Sinon converti en **AAC** à la volée (Vorbis des OGM, et pour le web : AC3, DTS…). Coût faible (§2.3). |
| L-D6 | Android | Dépendance **`androidx.media3:media3-exoplayer-hls:1.5.1`** (✅ validée le 2026-10-18). L'app annonce qu'elle sait lire le HLS (`?hls=1`) ; les anciennes versions gardent le remux en cache actuel jusqu'à l'étape L4. |
| L-D7 | Web | Les classes **W1 (remux) et W2 (son seul) passent à la volée** (✅ validé le 2026-10-18) ; W3 (conversion vidéo) inchangée. |
| L-D8 | Limites | **4 lectures à la volée en même temps** au plus (réglable). Au-delà : message clair (503). Lecture arrêtée après 60 s sans requête. |
| L-D9 | Sous-titres intégrés | Texte (ASS, SRT) : extraits à part, comme pour le web, et donnés à Android comme les sous-titres externes. **Image (PGS, VobSub) : perdus pour les fichiers remuxés à la volée** (rare : AVI/OGM n'en ont pas) (§7). |
| L-D10 | Signature | Une signature `v1-live` par fichier, utilisateur et expiration, valable pour toutes les ressources de cette lecture (§5). |
| L-D11 | Code | Nouveau paquet `live`, découpé **Resource / Service / Repository** (§10). |

## 1. Ce qui change pour les utilisateurs

- **Android** : plus de « Préparation de l'épisode… » pour les AVI, OGM et MKV « mode Windows ». La première lecture
  d'un fichier jamais lu attend juste le relevé de ses images clés (quelques secondes sur le NAS, §4). Le seek marche
  partout, y compris loin dans l'épisode.
- **Navigateur** : les MKV H.264, avec plusieurs langues ou avec un son à convertir, se lisent tout de suite, au lieu
  de « Préparation… » suivi d'un seek limité à la partie prête. Les conversions vidéo (H.264 10 bits, HEVC sans
  décodeur, Xvid…) ne changent pas.
- **Disque** : le cache des copies Android disparaît (étape L4). Le cache web ne garde plus que les conversions vidéo
  et les sous-titres extraits.

## 2. Essai du 2026-10-18 (PC, ffmpeg 4.4, Media3 1.5.1)

Fichiers réels :

- AVI Air Gear (Xvid + MP3) ;
- OGM Da Capo (MPEG-4 + Vorbis) ;
- « Le chant des rêves » (MKV, DivX 5 en mode compatibilité Windows + Vorbis + SRT) ;
- extrait de 5 min de Days (MKV H.264 + AAC + SRT + PGS).

Les segments ont été lus par les classes de Media3 que l'app utilise pour le HLS (`FragmentedMp4Extractor`, `TsExtractor`), sur la JVM. **Pas encore sur le téléphone** : c'est le premier test de L2.

### 2.1 Index et découpage

- **Images clés** :
  - relevées par `ffprobe -select_streams v:0 -show_entries packet=pts_time,dts_time,flags` ;
  - environ 1 s par épisode sur le PC, disque en cache ;
  - l'AVI n'a pas de pts, on prend le dts ;
  - 400 à 800 images clés par épisode.
- **Saut** : `-ss` en entrée tombe sur l'image clé précédente si on vise l'image clé exacte (AVI). On vise donc
  **l'image clé + min(0,5 s, moitié de l'écart avec la suivante)** : ffmpeg part bien de l'image clé voulue.
- **Découpage** : coupure exactement aux endroits prévus, son continu d'un segment à l'autre (écart de 0 à 1 trame).

### 2.2 MPEG-TS écarté

- **H.264, AAC, MP3** : Media3 les lit en TS.
- **MPEG-4 ASP (Xvid/DivX)** : non. Le lecteur TS de Media3 (`H263Reader`) attend dans le flux les en-têtes VOS/VO
  (`00 00 01 B0`, `00 00 01 B5`). Les fichiers DivX/Xvid ne les ont pas : seul le VOL est dans l'en-tête du fichier.
  - Résultat : aucune image pour « Le chant des rêves » ; pour Air Gear, l'image n'arrive qu'au bout de quelques secondes de chaque segment.
  - `-bsf:v dump_extra` n'y change rien.
- Or ce sont justement les fichiers à remuxer pour Android.

### 2.3 fMP4 retenu

- **Commande** (un fragment par image clé, une boîte `moof` par piste) :
  ```
  ffmpeg -nostdin -v warning [-ss T] -i file:SRC -map_chapters -1 -map_metadata -1 \
    -map 0:v:0 -map 0:a:0 [-map 0:a:1 …] -c:v copy [-tag:v hvc1] -c:a copy|aac -b:a 160k \
    -f mp4 -movflags +frag_keyframe+empty_moov+default_base_moof+separate_moof pipe:1
  ```
- **Lecture par Media3** : vidéo lue dans tous les segments des 4 fichiers (`video/mp4v-es`, `video/avc`), son lu
  (`audio/mpeg`, `audio/mp4a-latm`). Un flux par piste, avec son propre en-tête (§3.3), est lu correctement.
- **En-tête (`ftyp` + `moov`)** : **identique octet pour octet** d'un lancement à l'autre, depuis le début ou depuis
  le milieu. Il faut `-map_chapters -1`, sinon une piste de chapitres s'ajoute.
- **Horodatages** :
  - ffmpeg remet le temps à 0 au début de chaque lancement (`tfdt` en version 1, 64 bits), avec le **même décalage pour toutes les pistes** : l'image clé de départ ;
  - le serveur rajoute ce décalage (§3.2) ;
  - au raccord, les segments d'un lancement relancé au milieu suivent ceux du premier avec moins de 20 ms d'écart. Seul le premier fragment après un saut peut commencer son son environ 20 ms plus tôt.
- **Coût sur le PC (2 cœurs)** :
  - épisode entier en copie : 0,75 à 1,4 s ;
  - avec conversion du son Vorbis en AAC : environ 12 s pour 24 min, soit environ 120 fois le temps réel ;
  - le NAS (R1600) est estimé 2 à 3 fois plus lent : la marge reste large pour 4 lectures.

## 3. Production des segments

### 3.1 Session de lecture

Une **session** par utilisateur et par fichier. Elle est créée à la première demande d'une playlist, et une nouvelle
session du même utilisateur sur le même fichier remplace l'ancienne. Elle contient :

- le **plan** : les bornes des segments, en secondes, tirées de l'index (§4) ;
- au plus **un ffmpeg** en cours, avec le segment où il a démarré ;
- un **dossier temporaire** `<cache remux>/live/<id aléatoire>/` qui contient les segments prêts, par piste
  (`v/12.m4s`, `a0/12.m4s`…) et les en-têtes par piste.

**Demande du segment k d'une piste** :

1. Prêt : envoyé.
2. Sinon, si k est dans la fenêtre que ffmpeg va produire bientôt (de son segment courant à 3 segments plus loin) : on attend qu'il soit prêt (25 s au plus).
3. Sinon, c'est un saut : ffmpeg est arrêté et relancé à la borne k (§2.1). Les segments déjà prêts sont gardés.

**Contre-pression** : quand 4 segments d'avance sont prêts, le serveur arrête de lire la sortie de ffmpeg. ffmpeg se
bloque tout seul en écriture, sans consommer de processeur, et reprend quand le lecteur avance. Les segments plus
anciens que 2 derrière la dernière demande sont supprimés.

**Fin** : 60 s sans requête, ou nouvelle session du même utilisateur. ffmpeg est arrêté (`destroyForcibly`) et le
dossier supprimé. Au démarrage du serveur, le dossier `live/` est vidé.

### 3.2 Découpe et correction des horodatages

Le serveur lit la sortie de ffmpeg boîte par boîte, comme l'a fait l'essai :

- `ftyp` + `moov` : l'en-tête, gardé une fois par session (§3.3) ;
- chaque paire `moof` + `mdat` appartient à une seule piste (`separate_moof`), donnée par `tfhd` ;
- **correction** : dans chaque `traf`, `tfdt` (64 bits) reçoit `+ (borne de départ − borne 0) × échelle de temps de la
  piste`. La modification se fait sur place, sans changer de taille. Puis recalage fin : le premier échantillon vidéo du
  lancement doit tomber exactement sur l'image clé (on tient compte du décalage de composition de `trun`) ;
- **rangement** : un fragment vidéo va dans le segment k du plan d'après son temps corrigé. Les fragments audio sont
  rangés dans le même segment que le fragment vidéo qu'ils suivent (ffmpeg coupe toutes les pistes à chaque image
  clé).

### 3.3 En-têtes par piste

À partir du `moov` complet, le serveur fabrique un `moov` par piste : il garde le `trak` de la piste et son `trex`,
retire les autres et recalcule les tailles. Contrôle à chaque relance : le nouvel en-tête doit être identique au
premier. Sinon, erreur journalisée et session terminée ; l'essai ne l'a jamais vu.

### 3.4 Playlists

- **Maîtresse** : vidéo, groupe audio (`EXT-X-MEDIA TYPE=AUDIO`, langue, piste par défaut), `CODECS` d'après
  l'analyse et l'en-tête. Mêmes règles qu'aujourd'hui pour le choix des pistes et des langues (`WebManifest`).
- **Par piste** : `EXT-X-PLAYLIST-TYPE:VOD`, `EXT-X-ENDLIST`, `EXT-X-MAP` (en-tête de la piste),
  `EXT-X-INDEPENDENT-SEGMENTS`, une ligne `EXTINF` par segment du plan. Durées exactes : la durée totale est connue
  dès le départ, la barre est complète et le seek libre.
- Toutes les URL portent la signature (§5). `Cache-Control: no-store` sur les playlists, comme le web aujourd'hui.

## 4. Index des images clés

- **Table** `media_keyframes` (V22) :
  - `media_file_id` (clé, cascade) ;
  - `probed_size`, `probed_modified` (le fichier modifié invalide l'index) ;
  - `times` : `bytea`, liste de millisecondes compressée ;
  - `computed_at`.

  Taille : quelques Ko par fichier.
- **Calcul** : `ffprobe` en lecture seule, arguments fixes, délai maximal, aux mêmes conditions que l'analyse 9.1.
  Il lit tout le fichier : environ 1 s sur le PC, **à mesurer sur le NAS** (estimé 3 à 8 s pour un épisode de 200 à
  400 Mo sur disque dur).
- **Quand** :
  1. À la première lecture : l'API répond « préparation » (202, comme aujourd'hui) pendant ces quelques secondes.
  2. **En tâche de fond, à l'avance** : les 2 épisodes suivants de chaque animé en cours, comme le préventif web (D8 de
     WEB-PLAYER), dans la même file que l'analyse 9.1, en pause pendant les lectures.

  Pas de relevé de toute la bibliothèque : ce serait des téraoctets à relire pour rien.
- **Plus tard (FUTURE)** : lire les `Cues` des MKV (positions des images clés déjà indexées dans le fichier), sans
  relire tout le fichier.

## 5. API et sécurité

| Requête | Rôle |
|---|---|
| `GET /api/episodes/{id}/stream-url?hls=1` (Android ≥ L2) | Fichier « remux nécessaire » : **200** `{kind:"HLS", url: master signée, mimeType:"application/x-mpegURL", subtitles:[…]}`, ou 202 pendant le relevé de l'index. Les autres fichiers : réponse actuelle (original). Sans `hls=1` : réponse actuelle (remux en cache). |
| `GET /api/episodes/{id}/web-playback` (web) | W1/W2 : la master à la volée au lieu d'une copie préparée ; W0 et W3 inchangés. |
| `GET /api/stream/{fileId}/live/{ressource}?u=&exp=&sig=` | `master.m3u8`, `v.m3u8`, `a{N}.m3u8`, `v/init.mp4`, `a{N}/init.mp4`, `v/{k}.m4s`, `a{N}/{k}.m4s`. Noms vérifiés par une expression stricte, jamais de chemin. |

- **Signature `v1-live`** : HMAC sur fichier, utilisateur et expiration, préfixe propre. Elle n'ouvre ni l'original,
  ni le remux en cache, ni les copies web, et inversement. Elle couvre toutes les ressources de la lecture : c'est
  l'équivalent de la signature actuelle de l'original, qui donne déjà tout le fichier. Même contrôle d'utilisateur
  actif, même durée de vie que les autres liens.
- **Aucune donnée du client dans la commande** : le chemin vient de la base, il est revérifié sous la bibliothèque. Les
  temps viennent du plan. Le client ne donne qu'un numéro de segment, vérifié contre le plan.
- Journaux : `sig=` masqué, comme aujourd'hui.

## 6. Charge du NAS

- **Copie** : le disque est le seul coût. Chaque lancement relit le fichier à partir du point de départ, au rythme de
  la lecture grâce à la contre-pression (§3.1). Un saut relit à partir du nouveau point.
- **Conversion du son** (Vorbis, et sur le web AC3/DTS/TrueHD) : environ 2 % d’un cœur par lecture d’après l’essai.
- **Priorité** : ffmpeg à la volée tourne en priorité **normale**, car il sert une lecture en cours. Les tâches de fond
  (analyse, préventif, conversions W3) passent après, comme aujourd'hui pendant une lecture (D6).
- **Plafond** : 4 sessions (réglage `LIVE_MAX_SESSIONS`, Administration > Réglages). Au-delà : **503 `LIVE_BUSY`**,
  « Le serveur lit déjà 4 épisodes à la volée, réessayez dans un instant ».
- **Mémoire** : les segments sont dans le dossier temporaire, pas dans la mémoire Java (512 Mo).

## 7. Sous-titres

- **Web** : inchangé. Les sous-titres texte et les polices sont extraits à part (quelques Ko à quelques Mo, gardés).
  Seule la vidéo n'est plus copiée.
- **Android** : aujourd'hui, le MKV remuxé emporte ses sous-titres intégrés. À la volée, les pistes texte (ASS/SSA,
  SRT) sont **extraites par la même étape que le web** (une lecture du fichier, en parallèle du démarrage) et données
  dans `subtitles[]`, comme les sous-titres externes : ASS en `text/x-ssa`, SRT en `application/x-subrip`.
- **Sous-titres en image (PGS, VobSub)** des fichiers remuxés à la volée : non transmis, message dans les pistes. Cas
  rare : les AVI et OGM n'en ont pas, et les MKV lus directement (la grande majorité) ne passent pas par là.

## 8. Application Android (étape L2)

- Dépendance `media3-exoplayer-hls:1.5.1`.
- `stream-url?hls=1` ; si `kind == "HLS"` : `MediaItem` avec `MimeTypes.APPLICATION_M3U8` (`ExoPlaybackEngine`).
  Reprise, progression, sous-titres externes et diagnostic sont inchangés.
- La phase « Préparation… » reste pour le relevé de l'index (quelques secondes, la première fois) et pour les anciens
  serveurs.
- **Compatibilité** : nouvel APK sur ancien serveur → le paramètre est ignoré, remux en cache comme aujourd'hui.
  Ancien APK sur nouveau serveur → remux en cache jusqu'à L4.

## 9. Lecteur web (étape L3)

- `WebDecision` : W1 et W2 → à la volée. W0 (original) et W3 (conversion vidéo, cache) inchangés.
- Le lecteur garde hls.js et l'interface. Il n'y a plus d'attente ni de barre « préparée » pour W1/W2 : playlist VOD
  complète.
- Préventif web (D8) : ne prépare plus que les W3 et les sous-titres. Plafond du cache web inchangé, rempli bien plus
  lentement.

## 10. Organisation du code (nouveau paquet `fr.plexwish.animeserver.live`)

| Fichier | Rôle |
|---|---|
| `LiveStreamResource` | API `/api/stream/{fileId}/live/...` : vérifie signature et nom, délègue, réponses HTTP. |
| `LivePlaylistService` | Plan des segments (à partir de l'index) et textes des playlists. |
| `LiveSessionService` | Sessions : lancement et relance de ffmpeg, contre-pression, attente d'un segment, fin et nettoyage, plafond. |
| `Fmp4` | Utilitaire sans état : lecture des boîtes, correction `tfdt`, séparation par piste, en-tête par piste (testé seul). |
| `KeyframeIndexService` | Relevé des images clés (ffprobe), décision « à jour ou à refaire ». |
| `KeyframeIndexRepository` | Table `media_keyframes`. |

`StreamResource` (stream-url) et `WebPlaybackResource` (web-playback) ne font qu'appeler `LivePlaylistService` pour
obtenir l'URL de la master. Aucune requête SQL hors du Repository (garde-fou `RepositoryBoundaryTest`).

## 11. Étapes

| Étape | Contenu | Test |
|---|---|---|
| **L1** serveur | Index (V22, service, repository) ; `Fmp4` ; sessions ; playlists ; `/live/...` ; `stream-url?hls=1`. **Vérifié d'abord avec le ffmpeg 7.1.5 de l'image** : remise à zéro, `separate_moof`, en-tête identique. | Unitaires `Fmp4` (fragments produits par le vrai ffmpeg dans le test, ignorés sans ffmpeg) ; plan et playlists ; sessions avec un faux ffmpeg (relance sur saut, contre-pression, fin, plafond) ; sécurité (signature, autre utilisateur, expiré, nom invalide). |
| **L2** Android | Module HLS, `hls=1`, `MediaItem` M3U8, sous-titres intégrés extraits. | Unitaires ; **S24** : Air Gear, Da Capo, « Le chant des rêves », un MKV multi-langues ; seek, reprise, changement de langue. |
| **L3** web | W1/W2 à la volée. | Unitaires `WebDecision` ; lecture Chrome (Playwright) sur un MKV H.264 multi-langues et un MKV avec son AC3. |
| **L4** nettoyage | Retrait du remux en cache Android, du test à blanc et de leurs écrans admin, une fois tous les APK à jour (date à fixer avec Matthieu). | Tests touchés. |

Version : **1.4.0** à la fin de L2 (première livraison utile), puis 1.4.x.

Tests : seulement ceux touchés par chaque étape (règle du 2026-10-18).

## 12. Risques

| Risque | Mesure |
|---|---|
| ffmpeg 7.1.5 (image) se comporte autrement que le 4.4 de l'essai | Premier point de L1, avec un test qui échoue clairement. |
| Lecture réelle sur le S24 (MPEG-4 ASP en fMP4 HLS) | Premier point de L2 ; repli : remux en cache gardé jusqu'à L4. |
| Relevé de l'index lent sur le NAS (disque dur) | Mesuré en L1 ; préventif sur les épisodes suivants ; Cues MKV plus tard. |
| Plusieurs lectures + scan + analyse sur le NAS | Plafond de sessions, tâches de fond en pause pendant les lectures (déjà en place). |
| Fichiers à horodatages cassés (AVI sans index, VFR exotique) | Relance contrôlée ; sinon erreur claire « lecture à la volée impossible » et repli sur le remux en cache tant qu'il existe. |

## 13. Réalisé

### L1 — serveur (2026-10-18)

- **ffmpeg 7.1.5** (mêmes sources et options que l'image, compilé sur le PC) : même comportement que le 4.4 de l'essai
  (en-tête identique d'un lancement à l'autre, remise à zéro commune à toutes les pistes, `separate_moof`) ; Media3 lit
  les 155 segments de « Le chant des rêves » (DivX en mode Windows + Vorbis converti en AAC).
- Paquet `live` :
  - `LiveStreamResource` : préfixe de classe `/api`, comme les autres ressources. Un préfixe de classe
    `/api/stream/{fileId}/live` captait aussi `/api/stream/{fileId}` et cassait la lecture des originaux (vu par
    `StreamTest`).
  - `LiveService`, `LiveSessionService`, `KeyframeIndexService`, `LiveRepository`, `Fmp4`, `LivePlan`.
  - Migration **V22** `media_keyframes`.
- `GET /api/episodes/{id}/stream-url?hls=1` :
  - fichier « remux nécessaire » : `kind: "HLS"`, ou 202 pendant le relevé de l'index ;
  - lecture à la volée impossible (fichier non analysé, pas d'image clé) : repli sur le remux en cache, comme avant ;
  - sans `hls=1` : réponse inchangée (`kind: "FILE"` ajouté, ignoré par les anciens APK).
- Réglages (`anime.media.live-*`) :
  - `max-sessions` : 4 ;
  - `idle` : 60 s ;
  - `ahead` : 4 segments ;
  - `segment-wait` : 25 s ;
  - `index-timeout` : 5 min.

  Le dossier temporaire est `<remux-cache>/live/`, vidé au démarrage.
- **Différences avec la conception** :
  - pas d'attribut `CODECS` dans la playlist maîtresse (Media3 n'en a pas besoin ; à ajouter pour hls.js en L3,
    d'après l'en-tête) ;
  - pas de recalage fin par `trun` (inutile d'après l'essai : seul le raccord d'un saut garde un écart ≤ 20 ms) ;
  - relevé préventif de l'index pour les épisodes suivants : **pas encore fait** (à faire avec L2) ;
  - réglage du plafond dans Administration > Réglages : pas encore (variable `anime.media.live-max-sessions`).
- Tests (ciblés) :
  - `LivePlanTest` (découpage, visée, lecture de la sortie de ffprobe) ;
  - `Fmp4Test` (vrai ffmpeg : pistes, fragments, correction `tfdt`, en-tête d'une piste relu par ffprobe) ;
  - `LiveStreamTest` (vrais outils, AVI MPEG-4 à deux pistes MP3 + MP2→AAC : 202 puis HLS, playlists, segments à leur
    borne, saut en avant et en arrière, vidéo complète relue par ffprobe ; signature par fichier, utilisateur et
    expiration, ni l'original ni le remux en cache ouverts par une signature `v1-live`, compte désactivé ; plafond et
    une session par utilisateur ; réponse inchangée sans `hls=1`) ;
  - et les tests touchés : `StreamTest`, `SubtitleStreamTest`, `RemuxTest`, `WebSignatureTest`, `LogLeakTest`,
    `ClientDisconnectLogFilterTest`, `SocleTest`, `RepositoryBoundaryTest`.
