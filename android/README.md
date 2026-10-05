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
- Lecture des épisodes : au bloc suivant (Media3, URL signée).

Les images ne viennent que du serveur : une affiche ou une photo pas encore stockée sur le NAS s'affiche en initiales colorées.

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
