# Phase 10 — Lecteur web et préparation des médias pour le navigateur

Conception (10.1, 2026-10-10), **décisions prises le 2026-10-10** (§0) ; changements de sécurité S1 à S7 **acceptés**
(§9), rien d'autre ne change dans la CSP sans accord. Réalisation : 10.2 (lecteur, sans conversion lourde), puis 10.3
(conversion et file d'attente).

Rappel du point de départ : Android lit déjà tout (décodeur du téléphone, repli FFmpeg logiciel depuis le
2026-10-10) et ne change pas : il continue de lire l'original ou la copie remux existante (§23 d'ARCHITECTURE). Tout
ce qui suit est **à part** : un nouveau cache « web », une nouvelle file, une nouvelle route de lecture pour le
navigateur.

## 0. Résumé et décisions (prises le 2026-10-10)

Principe : **le navigateur dit ce qu'il sait décoder, le serveur choisit la source la moins chère** :

1. original tel quel (Range),
2. sinon une copie remuxée sans ré-encodage,
3. sinon une copie dont seul le son est converti,
4. sinon une copie convertie en H.264 8 bits.

Les étapes 2 à 4 se font à l'avance ou en tâche de fond, jamais pendant la lecture. Une copie web est un **HLS fMP4 à
fichier unique par piste**, lu par hls.js : la vidéo en un fichier, chaque piste audio en un petit fichier à part. Ce
format donne le choix de la langue, un seek fiable et une lecture possible avant la fin de la préparation. Les
sous-titres sont extraits à part : ASS rendu par JASSUB, SRT en WebVTT natif.

| # | Décision | Retenu |
|---|---|---|
| D1 | Format des copies web (§2) | ✅ **HLS fMP4 (fichier unique par piste) + hls.js 1.7.3** (Apache 2.0) |
| D2 | Sous-titres ASS (§3) | ✅ **JASSUB 2.5.18** (libass en WebAssembly, chargé seulement si l'épisode a de l'ASS), polices jointes du MKV extraites ; WebVTT en secours |
| D3 | Sous-titres image PGS/VobSub (§3) | ✅ **Message clair** (incrustation : plus tard, FUTURE) |
| D4 | ffmpeg de l'image (§4.5) | ✅ Recompilé avec l'**assembleur**, l'encodeur **AAC**, **libx264** et le muxer HLS (10.3.1 ; le muxer HLS seul dès 10.2.2). Binaire ffmpeg GPL : **sources fournies sur demande**, noté dans la doc de déploiement et dans « À propos » |
| D5 | Réglages de conversion (§4.2, §6) | ✅ x264 `veryfast`, CRF 21, `-tune animation`, 2 fils, `nice 19` + `ionice` classe 3 ; AAC 160 kb/s stéréo. **Plafond 720p par défaut, réglable en 1080p** dans Administration > Réglages (10.3) |
| D6 | Pause pendant les lectures (§4.6) | ✅ Préventif suspendu pendant les lectures ; à la demande poursuivi mais bridé |
| D7 | Cache web (§4.3) | ✅ Plafond **15 % du volume, 200 Go au plus**, réglable ; nettoyage la nuit + à la demande. **Dossier configurable** : `WEB_CACHE_PATH` dans `nas.env` (un volume qui a de la place), montré par l'assistant, la doc de déploiement, créé par l'installateur et ajouté par la mise à jour (§4.3) |
| D8 | Préventif (§5) | ✅ La nuit : 2 épisodes suivants de chaque animé en cours, puis les ajouts récents ; conversions vidéo préventives désactivées par défaut |
| D9 | Prévenir l'utilisateur (§5) | ✅ Dans le lecteur, lecture dès que possible ; pastille « Prêt » sur la fiche et l'accueil ; pas de notification du navigateur |
| D10 | Sécurité (§9) | ✅ **S1 à S7 acceptés tels que décrits** ; aucun autre changement de CSP sans accord |
| D11 | Planificateur interne | ✅ Minimal, en 10.3 |
| — | Tests de bout en bout | ✅ `@playwright/test` en dépendance de **développement** seulement (Chrome automatisé) |

Ajouts du 2026-10-10 :

- **HEVC en fMP4** : la piste vidéo est marquée `hvc1` (`-tag:v hvc1`), sinon Chrome (et Safari) la refusent.
- Chaque étape dit précisément **quels navigateurs ont été testés automatiquement** (Chrome via Playwright) et
  lesquels sont **à tester à la main** (Firefox, Chrome Android, S24).

## 1. Ce que les navigateurs lisent (Chrome, Edge, Firefox récents ; pas d'Apple)

« Vérifié » = source lue le 2026-10-10 (liens en §10). « Supposé » = connaissance générale, non revérifiée ici ; le
lecteur le **teste au démarrage** (`MediaSource.isTypeSupported`, `HTMLVideoElement.canPlayType`,
`navigator.mediaCapabilities.decodingInfo`) et l'envoie au serveur, qui tranche. Les suppositions ne décident donc
de rien sur le terrain : elles servent seulement à estimer le volume de conversions.

| Élément | Chrome / Edge | Firefox | Statut |
|---|---|---|---|
| **Conteneur MP4 / fMP4** | oui | oui | supposé (universel) |
| **Conteneur WebM** | oui | oui | supposé |
| **Conteneur MKV** | non annoncé (`canPlayType` vide ; lit parfois, sans garantie) | **oui depuis Firefox 145** (Opus, Vorbis, VP8/9, AV1, HEVC ; AVC/AAC limités) | Firefox vérifié ; Chrome supposé |
| **H.264 8 bits** (High, ≤ 1080p) | oui | oui | supposé (base de tout le web) |
| **H.264 10 bits (Hi10P)** | non : décodeurs matériels sans profil High 10 | non | supposé (cohérent avec Android, où le S24 le refuse aussi) |
| **HEVC 8/10 bits** | **matériel seulement** (Chrome 105+ par défaut) : oui sur la plupart des PC récents et sur Android, rien sans décodeur | **Windows, matériel seulement** (Firefox 134+) ; non sur Linux | vérifié (versions) ; Main 10 supposé si le GPU le décode |
| **VP9** | oui | oui | supposé |
| **AV1** | oui (logiciel dav1d si pas de matériel) | oui | supposé |
| **MPEG-4 ASP (Xvid/DivX), MPEG-2, VC-1** | non | non | supposé |
| **AAC, MP3** | oui | oui | supposé |
| **Opus** (MP4/WebM), **FLAC** (MP4) | oui | oui | supposé (FLAC-in-MP4 annoncé par Chrome) |
| **Vorbis** | WebM/Ogg seulement | idem | supposé |
| **AC3 / E-AC3** | non fiable (Edge Windows selon le système, codec retiré de Windows 11 24H2) | non | supposé → toujours converti |
| **DTS, TrueHD** | non | non | supposé |
| **Plusieurs pistes audio dans `<video>`** | **non** (`audioTracks` désactivé par défaut) | **non** | vérifié (caniuse) |
| **Sous-titres** | **WebVTT seulement** (`<track>`) ; ni ASS, ni pistes internes du MKV, ni images | idem | supposé (standard HTML) |
| **MSE** (pour hls.js) | oui (y compris Chrome Android) | oui | supposé |

Conséquences pour la bibliothèque (relevé de noms, faute d'analyse ffprobe du vrai NAS) :

- 75 % de MKV, 22 % de MP4, 3 % d'AVI/OGM. Le MKV n'est fiable que dans Firefox : **presque tout le MKV passe par
  un remux**, qui est peu coûteux.
- Au moins 7,5 % des noms annoncent du HEVC et 6 % du 10 bits. **Les vrais chiffres viendront de l'analyse 9.1 lancée
  sur le NAS (D2)** : c'est le premier chiffre à regarder avant de régler le préventif.
- Sous-titres : quasi toujours ASS dans les MKV (VOSTFR de fansubs). Sans rendu ASS, le lecteur web ne servirait à
  personne (ROADMAP phase 10).

Format de sortie universel des conversions : **H.264 High 8 bits 4:2:0, ≤ 1080p, AAC-LC stéréo**, en fMP4.

## 2. Pistes audio multiples et seek

`<video>` ne change pas de piste audio dans un MP4 qui en a plusieurs (§1). Près de 7 % des noms annoncent du
« MULTI » / VF + VOSTFR.

| Option | Principe | Dépendance | Disque | Risque | Avis |
|---|---|---|---|---|---|
| **A. HLS fMP4, pistes séparées** | ffmpeg écrit, sans ré-encoder, `video.mp4` (vidéo seule) + `audio-N.mp4` par piste (fMP4 à fichier unique), + des playlists `.m3u8` avec plages d'octets (`EXT-X-BYTERANGE`) ; hls.js lit, langue changée **instantanément** (`EXT-X-MEDIA TYPE=AUDIO`) | hls.js 1.7.3 (Apache 2.0, aucune dépendance, ~120 Ko gzip en version *light*) | vidéo une fois + audio ~5-10 % par piste | moyen : MSE, `blob:` dans `media-src` ; playlists à signer | **recommandé** |
| B. Une copie MP4 par piste audio | MP4 « faststart » natif avec une seule piste audio ; autre langue = autre copie (préparée à la demande) | aucune | **vidéo dupliquée par langue** | faible techniquement ; attente d'une préparation à chaque changement de langue | repli si A est refusée |
| C. Vidéo muette + `<audio>` synchronisé | deux éléments recalés en JavaScript | aucune | comme A | **élevé** : dérive, coupures, seek, arrière-plan mobile | non |
| D. MSE maison (sans hls.js) | réécrire ce que fait hls.js | aucune | comme A | élevé (beaucoup de code délicat) | non |

**Seek** :

- **Originaux lus tels quels** : requêtes Range sur l'endpoint existant. Un MP4 dont l'index `moov` est à la fin
  marche aussi : le navigateur lit la fin du fichier d'abord, une requête de plus.
- **Copies web** : segments de ~6 s découpés aux images clés. En copie sans ré-encodage, la coupe tombe sur les images
  clés d'origine (2 à 10 s en anime) ; en conversion, une image clé toutes les 6 s est imposée. hls.js demande les
  plages d'octets d'un **seul fichier par piste** : pas de milliers de petits fichiers sur le NAS, et le service
  « Range » existant suffit.
- **Lecture pendant la préparation** : la playlist est de type `EVENT` et grandit au fil de l'écriture. On peut donc
  commencer dès les premiers segments écrits ; le seek est limité à ce qui est prêt et la barre le montre.

Un MP4 « faststart » unique (option B) resterait possible pour la lecture directe d'un MP4 d'origine compatible : il
est lu nativement, sans hls.js.

## 3. Sous-titres

| Cas | Traitement | Coût |
|---|---|---|
| **SRT, WebVTT, mov_text** | extraits en **WebVTT** (`ffmpeg -map 0:s:N -c:s webvtt`), servis à part, `<track>` natif | quelques Ko, instantané |
| **ASS/SSA** (la majorité) | ASS d'origine extrait + **polices jointes du MKV** (`-dump_attachment:t ""`), rendus par **JASSUB** ; WebVTT dérivé en secours (sans styles) si JASSUB ne démarre pas | extraction : secondes ; navigateur : un fil de calcul |
| **PGS, VobSub** (images) | **message** : « Sous-titres en image : non affichables dans le navigateur. Utilisez l'application Android. » Option admin (10.3, plus tard) : incruster pendant une conversion complète | incrustation = conversion vidéo complète, même si la vidéo était lisible |

**JASSUB 2.5.18** (npm, publié le 2026-10-03), vérifié dans le paquet :

- **Licence** : enveloppe MIT. Bibliothèques compilées sous LGPL 2.1+ (libass), FreeType (FTL ou GPL 2), HarfBuzz
  (MIT), etc. Le `.wasm` est un fichier séparé et remplaçable, ce qui suffit pour la LGPL ; licences à lister dans
  « À propos ».
- **Poids** :
  - `jassub-worker-modern.wasm` : 2,2 Mo (0,9 Mo gzip) ;
  - worker : 80 Ko ;
  - police par défaut : 146 Ko (incluse, aucune requête externe).
  
  Le tout est chargé **seulement** à l'ouverture d'un épisode qui a de l'ASS.
- **4 petites dépendances** : `abslink`, `lfa-ponyfill`, `rvfc-polyfill`, `throughput`, licences à vérifier à
  l'installation.
- **Fonctionnement** : worker *module* chargé depuis notre origine (`new Worker(new URL(...), {type: 'module'})`), rendu
  OffscreenCanvas par WebGL (WebGPU ou 2D en repli).
- **Fils de calcul** : plusieurs seulement avec `SharedArrayBuffer`, donc avec les en-têtes COOP/COEP. **On ne les
  ajoute pas** : COEP casserait les affiches distantes (TMDB, AniList). JASSUB passe alors en **un seul fil**, ce qui
  suffit pour du sous-titrage (karaoké lourd : à mesurer en 10.2).
- **CSP** : instancier du WebAssembly exige `'wasm-unsafe-eval'` dans `script-src`. Ni `blob:`, ni domaine externe, ni
  `unsafe-eval`.
- **Recherche de polices sur Google Fonts** : facultative, **désactivée** (pas de ressource externe).

Polices : les polices jointes du MKV sont extraites avec les sous-titres (plafond par épisode, par exemple 30 Mo) et
servies à part. Une police manquante retombe sur la police par défaut.

## 4. Pipeline de préparation côté serveur

### 4.1 Classes web (calculées à la demande, à partir de l'analyse 9.1 et de ce que dit le navigateur)

| Classe | Condition | Travail | Ordre de grandeur |
|---|---|---|---|
| **W0 direct** | MP4/WebM, vidéo lisible par **ce** navigateur, son lisible, une seule piste audio (ou la bonne en premier), sous-titres texte ou aucun | aucun : original par Range ; sous-titres extraits à part | — |
| **W1 remux** | vidéo et son lisibles, mais MKV/AVI/OGM/TS, ou plusieurs pistes audio | copie HLS fMP4 sans ré-encodage | ~100-300× le temps réel ici ; limité par le disque sur le NAS |
| **W2 son** | vidéo lisible, son non lisible (AC3, E-AC3, DTS, TrueHD, Vorbis dans MP4…) | vidéo copiée, chaque piste audio en AAC | ~30× le temps réel ici |
| **W3 vidéo** | vidéo non lisible par ce navigateur (H.264 10 bits, HEVC sans décodeur, MPEG-4 ASP, MPEG-2, VC-1, WMV…) | conversion H.264 8 bits + AAC | §6 |

Une même source peut avoir **deux copies** : une W1 en HEVC pour un navigateur qui le décode, une W3 pour les autres.
Pour limiter le disque, une seule copie W3 suffit pour tous les navigateurs ; la copie W1 HEVC n'est faite que si un
navigateur capable la demande. L'API renvoie au navigateur la meilleure copie prête, ou l'état de la préparation.

### 4.2 Commandes (arguments passés sans shell par `ProcessRunner`, chemins venant de la base)

Remux (W1) et son seul (W2), une playlist par piste et une playlist maîtresse :

```
nice -n 19 ionice -c 3 ffmpeg -nostdin -y -i SRC \
  -map 0:v:0 -map 0:a:0 -map 0:a:1 \
  -c:v copy  -c:a copy                       # W2 : -c:a aac -b:a 160k -ac 2
  -tag:v hvc1                                # HEVC seulement (sinon Chrome refuse la piste)
  -f hls -hls_segment_type fmp4 -hls_flags single_file+independent_segments \
  -hls_playlist_type event -hls_time 6 \
  -master_pl_name master.m3u8 \
  -var_stream_map "v:0,agroup:aud a:0,agroup:aud,language:jpn,default:yes a:1,agroup:aud,language:fre" \
  OUT/s_%v.m3u8          # -> master.m3u8, s_0.m3u8 + s_0.m4s (vidéo), s_1/s_2 (audio)
```

Conversion (W3) : même chose, avec :

```
  -vf "scale='min(1920,iw)':-2:flags=bicubic,format=yuv420p" \
  -c:v libx264 -preset veryfast -tune animation -crf 21 -profile:v high -level 4.1 \
  -g 144 -keyint_min 48 -force_key_frames "expr:gte(t,n_forced*6)" -threads 2 \
  -c:a aac -b:a 160k -ac 2
```

- **Résolution plafond 1080p** : l'anime n'a presque rien au-delà ; un 4K éventuel est réduit.
- **CRF 21** + `tune animation` : bon compromis pour l'anime, qui se compresse bien. Une copie 1080p d'anime fera
  environ 1 à 2 Go/h.
- **Preset `veryfast`** : sur le DS923+ c'est la vitesse qui compte (§6). `faster` gagne ~10 % de taille et coûte
  ~25 % de temps en plus.
- **`-threads 2`** sur les 4 fils du R1600 : 2 restent libres pour servir les lectures (Range, disque) et la base.
- **Sous-titres** : extraits par une commande séparée et rapide (`-map 0:s:N -c:s ass|webvtt`), et polices jointes,
  pour toutes les classes y compris W0.
- **Vérification de la sortie** par ffprobe (durée à ±2 s de la source, pistes attendues) avant de marquer la copie
  prête, comme pour le remux 9.2.

### 4.3 Dossier, plafond, nettoyage

- **Dossier** : `/data/web/<clé>/` dans le conteneur. Sur le NAS : **`WEB_CACHE_PATH` dans `nas.env`**
  (défaut : `<dossier du projet>/web-cache` ; à mettre sur un volume qui a de la place). L'installateur le crée
  (`--web-cache DOSSIER`), la mise à jour l'ajoute aux installations existantes, l'assistant et Administration >
  Réglages l'affichent avec l'espace libre, la doc de déploiement explique comment le déplacer.
  - Clé = id du fichier + taille + date de la source : une source remplacée invalide la copie.
  - Écriture dans `<clé>.part/`, renommé à la fin : jamais une copie à moitié servie comme prête, sauf la lecture
    pendant préparation, qui passe par la playlist `EVENT`.
- **Plafond** :
  - réglage d'installation (assistant / Administration > Réglages) ;
  - par défaut **15 % de la taille du volume, 200 Go au plus** ;
  - une réserve fixe (10 Go) reste toujours libre sur le volume.
  - Ordre de grandeur : 200 Go ≈ 150 à 200 épisodes 1080p remuxés, ou 400 à 600 épisodes 720p.
- **Nettoyage** :
  - **copies sans conversion** (remux HLS, refaites en une minute environ) : effacées après **48 h sans lecture**
    (vérifié toutes les 10 min), sauf si une conversion prête du même fichier s'en sert pour ses sous-titres
    (décision du 2026-10-11) ; les conversions, chères à refaire, ne suivent que le plafond ;
  - **la nuit**, les copies orphelines (source disparue, changée), puis les moins récemment lues jusqu'à repasser
    sous 85 % du plafond ;
  - **à la demande**, quand une nouvelle copie a besoin de place.
  - Jamais une copie lue dans les 3 dernières heures (un épisode mis en pause puis repris), jamais un travail en cours.
  - Même mécanique que le cache de remux (9.2), qui garde son propre plafond.
- **Disque presque plein** : aucune copie HLS ne démarre si la réserve n'est pas garantie (sous-titres et polices
  seuls, quelques Mo pour lire l'original, passent quand même). Un fichier que le navigateur ne lirait pas de toute
  façon reçoit sa vraie raison, pas « plus de place ». La demande répond « le serveur manque
  de place » (comme `REMUX_CACHE_FULL`) et l'admin le voit.

### 4.4 Reprise après redémarrage

File persistante en base (table `web_job` : source, clé, classe, priorité, état, tentatives, erreur, dates, taille).

- **Au démarrage** :
  - un travail `RUNNING` repasse en `QUEUED`, et son dossier `.part` est effacé (ffmpeg ne sait pas reprendre un
    fichier entamé de manière fiable) ;
  - les dossiers sans ligne en base sont supprimés ;
  - les lignes `READY` dont le dossier a disparu repassent en attente si elles sont encore utiles.
- **Échecs** : 3 tentatives au plus, avec un délai croissant ; ensuite `FAILED`, visible avec la fin du journal
  ffmpeg, et alerte ntfy si plusieurs échecs s'enchaînent.

### 4.5 Changements dans l'image Docker

L'ffmpeg actuel (7.1.5, ARCHITECTURE §22.1) n'a **aucun encodeur audio/vidéo** et est compilé **sans assembleur**
(`--disable-x86asm`). Pour 10.3 :

- `--enable-x86asm` (nasm au moment de la compilation) : sans lui, le décodage et x264 sont plusieurs fois plus lents.
- `--enable-encoder=aac` : encodeur natif d'ffmpeg, LGPL.
- `--enable-libx264 --enable-gpl --enable-encoder=libx264` : x264 compilé depuis sa source officielle (empreinte
  vérifiée), statique. Le binaire `ffmpeg` devient GPL 2+ : l'image est distribuée à l'ami, donc on fournit les
  sources (versions et URL) sur demande, comme pour l'APK (GPL 3). **Décidé** : noté dans `docs/DEPLOIEMENT.md` et
  dans « À propos » du site.
- `--enable-muxer=hls` et les filtres `scale`, `format`, `aresample`.
- **Impact** :
  - image +~4 Mo ;
  - compilation plus longue, +~3 min sur un PC (une seule fois sur le NAS, cache Docker) ;
  - les tests « RealFfmpeg » existants restent valables.

### 4.6 Charge du NAS (2 à 3 lectures simultanées)

- **Une seule tâche lourde à la fois** : W2 ou W3. Les remux W1 et l'extraction des sous-titres passent en plus, un
  à la fois aussi.
- **Plafonds** : `nice 19`, `ionice -c 3`, `-threads 2`.
- **Lecture en cours** : l'endpoint de streaming note la dernière requête par lecteur, et « au moins une lecture dans
  les 60 dernières secondes » = lecture en cours.
  - Les tâches **préventives** sont **suspendues** (SIGSTOP / SIGCONT, sans perdre le travail fait) pendant les
    lectures.
  - Une tâche **à la demande** continue, car quelqu'un attend devant l'écran, mais bridée. **Décision D6.**
- **Aucune conversion en direct** : une lecture ne lance jamais ffmpeg sur son propre flux. Elle lit une copie, au
  besoin pendant que celle-ci s'écrit.
- **L'analyse 9.1 et le test à blanc** restent en pause tant que la file web travaille (règle actuelle étendue).

## 5. Déclenchement et notification

**À la demande** :

1. Clic sur un épisode → `GET /api/episodes/{id}/web-playback?caps=…` (capacités du navigateur).
2. Copie prête ou W0 : URL signée, la lecture commence.
3. Sinon : réponse 202, la copie entre en file avec une priorité haute, et le lecteur affiche « Préparation pour le
   navigateur… », la position dans la file et une estimation. L'estimation vient de la durée de l'épisode et de la
   **vitesse mesurée** des derniers travaux de même classe sur ce NAS ; la valeur de départ est celle de §6.
4. La lecture démarre dès que **2 minutes** d'avance sont écrites et que la vitesse de préparation dépasse la vitesse
   de lecture. Sinon elle démarre à la fin, avec un message « Votre épisode sera prêt dans ~X min ; vous pouvez
   fermer cette page ».

**Préventif** (planificateur interne, la nuit, réglable) :

1. Les **2 épisodes suivants** de chaque animé « en cours », tous comptes, d'après la progression existante.
2. Les épisodes ajoutés depuis 7 jours.
3. Ce que l'admin a demandé (« Préparer l'animé pour le navigateur »).

Par défaut, le préventif ne fait que W1 et W2 (peu coûteux). **W3 préventif est désactivé par défaut**, car c'est
plusieurs heures de calcul par nuit (§6) ; il s'active dans les réglages. Arrêt à 7 h même au milieu d'un travail
(suspendu puis reporté).

**Prévenir que c'est prêt** :

- Dans le lecteur ouvert : bascule automatique.
- Sur la fiche de l'animé et dans « Continuer » : pastille « Prêt pour le navigateur » ou « En préparation ».
- Pas de notification du navigateur : il faudrait une permission et un service worker, pour un gain faible ici.
- ntfy reste pour l'admin (échecs répétés, disque).

## 6. Mesures sur cette machine, extrapolation prudente pour le DS923+

Machine de mesure : VM à **2 vCPU Intel Xeon 2,8 GHz**, 7 Go, ffmpeg 6.1 d'Ubuntu (**avec** assembleur), `nice 19`.
Sources : extraits de **60 s** de *Big Buck Bunny* (Blender, CC BY ; image de synthèse 3D, **plus difficile à
compresser que l'anime 2D** : les chiffres sont donc pessimistes), ramenés à 23,976 i/s, son AC3 5.1. Une source
par cas : 1080p H.264 8 bits, 1080p H.264 10 bits, 1080p HEVC 10 bits, SD 720×404 H.264, SD 640×360 Xvid + MP3 en
AVI. Script : 5 sources × 7 commandes, toutes mesurées (vitesse = durée de l'extrait / temps de calcul ; « 2× » =
un épisode de 24 min en 12 min).

| Source | Remux HLS (copie) | Son seul → AAC | x264 veryfast, 2 fils | faster, 2 fils | medium, 2 fils | veryfast, 1 fil | veryfast → 720p, 2 fils |
|---|---|---|---|---|---|---|---|
| SD H.264 8 bits | 293× | 31× | **8,1×** | 6,2× | 4,0× | 6,4× | 8,9× |
| SD Xvid (AVI) | 274× | 35× | **11,1×** | 7,5× | 4,5× | 7,9× | 10,5× |
| 1080p H.264 8 bits | 239× | 30× | **2,0×** | 1,2× | 0,75× | 1,4× | 2,7× |
| 1080p H.264 10 bits | 242× | 31× | **1,8×** | 1,2× | 0,72× | 1,2× | 2,9× |
| 1080p HEVC 10 bits | 294× | 30× | **1,6×** | 1,2× | 0,71× | 1,3× | 2,5× |

Tailles des copies (CRF 21, `tune animation`) : 1080p ≈ **1,1 Go/h** en `veryfast` (1,3 Go/h en `faster` : pas plus
petit sur cette source) ; SD ≈ 0,35 Go/h. Piste AAC 160 kb/s stéréo ≈ 70 Mo/h. Commande HLS à plusieurs pistes audio
(§4.2) vérifiée : playlist maîtresse avec `EXT-X-MEDIA` jpn/fre, un fichier par piste, plages d'octets.

**Extrapolation pour le DS923+ — estimation, NON mesurée.** Ryzen R1600 : 2 cœurs / 4 fils Zen 1 (14 nm),
2,6 GHz (3,1 GHz en pointe). Hypothèses prudentes : un cœur Zen 1 vaut 0,6 à 0,9 cœur de ce Xeon. Avec 2 fils sur
le NAS, des lectures en parallèle et `nice 19`, je divise les vitesses par **2** ; l'anime 2D, plus facile, n'est pas
compté en bonus. Le remux est limité par le disque (lecture + écriture de la taille du fichier, ~100 Mo/s sur les
disques du NAS, à mesurer).

| Travail | Ici (mesuré) | DS923+ (estimé, prudent) | Épisode de 24 min sur le NAS |
|---|---|---|---|
| Remux 1080p (~1,2 Go) | > 200× | limité par le disque | **20 s à 1 min** |
| Son seul → AAC | ~30× | ~15× | **~2 min** |
| Vidéo SD → H.264 | 8-11× | ~4-5× | **5 à 6 min** |
| Vidéo 1080p → H.264 1080p | 1,6-2,0× | **~0,8-1×** | **25 à 30 min** (jusqu'à 45 min si lectures en cours) |
| Vidéo 1080p → H.264 720p | 2,5-2,9× | ~1,3-1,5× | **16 à 20 min** |

Conséquences :

- **W1 et W2 sont bon marché** : à la demande, l'utilisateur attend de quelques secondes à 2 minutes, et la lecture
  peut démarrer pendant la préparation.
- **W3 en 1080p tourne à peu près en temps réel sur le NAS** : la lecture pendant la préparation est limite (attente
  conseillée), et une nuit de 6 h convertit ~12 épisodes 1080p. D'où W3 préventif désactivé par défaut (D8) et le
  **choix du plafond W3 (D5) : 1080p (meilleure image) ou 720p** (~1,5× plus rapide, lecture pendant la préparation
  fiable, différence peu visible sur téléphone et portable).
- `medium` est exclu (plus lent que le temps réel). `faster` ne gagne rien ici.
- Il faut **mesurer sur le NAS en D2** : remux et W3 d'un vrai épisode HEVC 10 bits, avec ffmpeg compilé comme en
  10.3.1. Les estimations seront remplacées par la vitesse réellement observée, que le serveur mémorise.

## 7. Interface du lecteur web

Même direction visuelle que le lecteur Android et le Polish (DESIGN §5) : image plein cadre, surcouche sombre en
dégradé, commandes rondes, texte blanc, accent turquoise, icônes Material Symbols déjà copiées, police Figtree.

- **Page** : route `/watch/:episodeId` en plein écran dans la page, avec retour vers la fiche. Pas de mini-lecteur :
  la navigation pendant la lecture compliquerait la progression et le focus, pour peu d'usage.
- **Commandes** :
  - retour, titre et épisode en haut ;
  - au centre : −10 s / lecture-pause / +10 s ;
  - en bas : barre de progression avec la partie chargée, et la partie **préparée** pendant une préparation ;
  - temps écoulé / durée ;
  - volume ;
  - bouton « Audio et sous-titres », qui ouvre un panneau listant les langues, « Désactivés » compris ;
  - plein écran.
  
  Masquées après 3 s sans mouvement (jamais pendant la navigation au clavier ou un panneau ouvert).
- **Clavier** :

  | Touche | Action |
  |---|---|
  | Espace / K | lecture / pause |
  | ← / → | ±10 s (J / L aussi) |
  | ↑ / ↓ | volume |
  | M | muet |
  | F | plein écran |
  | C | sous-titres oui / non |
  | Maj+N | épisode suivant |
  | Échap | quitte le plein écran puis la page |
  
  Pas de raccourci quand le focus est dans un champ.
- **Mobile (navigateur)** : un toucher affiche ou masque les commandes ; double toucher à gauche ou à droite = ±10 s ;
  plein écran avec orientation paysage quand l'API le permet.
- **Reprise** : position enregistrée (`PUT /api/episodes/{id}/progress`, toutes les 10 s, à la pause, à la fermeture
  avec `fetch(..., {keepalive: true})`) ; reprise automatique, ou « Reprendre à 12:34 / Depuis le début » au-delà de
  5 min.
- **Épisode suivant** : à la fin (ou à 90 %, au début du générique si la durée est connue), carte « Épisode suivant »
  avec compte à rebours de 10 s et « Annuler ». Il passe par la même préparation si besoin.
- **États** :
  - chargement (indicateur sur l'image) ;
  - préparation (texte, barre de progression, temps estimé, position dans la file, « Annuler » qui garde la place
    pour une autre fois) ;
  - reconnexion ;
  - erreur avec « Réessayer » et « Détails » (comme Android, sans URL ni jeton) ;
  - **format non pris en charge** : « Ce fichier ne peut pas être préparé pour le navigateur (raison). Utilisez
    l'application Android. »
  - sous-titres en image : message sur l'image, une seule fois.
- **Accessibilité** :
  - tout est atteignable au clavier, avec le focus visible ;
  - les boutons ont des noms en français ;
  - la barre de progression est un `role=slider` avec la valeur parlée (« 12 minutes 34 sur 24 minutes ») ;
  - les états sont annoncés par une zone `aria-live` ;
  - contrastes vérifiés par axe ;
  - texte agrandi à 200 % sans perte ;
  - `prefers-reduced-motion` respecté ;
  - sous-titres WebVTT stylés par `::cue` lisible ; JASSUB garde les styles du fichier.

## 8. Découpage

### 10.2 — lecteur web (sans conversion lourde)

| Bloc | Contenu | Risque |
|---|---|---|
| 10.2.1 [serveur][sécurité] | API `GET /api/episodes/{id}/web-playback` (capacités, décision W0/W1, état) ; URL signées pour copies web, playlists réécrites avec signatures, sous-titres et polices ; journal sans `sig=` ; tests (signature, chemins, permissions) | moyen (nouvelle forme d'URL signée) |
| 10.2.2 [serveur] | Extraction des sous-titres et polices (cache, plafond par épisode), WebVTT dérivé ; remux W1 en HLS fMP4 via la file existante (un à la fois), lecture pendant l'écriture | moyen (MKV exotiques : test à blanc sur le NAS) |
| 10.2.3 [sécurité] web | hls.js + CSP `media-src blob:` ; lecteur `/watch/:id` : commandes, clavier, plein écran, panneau pistes, progression et reprise, épisode suivant, états | moyen |
| 10.2.4 [sécurité] web | JASSUB + CSP `'wasm-unsafe-eval'`, polices, repli WebVTT ; mesure CPU sur un karaoké lourd | moyen (perf sur petits PC) |
| 10.2.5 | Tests : unitaires (décision, playlist signée, raccourcis, progression), bout en bout Playwright **ou** jsdom + serveur réel sur un MKV généré (H.264 + 2 audio + ASS + polices), captures d'écran, accessibilité | — |

Tests de bout en bout dans un vrai navigateur : la phase P2 a proposé Playwright (option non retenue alors). Pour un
lecteur vidéo, jsdom ne lit rien : **je propose `@playwright/test` en dépendance de dev** (Apache 2.0, Chromium déjà
présent dans l'environnement de test). Il ne touche pas l'image de production. Firefox n'y serait pas testé
automatiquement.

### 10.2 réalisé (2026-10-10)

Commits : 10.2.1 `bb96020`, 10.2.2 `99b408e`, 10.2.3 `bad156c`, 10.2.4 `6439397` (JASSUB), 10.2.5 (essais de bout en bout et corrections).

**API**
- `GET /api/episodes/{id}/web-playback?caps=h264,aac,…` (connecté) : ce que le navigateur sait lire (détecté par
  `MediaSource.isTypeSupported`, liste blanche côté serveur) → réponse :
  - `READY` + `mode` `DIRECT` (original, élément vidéo natif) ou `HLS` (copie fMP4 sans ré-encodage, hls.js) ;
  - `202 PREPARING` + `Retry-After` (phase, position dans la file, progression, attente estimée ; la copie HLS est
    donnée avant la fin dès que ses premiers segments existent, `growing: true`) ;
  - `UNSUPPORTED` + raison en français (dès que l'analyse est faite, même si le cache est plein) ;
  - erreurs : `409 WEB_PREP_FAILED`, `503 WEB_CACHE_FULL`, `503 WEB_PREP_UNAVAILABLE`.
  - Toujours : pistes audio (japonais d'abord), sous-titres (ASS + WebVTT dérivé), polices jointes, épisode, épisode
    suivant, position de reprise, décalage des sous-titres (copie HLS qui commence à 0).
- `GET|HEAD /api/stream/{fileId}/web/{clé}/{nom}?a=&u=&exp=&sig=` : `master.m3u8` (généré), `s_N.m3u8` (réécrits
  avec une signature par segment, `no-store`), `s_N.m4s`, `sub_N.ass|vtt`, `font_N.ttf|otf` (Range).
- Progression : l'API existante (`PUT /api/episodes/{id}/progress`), toutes les 10 s, à la pause, en quittant la page.

**Lecteur** (`/regarder/:id`, liens depuis l'accueil, « Reprendre », la fiche et chaque épisode) : lecture/pause,
±10 s, barre de temps, volume, muet, plein écran (paysage sur téléphone), panneau « Audio et sous-titres » (choix
mémorisés sur l'appareil), reprise, épisode suivant avec compte à rebours de 10 s annulable, états (préparation,
non lisible, erreur avec « Réessayer »). Clavier : Espace/K, ←/→ (10 s), J/L, ↑/↓ volume, M, F, C (sous-titres), Maj+N (épisode suivant),
Échap. Lecteur d'écran : boutons nommés, temps parlé, annonces (`aria-live`), panneau en boutons radio.
Sous-titres ASS : JASSUB (styles, positions, polices du fichier) ; s'il ne démarre pas (navigateur ancien, 20 s
sans réponse), WebVTT avec un message.

**Règles « navigateur » v3** (fiche, Administration > Médias) : le conteneur ne compte plus (copie HLS) ; à
convertir : H.264 10 bits, MPEG-4 ASP (Xvid/DivX), son DTS/TrueHD/… ; « HEVC : selon le navigateur » ; sous-titres
seulement en image (PGS/VobSub) : non affichables. Les fichiers déjà analysés sont reclassés sans nouvelle analyse.

**Dépendances ajoutées (web)**

| Paquet | Licence | Rôle |
|---|---|---|
| hls.js 1.7.3 | Apache-2.0 | lecture de la copie HLS (chargé seulement pour une copie HLS) |
| jassub 2.5.18 | MIT (libass ISC, FreeType FTL, HarfBuzz « Old MIT », fribidi LGPL-2.1 compilés en WebAssembly) | sous-titres ASS |
| ↳ abslink, lfa-ponyfill, throughput | Apache-2.0, MIT, MIT | dépendances de jassub |
| ↳ rvfc-polyfill | **GPL-3.0** → **remplacé** par un module vide local (`web/vendor/rvfc-polyfill-noop`, MIT, `overrides` npm) : Chrome, Edge, Firefox récents ont `requestVideoFrameCallback` | — |
| esbuild 0.28.2 (dev) | MIT | construit le worker de JASSUB (`scripts/jassub-assets.mjs`) |
| @playwright/test 1.64.0 (dev) | Apache-2.0 | essais de bout en bout |

Le worker et les fichiers WebAssembly de JASSUB sont servis par le site (`/jassub/`), aucune ressource externe.
« À propos » liste ces licences et la mention « code source disponible sur demande » (fribidi LGPL, ffmpeg GPL en
10.3).

**Image serveur** : ffmpeg 7.1.5 avec le muxer `hls` et le décodeur `movtext` (sous-titres des MP4 : le nom
`mov_text` était ignoré sans erreur par `configure`, trouvé par l'essai de bout en bout).

**Essais**
- Serveur : `WebDecisionTest`, `WebSignatureTest`, `WebPlaybackTest` (outils simulés : signatures, permissions,
  cache plein, trop gros, reprise après redémarrage), `WebPlaybackRealTest` (vrai ffmpeg : MKV H.264 + 2 AAC + ASS +
  police, MP4 mov_text, HEVC `hvc1`, lecture avant la fin).
- Web : unitaires (logique du lecteur, détection des formats, page du lecteur, repli ASS → WebVTT).
- Bout en bout : `scripts/test/e2e-web.sh` (vraie installation : nginx et sa CSP, Caddy, serveur, ffmpeg de
  l'image ; fichiers générés) puis `web/e2e/player.spec.ts` dans **Google Chrome** (le Chromium de Playwright ne
  lit ni H.264 ni AAC) : MKV en HLS + deux pistes + ASS rendu par JASSUB + clavier + progression et reprise ; MP4 lu
  tel quel + WebVTT ; AVI Xvid « non lisible » ; épisode suivant ; aucune violation de CSP ; axe sans erreur grave.
  Captures : `web/e2e/screenshots/` (non versionnées).

**Testé automatiquement** : Chrome 155 (Linux, Playwright). **À tester à la main** : Firefox, Edge, Chrome Android
(S24), avec de vrais fichiers. **Non vérifié** : HEVC (pas de décodeur matériel ici), vitesse de préparation sur le
DS923+, karaoké ASS lourd (charge CPU de JASSUB), Firefox Android.

### 10.3 — conversion et file d'attente

| Bloc | Contenu |
|---|---|
| 10.3.1 [serveur] | Image : ffmpeg avec asm, AAC, libx264, HLS ; test RealFfmpeg étendu ; licences |
| 10.3.2 [serveur] | Table `web_job`, service de file (une tâche lourde, plafonds, nice/ionice, SIGSTOP pendant les lectures), W2 et W3, vérification ffprobe, reprise au démarrage |
| 10.3.3 [serveur] | Cache web : plafond, réserve, nettoyage de nuit et à la demande ; planificateur interne minimal (fenêtre de nuit) ; préventif |
| 10.3.4 web | États dans le lecteur (position, estimation, lecture en avance), pastilles fiche et accueil ; admin Médias : en cours, en attente, échecs, espace, relancer, annuler, « Préparer l'animé » |
| 10.3.5 [serveur] | Alerte ntfy (échecs répétés, disque) — **dépend de ntfy (D1b)** : à faire avec D1b si ntfy n'existe pas encore |
| 10.3.6 | Tests : arrêt du serveur en pleine conversion, disque presque plein (plafond simulé), 2 lectures pendant une conversion (suspension), échecs répétés |

### 10.3 réalisé (2026-10-10)

Commits : 10.3.1 `ae8e4be` (image), 10.3.2 `c532ecf` (conversions), 10.3.3 `d516cbb` (nuit), 10.3.4 `807a3d0` (interface et
administration), 10.3.6 (essais de bout en bout, docs). **10.3.5 (alerte ntfy) reporté à D1b** : ntfy n'existe pas
encore ; les échecs et le cache sont visibles dans Administration › Médias › Lecteur web.

**Image (10.3.1)** : ffmpeg 7.1.5 avec assembleur (nasm), encodeurs AAC et libx264, filtres `scale`, `format`,
`aformat`, `aresample`. Écart avec §4.5 : x264 vient du **paquet Ubuntu** (archive signée) au lieu d'une compilation
depuis la source ; Ubuntu 26.04 ne fournit plus la version statique, la bibliothèque partagée est recopiée dans
l'image finale et vérifiée à la construction (`ffmpeg -encoders`). Binaire GPL 2+ : DEPLOIEMENT §3, « À propos ».
Étape `ffmpeg` du `Dockerfile` construite ici dans Docker (avec réseau) : c'est elle qui sert aux images d'essai.

**Conversions (10.3.2)** : préparation `CONV` (une seule copie pour tous les navigateurs) à côté de `BASE` (sous-titres,
polices, copie sans conversion) :

- vidéo H.264 8 bits copiée (« W2 », son seul converti : minutes), sinon x264 `veryfast`, CRF 21, `-tune animation`,
  2 fils, image clé toutes les 6 s, hauteur au plus 720 (réglable en 1080) (« W3 ») ;
- chaque piste audio copiée (AAC, MP3) ou convertie en AAC stéréo 160 kb/s ;
- sous-titres et polices toujours ceux de la préparation de base.

Quand un navigateur ne lit ni la vidéo ni le son (et qu'il lit H.264 + AAC), `web-playback` met la conversion en file
et répond 202 avec `preparing.conversion = true` ; lecture dès 2 minutes d'avance si ffmpeg va plus vite que la
lecture. Deux files (une préparation de base et une conversion à la fois), priorités 0 = utilisateur, 1 = admin,
2 = nuit. Une conversion admin ou de nuit est **suspendue (SIGSTOP) pendant les lectures** (une requête de lecture
dans les 60 dernières secondes) puis reprend (SIGCONT) ; elle **cède la place** (arrêt, remise en file, essai non
compté) à une demande d'utilisateur. Échecs : 3 essais (10 puis 20 min d'écart), ensuite « Relancer » dans
l'administration. Vitesse observée gardée par sorte (son seul, vidéo SD, 720p, 1080p) : elle remplace les estimations
de §6 pour annoncer l'attente.

**Nuit (10.3.3)** : planificateur interne (toutes les 10 min ; une fois par nuit entre 1 h et 7 h, heure du serveur) :
nettoyage (sources disparues ou changées, puis les moins lues jusqu'à 85 % du plafond, jamais une lue depuis 3 h) et
préventif (2 épisodes suivants des animés regardés depuis 30 jours, ajouts des 7 derniers jours, 200 au plus) ;
conversion vidéo préventive seulement si l'admin l'a permis (D8). Le travail de nuit n'est pris que la nuit ; à 7 h
une conversion de nuit est arrêtée et reprendra la nuit suivante (perte du travail fait : ffmpeg ne reprend pas).

**Interface (10.3.4)** :

- lecteur : « Conversion de la vidéo pour le navigateur… », attente en minutes ou heures, « vous pouvez fermer cette
  page » ;
- pastilles « Prêt pour le navigateur » / « En préparation » sur la fiche (épisodes affichés) et dans « Continuer »
  (`GET /api/web-status?episodes=…`, 100 au plus) ; « À convertir » remplace « Android conseillé » ;
- Administration › Médias › **Lecteur web** : cache (place, plafond, disque, dossier du NAS), en cours (étape,
  avancement, vitesse, « en pause : lecture en cours »), file, échecs avec « Relancer », « Annuler » / « Retirer »,
  « Nettoyer le cache maintenant », vitesses observées, et les **réglages** (hauteur 720/1080, préventif, conversion
  vidéo la nuit) : placés ici plutôt que dans Réglages (§0 D5) pour les avoir à côté de leur effet ;
- « Préparer l'animé pour le navigateur » (admin) sur la fiche et dans la liste des médias.
- API admin : `GET /api/admin/web`, `PUT /api/admin/web/settings`, `POST /api/admin/web/jobs/{fichier}/{BASE|CONV}/retry`,
  `DELETE /api/admin/web/jobs/{fichier}/{BASE|CONV}`, `POST /api/admin/web/anime/{id}/prepare`, `POST /api/admin/web/cleanup`.

**Essais** : vrai ffmpeg (`WebConvertRealTest` : Hi10P 1080p → H.264 720p + AAC avec l'ASS de la préparation de
base, AC3 → vidéo copiée + AAC, Xvid, suspension vue dans `/proc` puis reprise, conversion admin qui cède la place),
faux outils (`WebPlaybackTest` : 3 échecs puis plus d'essai, redémarrage en pleine conversion ; `WebNightTest` :
préventif, jour/nuit, conversion vidéo seulement si permise, nettoyage ; `WebAdminTest` : droits, réglages,
préparer, relancer, annuler, pastilles), web (section admin, pastilles, carte de conversion), bout en bout dans
Chrome (`scripts/test/e2e-web.sh`, 7 essais : AVI Xvid et MKV Hi10P convertis par le ffmpeg de l'image puis lus,
carte « Conversion », pastille « Prêt » sur la fiche, section admin, son seul « non lisible »).

**Ajout du 2026-10-11** :
- copies sans conversion effacées après 48 h sans lecture (§4.3) ;
- **reprise au milieu d'une copie refaite** : la copie en cours n'est servie que lorsqu'elle couvre la position de
  reprise + 12 s. Le lecteur annonce « La lecture reprendra à 12:34 dès que cette partie sera prête » et propose
  « Lire depuis le début » (déjà prêt). Le lecteur peut donner sa position de départ (`web-playback?at=`, 0 = depuis le
  début) ; sinon le serveur applique la règle de reprise du lecteur.

**Non vérifié** : la reprise au milieu d'une copie en cours dans un vrai navigateur (hls.js et une playlist qui grandit :
essayé seulement par tests unitaires, la copie d'essai se fait trop vite pour l'observer) ; vitesse réelle sur le DS923+ (à lire dans Administration › Médias › Lecteur web après quelques
conversions), charge du NAS pendant une conversion avec 2 à 3 lectures, comportement sur un vrai épisode de 24 min
(ici des extraits de 4 à 40 s), Firefox et Chrome Android.

### Risques

- **Vitesse du DS923+** : non mesurée (§6). Si W3 est trop lent, on se limite au remux et au son, et la vidéo
  non lisible renvoie vers Android, comme aujourd'hui.
- **Disque** : copie ≈ taille de la source pour W1. Le plafond est indispensable ; sans lui, tout le catalogue MKV
  doublerait.
- **Variété des MKV** (horodatages, pistes) : test à blanc sur le vrai catalogue avant d'ouvrir à tous.
- **Firefox** : HEVC seulement sous Windows ; JASSUB en un seul fil.
- **Navigateurs mobiles** : le plein écran en paysage dépend du navigateur. Firefox Android n'est pas testable ici.

## 9. Changements de sécurité (S1 à S7 acceptés le 2026-10-10 ; rien d'autre sans accord)

| # | Changement | Pourquoi | Ce que ça ouvre |
|---|---|---|---|
| S1 ✅ | `script-src 'self' 'wasm-unsafe-eval'` | JASSUB instancie du WebAssembly | Autorise **la compilation de WebAssembly** par nos scripts ; pas d'`eval` JavaScript, pas de script externe. Un attaquant qui injecterait déjà du script pourrait aussi lancer du WASM (pas de nouveau point d'entrée) |
| S2 ✅ | `media-src 'self' blob:` | hls.js donne au `<video>` une source MediaSource (`blob:`) | Les `blob:` sont créés par nos scripts dans la page ; aucun contenu distant nouveau |
| S3 ✅ | Pas de `worker-src blob:` : workers de hls.js (`workerPath`) et de JASSUB chargés comme fichiers de notre origine | Éviter les workers `blob:` | Rien de plus (`'self'` suffit) |
| S4 ✅ | Pas d'en-têtes COOP/COEP | Ils casseraient les images distantes ; JASSUB s'en passe (un fil) | — |
| S5 ✅ | Nouvelles URL signées : `/api/stream/{fileId}/web/{clé}/{fichier}` (playlists `.m3u8`, fichiers `.m4s` de chaque piste), sous-titres `/…/sub_{n}.ass|vtt`, polices `/…/font_{n}.ttf|otf` (liste blanche de noms) | Copies web et sous-titres | Même signature HMAC et même durée que l'existant ; **liée au fichier et au nom de la ressource** (une signature n'ouvre pas une autre copie) ; noms de fichiers générés par le serveur seulement (aucun chemin client) |
| S6 ✅ | Playlists `.m3u8` réécrites à la volée avec une signature par ressource | hls.js redemande chaque ressource | Les playlists contiennent des signatures : `Cache-Control: no-store`, jamais journalisées (`sig=` masqué, comme aujourd'hui) |
| S7 ✅ | ffmpeg avec encodeurs (libx264, AAC) | Conversion | Plus de code exécuté sur des fichiers : processus non root, sans shell, sans réseau (`--disable-network` conservé), délai maximal |

## 10. Sources (lues le 2026-10-10)

- Firefox MKV : bug Mozilla 1422891 (« enabled by default since Firefox 145 ») — https://bugzilla.mozilla.org/show_bug.cgi?id=1422891
- Chrome HEVC (matériel seulement, Chrome 105) — https://bitmovin.com/blog/google-adds-hevc-support-chrome/
- Firefox 134 HEVC Windows matériel — https://lecrabeinfo.net/?p=1681640
- `audioTracks` désactivé par défaut (Chrome, Firefox, Edge récents) — https://caniuse.com/audiotracks
- JASSUB (MIT, worker, OffscreenCanvas, COOP/COEP facultatifs) — https://github.com/ThaUnknown/jassub ; paquet npm `jassub@2.5.18` examiné (licences, tailles, workers)
- hls.js 1.7.3 (Apache 2.0, aucune dépendance) — paquet npm examiné (tailles, workers, `createObjectURL`)
- AC-3 retiré de Windows 11 24H2 — https://www.elevenforum.com/t/ac-3-dolby-digital-codec-no-longer-included-with-windows-11-version-24h2.25597/post-467830
