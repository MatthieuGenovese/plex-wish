# Application Android

Application téléphone (Kotlin, Jetpack Compose, Media3) dans `android/app`. Même API REST que l'interface web, aucune logique métier dupliquée : l'app affiche ce que renvoie le serveur. Choix techniques : `docs/ARCHITECTURE.md` §20.

`android/spike/` (phase 0) sera supprimé dès que le lecteur de l'app lira les vidéos par URL signée.

## Prérequis

- **Android Studio** récent (Ladybug ou plus), avec le SDK Android 35. Le JDK fourni avec Android Studio convient (JDK 17 ou plus).
- Un téléphone Android 8.0 ou plus (minSdk 26), par exemple le Galaxy S24.

## Ouvrir le projet

*File → Open* → choisir le dossier **`android/app`** (pas `android/`). Android Studio télécharge Gradle et les dépendances au premier lancement. En ligne de commande, depuis `android/app` :

```powershell
.\gradlew.bat assembleDebug          # Linux / macOS : ./gradlew assembleDebug
.\gradlew.bat testDebugUnitTest      # tests unitaires (fausse API, aucun réseau réel)
```

L'APK de test est dans `build/outputs/apk/debug/anime-android-debug.apk` (application « Anime Server (debug) », installable à côté de la version normale).

## Lancer sur le téléphone

### En USB

1. Sur le téléphone : *Paramètres → À propos du téléphone → Informations sur le logiciel*, toucher 7 fois « Numéro de version » pour activer les options de développement.
2. *Options de développement* → activer **Débogage USB**, brancher le câble, accepter l'empreinte de l'ordinateur.
3. Android Studio : choisir le téléphone dans la liste des appareils, puis ▶ (Run). Ou : `adb install -r build\outputs\apk\debug\anime-android-debug.apk`.

### Sans fil (Android 11+)

*Options de développement → Débogage sans fil* → activer, puis dans Android Studio : *Device Manager → Pair Devices Using Wi-Fi* (QR code). En ligne de commande : « Associer l'appareil avec un code » sur le téléphone, puis `adb pair <ip>:<port>` (code à 6 chiffres) et `adb connect <ip>:<port>`. Le PC et le téléphone doivent être sur le même réseau.

## Tester contre le serveur de développement du PC (adb reverse)

La stack Docker du PC publie l'interface sur `127.0.0.1:8080` seulement (`WEB_BIND`, voir le README principal) : le téléphone ne peut pas la joindre par l'adresse IP du PC. `adb reverse` fait passer le port par le câble (ou le débogage sans fil) :

```powershell
adb reverse tcp:8080 tcp:8080
```

Dans l'app **debug**, adresse du serveur : `http://localhost:8080`. Le HTTP en clair n'est accepté que dans la version debug. L'app n'utilise pas de cookie : la limitation du cookie `Secure` du navigateur (qui oblige à passer par `localhost` ou le HTTPS) ne la concerne pas. Il faut refaire `adb reverse` après chaque rebranchement.

## Adresse du serveur

Au premier lancement : l'adresse publique (celle de `PUBLIC_URL`, ex. `https://anime.mondomaine.fr` ou `https://monnas.synology.me`), puis identifiant et mot de passe. Le `https://` peut être omis. L'app vérifie que l'adresse répond comme un Anime Server avant d'envoyer le mot de passe. L'adresse et l'identifiant sont mémorisés ; le mot de passe ne l'est jamais. Pour changer de serveur : *Compte → Se déconnecter*.

- Version **release** : `https://` obligatoire (le HTTP en clair est bloqué par la configuration réseau d'Android).
- Trop d'essais de mot de passe : le serveur bloque la connexion un moment ; l'app affiche le délai à attendre.
- Session : l'app reste connectée (30 jours sans ouverture au plus) ; si le serveur révoque la session (mot de passe changé, compte désactivé), elle revient à l'écran de connexion avec un message.

## Ce que fait l'app (à ce stade)

- Connexion, accueil (reprendre, derniers ajouts), bibliothèque (recherche, tri).
- Fiche d'un animé : synopsis, saisons, épisodes (par tranches de 100 pour les longues séries), épisodes vus ou en cours, distribution (photo et nom des doubleurs japonais, personnage joué, rôle) et page de chaque comédien.
- *Compte → À propos* : sources des données (AniList, TMDB) et mention TMDB.
- Lecture (Media3) depuis la fiche (un épisode) ou l'accueil (« Continuer à regarder »), reprise à la position enregistrée. La page d'un comédien liste des animés, pas des épisodes : on passe par la fiche.

Les images ne viennent que du serveur : une affiche ou une photo pas encore stockée sur le NAS s'affiche en initiales colorées.

## Lecteur

Plein écran en paysage. Commandes de Media3 : lecture / pause, avance et recul, barre de progression, bouton **Sous-titres** et bouton **Paramètres** (piste audio). Tous sont lus par TalkBack, en français.

### Pistes par défaut

- Audio **japonais**, sous-titres **français**, quand ils existent. Sans piste française, une piste de sous-titres sans langue (fréquent chez les fansubs) ou marquée « par défaut » est choisie.
- Un changement fait dans le lecteur est **mémorisé** sur le téléphone (langue de l'audio, langue des sous-titres, ou sous-titres désactivés) et s'applique aux épisodes suivants.
- Taille et style des sous-titres : ceux du fichier, ajustés par *Paramètres Android → Accessibilité → Sous-titres* (taille, police, fond).

### Ce que Media3 fait des sous-titres

| Format | Ce qui s'affiche | Ce qui est ignoré |
|---|---|---|
| **ASS/SSA** (texte, MKV) | Le texte ; par **style** (section `[V4+ Styles]`) : couleur principale, gras, italique, souligné, barré, taille de police, alignement (`Alignment`), fond en encadré (`BorderStyle=3`) ; dans une ligne : **position** `\pos`, point de départ de `\move`, alignement `\an`. | Polices du fichier (police d'Android à la place), contour et ombre, **toutes les autres balises dans la ligne** (`{\i1}`, `{\c&H…&}`, `{\fs…}`, karaoké, fondus `\fad`, animations `\t`, rotation, flou, découpe). Les panneaux dessinés (`\p1`) peuvent apparaître comme du texte parasite. Résultat : lisible, mais les « signs » et effets typographiques des fansubs sont perdus. |
| **SRT, WebVTT** | Texte, italique / gras simples. | — |
| **VobSub, PGS** (images, MKV) | Affichés **tels quels, en images** (rendu fidèle du disque d'origine). | Taille et style non réglables ; pas de choix de police. |
| **ASS dans un MP4** | Rien : Android ne voit pas la piste. | Toute la piste. |
| **Sous-titres dans un AVI** (VobSub…) | Rien : l'extracteur AVI de Media3 ne lit que la vidéo et l'audio. | Toute la piste. |

Le lecteur le signale par un bandeau (« Aucun sous-titre lisible… ») avec un bouton **Détails**.

### Quand un fichier ne se lit pas

Message clair, puis **Détails** : fichier (conteneur), code d'erreur Media3, réponse HTTP éventuelle, codec en cause, pistes détectées (codec, langue, lisible ou non). Jamais d'URL ni de jeton (le texte peut être sélectionné et copié pour un signalement). Cas distingués :

| Fichier | Ce que l'app dit |
|---|---|
| OGM | Format non lu par Android, à convertir sur le serveur (remux, phase 9). |
| AVI (MPEG-4 Part 2 + MP3) | Lu ; bandeau « pas de sous-titres » (normal pour un AVI). |
| AVI (H.264 + HE-AAC + VobSub) | Lu si Android reconnaît l'audio ; sinon bandeau « Pas de son : aucune piste audio reconnue… (HE-AAC dans un AVI) », et « pas de sous-titres ». |
| MKV HEVC 10 bits + ASS | Lu si le téléphone a le décodeur (le S24 l'a) ; sinon « Ce téléphone ne sait pas décoder la vidéo (HEVC (H.265) 10 bits) ». |
| MP4 H.264 + ASS | Lu ; bandeau « ASS mis dans un MP4 : ignorés par Android ». |
| Réseau coupé | « Connexion au serveur perdue », après les nouveaux essais automatiques. |
| 403 qui persiste | « Le serveur refuse la lecture (lien expiré, ou compte désactivé) ». |
| Fichier retiré du NAS | « Ce fichier n'est plus disponible sur le serveur ». |

### Reprise de connexion

- Une pause de plus d'environ 60 s fait fermer la connexion par nginx (ARCHITECTURE §6.4), une coupure réseau aussi. ExoPlayer rouvre la connexion lui-même (requête `Range` à la position courante) ; s'il n'y arrive pas, l'app recharge l'épisode **à la dernière position connue** (celle d'un seek fait pendant la pause comprise) : jusqu'à 5 essais espacés (1, 2, 4, 8, 15 s), puis message et bouton « Réessayer ».
- Le serveur répond 403 (lien expiré, au bout de 6 h) : l'app redemande une URL signée et repart au même endroit, sans message. À la reprise après une très longue pause, l'URL est renouvelée d'avance si elle expire dans moins de 2 minutes.

#### Scénario de test manuel (à travers le reverse proxy du DSM, idéalement en 4G)

1. Lancer un épisode, laisser jouer 1 minute, noter la position (ex. 1:00).
2. **Pause longue** : pause, attendre **2 minutes**, reprendre. Attendu : reprise à 1:00 sans erreur visible (au plus un bref « Reconnexion… »).
3. **Seek pendant la pause** : pause, attendre 2 minutes, avancer à 10:00 pendant la pause, reprendre. Attendu : lecture à 10:00.
4. **Coupure courte** : pendant la lecture, mode avion 5 à 10 secondes, puis le couper. Attendu : « Reconnexion… », puis reprise au bon endroit.
5. **Coupure longue** : mode avion 1 minute. Attendu : « Connexion au serveur perdue » ; couper le mode avion, « Réessayer » → reprise au bon endroit.
6. **Lien expiré** (facultatif, sur le PC) : mettre `STREAM_URL_LIFETIME=3m` dans `.env`, relancer le backend, lancer un épisode, pause plus longue que ce délai, reprendre. Attendu : reprise sans message (nouvelle URL).
7. Dans chaque cas, revenir à la fiche : la position enregistrée doit correspondre (voir « Progression »).

## APK de release (sans Play Store)

### Clé de signature (une fois)

Les téléphones n'acceptent une mise à jour que si elle est signée **avec la même clé** : la garder précieusement (sauvegarde hors du PC), sans la versionner.

```powershell
keytool -genkeypair -v -keystore plexwish-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias plexwish
```

Créer `android/app/keystore.properties` (ignoré par git) :

```properties
storeFile=plexwish-release.jks
storePassword=…
keyAlias=plexwish
keyPassword=…
```

### Construire

```powershell
.\gradlew.bat assembleRelease
```

APK signé : `build/outputs/apk/release/anime-android-release.apk`. Sans `keystore.properties`, Gradle produit `anime-android-release-unsigned.apk`, qui ne s'installe pas. Avant chaque nouvelle version, augmenter `versionCode` (et `versionName`) dans `build.gradle.kts`.

### Installer sur les téléphones des amis

1. Envoyer le fichier `.apk` (lien de partage Synology Drive, messagerie, câble…).
2. Sur le téléphone, l'ouvrir : Android demande d'autoriser l'installation d'applications inconnues **pour l'appli qui ouvre le fichier** (navigateur, Mes fichiers…) : accepter pour celle-ci seulement.
3. Play Protect peut avertir qu'il ne connaît pas le développeur : *Plus de détails → Installer quand même*.
4. Mise à jour : même procédure avec le nouvel APK (même clé de signature), les données sont conservées.
