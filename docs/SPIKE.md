# Spike vidéo (phase 0)

**But** : savoir si les vrais fichiers du NAS se lisent tels quels (« Direct Play », sans conversion) sur un téléphone Android, avant de construire le reste.

Le code du spike est jetable, sauf `ByteRange` et la logique Range de `SpikeStreamResource`, qui seront réutilisés par le streaming définitif.

```
Téléphone (app spike, ExoPlayer) ──Wi-Fi──► PC Windows :8080 (Quarkus, mode dev) ──► dev-media/
```

## 1. Prérequis

- **Backend** : JDK 21 + Maven 3.9, **ou** seulement Docker Desktop (voir 3.b).
- **Android** : Android Studio récent (compatible AGP 8.7 / Gradle 8.9), un téléphone Android 7+ avec le débogage USB activé.
- **Codecs** : `ffprobe` (fourni avec ffmpeg) ou MediaInfo (interface graphique).
- Le téléphone et le PC sur **le même réseau Wi-Fi** (pas un réseau « invité », qui isole souvent les appareils entre eux).

## 2. Préparer `dev-media/`

Copier 5 à 10 **vrais** fichiers du NAS dans `dev-media/` (le contenu est ignoré par git). Des sous-dossiers sont possibles.
Varier autant que possible :

- une release récente en **HEVC/H.265 10 bits** (le cas le plus fréquent et le plus risqué) ;
- une release plus ancienne en **H.264** ;
- un fichier **MP4** si le NAS en contient ;
- un fichier avec **plusieurs pistes audio** (japonais + français) ;
- si possible un fichier avec de l'audio **FLAC**, **AC3** ou **DTS** ;
- un fichier avec des **sous-titres ASS** intégrés (on note juste s'ils s'affichent, ce n'est pas un critère).

## 3. Lancer le backend

L'endpoint de spike est **désactivé par défaut** et n'a **aucune authentification**. Il ne s'active qu'avec `DEV_SPIKE_STREAM_ENABLED=true`. Ne jamais l'activer sur le NAS.

### 3.a Avec le Maven Wrapper (Java 21 requis, Maven non requis)

Le wrapper (`mvnw` / `mvnw.cmd`) télécharge Maven 3.9.11 au premier lancement. Il faut seulement un JDK 21 (`java -version`, ou `JAVA_HOME` renseigné).

PowerShell :

```powershell
cd backend
$env:DEV_SPIKE_STREAM_ENABLED = "true"
.\mvnw.cmd quarkus:dev "-Dquarkus.http.host=0.0.0.0"
```

Invite de commandes (cmd) :

```bat
cd backend
set DEV_SPIKE_STREAM_ENABLED=true
mvnw.cmd quarkus:dev -Dquarkus.http.host=0.0.0.0
```

Notes :
- en PowerShell, `.\` est obligatoire (le dossier courant n'est pas dans le PATH) et l'option `-D...` doit être **entre guillemets**, sinon PowerShell la découpe au niveau des points ;
- `-Dquarkus.http.host=0.0.0.0` est redondant avec `application.properties` (`%dev.quarkus.http.host=0.0.0.0`) mais rend l'écoute réseau explicite ;
- `set` (cmd) et `$env:` (PowerShell) ne valent que pour la fenêtre en cours.

Au démarrage, un avertissement indique le dossier servi et le nombre de vidéos trouvées.

### 3.b Sans Java installé, avec Docker Desktop (PowerShell, depuis la racine du dépôt)

```powershell
docker run --rm -it -p 8080:8080 -e DEV_SPIKE_STREAM_ENABLED=true `
  -v "${PWD}:/app" -v anime-m2:/root/.m2 -w /app/backend `
  maven:3.9-eclipse-temurin-21 mvn quarkus:dev -Dquarkus.http.host=0.0.0.0
```

### 3.c Tests automatisés

`.\mvnw.cmd test` (PowerShell) ou `mvnw.cmd test` (cmd), depuis `backend/` (ou la commande Docker ci-dessus en remplaçant `quarkus:dev` par `test`).
Ils couvrent : plage au début, au milieu, à la fin (`bytes=9900-` et `bytes=-500`), fin de plage au-delà du fichier, plage invalide (416), header mal formé (ignoré → 200), types MIME, fichiers cachés/non vidéo, liens symboliques qui sortent du dossier, tentatives de path traversal, endpoint désactivé (404).

### 3.d Vérifier sur le PC

<http://localhost:8080/api/dev/files> doit renvoyer la liste des vidéos, chacune avec son `id` et une `url` prête à l'emploi.
Le client ne donne jamais de chemin : seulement ce numéro.

## 4. Rendre le PC joignable depuis le téléphone

Trois obstacles classiques, dans l'ordre :

### 4.1 Écouter sur 0.0.0.0
Par défaut, Quarkus en mode dev n'écoute que sur `localhost`, donc rien n'est joignable depuis le réseau. C'est déjà réglé dans `application.properties` (`%dev.quarkus.http.host=0.0.0.0`).
Vérification : `netstat -an | findstr :8080` doit montrer `0.0.0.0:8080` (et pas seulement `127.0.0.1:8080`).

### 4.2 Pare-feu Windows
Au premier lancement, Windows peut afficher « Autoriser l'accès » pour `java.exe` : cocher **Réseaux privés** uniquement.
Si rien ne s'affiche ou si le téléphone n'arrive toujours pas à se connecter, créer une règle (PowerShell **administrateur**) :

```powershell
# Le Wi-Fi doit être en profil "Private" (sinon la règle ne s'applique pas)
Get-NetConnectionProfile

New-NetFirewallRule -DisplayName "Anime spike 8080" -Direction Inbound `
  -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private
```

Si `Get-NetConnectionProfile` affiche `Public` pour ton Wi-Fi domestique, passe-le en privé dans *Paramètres → Réseau et Internet → Wi-Fi → (ton réseau) → Type de profil réseau → Privé*. On n'ouvre pas le port sur le profil Public.
Une fois le spike fini : `Remove-NetFirewallRule -DisplayName "Anime spike 8080"`.

### 4.3 Trouver l'IP du PC
`ipconfig` → carte **Wi-Fi** ou **Ethernet** → « Adresse IPv4 » (souvent `192.168.x.x`). Ignorer les cartes `vEthernet (WSL)` / `Hyper-V`.

**Test décisif avant Android** : dans Chrome sur le téléphone, ouvrir `http://<IP-du-PC>:8080/api/dev/files`.
Si le JSON s'affiche, le réseau est bon. Les URL de la liste contiennent alors directement l'IP du PC : il suffit de les copier.

## 5. Lancer l'app Android

1. Android Studio → *Open* → `android/spike`. Laisser la synchronisation Gradle se terminer (elle télécharge Gradle 8.9, AGP et Media3).
2. Brancher le téléphone (débogage USB activé), bouton *Run*.
   Alternative : `gradlew.bat assembleDebug`, puis installer `app/build/outputs/apk/debug/app-debug.apk`.
3. Coller l'URL (ex. `http://192.168.1.10:8080/api/dev/stream/3`), appuyer sur **Lire**. L'URL est mémorisée.
4. Toucher l'écran pendant la lecture affiche les contrôles **et la liste des pistes**, avec pour chacune son codec et `décodable` / `NON SUPPORTÉE` (selon le téléphone). ▶ marque la piste en cours de lecture.
5. Bouton Retour : arrêt et retour au formulaire. En cas d'erreur, le code d'erreur ExoPlayer et les pistes détectées s'affichent sous le bouton (texte sélectionnable, pour le copier dans la grille).

**HTTP en clair** : depuis Android 9, les apps refusent `http://` par défaut. Le spike l'autorise via `res/xml/network_security_config.xml` et `usesCleartextTraffic`. Une erreur `Cleartext HTTP traffic ... not permitted` voudrait dire que cette config n'est pas prise en compte. La vraie app passera par HTTPS (reverse proxy) et n'aura pas besoin de cette exception.

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

## 9. Lire les résultats

| Constat | Ce que ça veut dire | Piste pour la suite |
|---|---|---|
| Tout est OK | Direct Play suffit | Pas de transcodage, on continue le plan |
| Image OK, **son absent** sur AC3/DTS/TrueHD | Pas de décodeur audio sur le téléphone | Extension ffmpeg de Media3 **côté app** (pas de conversion serveur) |
| **Image noire / erreur** sur HEVC 10 bits | Décodeur matériel absent sur ce téléphone | Tester un autre téléphone ; sinon fallback ffmpeg côté serveur à étudier |
| **Seek** lent ou impossible, lecture OK | Fichier sans index (MKV sans « cues ») ou bug Range | Vérifier les requêtes Range côté serveur (logs), tester le même fichier en local |
| Rien ne se lit | Réseau / pare-feu / cleartext | Revoir la section 4 |

La décision (Direct Play suffisant / fallback ffmpeg à prévoir / remise en question) se prend ensemble à partir de la grille remplie.
