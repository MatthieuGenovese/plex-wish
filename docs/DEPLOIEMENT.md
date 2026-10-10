# Déploiement sur le NAS

Anime Server s'installe sur le NAS Synology d'un ami en **trois gestes de sa part**. Ensuite, presque tout se fait
seul : sauvegardes, renouvellement du certificat HTTPS, mise à jour du nom de domaine. Les mises à jour, elles, sont
lancées par Matthieu.

- **Partie 1 : pour l'ami.** Une page, sans jargon.
- **Partie 2 : pour moi (Matthieu).** Préparation, détails, maintenance, dépannage.
- **Aide-mémoire pour guider l'ami pendant l'appel** : [`AIDE-DEPLOIEMENT-AMI.md`](AIDE-DEPLOIEMENT-AMI.md).

Légende : **(vérifié)** = lu à la source ou testé ici ; **(à vérifier sur place)** = pas pu être confirmé sans le
vrai NAS ou la vraie box.

---

# Partie 1 — Pour mon ami

Anime Server est un petit site privé qui permet à quelques amis de regarder les animés de ton NAS, sur leur téléphone
ou leur navigateur. Il tourne dans des « conteneurs » : des boîtes isolées, gérées par l'application Container
Manager du DSM. Il ne modifie, ne déplace et ne supprime **jamais** tes vidéos (il les lit seulement).

## Ce que tu as à faire : 3 étapes

**1. Installer Container Manager** (une fois, 3 clics)
Ouvrir le **Centre de paquets** du DSM, chercher « Container Manager », cliquer sur **Installer**.

**2. Coller la commande que Matthieu t'envoie** (une fois, environ 12 clics)
Dans le DSM : **Panneau de configuration › Planificateur de tâches › Créer › Tâche planifiée › Script défini par
l'utilisateur** (libellés français à vérifier sur place ; en anglais : *Control Panel › Task Scheduler › Create ›
Scheduled Task › User-defined script*).

- Onglet **Général** : nom « Installer Anime Server », utilisateur **root**, décocher « Activé ».
- Onglet **Programmer** : « Exécuter à la date suivante », « Ne pas répéter ».
- Onglet **Paramètres de tâche** : coller la commande de Matthieu dans la grande case, puis **OK** (le DSM demande ton
  mot de passe).
- Sélectionner la tâche, puis **Exécuter**. L'installation prend quelques minutes.

**3. Ouvrir l'assistant et le suivre**
Dans un navigateur, à la maison : l'adresse de ton NAS (celle du DSM), avec **:8080** à la fin au lieu de :5000.
Exemple : `http://192.168.1.20:8080`. Tu choisis le mot de passe de l'administrateur : note-le en lieu sûr.

Si Matthieu te l'a demandé, il y a aussi la **redirection de port** dans ta box : vous la faites ensemble, au
téléphone.

## Si quelque chose ne va pas

Préviens Matthieu. S'il te le demande : *Administration › Réglages*, ou le résultat de la tâche du Planificateur.
Tu n'as rien à réparer toi-même.

## Ce que Matthieu peut faire sur ton NAS, et comment l'en empêcher

- **Seulement si tu lui as ouvert un accès à distance** (paquet Tailscale et compte DSM à son nom, facultatif) : il
  peut alors administrer le NAS entier. Il s'engage à ne toucher qu'au dossier `docker/anime-server` et à te prévenir
  avant d'intervenir. Ses connexions apparaissent dans le **Centre des journaux** du DSM.
- **Tout arrêter** : désactiver son compte DSM, ou arrêter le paquet Tailscale. Pour arrêter le site lui-même :
  Container Manager › Projet ou Conteneur › arrêter ceux dont le nom commence par `anime-server`.

## Ce que tu n'as jamais à faire

Mettre à jour, sauvegarder, renouveler un certificat, relancer un scan, gérer les comptes des amis (l'administrateur
les invite par un lien) : tout cela est automatique ou fait par Matthieu.

## La commande, expliquée ligne par ligne

Tu peux la relire avant de l'exécuter. Elle ressemble à ceci :

```sh
set -e
export PATH=/usr/local/bin:$PATH
echo 'ghp_…' | docker login ghcr.io -u compte-images --password-stdin
docker run --rm --pull always --entrypoint cat ghcr.io/compte-images/anime-server-backend:1.0.0 /app/deploy/install.sh > /tmp/anime-install.sh
sh /tmp/anime-install.sh --version 1.0.0 --registry ghcr.io/compte-images --media '/volume1/animes' --domain mon-anime.duckdns.org
```

1. `set -e` : s'arrêter à la première erreur, sans continuer à l'aveugle.
2. `export PATH=…` : indiquer où se trouve le programme `docker` installé par Container Manager.
3. `docker login …` : donner à Docker le droit de **télécharger** les images d'Anime Server, qui sont privées.
   `ghp_…` est un jeton en **lecture seule** : il ne donne accès à rien d'autre sur ton NAS ni chez Matthieu.
4. `docker run … cat … install.sh` : télécharger l'image (`--pull always` : toujours depuis le registre, ce qui
   vérifie aussi le jeton), en extraire le script d'installation et le poser dans `/tmp`.
5. `sh /tmp/anime-install.sh …` : lancer l'installation, avec le dossier de tes vidéos (`--media`) et l'adresse du
   site (`--domain`).

Le script d'installation :

1. vérifie le dossier des vidéos, la présence de Container Manager et l'accès aux images (même en simulation) ;
2. télécharge la version demandée ;
3. crée le dossier `docker/anime-server` ;
4. y écrit la configuration (`nas.env`, sans aucun secret), avec une plage d'adresses interne libre sur ce NAS
   (choisie seule, pour ne gêner aucun autre projet Docker) ;
5. démarre le site et affiche l'adresse de l'assistant.

Il ne touche à rien d'autre. Pour **voir ce qu'il ferait sans rien modifier**, Matthieu peut t'envoyer la même
commande avec `--dry-run` à la fin.

Après l'installation, tu peux supprimer la tâche du Planificateur (elle contient le jeton de lecture ; Docker l'a
déjà enregistré pour les mises à jour).

---

# Partie 2 — Pour moi

## 1. À préparer, dans l'ordre

| # | Quoi | Pourquoi | Où |
| --- | --- | --- | --- |
| 1 | Un **compte ou une organisation GitHub dédiés aux images** | Le jeton « classic » `read:packages` lit **tous** les paquets du compte (GHCR n'accepte pas les jetons fins) : un compte dédié limite ce qu'un jeton volé permet de lire. | github.com |
| 2 | Deux jetons « classic » sur ce compte : `write:packages` (pour le PC, publication) et `read:packages` (pour le NAS) | Le NAS ne peut que lire. Expiration conseillée : un an, avec un rappel. | GitHub › Settings › Developer settings |
| 3 | `docker login ghcr.io` sur le PC avec le jeton d'écriture | Publication des images | PC |
| 4 | Un compte **DuckDNS** et un sous-domaine (ex. `mon-anime.duckdns.org`), son **jeton** | Nom de domaine gratuit, mis à jour par le serveur toutes les 5 min | duckdns.org (connexion par GitHub, Google…) |
| 5 | L'application **ntfy** sur ton téléphone | Alertes (D1b) | magasin d'applications |
| 6 | Si accès à distance ou plan B : un compte **Tailscale** et sa console | SSH au NAS ; Funnel si CGNAT | login.tailscale.com |
| 7 | Clé TMDB **propre au NAS** (facultatif) | Ta clé personnelle ne quitte jamais ton PC | themoviedb.org |

## 2. Architecture sur le NAS

Dossier du projet : `/volume1/docker/anime-server` (`--data` pour un autre).

| Conteneur | Rôle | Données |
| --- | --- | --- |
| `init` | Au démarrage : crée les secrets (64 caractères aléatoires, fichiers 600), choisit l'utilisateur qui lit le dossier des vidéos, remet les droits des dossiers, puis s'arrête | volumes `secrets_app`, `secrets_pg` |
| `postgres` | Base de données (PostgreSQL 16) | volume `pgdata` |
| `backend` | Serveur (Java), sans root (utilisateur du dossier des vidéos), ffmpeg | `posters/`, `remux-cache/`, vidéos en **lecture seule** |
| `web` | nginx : porte locale (port 8080 du NAS, **assistant seulement**) et porte publique (8081, interne, pour Caddy) | — |
| `caddy` | HTTPS sur Internet (port 8443 du NAS), certificat Let's Encrypt renouvelé seul | volume `caddy_data` |
| `backup` | Sauvegarde vérifiée chaque nuit à 3 h, rotation 7/4/6 | `backups/` |
| `tailscale` | Plan B seulement (profil `funnel`) | volumes `secrets_ts`, `tailscale_state` |

Fichiers du dossier du projet :

- `compose.yml` : la description des conteneurs.
- `nas.env` : les réglages, sans secret.
- `app/` : les scripts de maintenance, recopiés depuis l'image à chaque mise à jour.
- `backups/`, `posters/`, `remux-cache/` : les données.

Les secrets sont dans des **volumes Docker**, pas dans un dossier partagé du DSM : leurs droits ne dépendent pas des
ACL Synology, et ils ne sont visibles d'aucun utilisateur du DSM.

## 3. Publier une version

1. Augmenter `VERSION`, puis commiter.
2. Lancer `scripts\publish-images.ps1` après `$env:GHCR_OWNER = "compte-images"` (sous Linux : `scripts/publish-images.sh`).

Le script :

- construit les deux images (l'application y est **déjà compilée** : rien n'est compilé sur le NAS) ;
- les inspecte **couche par couche** ;
- **refuse de publier** dans ces cas :
  - un secret est trouvé : nom de fichier sensible, motif de clé ou de jeton, ou valeur de ton `.env` et des fichiers
    de signature Android ;
  - le dépôt n'est pas propre ;
  - la version existe déjà sur le registre, sauf avec `-Replace` (`REPLACE=1` sous Linux) : **réservé aux versions
    d'essai jamais installées chez l'ami**. Sur une pile d'essai déjà à cette version, `update.sh` refuse (« déjà
    installée ») : recharger les images avec `docker compose --env-file nas.env -f compose.yml pull backend web`
    puis `sh app/restart.sh`.

Les images contiennent l'application et les scripts de `deploy/`, aucun secret.

## 4. Installer

1. **Avant l'appel** : `scripts\make-install-command.ps1 -Owner compte-images -Media /volume1/animes -Domain mon-anime.duckdns.org`
   (le jeton de lecture est demandé sans s'afficher).
   - La commande produite (5 lignes, lisible, sans bloc encodé) part par **message privé**.
   - Pour une simulation : `--dry-run` à la fin de la dernière ligne.
2. Pendant l'appel : l'ami suit la partie 1.
3. **Vérifications d'accès** :
   - avec l'ami, sur un PC de son réseau : `check-access.ps1 -Domain … -WanIp <adresse WAN de la box>` ;
   - chez toi : `check-access.ps1 -Domain … -Outside`.

   Les scripts détectent le CGNAT, le nom de domaine qui ne pointe pas au bon endroit, le port fermé et l'absence de
   boucle locale, et disent quoi faire pour chacun.
4. **Assistant** (réseau local, `http://<NAS>:8080`) :
   - vérifications ;
   - compte administrateur ;
   - seuils d'espace disque, proposés d'après l'espace libre mesuré (10 % / 4 % / 15 %) ;
   - jeton DuckDNS, vérifié auprès de DuckDNS avant d'être gardé ;
   - clé TMDB (facultative) ;
   - premier scan ;
   - fin.

   Tant que l'assistant n'est pas terminé, le site public répond « installation en cours », et l'assistant n'est
   accepté que depuis le réseau local.
5. **Inviter les amis** : *Administration › Utilisateurs* : créer le compte, puis copier le lien (usage unique, 72 h)
   et l'envoyer.

Relancer la commande d'installation ne casse rien : `nas.env` et les données sont gardés.

### Répétition sur ton PC (Windows), avant le NAS

Faite le 2026-10-10 avec la version 1.0.0 : installation, assistant, HTTPS public par Caddy et DuckDNS. Ce qu'il faut
savoir :

- **Un vrai Linux** : le script d'installation tourne en root, comme sur le NAS. Utiliser **WSL** (Ubuntu), pas Git
  Bash : Git Bash réécrit les chemins (`/app/deploy/install.sh` devient `C:/Program Files/Git/app/…`).
- **Docker Desktop** : *Settings › Resources › WSL integration*, activer Ubuntu, puis rouvrir le terminal
  (sinon « The command 'docker' could not be found in this WSL 2 distro »). Vérifier `sudo docker version`.
- **Dossiers** : `sudo mkdir -p /srv/essai/media`, y copier quelques vidéos (`dev-media`), puis `sudo -i` et coller
  la commande générée avec `-Media /srv/essai/media`. Le projet s'installe dans `/volume1/docker/anime-server`,
  comme sur un NAS.
- **Assistant** : `http://localhost:8080` dans le navigateur Windows (l'installateur affiche `localhost` sous WSL).
  L'adresse publique répond « installation en cours » tant que l'assistant n'est pas fini : c'est voulu.
- **HTTPS réel** (facultatif) : redirection 443 → 8443 du PC sur ta box, port 8443 autorisé dans le pare-feu
  Windows, jeton DuckDNS dans l'assistant (le nom pointe alors chez toi ; il sera repointé vers le NAS à son
  installation).
- **Plage réseau** : depuis la 1.0.1, l'installateur en choisit une libre (ta pile de démonstration occupe
  `172.30.65.0/24`).
- **Nettoyage** : `cd /volume1/docker/anime-server && docker compose --env-file nas.env -f compose.yml down -v`,
  puis `rm -rf /volume1 /srv/essai`, `docker logout ghcr.io`, et retirer la redirection de la box.

## 5. Accès depuis Internet

**Par défaut : Caddy dans le projet.**

- La box redirige **443 TCP (externe) → port 8443 du NAS** : le DSM occupe déjà 80, 443, 5000 et 5001 (vérifié,
  [Synology](https://kb.synology.com/en-global/DSM/tutorial/What_network_ports_are_used_by_Synology_services)).
- Caddy obtient le certificat par le **défi TLS-ALPN**, sur le port 443 seulement : le port 80 n'est pas nécessaire
  (vérifié, [Let's Encrypt](https://letsencrypt.org/docs/challenge-types/),
  [Caddy](https://caddyserver.com/docs/caddyfile/directives/tls)).
- Le nom DuckDNS est mis à jour par le serveur toutes les 5 minutes. Le jeton est un secret (fichier 600) ;
  l'URL d'appel, qui contient le jeton, n'est jamais journalisée.
- Pas à pas de la box : [`AIDE-DEPLOIEMENT-AMI.md`](AIDE-DEPLOIEMENT-AMI.md).

**Choix du DNS dynamique**

| Service | Mise à jour par un conteneur | Conditions (lues à la source) | Retenu |
| --- | --- | --- | --- |
| **DuckDNS** | Oui : `https://www.duckdns.org/update?domains=…&token=…&ip=` ; une IP vide = l'adresse de l'appelant ([spec](https://www.duckdns.org/spec.jsp)) | Gratuit, usages illégaux interdits, compte suspendable « à tout moment » ; rien sur la vidéo ni la bande passante ([CGU](https://www.duckdns.org/tac.jsp)). `duckdns.org` est sur la *Public Suffix List* : les quotas Let's Encrypt comptent par sous-domaine ([PSL](https://publicsuffix.org/list/public_suffix_list.dat)). Non confirmé : expiration après inactivité. | **Oui** |
| DDNS Synology (`synology.me`) | Non (mise à jour depuis le DSM seulement ; aucune API publique trouvée) | Compte Synology ; Synology peut retirer un nom ([CGU](https://www.synology.com/en-global/company/legal/terms_conditions_account)) | Non : clics dans le DSM |
| deSEC (`dedyn.io`) | Oui (HTTP Basic) | Gratuit ; un seul nom `dedyn.io` par compte ; CGU détaillées non trouvées | Plan de rechange |
| dynv6 | Oui (`ipv4=auto`) | Gratuit ; le site dit « ne pas l'utiliser pour des services critiques » | Non |

**Plan B, box derrière un CGNAT : Tailscale Funnel** (profil `funnel`, section 9).

**Exclu : Cloudflare Tunnel.** Ses conditions interdisent la vidéo par sa bande passante sur les offres Free, Pro et
Business, routes publiques de Tunnel comprises (vérifié,
[Cloudflare](https://developers.cloudflare.com/fundamentals/reference/policies-compliances/delivering-videos-with-cloudflare/index.md)).

## 6. Mettre à jour

Depuis le PC : `scripts\update.ps1 -Nas <nom Tailscale du NAS> -User <ton compte DSM>`. Le script publie les images,
puis lance `app/update.sh` sur le NAS par SSH ; le NAS demande ton mot de passe DSM pour `sudo`.

`update.sh` déroule cinq étapes :

1. téléchargement (le site tourne encore) ;
2. sauvegarde « avant-maj », vérifiée ;
3. nouvelle version ;
4. santé du serveur, du web et de la chaîne web → serveur (5 min au plus) ;
5. en cas d'échec : **retour automatique** à l'ancienne version, et restauration de la sauvegarde si une migration a
   modifié la base. Le journal de l'échec reste dans `backups/echec-maj-<version>.log`.

Les mises à jour ne sont **jamais automatiques**, pour deux raisons :

- un conteneur qui se met à jour seul doit piloter Docker, ce qui revient à être root en permanence sur le NAS ;
- une migration ne se défait que par une restauration : mieux vaut que quelqu'un surveille.

Sans accès à distance : `sudo sh app/update.sh <version>` dans le dossier du projet, lancé par l'ami (Planificateur de
tâches) ou sur place.

## 7. Sauvegardes et restauration

- **Chaque nuit à 3 h**, plus un rattrapage si la dernière a plus de 26 h. Chaque sauvegarde est **restaurée dans une
  base d'essai** et sa version de schéma comparée avant d'être gardée.
- Rotation : 7 quotidiennes, 4 hebdomadaires, 6 mensuelles, plus les 5 dernières « avant-maj » et « avant-restauration ».
- État visible dans *Administration › Réglages* (fichier `backups/status.json`).
- **Restaurer** : `sudo sh app/restore.sh backups/anime-db-AAAAMMJJ-HHMMSS.dump`. Le script vérifie le fichier, fait
  une sauvegarde de sécurité, restaure en une transaction, redémarre et vérifie la santé.
- **Limite** : tout est sur le même NAS. Pour une copie ailleurs, l'ami ajoute `docker/anime-server/backups` à
  Hyper Backup, ou tu récupères le dossier par Tailscale de temps en temps.
- Aucune clé ni aucun jeton du serveur dans les sauvegardes (seulement les empreintes des mots de passe, d'où les
  droits 600). Après une perte totale, les secrets se recréent seuls ; seules les sessions sont à rouvrir.

## 8. Accès à distance pour toi (facultatif, avec le consentement de l'ami)

1. Paquet **Tailscale** du Centre de paquets, connecté à ton compte Tailscale
   ([doc Tailscale pour Synology](https://tailscale.com/docs/integrations/synology.md), vérifié).
2. Un **compte DSM à ton nom** dans le groupe administrateurs (Docker l'exige ; le DSM n'a pas de droit plus fin).
3. **SSH** activé dans le DSM. Tailscale SSH ne fonctionne pas sur Synology (vérifié) : c'est le SSH du DSM qui sert,
   joignable seulement depuis le réseau local et ton tailnet, jamais redirigé par la box.
4. Dans ta console Tailscale : règles d'accès qui n'autorisent que **tes** appareils vers le NAS (ports 22 et 5001),
   et rien du NAS vers tes appareils.

Ce que ça implique :

- **Portée** : administrateur = accès à tout le NAS de l'ami. C'est un engagement moral : ne toucher qu'au projet, et
  prévenir avant d'intervenir.
- **Révocation** par l'ami, en 1 à 3 clics : désactiver ton compte DSM, ou arrêter le paquet Tailscale.

## 9. Plan B : Tailscale Funnel (box derrière un CGNAT)

1. **Console Tailscale** :
   - activer MagicDNS et HTTPS ;
   - dans la politique, ajouter l'attribut `funnel` à l'étiquette `tag:anime` ;
   - créer une clé d'authentification **étiquetée** `tag:anime`, à usage unique, qui expire vite
     ([doc Funnel](https://tailscale.com/docs/features/tailscale-funnel.md), vérifié).
2. **Installation** : `--funnel` et `--domain anime.<ton-tailnet>.ts.net`. La clé est demandée sans s'afficher et
   rangée dans le volume `secrets_ts` (600). Le conteneur la lit dans ce fichier (`TS_AUTHKEY=file:…`) : le code de
   l'image transmet la valeur telle quelle à `tailscale up`, qui accepte le préfixe `file:` (vérifié dans le code
   source de la version 1.102.5 ; à confirmer en vrai).
3. **Limites à connaître** :
   - « bande passante limitée, non configurable », sans chiffre publié, et Funnel est en bêta : toute la vidéo passe
     par les relais Tailscale. Débit à mesurer avant d'inviter tout le monde.
   - Les conditions de Tailscale ne parlent pas de vidéo, mais interdisent une « charge excessive » sur le service
     ([conditions](https://tailscale.com/legal/terms), [politique d'usage](https://tailscale.com/tailscale-aup)).
   - Non vérifié : si Funnel transmet la vraie adresse des visiteurs. Sinon, l'anti brute force voit tous les
     visiteurs avec la même adresse.

## 10. Sécurité

- **Pas de mot de passe par défaut.** Les secrets sont générés sur le NAS, jamais affichés ni journalisés, et
  absents des images et des sauvegardes.
- **Portes d'entrée** : le port local du NAS (8080) ne sert que l'assistant ; Internet passe par Caddy (HTTPS,
  HSTS). En-tête de porte posé par nginx, impossible à imiter par un visiteur (testé).
- **Comptes** : liens d'invitation à usage unique ; anti brute force sur la connexion et sur les liens.

**En cas de compromission** :

- **Un compte** : le désactiver (sessions et lien en attente révoqués), puis lui envoyer un nouveau lien.
- **Le compte administrateur** : se connecter avec un second administrateur (en créer un pour toi dès
  l'installation), désactiver le compte touché.
- **Un secret du serveur** :
  1. Dans un conteneur jetable qui monte le volume, supprimer `keys/jwt_secret` ou `keys/stream_signing_secret` :
     `docker run --rm -v anime-server_secrets_app:/s alpine rm /s/keys/jwt_secret` (exemple).
  2. Lancer `sh app/restart.sh` : init en crée un nouveau. Tout le monde se reconnecte, ou les lectures en cours
     redémarrent.
- **Le mot de passe de la base** : `sh app/repair-db-password.sh` (nouveau mot de passe appliqué sans perte).
- **Jeton GHCR ou clé Tailscale** : les révoquer dans leur console, en recréer, relancer la commande d'installation.
- **Doute sur le NAS** : couper la redirection de port (ou `docker stop anime-server-caddy-1`), puis analyser.

## 11. Dépannage

| Symptôme | Piste |
| --- | --- |
| « accès refusé à … : jeton de lecture absent, invalide ou révoqué » | Refaire `docker login ghcr.io` avec le jeton `read:packages` (un jeton supprimé sur GitHub ne marche plus) |
| « Pool overlaps with other one on this address space » | Une installation antérieure à la 1.0.1 : mettre une plage libre dans `DOCKER_SUBNET` (`nas.env`), puis `sh app/restart.sh` |
| Affiche ancienne après une correction manuelle | Depuis la 1.0.1, la nouvelle s'affiche tout de suite ; sinon *Affiches › retélécharger* |
| Distribution « en attente » | *Distribution* dit pourquoi : métadonnées en cours (prioritaires), ou heure du prochain passage |
| La tâche d'installation échoue | Activer « Enregistrer les résultats » dans la tâche (à vérifier sur place), relancer avec `--dry-run`, lire les messages « ÉCHEC : … » |
| L'assistant signale « Dossier des vidéos » | Droits du dossier partagé pour l'utilisateur indiqué, ou `MEDIA_UID` / `MEDIA_GID` dans `nas.env`, puis `sh app/restart.sh` |
| `init` refuse : « les secrets ont disparu, mais la base existe encore » | `sh app/repair-db-password.sh` (aucune donnée effacée) |
| Site injoignable de l'extérieur | `check-access.ps1 -Outside` : CGNAT, redirection, pare-feu du DSM (autoriser 8443) |
| Marche dehors, pas à la maison | Box sans boucle locale (hairpin) : voir l'aide-mémoire |
| Certificat invalide | Attendre quelques minutes ; `docker logs anime-server-caddy-1` ; port 443 bien redirigé vers 8443 ? |
| Mise à jour en échec | Retour arrière déjà fait ; lire `backups/echec-maj-<version>.log` |
| Disque presque plein | *Réglages* : seuils et place des copies converties ; le cache de remux se vide seul au-delà du plafond |

## 12. Ce qui exige le vrai NAS (D2)

| Bloc | À vérifier sur le DS923+ |
| --- | --- |
| D1.1 | Téléchargement depuis GHCR avec le jeton de lecture ; images x86-64 sur le Ryzen R1600 |
| D1.2 | Utilisateur trouvé pour le vrai dossier des vidéos (ACL Synology) ; droits des volumes sur Btrfs |
| D1.3 | Port 8080 libre ; Caddy et Let's Encrypt avec la vraie redirection ; boucle locale de la box ; DuckDNS |
| D1.4 | Lien d'invitation ouvert sur un téléphone, à l'adresse publique |
| D1.5 | Durée et taille d'une sauvegarde de la vraie base ; Hyper Backup du dossier `backups` |
| D1.6 | `docker compose` du DSM (Container Manager 24.0.2-1543 pour DSM 7.2.1 annonce la commande `docker compose`, vérifié dans les notes de version) ; `update.sh` en `sudo` par SSH |
| D1.7 | Libellés du Planificateur de tâches ; sortie de la tâche ; `--dry-run` ; paquet Tailscale ; Funnel si CGNAT |
