# Phase Polish : audit et direction (P1)

> Rédigé le 2026-10-07. Statut : **P1 livrée, en attente de décisions** (§10). P2 (web) et P3 (Android) ne commencent qu'après feu vert.
> Référence visuelle : `docs/design/guide-de-style.html` (s'ouvre par double-clic, thème sombre/clair, trois accents, texte 100/130/200 %).
> Données pour juger : catalogue de démonstration (§6), `scripts/demo-catalog.sh`.

Objectif : une interface évidente au premier regard pour une dizaine de personnes qui ne veulent rien apprendre, au niveau des meilleures applications de streaming, sans copier ni leurs marques ni leurs visuels. Rien dans P1 ne change le code des écrans, le serveur, l'API ni la sécurité.

---

## 1. Inventaire des écrans

### 1.1 Web (Angular, `web/src/app/pages`)

| Écran | Route | Contenu actuel |
|---|---|---|
| Connexion | `/login` | identifiant ou e-mail, mot de passe, blocage après essais |
| Accueil | `/` | « Récemment ajoutés » (12), puis les 24 premiers titres A→Z, lien « Toute la bibliothèque » |
| Bibliothèque | `/anime?q=&tri=&page=` | recherche (300 ms), tri titre / derniers ajouts, grille paginée par 60 |
| Fiche animé | `/anime/:id?saison=` | affiche, titres, synopsis complet, sources ; saisons ; épisodes en tuiles (plages de 100) ; distribution |
| Comédien | `/personne/:id` | photo, nom (romaji, japonais), animés de la bibliothèque et personnages |
| À propos | `/a-propos` | sources, mention TMDB |
| Introuvable | `**` | message |
| En-tête (toutes) | — | « Anime Server », Accueil, Bibliothèque, Administration (admin), nom, « Se déconnecter » |
| Admin (9 onglets) | `/admin/*` | Scan, Rapport, Corrections, Métadonnées, Synopsis français, Affiches, Distribution, Médias, Utilisateurs |

Pas de lecteur web (phase 10), donc **ni lecture, ni progression, ni « Continuer à regarder » sur le web**.

### 1.2 Android (Compose, `android/app/.../ui/phone`)

| Écran | Contenu actuel |
|---|---|
| Connexion | adresse du serveur (mémorisée), identifiant, mot de passe |
| Accueil | « Continuer à regarder » (épisodes commencés), « Récemment ajoutés » ; tirer pour rafraîchir |
| Bibliothèque | recherche, puces « Titre / Récents », grille avec chargement au défilement |
| Fiche | affiche, titres, synopsis replié à 6 lignes, sources ; puces de saisons et de plages ; épisodes (vu ✓, barre en cours) ; distribution |
| Comédien | photo, noms, animés et personnages |
| Lecteur | plein écran paysage, commandes Media3 (pistes via « Paramètres » et « Sous-titres »), écrans « Préparation de l'épisode… » et d'erreur avec « Détails » |
| Navigation | barre du bas (Accueil, Bibliothèque), barre du haut avec menu Compte (nom, À propos, Se déconnecter) |

## 2. Parcours principaux aujourd'hui

| Parcours | Web | Android |
|---|---|---|
| Première connexion | 1 écran, 2 champs | 1 écran, **3 champs dont l'adresse du serveur à taper** (`https://…`) |
| Trouver un animé | Bibliothèque → taper → grille → fiche : **2 clics + saisie** ; sinon 22 pages de 60 | onglet Bibliothèque → taper → fiche : 2 gestes + saisie |
| Reprendre un épisode commencé | **impossible** (pas de lecteur, progression invisible) | Accueil → carte « Continuer » : **1 geste** |
| Épisode suivant d'une série en cours | impossible | l'épisode fini disparaît de « Continuer » ; le suivant n'y est pas → Bibliothèque/recherche → fiche → (plage) → défiler → épisode : **4 à 6 gestes** |
| Regarder (AVI/OGM) | — | épisode → « Préparation de l'épisode… » → lecture |
| Changer de piste audio / sous-titres | — | toucher l'écran → « Paramètres » → « Audio » → piste : **4 gestes** ; choix ja/fr retenu |
| Voir un comédien | fiche → défiler jusqu'à la distribution → carte : 2 clics | idem : 2 gestes |
| Gérer son compte | nom affiché, « Se déconnecter » ; **pas de changement de mot de passe, pas de thème** | menu Compte : nom, À propos, Se déconnecter |

## 3. Audit

Mesuré sur le catalogue de démonstration (1 300 animés, 1 935 saisons, 33 354 épisodes), navigateur 1280 × 900 et téléphone 390 × 844. Captures « avant » : dossier `polish-p1/avant` livré à côté du dépôt.

### 3.1 Ce qui marche
- Socle sain : jetons CSS centralisés (`styles.scss`), focus jaune très visible, cibles de 48 px, lien d'évitement, état dans l'URL (F5, retour arrière), `aria-*` corrects, affiches en chargement différé.
- Android : barre du bas, « Continuer à regarder », vu / en cours sur les épisodes, TalkBack soigné, tirer pour rafraîchir.
- Volume : l'API tient sans effort (liste de 60 : 9 Ko en 70 ms ; saison de 1 100 épisodes : 117 Ko en 25 ms ; recherche : 40 ms). Pages web légères (≤ 800 nœuds DOM). Le volume n'est pas un problème de performance, c'est un problème de **navigation** (trouver, reprendre).

### 3.2 Confus ou incohérent
1. **Reprendre** : le geste le plus fréquent n'a pas de bouton. Ni le web ni la fiche Android n'ont de « Reprendre » / « Lire » ; l'épisode suivant n'est nulle part.
2. **Le web ignore la progression** : aucun état vu / en cours, alors que l'app l'affiche. Les deux clients ne racontent pas la même histoire.
3. **Accueil web** : la seconde rangée montre toujours les 24 mêmes titres en « A » (« Akuma… ») ; « Récemment ajoutés » se répète sur la page Bibliothèque.
4. **Fiche** : synopsis long jamais replié sur le web (3 paragraphes repoussent les épisodes d'un écran entier sur téléphone) ; tuiles d'épisodes de hauteurs inégales dès qu'un titre est long ; série fleuve : 11 boutons de plages sur 4 lignes ; distribution en grandes cartes 2:3 à initiales (un « mur » de couleurs, 2 écrans pour 14 rôles).
5. **Affiche manquante** : un aplat à deux initiales (« AN », « AG », « AN »…) ; avec 35 % d'affiches absentes, la grille devient monotone et les cartes se confondent.
6. **Téléphone (web)** : en-tête sur trois lignes (≈ 170 px) avant le contenu, navigation en haut loin du pouce, grille à 2 colonnes (30 écrans pour une page de 60).
7. **Libellés** : « Récents » (Android) / « Derniers ajouts » (web) ; « Anime Server » partout alors que le projet s'appelle PlexWish (voir §10, point ouvert).
8. **Chargement** : un simple « Chargement… » ; pas de squelettes, la page saute quand les données arrivent.
9. **Lecteur Android** : commandes Media3 par défaut ; les pistes sont cachées derrière l'engrenage ; aucune indication de la langue choisie à l'écran.
10. **Connexion Android** : taper l'adresse du serveur est la première chose demandée à un ami qui n'y connaît rien.

### 3.3 Accessibilité
- **Texte à 200 % (web)** : la fiche déborde horizontalement (titre et synopsis coupés, défilement latéral) : non conforme WCAG 1.4.4 / 1.4.10. Les mises en page côte à côte doivent passer en colonne.
- Android : tailles en `sp` respectées, mais les rangées (cartes à largeur fixe 120–140 dp) et le héros tiennent mal à 200 % (titres tronqués à 2-3 lignes).
- **Focus Android** (futur Android TV) : `clickable` rend les cartes focalisables, mais l'indication de focus par défaut (voile Material à ~10 %) est presque invisible sur fond sombre : à remplacer par un anneau et un léger agrandissement.
- Contrastes actuels conformes (AA) ; pas de thème clair.

### 3.4 Admin
Utile et complète, mais 9 onglets en ligne (deux lignes sur téléphone) et des tableaux de 60 rem qui défilent : acceptable pour un administrateur, à habiller avec les nouveaux composants sans refonte (P2.7).

## 4. Propositions par parcours

Légende : ✅ retenu · ❌ écarté · **[S]** demande un changement serveur/API (pas fait sans accord, §8).

**Première connexion**
- ✅ Android : adresse du serveur **préremplie** à la compilation (`BuildConfig`, valeur dans `keystore.properties`/`local.properties`), modifiable sous « Autre serveur ». Aucune modification serveur.
- ✅ Web et Android : écran de connexion avec le logo, un seul bouton, message clair en cas de blocage (déjà le cas), « Mot de passe oublié ? → demandez à l'administrateur ».
- ❌ Inscription, connexion par QR code : inutile pour 10 personnes (FUTURE).

**Trouver un animé**
- ✅ Recherche toujours visible (bureau : barre du haut ; téléphone : onglet « Rechercher » qui ouvre le clavier), résultats au fil de la frappe, insensible aux accents (déjà côté serveur).
- ✅ Grille de **3 colonnes** sur téléphone, « Charger plus » au lieu des numéros de page (l'URL garde la position).
- ✅ Tri : titre, derniers ajouts ; **année [S]**.
- ✅ Filtres en puces : **non vus / en cours / vus [S]**, **années [S]**, **lisible dans le navigateur [S]**, **genre [S]**.
- ❌ Défilement infini sans fin sur le web (perte de repères, pied de page inaccessible) ; liste virtualisée (inutile à 60 par page, mesuré).

**Accueil**
- ✅ En tête, un **héros « À reprendre »** : le dernier épisode commencé, grand bouton « Reprendre ». Sans épisode en cours : le dernier ajout.
- ✅ Rangées horizontales : « Continuer à regarder », « Récemment ajoutés », « À découvrir » (une page tirée au hasard dans l'API existante), puis **« Par genre » [S]**.
- ✅ **« À suivre »** : l'épisode suivant d'une série dont on vient de finir un épisode, dans « Continuer à regarder » **[S]**. C'est la plus grosse amélioration d'usage.
- ❌ « Mes animés » (liste personnelle) : demande une table et des boutons « ajouter » ; « Continuer à regarder » + « en cours » couvrent l'essentiel (FUTURE).
- ❌ Bannière vidéo / bande-annonce automatique : pas de contenu, lourd.

**Fiche animé**
- ✅ Héros : affiche nette sur un fond fait de **l'affiche floutée** (pas besoin d'images « backdrop »), titre, titre japonais, année · saisons · épisodes.
- ✅ **Bouton principal unique** : « Reprendre S1 · É4 · reste 12 min », sinon « Lire l'épisode 1 », sinon « Revoir ». Calculé côté client avec `/me/progress?animeId=` (existe) ; une version serveur serait plus simple pour les deux clients (S5, facultatif).
- ✅ Synopsis replié à 3 lignes + « Lire la suite ».
- ✅ Saisons en boutons segmentés (≤ 5), sinon menu ; plages d'une série fleuve dans un menu « Épisodes 101–200 » ; ouverture directe sur la saison et la plage de l'épisode à reprendre.
- ✅ Épisodes en **liste** (une ligne chacun, hauteur régulière) : numéro en grand à la place d'une vignette, titre (ou « Épisode N »), durée, état vu ✓ / en cours (barre + « reste 12 min »), « Android seulement » si illisible dans un navigateur.
- ✅ Distribution en rangée d'avatars ronds.
- ❌ Vignettes d'épisodes : il faudrait extraire des images avec ffmpeg sur le NAS (charge, stockage) → FUTURE.
- Web : tant que le lecteur web (phase 10) n'existe pas, le bouton principal est remplacé par l'état « Vous en êtes à l'épisode 4 — regardez-le dans l'application Android ». Le lecteur web reste en phase 10.

**Regarder (Android)**
- ✅ Lecteur épuré : barre du haut (retour, titre, « S1 · É4 »), au centre lecture/pause et ±10 s, en bas la barre de progression ; un bouton visible **« Audio et sous-titres »** qui ouvre un panneau en français (« Japonais », « Français (forcés) »), choix retenu comme aujourd'hui.
- ✅ Écrans « Préparation de l'épisode… » et d'erreur restylés (même contenu).
- ✅ Fin d'épisode : « Épisode suivant » proposé (le saut automatique reste en FUTURE).
- ❌ Changer de Media3 ou de lecteur pendant le Polish (validé sur le S24 tel quel).

**Comédien**
- ✅ En-tête avec avatar rond, noms, nombre d'animés ; grille d'affiches avec le personnage joué sous chaque titre.

**Compte**
- ✅ Menu Compte : thème (Système / Sombre / Clair, enregistré sur l'appareil), À propos, Administration (admins), Se déconnecter.
- ✅ **Changer son mot de passe [S]** ; « Appareils connectés » [S] facultatif.

**Navigation**
- ✅ Bureau : barre du haut (logo, Accueil, Bibliothèque, recherche, avatar). Téléphone (web et Android) : barre du bas Accueil · Rechercher · Bibliothèque · Compte.
- ✅ Transitions sobres (fondu 160 ms, léger agrandissement des cartes au survol/focus), désactivées si « réduire les animations ».

## 5. Direction visuelle

Voir le guide de style pour le rendu. Principes : les affiches font le spectacle, l'interface reste sobre ; un seul accent ; tout se lit sans couleur (icône + texte).

### 5.1 Couleurs (jetons)

| Rôle | Sombre (défaut) | Clair | Usage |
|---|---|---|---|
| `--bg` | `#0d0f14` | `#f5f6f8` | fond (encre, pas noir pur) |
| `--surface-1/2/3` | `#151821` / `#1c202b` / `#262b38` | `#ffffff` / `#eceef3` / `#e1e4eb` | barres et cartes / survol, champs / boutons secondaires |
| `--outline`, `--outline-strong` | `#2b303d`, `#646d82` | `#d9dde5`, `#7d8596` | séparateurs ; bordures de champs (≥ 3:1) |
| `--text`, `--text-2`, `--text-3` | `#eef0f5`, `#b3b9c7`, `#8e95a6` | `#12151c`, `#4a5162`, `#636b7c` | texte, secondaire, tertiaire |
| `--accent` (Lagune, proposé) | `#3ed6c2` sur `#06221f` | `#0a7468` sur blanc | bouton principal, progression, sélection |
| `--focus` | `#ffd54a` | `#0a58ca` | anneau de focus (clavier, télécommande) |
| `--ok` / `--warn` / `--err` | `#5ad48f` / `#f2c464` / `#ff7d7d` | `#1b7a43` / `#8a5a00` / `#c62828` | vu, avertissement, erreur |

Contrastes mesurés (WCAG 2.x, sur `--bg`) — sombre : texte 16,8 ; texte-2 9,8 ; texte-3 6,4 ; accent 10,6 ; texte sur accent 9,2 ; focus 13,6 ; erreur 7,7. Clair : texte 16,9 ; texte-2 7,3 ; texte-3 5,0 ; accent 5,2 ; blanc sur accent 5,7 ; focus 6,0 ; erreur 5,2. Tous ≥ 4,5 (AA) ; `--text-3` est réservé à `--bg` et `--surface-1`.
Accents comparés (décision 2) : **Lagune** (sarcelle, aucune grande plateforme ne l'utilise), **Bleu actuel** (`#7aa2ff`, continuité), **Sakura** (rose). Rouge, orange, jaune et violet écartés : trop associés à des marques de streaming connues.

### 5.2 Typographie
- **Figtree** (variable 300–900, SIL OFL 1.1), embarquée : 20 Ko (latin, français complet) + 10 Ko (latin étendu, chargé seulement pour les macrons : Shōnen, Ōkami). Chiffres tabulaires pour numéros et durées. Japonais : police du système (Noto Sans CJK sur Android, Yu Gothic / Hiragino sur ordinateur) ; embarquer une police japonaise coûterait plusieurs Mo.
- Échelle : affiche 40 · titre 1 32 · titre 2 22 · titre de carte 17 · texte 16 · secondaire 14 · légende 13 (px à 100 %, en `rem` / `sp`).
- Alternative : police du système (0 Ko, mais rendu différent entre Windows, Android et les navigateurs).

### 5.3 Espacements, formes, icônes
- Base 4 px (4, 8, 12, 16, 24, 32, 48, 64) ; marges latérales fluides 16 → 48 px.
- Rayons : 6 (badges), 10 (affiches, cartes), 16 (panneaux, fenêtres), pilule (boutons, puces, recherche).
- Cibles ≥ 48 px partout (puces de 40 px avec zone tactile étendue).
- Icônes **Material Symbols Rounded** (poids 400, Apache 2.0), 39 SVG copiés (`docs/design/icons.svg`), mêmes dessins sur le web et Android ; toujours avec un libellé.

### 5.4 Composants communs
Bouton (principal, secondaire, contour, danger, icône), champ de recherche, menu déroulant, puce de filtre, sélecteur segmenté (saisons), badge (Nouveau, Android seulement), barre de progression, **carte d'affiche** (états : normale, sans affiche avec titre composé, nouveau, en cours, vu, focus), **carte « Reprendre »** (paysage, affiche floutée en fond), **rangée horizontale**, **ligne d'épisode**, **avatar de comédien**, squelettes, état vide, état d'erreur avec action, message (info, avertissement, erreur), toast, panneau de préparation, barre du haut, barre du bas, menu Compte, héros.

### 5.5 Thèmes, texte agrandi, mouvement
- Sombre par défaut ; Clair au choix ; « Système » suit le réglage de l'appareil. Choix enregistré sur l'appareil (web : `localStorage`, appliqué par un petit fichier JS chargé avant l'application pour éviter un flash — pas de script en ligne, donc **aucun changement de CSP**).
- Texte agrandi : tout est en `rem` / `sp` ; au-delà d'environ 130 %, les mises en page côte à côte (héros, fiche, colonnes) passent en colonne (requêtes de conteneur sur le web, `fontScale` en Compose). Démonstration : guide de style, bouton « 200 % ».
- Mouvement : 160 ms, courbe douce ; agrandissement 1,04 des cartes au survol / focus ; squelettes animés ; tout est coupé avec « réduire les animations ».

### 5.6 Équivalent Android (Compose, Material 3)

| Jeton | Rôle Material 3 |
|---|---|
| `--bg` | `background`, `surface` |
| `--surface-1/2/3` | `surfaceContainer`, `surfaceContainerHigh`, `surfaceContainerHighest` |
| `--text`, `--text-2` | `onSurface`, `onSurfaceVariant` |
| `--outline`, `--outline-strong` | `outlineVariant`, `outline` |
| `--accent`, `--on-accent` | `primary`, `onPrimary` ; `primaryContainer` = accent à 16 % |
| `--err` | `error` |
| `--focus` | couleur de l'anneau de focus (hors schéma Material) |

- `AppTheme(theme = Système | Sombre | Clair)` : `darkColorScheme` / `lightColorScheme` construits depuis ces jetons, suppression des couleurs en dur (`AppColors`) dans les écrans ; préférence enregistrée avec le stockage existant de l'app.
- `Typography` sur `FontFamily(Font(R.font.figtree, variationSettings = …))` (TTF variable OFL, poids dans l'APK à mesurer en P3, de l'ordre de 100 Ko) ; tailles en `sp`.
- `Shapes` : small 6 dp, medium 10 dp, large 16 dp, boutons `CircleShape`.
- Composants : `PosterCard`, `ResumeCard`, `Rail` (`LazyRow`), `EpisodeRow`, `PersonAvatar`, `SeasonSelector` (`SegmentedButton` ou menu), `StateBox` (vide / erreur), `Skeleton`, `PreparingPanel`, `PlayerOverlay` + panneau « Audio et sous-titres » (`ModalBottomSheet`).
- **Télécommande (Android TV plus tard)** : un `Modifier.focusRing()` commun (anneau 3 dp couleur focus + agrandissement 1,04 animé) sur tout élément cliquable ; rangées `LazyRow` avec `focusRestorer()` (retour sur la dernière carte) ; ordre de focus = ordre de lecture (héros, rangées de haut en bas) ; aucune action réservée au toucher long ou au glissement ; le lecteur garde la navigation D-pad de Media3.
- Texte agrandi : si `LocalDensity.current.fontScale ≥ 1.3`, héros et en-têtes en colonne, cartes de rangée plus larges.
- Flou du fond : `Modifier.blur` (Android 12+) ; avant Android 12, affiche agrandie + dégradé sombre, sans flou.
- Rien de cela n'a pu être vu sur un téléphone : P3 livrera des captures d'écran Compose (`@Preview`) et un scénario de test manuel.

## 6. Catalogue de démonstration (outil de développement)

- `scripts/demo-catalog.sh` (ou `.ps1`) : génère `demo-catalog/` (ignoré par git) avec `scripts/demo-catalog/generate.py` (Python standard, lancé dans un conteneur `python:3.13-alpine` : rien à installer), démarre une **stack séparée** `plexwish-demo` (sa propre base, son réseau `172.30.65.0/24`, port **8090**) avec `docker-compose.demo.yml`, puis charge les données. `scripts/demo-catalog.sh down` efface tout.
- Contenu (déterministe) : 1 300 animés inventés, 1 935 saisons (dont Spéciaux), 33 354 épisodes (trois séries fleuves de 500 à 1 100 épisodes), titres longs, français accentués, macrons, symboles (⁄, ×, Δ, !!), titres alternatifs en kanji/kana, synopsis absents (25 %), très courts, longs (3 à 5 paragraphes), en anglais (20 %) ; durées manquantes ; 822 affiches générées (formes géométriques, 65 %), le reste sans affiche ; 160 comédiens et 4 200 rôles inventés ; progression (épisodes vus, 9+ en cours) pour chaque compte.
- Garde-fous : jamais dans une image Docker (contextes `backend/` et `web/`) ; le SQL refuse de s'exécuter si la base contient un vrai fichier ; tâches de fond Internet (AniList, TMDB, affiches, distribution) et ffprobe coupées, jetons TMDB vidés ; `/media` remplacé par un volume vide. Ne pas lancer de scan dans la stack de démonstration (il marquerait le catalogue comme disparu) ; la lecture d'un épisode y répond 404 (pas de fichier).

## 7. Dépendances proposées

Rien n'est installé avant ta décision. Licences vérifiées dans les paquets (npm, Maven Central, Google Maven), versions au 2026-10-07.

| Dépendance | Côté | Apport | Coût / risque | Licence | Proposition |
|---|---|---|---|---|---|
| Figtree 5.3 (fichiers woff2 copiés, pas de paquet npm) | web | identité, chiffres tabulaires | +30 Ko, 1 requête ; aucun risque de régression | SIL OFL 1.1 | ✅ |
| Figtree (TTF variable, Google Fonts) | Android | même identité que le web | de l'ordre de 100 Ko dans l'APK (à mesurer) | SIL OFL 1.1 | ✅ (décision 3) |
| Material Symbols Rounded (SVG copiés) | web + Android | icônes modernes cohérentes | 39 icônes, ~21 Ko ; pas de paquet | Apache 2.0 | ✅ |
| `material-icons-core` (actuel, figé en 1.7.8) | Android | — | n'est plus mis à jour par Google | Apache 2.0 | ❌ retiré au profit des vecteurs copiés |
| axe-core 4.14 (dev) | web | tests d'accessibilité automatiques dans les tests existants (jsdom) | dev seulement ; ne mesure pas les contrastes dans jsdom | MPL 2.0 | ✅ (décision 5) |
| @playwright/test + @axe-core/playwright 4.13 (dev) | web | axe dans un vrai navigateur (contrastes), captures avant/après automatiques | Chromium ~150 Mo sur le PC ; tests plus lents | Apache 2.0 / MPL 2.0 | option (décision 5) |
| @angular/cdk 22.2 | web | défilement virtuel, menus | inutile : pagination mesurée suffisante, `<dialog>` et `popover` natifs | MIT | ❌ |
| Angular | web | déjà en 22.2 (dernière : 22.2.1) | — | MIT | pas de mise à jour majeure |
| Compose BOM 2024.12.01 → 2026.09.00 (UI 1.7.6 → 1.12.1, Material 3 1.3.1 → 1.4.0) avec Kotlin 2.0.21 → 2.4.x et AGP 8.7.3 → version compatible | Android | composants Material 3 récents, corrections de focus et de performances, base saine pour la TV | chaîne de build à remonter d'un coup (compileSdk, AGP) ; avertissements de dépréciation ; à revalider sur le S24 | Apache 2.0 | ✅ en bloc séparé P3.0 (décision 4) |
| Coil 2.7.0 → 3.6.3 | Android | branche maintenue (Coil 2 n'évolue plus) | nouveaux noms de paquets + `coil-network-okhttp` ; 2 fichiers touchés | Apache 2.0 | ✅ avec P3.0 |
| Media3 1.5.1 → 1.11.1, `media3-ui-compose` | Android | commandes de lecteur en Compose | lecture validée sur le S24 avec 1.5.1 : tout changement = nouveau test complet (AVI, OGM, sous-titres) | Apache 2.0 | ❌ pendant le Polish (FUTURE) |
| `androidx.tv:tv-material` 1.1.0 | Android | composants TV | — | Apache 2.0 | phase 8 |

## 8. Changements serveur que l'interface demanderait (accord nécessaire)

Aucun n'est fait. Sans eux, P2/P3 restent possibles (les éléments marqués [S] sont alors simplement absents).

| # | Changement | Pour | Taille | Remarques |
|---|---|---|---|---|
| S1 | « À suivre » : une entrée par animé dans `GET /api/me/continue-watching` (dernier épisode commencé **ou épisode suivant** du dernier fini) | Accueil, héros « À reprendre » | petit (requête SQL, tests) | le plus utile ; champ `kind: RESUME / NEXT` |
| S2 | Filtres de `GET /api/anime` : `yearFrom`, `yearTo`, `watch=unseen\|inProgress\|seen` (par utilisateur), `browser=true` (analyse ffprobe) | Bibliothèque | petit à moyen | index existants suffisent à 1 300 animés |
| S3 | Genres AniList : ajout du champ `genres` à la requête AniList déjà faite, migration (table `anime_genre`), filtre `genre=` et liste des genres | rangées « Par genre », filtre | moyen | même nombre d'appels AniList (conditions respectées) ; remplissage au prochain passage de la tâche |
| S4 | `POST /api/me/password` (ancien + nouveau, limitation des essais, révocation des autres sessions) | Compte | petit | sécurité : mêmes règles que l'admin |
| S5 | `resume` dans `GET /api/anime/{id}` (épisode à reprendre ou suivant) | bouton principal de la fiche | petit | facultatif : faisable côté client avec les API actuelles |
| S6 | `sort=year` | Bibliothèque | très petit | |

Sécurité : rien dans la direction proposée ne touche à la CSP (polices et icônes servies par `self`, styles en ligne déjà autorisés, pas de script en ligne), aux jetons ni aux URL signées. Les fonds floutés réutilisent les URL d'affiches existantes.

## 9. Plan de P2 et P3

Chaque bloc : tests verts, un commit, captures avant/après. Mesures « avant » prises sur le catalogue de démonstration : JS initial 130 Ko (38 Ko compressés) + morceaux par page, 500 Ko au total ; DOM 300 (accueil) à 800 (admin Médias) nœuds.

**P2 — web**
- P2.0 *(si accord)* serveur S1, S2, S6 (+ S3, S4 selon décision 1) : tests backend, documentation API.
- P2.1 Fondations : jetons sombre/clair + accent, Figtree, composant d'icône, thème Système/Sombre/Clair, boutons, champs, puces, badges, squelettes, états vide/erreur ; axe dans les tests.
- P2.2 Navigation : barre du haut (bureau), barre du bas (téléphone), menu Compte, page « Rechercher » sur téléphone.
- P2.3 Accueil : héros « À reprendre », rangées.
- P2.4 Bibliothèque / recherche : grille 3 colonnes sur téléphone, « Charger plus », filtres, états.
- P2.5 Fiche : héros, synopsis replié, état de reprise, saisons et plages, liste d'épisodes, distribution en avatars ; texte à 200 % sans débordement.
- P2.6 Comédien, connexion, compte, À propos, page introuvable.
- P2.7 Admin : composants et jetons appliqués, onglets en menu sur téléphone ; pas de refonte.
- P2.8 Mesures après (poids, temps d'affichage, DOM), captures avant/après, axe sur les écrans principaux.
- Risques : tests existants liés au texte et aux sélecteurs (à adapter, pas à supprimer) ; flou CSS coûteux sur un vieux téléphone (fond flouté limité à une petite image, à vérifier) ; poids des polices (mesuré).

**P3 — Android**
- P3.0 *(si accord)* chaîne de build : Compose BOM, Kotlin, AGP, Coil 3 ; tests, APK ; **test de non-régression du lecteur sur le S24** avant la suite.
- P3.1 Thème : `AppTheme` sombre/clair + accent + Figtree + formes, préférence de thème, `focusRing()`, icônes vecteurs.
- P3.2 Composants : `PosterCard`, `ResumeCard`, `Rail`, `EpisodeRow`, `PersonAvatar`, `StateBox`, `Skeleton`.
- P3.3 Accueil, onglet Rechercher, Bibliothèque (filtres si S2).
- P3.4 Fiche (bouton principal, saisons, plages, épisodes), Comédien.
- P3.5 Lecteur : surcouche restylée, panneau « Audio et sous-titres », écrans Préparation et erreurs ; `PlayerView` et Media3 1.5.1 conservés.
- P3.6 Connexion (serveur prérempli), Compte (thème, À propos).
- P3.7 Tests (calculs d'affichage : bouton principal, « reste N min », libellés de pistes, mise en colonne selon `fontScale`), scénario manuel dans `android/README.md` (TalkBack, police 200 %, thème clair, clavier ou manette en D-pad), APK.
- Risques : impossible de voir l'app tourner ici (captures `@Preview` seulement) ; navigation D-pad testée seulement par toi ; flou indisponible avant Android 12 ; la mise à jour de la chaîne peut casser le build (bloc séparé, annulable).

## 10. Décisions à prendre

1. **Bloc serveur P2.0** : lesquels de S1 (« À suivre »), S2 (filtres), S3 (genres), S4 (mot de passe), S6 (tri année) ? Proposition : S1 + S2 + S6 tout de suite, S3 et S4 ensuite.
2. **Accent** : Lagune (proposé), Bleu actuel ou Sakura — à comparer dans le guide de style (boutons en haut).
3. **Police** : Figtree embarquée sur le web et Android (proposé) ou polices du système.
4. **Chaîne Android** : remonter Compose / Kotlin / AGP / Coil en P3.0 (proposé), ou faire le Polish sur les versions actuelles. Media3 ne bouge pas dans les deux cas.
5. **Tests d'accessibilité** : axe-core dans les tests unitaires (proposé, léger) ou, en plus, Playwright (vrai navigateur, captures automatiques, ~150 Mo).

Point ouvert, sans urgence : nom affiché dans les interfaces (« Anime Server » aujourd'hui, ou « PlexWish »).

## 11. Licences de ce qui a été ajouté en P1

| Élément | Où | Licence |
|---|---|---|
| Figtree 5.3.0 (woff2, via le paquet @fontsource-variable/figtree) | `docs/design/*.woff2`, intégrée au guide | SIL OFL 1.1 (`docs/design/OFL-Figtree.txt`) |
| Material Symbols Rounded 400 (SVG, via @material-symbols/svg-400 0.47.6) | `docs/design/icons.svg`, intégrées au guide | Apache 2.0 (`docs/design/Apache-2.0-MaterialSymbols.txt`) |
| Affiches de démonstration | générées par `scripts/demo-catalog/generate.py` | code du projet |
| Image Docker `python:3.13-alpine` (outil de dev, non distribuée) | `scripts/demo-catalog.sh` | PSF / licences Alpine |
