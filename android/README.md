# Application Android

Application téléphone (Kotlin, Jetpack Compose, Media3) dans `android/app`. Même API REST que l'interface web, aucune logique métier dupliquée : l'app affiche ce que renvoie le serveur. Choix techniques : `docs/ARCHITECTURE.md` §20.

L'app du spike (phase 0, `android/spike/`) et l'endpoint `/api/dev/*` ont été supprimés à la fin du Polish (P3) ; compte rendu : `docs/SPIKE.md`.

## Prérequis

- **Android Studio** à jour (compatible AGP 9.4 : *Help → Check for Updates*), avec le **SDK Android 37** (*SDK Manager → SDK Platforms → Android 37.0*, et *SDK Tools → Build-Tools 37*). Le JDK fourni avec Android Studio convient (JDK 17 ou plus). Chaîne : Gradle 9.8.1, AGP 9.4.1 (Kotlin intégré), Kotlin 2.4.21, Compose BOM 2026.09.00, Coil 3.6.3, Media3 1.5.1 (inchangé, validé sur le S24). `targetSdk` reste 35 (comportement système inchangé).
- Un téléphone Android 8.0 ou plus (minSdk 26), par exemple le Galaxy S24.

## Ouvrir le projet

*File → Open* → choisir le dossier **`android/app`** (pas `android/`). Android Studio télécharge Gradle et les dépendances au premier lancement. En ligne de commande, depuis `android/app` :

```powershell
.\gradlew.bat assembleDebug          # Linux / macOS : ./gradlew assembleDebug
.\gradlew.bat testDebugUnitTest      # tests unitaires (fausse API, aucun réseau réel) et écrans (voir ci-dessous)
```

**Écrans testés sans téléphone** : les écrans Compose sont rendus sur la JVM par Robolectric (dépendance de test seulement, rien dans l'APK) : libellés TalkBack, cibles de 48 dp, ordre de focus au clavier, contrastes des couleurs. Chaque test d'écran écrit aussi ses captures dans `build/screenshots/` (thème sombre et clair, texte à 100 et 200 %, téléphone 384 × 832 dp ; lecteur en paysage) : à regarder, aucune comparaison automatique.

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

Dans l'app **debug**, adresse du serveur : `http://localhost:8080`.

**Catalogue de démonstration** (1 300 animés inventés, README principal) : `adb reverse tcp:8090 tcp:8090`, puis l'adresse `http://localhost:8090` (compte admin du `.env`). Les épisodes de démonstration n'ont pas de fichier : la lecture y répond « Ce fichier n'est plus disponible sur le serveur », tout le reste (accueil, fiche, progression affichée, recherche) se teste. Le HTTP en clair n'est accepté que dans la version debug. L'app n'utilise pas de cookie : la limitation du cookie `Secure` du navigateur (qui oblige à passer par `localhost` ou le HTTPS) ne la concerne pas. Il faut refaire `adb reverse` après chaque rebranchement.

## Adresse du serveur

**Préremplie à la compilation** (facultatif) : `plexwish.serverUrl=https://anime.mondomaine.fr` dans `android/app/local.properties` (non versionné), ou `.\gradlew.bat assembleRelease -Pplexwish.serverUrl=https://…`. Les amis n'ont alors que leur identifiant à taper ; l'adresse reste modifiable à l'écran de connexion. Sans cette propriété, le champ est vide.

Au premier lancement : l'adresse publique (celle de `PUBLIC_URL`, ex. `https://anime.mondomaine.fr` ou `https://monnas.synology.me`), puis identifiant et mot de passe. Le `https://` peut être omis. L'app vérifie que l'adresse répond comme un Anime Server avant d'envoyer le mot de passe. L'adresse et l'identifiant sont mémorisés ; le mot de passe ne l'est jamais. Pour changer de serveur : *Compte → Se déconnecter*.

- Version **release** : `https://` obligatoire (le HTTP en clair est bloqué par la configuration réseau d'Android).
- Trop d'essais de mot de passe : le serveur bloque la connexion un moment ; l'app affiche le délai à attendre.
- Session : l'app reste connectée (30 jours sans ouverture au plus) ; si le serveur révoque la session (mot de passe changé, compte désactivé), elle revient à l'écran de connexion avec un message.

## Ce que fait l'app

Même direction que l'interface web (`docs/DESIGN.md`) : accent « Lagune », police Figtree (embarquée), icônes Material Symbols, thème sombre ou clair.

- **Connexion** : adresse du serveur (préremplie ou mémorisée), identifiant, mot de passe (bouton « Afficher »), aide « Mot de passe oublié ».
- **Barre du bas** : Accueil, Rechercher, Bibliothèque, Compte.
- **Accueil** : bandeau « À reprendre » (lecture directe) ou « À suivre » (épisode suivant du dernier terminé), sinon le dernier ajout ; rangées « Continuer à regarder », « Récemment ajoutés », trois genres, « À découvrir » (« Tout voir » mène à la bibliothèque filtrée). Tirer vers le bas pour actualiser.
- **Rechercher / Bibliothèque** : recherche, filtres Non vus / En cours / Vus, genre, période, tri (titre, derniers ajouts, année) ; grille de 3 colonnes (2 avec un grand texte) ; messages dédiés quand rien ne correspond.
- **Fiche d'un animé** : bandeau (affiche, titres, genres cliquables) et bouton principal : « Reprendre », « Épisode suivant », « Commencer » ou « Revoir depuis le début » ; synopsis (« Lire la suite »), sources, saisons (boutons, ou liste au-delà de 3), tranches de 100 épisodes, épisodes avec leur état (vu, en cours, prochain), distribution en portraits ronds, page de chaque comédien.
- **Compte** : thème (Système / Sombre / Clair, enregistré sur le téléphone, sombre par défaut), changement du mot de passe (les autres appareils sont déconnectés, celui-ci reste connecté), À propos (sources, mention TMDB, licences), déconnexion.
- **Accessibilité** : taille de texte du système respectée (testée à 200 % : mises en page en colonne), TalkBack (chaque carte est lue en une phrase), clavier et télécommande (anneau de focus jaune ou bleu, ordre de lecture, aucune action réservée au toucher long).

Les images ne viennent que du serveur : une affiche absente s'affiche en couverture composée (couleur tirée du titre, titre écrit dessus), un comédien sans photo en initiales.

## Lecteur

Plein écran en paysage. Un toucher (ou une touche de la télécommande) affiche la surcouche : retour et titres en haut ; **reculer de 10 s**, **lecture / pause**, **avancer de 10 s** au centre ; barre de progression (d'un seul tenant) et temps en bas, avec le bouton **« Audio et sous-titres »** (panneau qui liste les pistes audio et de sous-titres, « Désactivés » compris). Elle se masque seule pendant la lecture (plus tard avec TalkBack). Touches de la télécommande : Lecture/Pause, avance et retour rapides, Retour. Tout est lu par TalkBack, en français. Chargement et reconnexion : un indicateur qui tourne au centre de l'image.

### Pistes par défaut

- Audio **japonais**, sous-titres **français**, quand ils existent. Sans piste française, une piste de sous-titres sans langue (fréquent chez les fansubs) ou marquée « par défaut » est choisie.
- Un choix fait dans « Audio et sous-titres » est **mémorisé** sur le téléphone (langue de l'audio, langue des sous-titres, ou sous-titres désactivés) et s'applique aux épisodes suivants.
- Taille et style des sous-titres : ceux du fichier, ajustés par *Paramètres Android → Accessibilité → Sous-titres* (taille, police, fond).

### Sous-titres

Ceux du fichier : piste de sous-titres (MKV, MP4, copies préparées des AVI et OGM) ou sous-titres incrustés dans l'image (fréquent dans les AVI). Vérifié sur le S24 pour les quatre formats. L'app n'affiche aucun message à leur sujet : l'absence de piste ne veut pas dire qu'il n'y a pas de sous-titres. Pour les sous-titres ASS, le texte et sa position sont respectés, les effets typographiques avancés des fansubs (karaoké, animations) sont simplifiés par Media3.

### Quand un fichier ne se lit pas

Message clair, puis **Détails** : fichier (conteneur), code d'erreur Media3, réponse HTTP éventuelle, codec en cause, pistes détectées (codec, langue, lisible ou non). Jamais d'URL ni de jeton (le texte peut être sélectionné et copié pour un signalement). Cas distingués :

| Fichier | Ce que l'app dit |
|---|---|
| Lecture bloquée au chargement (tout fichier) | Détectée par Media3 (« stuck buffering ») ou par l'app (20 s de mise en tampon sans que les données chargées n'avancent de 2 s) : même message ; **Détails** indique la durée de mise en tampon, la position chargée et les pistes actives. Une connexion lente, qui fait avancer le chargement, n'est pas prise pour un blocage. |
| AVI, OGM | Convertis par le serveur (voir « Préparation de l'épisode »). Si une copie posait encore problème, le filet de sécurité reste : lecture bloquée → message avec **Réessayer** et **Lire sans le son**. |
| Son illisible | Bandeau « Pas de son : … » (piste audio absente ou dans un format que le téléphone ne lit pas) ; la vidéo continue. |
| MKV HEVC 10 bits | Lu si le téléphone a le décodeur (le S24 l'a) ; sinon « Ce téléphone ne sait pas décoder la vidéo (HEVC (H.265) 10 bits) ». |
| Réseau coupé | « Connexion au serveur perdue », après les nouveaux essais automatiques. |
| 403 qui persiste | « Le serveur refuse la lecture (lien expiré, ou compte désactivé) ». |
| Fichier retiré du NAS | « Ce fichier n'est plus disponible sur le serveur ». |

### Préparation de l'épisode (AVI, OGM)

Pour un AVI ou un OGM, le serveur prépare d'abord une copie lisible (remux sans ré-encodage, ARCHITECTURE §23). L'app affiche un indicateur qui tourne et **« Préparation de l'épisode… »**, directement sur l'image, sans cadre ; elle redemande toute seule au rythme indiqué par le serveur, puis lance la lecture. Pour annuler : retour (bouton ou geste, touche Retour de la télécommande). Ensuite, la copie est gardée : les lectures suivantes démarrent tout de suite. Cas d'erreur (message du serveur) : « Ce fichier n'a pas pu être converti pour Android… » (remux impossible, l'admin le voit et peut relancer) ; « Le serveur n'a plus de place… Réessayez dans quelques minutes. » (cache plein de copies en cours de lecture).

#### Scénario de test (Air Gear AVI)
1. Serveur à jour, dossier `REMUX_CACHE_PATH` inscriptible (README racine, « Remux à la demande ») ; *Médias → Remux à la demande* vide.
2. Lancer l'épisode AVI : indicateur et « Préparation de l'épisode… » quelques secondes, puis lecture **avec le son et les sous-titres**, seek à 80 % puis retour.
3. Regarder 1 minute, quitter : la position est enregistrée (barre « en cours » sur la fiche), relancer : reprise immédiate (copie déjà prête, pas d'écran de préparation).
4. Admin : copie « prête », variante utilisée, taille.
5. Un OGM : même chose (avec ses sous-titres s'il en a).
6. Deux épisodes AVI non préparés lancés depuis deux téléphones : le second attend plus longtemps (même indicateur), puis lit.

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

### Progression

- Envoyée au serveur toutes les ~10 s pendant la lecture, et aussitôt à la **pause**, au passage en **arrière-plan** (bouton d'accueil, appel, écran éteint : la lecture se met en pause), à la **fin** de l'épisode et à la **sortie** du lecteur.
- **Terminé** au-delà de 90 % (décidé par le serveur) : coche sur la fiche, l'épisode repart du début à la prochaine lecture.
- Rien n'est envoyé si la position est incohérente (durée inconnue, position négative ou au-delà de la fin, moins d'une seconde, pendant une reconnexion).
- Sans réseau, la lecture continue ; la position est renvoyée à la prochaine occasion (envoi suivant, pause, sortie).
- La fiche et l'accueil se mettent à jour **dès qu'une position est enregistrée** (la dernière part à la sortie du lecteur, souvent après le retour sur la fiche) : la barre de l'épisode suit, y compris après un retour en arrière dans l'épisode ; au-delà de 90 %, la coche « vu » apparaît. Ils relisent aussi la progression à chaque retour à l'écran.
- Barre de progression : une seule barre pleine (comme le web).

À vérifier : regarder 2 minutes, revenir à la fiche (barre « en cours », à la bonne longueur) ; relancer, **reculer** de 1 minute, revenir : la barre raccourcit ; relancer (reprise au même endroit) ; avancer à 95 %, quitter (coche « vu ») ; même chose en mode avion pendant 30 s au milieu (la position finale arrive quand même).

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
