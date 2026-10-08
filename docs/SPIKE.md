# Spike vidéo (phase 0)

**But** : savoir si les vrais fichiers du NAS se lisent tels quels (« Direct Play », sans conversion) sur un téléphone Android, avant de construire le reste.

> **Spike retiré le 2026-10-08** (fin de la phase Polish P3) : l'app `android/spike/` et l'endpoint sans authentification `/api/dev/*` (`SpikeStreamResource`, `DevMediaIndex`, variables `DEV_SPIKE_STREAM_ENABLED` et `DEV_MEDIA_PATH`) n'existent plus. Ce document reste comme **compte rendu** : codecs, protocole et résultats (§6 à §9). Pour tester un fichier aujourd'hui : l'app Android (`android/README.md`) et l'onglet *Médias* de l'administration (analyse ffprobe, remux). `ByteRange` et `VideoMediaTypes`, nés ici, servent au streaming définitif (`/api/stream/…`).

## 6. Connaître les codecs d'un fichier

```powershell
ffprobe -v error -show_entries stream=index,codec_type,codec_name,profile,pix_fmt,channels:stream_tags=language -of compact "dev-media\Fichier.mkv"
```

Pour comprendre la sortie :
- `codec_name=hevc` + `pix_fmt=yuv420p10le` → HEVC **10 bits** (`yuv420p` sans `10` = 8 bits) ;
- `codec_name=h264` → H.264 / AVC ;
- audio `aac`, `opus`, `flac`, `ac3`, `eac3`, `dts`, `truehd` ;
- sous-titres `ass` / `subrip` (SRT) / `hdmv_pgs_subtitle` (images).

MediaInfo donne les mêmes informations sans ligne de commande.

## 7. Protocole pour chaque fichier

1. Lancer la lecture et noter le temps approximatif avant la première image.
2. Regarder ~30 s : image fluide ? son synchronisé ?
3. **Seek** : sauter au milieu, puis vers la fin, puis revenir au début. La reprise doit se faire en quelques secondes.
4. Ouvrir la liste des pistes : noter les codecs et toute piste `NON SUPPORTÉE`.
5. Facultatif : ouvrir la même URL dans Chrome sur le PC pour se faire une idée du navigateur.

## 8. Grille de résultats

Téléphone testé : Samsung Galaxy S24 (SM-S921B/DS), version d'Android non relevée.
Date : 29/09/2026. Codecs relevés avec `ffprobe`.

> **Portée limitée.** Ces 3 fichiers sont **synthétiques** (générés pour le test, 60 s, audio mono) et **sans sous-titres**. Ils valident la chaîne technique (Range Requests, réseau, ExoPlayer) et le décodage de ces codecs sur ce téléphone, pas la compatibilité avec la vraie bibliothèque.

| # | Fichier | Conteneur | Codec vidéo | Codec audio | Image OK | Son OK | Seek OK | Remarques |
|---|---|---|---|---|---|---|---|---|
| 1 | h264.mp4 | MP4 | H.264 High, 8 bits, 1280×720 | AAC-LC | Oui | Oui | Oui | Synthétique, sans sous-titres |
| 2 | hevc10bit.mkv | MKV | HEVC Main 10 (10 bits), 1280×720 | FLAC | Oui | Oui | Oui | Synthétique, sans sous-titres. HEVC 10 bits et FLAC décodés nativement |
| 3 | test.avi | AVI | MPEG-4 Part 2 Simple (FMP4), 640×480 | MP3 | Oui | Oui | Oui | Synthétique, sans sous-titres |
| 4 | | | | | | | | |
| 5 | | | | | | | | |
| 6 | | | | | | | | |
| 7 | | | | | | | | |
| 8 | | | | | | | | |
| 9 | | | | | | | | |
| 10 | | | | | | | | |

**Conclusion provisoire** : Direct Play suffit pour ces codecs sur un Galaxy S24. Pas de transcodage à prévoir à ce stade.

**Reste à faire** avant de considérer la compatibilité comme acquise :
- tester **5 à 10 vrais fichiers** de la bibliothèque du NAS (vrais encodages, pistes audio multiples, AC3/E-AC3/DTS/TrueHD éventuels) ;
- tester sur le **téléphone du propriétaire du NAS** (le S24 est haut de gamme : un téléphone plus ancien peut ne pas décoder le HEVC 10 bits en matériel) ;
- tester les **sous-titres** : **ASS** (styles, polices intégrées) et **VobSub** (sous-titres image, souvent en MKV/AVI). Hors scope de l'étape actuelle, mais ils conditionneront le choix du lecteur.

Dans *Remarques* : erreur affichée, sous-titres visibles ou non, temps de démarrage, saccades, test navigateur.

## 8 bis. Pistes audio et sous-titres (fichiers réels)

### Ce qu'il faut savoir sur les sous-titres
Dans un MKV, les sous-titres sont des **pistes** comme l'audio (« muxés »). Deux familles :

| Famille | Formats | Ce que fait le téléphone (Media3) | Navigateur (plus tard) |
|---|---|---|---|
| **Texte** | ASS/SSA (fansubs), SRT | Affiché. Pour l'ASS : texte, couleurs de base et position ; **pas** les polices intégrées au MKV, le karaoké ni les effets | ASS non lu nativement |
| **Image** | VobSub (DVD), PGS (Blu-ray) | Affiché tel quel (ce sont des images) | Non lu |

Les polices ASS intégrées apparaissent comme des pistes « AUTRES » (pièces jointes) : elles sont ignorées. Un ASS très stylé (panneaux traduits, karaoké) sera donc lisible mais moins joli que dans VLC ou mpv.

### Protocole
1. Lancer la lecture, ouvrir **Pistes** : noter pistes audio et sous-titres (langue, format, décodable ?).
2. **Audio** : passer d'une langue à l'autre, vérifier le son et la synchronisation.
3. **Sous-titres** : activer chaque piste l'une après l'autre ; regarder 1 à 2 min avec des dialogues, puis faire un seek et vérifier que les sous-titres suivent.
4. Pour l'ASS : les panneaux (textes à l'écran traduits) apparaissent-ils ? au bon endroit ? lisibles ?

### Grille

Téléphone : _modèle, version d'Android_

| # | Fichier | Piste | Format | Langue | Décodable | Affichée | Synchro après seek | Styles / position (ASS) | Remarques |
|---|---|---|---|---|---|---|---|---|---|
| 1 | | | | | | | | | |
| 2 | | | | | | | | | |
| 3 | | | | | | | | | |
| 4 | | | | | | | | | |
| 5 | | | | | | | | | |
| 6 | | | | | | | | | |

## 9. Lire les résultats

| Constat | Ce que ça veut dire | Piste pour la suite |
|---|---|---|
| Tout est OK | Direct Play suffit | Pas de transcodage, on continue le plan |
| Image OK, **son absent** sur AC3/DTS/TrueHD | Pas de décodeur audio sur le téléphone | Extension ffmpeg de Media3 **côté app** (pas de conversion serveur) |
| **Image noire / erreur** sur HEVC 10 bits | Décodeur matériel absent sur ce téléphone | Tester un autre téléphone ; sinon fallback ffmpeg côté serveur à étudier |
| **Seek** lent ou impossible, lecture OK | Fichier sans index (MKV sans « cues ») ou bug Range | Vérifier les requêtes Range côté serveur (logs), tester le même fichier en local |
| Rien ne se lit | Réseau / pare-feu / cleartext | Revoir la section 4 |

La décision (Direct Play suffisant / fallback ffmpeg à prévoir / remise en question) se prend ensemble à partir de la grille remplie.
