# Aide-mémoire : guider l'ami pendant l'installation

C'est **ma** fiche pour l'appel avec l'ami qui héberge le NAS : quoi préparer, quoi lui demander, quoi lui faire
faire, dans l'ordre. Chaque terme technique est expliqué en une phrase, pour que je puisse le lui redire simplement.

Le détail technique est dans [`DEPLOIEMENT.md`](DEPLOIEMENT.md). Situation au 2026-10-09.

**Légende** : **(vérifié)** = lu sur une page officielle du fournisseur ou de Synology, lien donné ;
**(source tierce)** = presse, forum ou blog, lien donné ; **(à vérifier sur place)** = je n'ai pas pu le confirmer :
regarder ensemble à l'écran, ne rien affirmer avant. Je n'invente aucun nom de menu : quand je n'ai pas la source,
je le dis.

---

## 1. Avant l'appel

### Ce que l'ami doit avoir sous la main

- [ ] **Accès administrateur au DSM** : son identifiant et son mot de passe, sur un ordinateur de la maison.
- [ ] **Accès à l'interface de sa box** : l'adresse (souvent `192.168.1.1` ou un nom comme `mafreebox.freebox.fr`)
      et le mot de passe administrateur (souvent sur l'étiquette de la box).
- [ ] **Le nom de son fournisseur et le modèle de sa box** (inscrits sur la box).
- [ ] **L'espace libre sur son volume** : le DSM l'affiche dans le Gestionnaire de stockage (à vérifier sur place).
- [ ] Un **téléphone en 4G**, pour tester depuis l'extérieur à la fin.

### Ce que je dois avoir préparé

- [ ] Les images de la version publiées : `scripts\publish-images.ps1`.
- [ ] Le **nom DuckDNS** créé sur duckdns.org (ex. `mon-anime.duckdns.org`) et son **jeton**, à coller dans
      l'assistant. L'ami n'a rien à créer.
- [ ] La **commande d'installation**, générée avec `scripts\make-install-command.ps1` (il faut déjà connaître le
      chemin du dossier des vidéos : question 7), prête à envoyer en message privé. Aussi sa variante `--dry-run`
      (simulation).
- [ ] `scripts\check-access.ps1` à lui envoyer (ou le lancer sur son PC à distance).
- [ ] L'application **ntfy** sur mon téléphone (alertes, D1b).
- [ ] Pour le plan B ou l'accès à distance : ma console Tailscale ouverte.

---

## 2. À dire à mon ami avant de commencer

- « Je vais installer un petit site qui **lit** tes animés pour les montrer à nos amis. Il ne modifie, ne déplace et
  ne supprime jamais tes fichiers. Il vit dans le dossier `docker/anime-server` de ton NAS. »
- « Je ne toucherai à rien d'autre sur ton NAS, et je ne regarderai pas tes autres dossiers. »
- « Je vais te demander d'ouvrir **un seul port** de ta box, vers le NAS. C'est ce qui permet aux amis d'accéder au
  site depuis chez eux, en connexion sécurisée (HTTPS). »
- « Si tu veux que je puisse faire les mises à jour à distance, il faudra me créer un compte sur ton NAS. C'est
  facultatif, et tu pourras le désactiver à tout moment. »
- « Pour tout arrêter : supprimer la redirection de port dans la box (plus d'accès depuis Internet), ou arrêter les
  conteneurs `anime-server` dans Container Manager. »

---

## 3. Les questions à poser

| # | Question | Comment l'ami trouve la réponse | Ce que ça change |
| --- | --- | --- | --- |
| 1 | **Fournisseur d'accès et modèle de box ?** | Étiquette de la box, ou son espace client | Vocabulaire des menus (section 4) ; risque de CGNAT (question 2) |
| 2 | **Est-il derrière un CGNAT ?** | Méthode ci-dessous | Oui : demander une IPv4 complète au fournisseur, ou plan B (section 9). Non : on continue. |
| 3 | **Adresse IP fixe ou dynamique ?** | Espace client ou interface de la box | Rien à faire : le nom DuckDNS suit l'adresse toutes les 5 minutes dans les deux cas |
| 4 | **IPv6 ?** | Interface de la box (rubrique IPv6, à vérifier sur place) | Rien en D1 : le site s'annonce en IPv4. Une box sans IPv4 publique mais avec IPv6 reste un cas de CGNAT pour nous |
| 5 | **Version du DSM et de Container Manager ?** | DSM : Centre d'infos du Panneau de configuration (à vérifier sur place). Container Manager : Centre de paquets | Container Manager 24.0.2-1543 demande DSM 7.2.1 et apporte la commande `docker compose` (vérifié, [notes de version](https://www.synology.com/en-global/releaseNote/ContainerManager)). Plus ancien : les scripts se rabattent sur `docker-compose` (à vérifier sur place) |
| 6 | **Modèle du NAS et mémoire ?** | Centre d'infos (à vérifier sur place) | DS923+ avec 4 Go : suffisant (le NAS ne compile rien). Moins de 4 Go : me prévenir |
| 7 | **Chemin du dossier des vidéos ?** | File Station, clic droit sur le dossier, Propriétés : l'emplacement ressemble à `/volume1/animes` (à vérifier sur place) | C'est `--media` dans la commande d'installation |
| 8 | **Droits et ACL de ce dossier ?** | Panneau de configuration, Dossier partagé, le dossier, Modifier, Permissions (à vérifier sur place) | L'installation trouve seule un utilisateur qui sait lire le dossier. Si l'assistant affiche « Dossier des vidéos : À corriger », donner la **lecture** du dossier à l'utilisateur indiqué. Une ACL est une liste de droits par utilisateur que Synology ajoute aux dossiers partagés |
| 9 | **Espace libre ?** | Gestionnaire de stockage (à vérifier sur place) | L'assistant propose les seuils d'alerte et la place des copies converties d'après cet espace. Moins de 20 Go : le cache sera petit |

### Méthode pour savoir s'il est derrière un CGNAT

Le CGNAT (*Carrier-Grade NAT*) : le fournisseur partage une même adresse Internet entre plusieurs clients. Sa box
n'a alors pas d'adresse à elle, et une redirection de port ne peut pas marcher.

1. Dans l'interface de la box, lire l'**adresse IPv4 Internet** (ou « WAN » : *Wide Area Network*, le côté Internet
   de la box).
2. Sur un ordinateur de la maison, lancer `check-access.ps1 -Domain <nom> -WanIp <cette adresse>`. Le script compare
   avec l'adresse que voit Internet (api.ipify.org).
3. **Lecture du résultat :**
   - adresses **identiques** : pas de CGNAT ;
   - adresse de la box en `10.…`, `100.64.…` à `100.127.…`, `172.16.…` à `172.31.…` ou `192.168.…` : **CGNAT** ;
   - adresses **différentes** : CGNAT, ou un second routeur devant la box.

Ce que disent les fournisseurs :

- **Free** : certaines lignes ont une IPv4 **partagée**, où seule une plage de ports est réservée au client
  (source tierce, Univers Freebox, article n° 35583, 2016, et article n° 596643, 2026). On obtient une adresse complète
  par l'Espace Abonné : **Ma Freebox**, puis « **Demandez une adresse IP V4 fixe full-stack** », puis Valider, et
  redémarrer la box 30 minutes après (vérifié, [assistance Free](https://assistance.free.fr/articles/1758)).
  Gratuit selon la presse en 2016 ([Kulturegeek](https://kulturegeek.fr/news-86903/freebox-free-propose-davoir-vraie-ip-fixe-zones-moyennement-denses)),
  non confirmé aujourd'hui.
- **Orange** : CGNAT présent sur certaines Livebox, désactivable dans l'interface selon la presse
  (source tierce, [AlloForfait](https://alloforfait.fr/internet/news/139173-orange-cgnat-livebox-partager-adresses-ipv4.html)).
  Les sources se contredisent ; situation actuelle non confirmée.
- **Bouygues** : connexions grand public « mutualisées » par défaut selon un blog, avec une option « IP dédiée » dans
  l'espace client (source tierce, [blog](https://blog.jeanvw.fr/fr/posts/configurer-sa-bbox-pour-avoir-une-ip-publique-dediee/)).
  Coût non confirmé.
- **SFR** (Box 8) : si la box n'a pas d'IPv4, ou une IPv4 en `10.…`, la connexion passe en IPv6 (vérifié,
  [assistance SFR](https://assistance.sfr.fr/internet-tel-fixe/box-8/se-connecter-en-ipv6-ipv4.html)) : c'est un
  CGNAT pour nous. Procédure pour obtenir une IPv4 complète non confirmée.

---

## 4. La redirection de port, pas à pas

Une **redirection de port** dit à la box : « ce qui arrive d'Internet sur tel port, envoie-le à tel appareil de la
maison, sur tel port ».

| Réglage | Valeur | Pourquoi |
| --- | --- | --- |
| Port externe | **443** | Le port standard du HTTPS : les amis tapent `https://mon-anime.duckdns.org` sans numéro |
| Port interne | **8443** | Le DSM occupe déjà 443 sur le NAS (et 80, 5000, 5001) (vérifié, [Synology](https://kb.synology.com/en-global/DSM/tutorial/What_network_ports_are_used_by_Synology_services)). Caddy, la porte d'entrée du site, écoute sur 8443 |
| Protocole | **TCP** | Le HTTPS passe en TCP ; inutile d'ouvrir l'UDP |
| Appareil de destination | **L'adresse locale du NAS** (ex. `192.168.1.20`) | Celle qui sert à ouvrir le DSM |

Le défi du certificat (Let's Encrypt) passe par ce même port 443 : **pas besoin d'ouvrir le port 80** (vérifié,
[Let's Encrypt](https://letsencrypt.org/docs/challenge-types/)).

### D'abord : une adresse fixe pour le NAS dans la maison

La box distribue des adresses aux appareils (le **DHCP**), et elle peut en changer. Si l'adresse du NAS change, la
redirection pointe dans le vide. On **réserve** donc son adresse : la box lui donnera toujours la même. On appelle
cela « bail statique » ou « réservation DHCP ».

### Les menus selon la box

À dire avec prudence : « ça peut s'appeler ainsi selon les modèles ». Il n'y a aucune capture d'écran ici.

| Box | Interface | Réserver l'adresse du NAS | Rediriger le port | Boucle locale (hairpin) |
| --- | --- | --- | --- | --- |
| **Freebox** | Appli Freebox Connect, ou `mafreebox.freebox.fr` (source tierce, [Selectra](https://selectra.info/telecom/fournisseurs/free/freebox-os)) | Freebox Connect : Réseau, « … », Paramètres réseau avancés, « **DHCP** » (attribuer une IP manuellement) (vérifié, [assistance Free](https://assistance.free.fr/articles/1395)). Freebox OS : « DHCP et baux statiques » (source tierce) | Freebox Connect : même chemin, « **Redirection de port** » (vérifié, même page). Freebox OS : « Gestion des ports » dans les Paramètres de la Freebox (source tierce, [forum HACF](https://forum.hacf.fr/t/gestion-des-ports-sur-une-freebox/1800)) | Non confirmé |
| **Livebox** (Orange) | `http://livebox/` ou `192.168.1.1` ; identifiant `admin` ; mot de passe : les 8 premiers caractères de la clé de sécurité de l'étiquette (vérifié, assistance.orange.fr, page « Comment accéder à l'interface de configuration de ma Livebox », réf. 457878-987107) | Livebox 6 : Réseau, onglet DHCP, « **Baux DHCP statiques** », Ajouter (vérifié, assistance Orange) | Livebox 6 et 7 : Paramètres avancés, **Réseau**, onglet **NAT/PAT** (vérifié, assistance Orange) | **Oui**, Livebox 4 à 7 (vérifié, page « Livebox : le loopback » de l'assistance Orange) |
| **Bbox** (Bouygues) | `mabbox.bytel.fr`, `gestionbbox.lan` ou `192.168.1.254` (source tierce, [ladsl.com](https://ladsl.com/internet/operateurs/bouygues/configuration-bbox)) | Libellé non confirmé (à vérifier sur place) | Réseau, « **NAT/PAT** » ou « Redirection de ports » (source tierce, même page) | Non confirmé |
| **SFR Box 8** | `http://192.168.1.1` | **LAN**, « **Baux statique** », rubrique IPv4, « ajouter bail statique » (vérifié, [assistance SFR](https://assistance.sfr.fr/internet-tel-fixe/box-8/se-connecter-en-ipv6-ipv4.html)) | **Sécurité**, **Accès**, Réseau v4, « **Redirection des ports** », Créer une règle (vérifié, même page) | Non confirmé |

Ordre pendant l'appel :

1. Réserver l'adresse du NAS.
2. Créer la redirection 443 → adresse du NAS, port 8443, TCP.
3. Enregistrer.
4. Noter les valeurs ensemble.

---

## 5. Réglages à vérifier ou à éviter

- **Pare-feu du DSM** (s'il est activé) : ajouter une règle qui autorise le port **8443** en TCP. Chemin : Panneau
  de configuration, Sécurité, Pare-feu, Modifier les règles, Créer, ports « Personnalisé » (vérifié en anglais :
  *Control Panel › Security › Firewall › Edit Rules › Create*,
  [Synology](https://kb.synology.com/en-global/DSM/help/DSM/AdminCenter/connection_security_firewall?version=7) ;
  libellés français à vérifier sur place). Les règles s'appliquent dans l'ordre de la liste : la placer **avant**
  une règle « tout refuser ». Le port **8080** (assistant) n'a besoin d'être autorisé que depuis le réseau local.
- **Boucle locale** (*hairpin*, *NAT loopback*, « loopback ») : la capacité de la box à laisser un appareil de la
  maison joindre l'adresse publique de la maison. Sans elle, le site marche en 4G mais pas en Wi-Fi à la maison.
  Livebox : prise en charge (vérifié). Autres box : non confirmé.
- **UPnP** : le laisser **désactivé** pour ce projet. Un appareil pourrait ouvrir des ports tout seul ; ici, on
  ouvre un seul port, à la main.
- **DMZ** : ne **pas** l'utiliser. Elle enverrait **tout** Internet vers le NAS, DSM compris. Une redirection d'un
  seul port suffit.
- **HTTP vers HTTPS** : le port 80 n'est pas ouvert. Les amis doivent taper `https://…` ; ensuite, le navigateur
  retient de toujours utiliser HTTPS (HSTS).
- **Ports déjà utilisés par le DSM** : 80 et 443 (serveur web), 5000 et 5001 (DSM) (vérifié). **Ports du projet** :
  8443 (Internet, via la box) et 8080 (assistant, réseau local seulement, **jamais** redirigé). Si l'un d'eux est
  déjà pris sur son NAS, l'installation le dit : choisir un autre port avec `--public-port` ou `--lan-port`.

---

## 6. Le nom de domaine dynamique

Un **nom de domaine dynamique** est un nom fixe (ex. `mon-anime.duckdns.org`) qui suit l'adresse de la box quand
elle change. Le fournisseur peut changer cette adresse : Orange la dit « préférentielle », renouvelable
(vérifié, assistance.orange.fr, page « Adresses IP : les éléments à connaître », réf. 238182-760947) ;
SFR la dit généralement dynamique (source tierce).

- **Moi**, avant l'appel :
  1. créer le compte DuckDNS ;
  2. choisir le sous-domaine ;
  3. garder le **jeton** ;
  4. pendant l'assistant, coller le jeton : le serveur le vérifie auprès de DuckDNS avant de le garder.
- **L'ami** : rien. C'est le serveur qui prévient DuckDNS toutes les 5 minutes.

---

## 7. Vérifications après l'installation

| Quoi | Comment | Attendu |
| --- | --- | --- |
| L'adresse répond **depuis l'extérieur** | Téléphone de l'ami **en 4G, Wi-Fi coupé** : ouvrir `https://mon-anime.duckdns.org`. Ou chez moi : `check-access.ps1 -Domain … -Outside` | La page de connexion, avec le cadenas |
| Le **certificat** est valide | Cadenas du navigateur, sans avertissement ; `check-access` affiche « le site répond, certificat valide » | Pas d'alerte de sécurité. Dans les premières minutes après le démarrage, attendre que Caddy obtienne le certificat |
| Ça marche **depuis le salon** (boucle locale) | Téléphone de l'ami **en Wi-Fi** : même adresse ; ou `check-access.ps1 -Domain … -WanIp …` sur son PC | La page de connexion |
| La redirection **survit au redémarrage de la box** | Redémarrer la box, attendre 5 minutes, refaire le test 4G | La page répond toujours : l'adresse du NAS est réservée, DuckDNS a suivi la nouvelle adresse |
| L'assistant n'est **pas** joignable de l'extérieur | En 4G : `https://…/installation` | « Installation terminée », ou « installation en cours » avant la fin, jamais le formulaire |

---

## 8. Dépannage par symptôme

**« Le site ne répond pas de l'extérieur »**

1. `check-access.ps1 -Domain … -WanIp …` sur son PC : CGNAT ? Si oui, section 9.
2. Le nom pointe-t-il vers la bonne adresse (point 3 du script) ? Sinon, jeton DuckDNS (*Administration › Réglages*).
3. Redirection : 443 **TCP**, vers la **bonne** adresse du NAS, port **8443** ?
4. Pare-feu du DSM : 8443 autorisé ?
5. `docker ps` sur le NAS : `anime-server-caddy-1` est-il lancé ?

**« Certificat invalide »**

- Dans les premières minutes : attendre ; Caddy réessaie seul.
- Ensuite :
  - `docker logs anime-server-caddy-1` ;
  - vérifier que c'est bien le port **443** externe qui est redirigé (le défi du certificat passe par lui) ;
  - vérifier que le nom pointe vers la box.
- Les quotas de Let's Encrypt comptent par sous-domaine DuckDNS (vérifié, *Public Suffix List*) : on ne gêne pas
  les autres utilisateurs de DuckDNS, mais trop d'essais ratés d'affilée bloquent une heure.

**« Ça marche dehors mais pas chez lui »**

C'est la boucle locale. Trois solutions :

- l'activer dans la box si elle le propose ;
- ou, sur ses appareils de la maison, utiliser la 4G ;
- ou, pour un ordinateur, ajouter le nom au fichier `hosts` avec l'adresse locale du NAS : à éviter, à refaire si
  l'adresse change.

**« La box a changé d'adresse »**

- Rien à faire en principe : DuckDNS suit en moins de 5 minutes.
- Vérifier avec `check-access`. Si le nom ne suit pas, regarder le jeton dans *Réglages* (« échec de la dernière
  mise à jour »).

**« Le port est déjà utilisé »**

- À l'installation, `docker compose` refuse de démarrer `caddy` ou `web` : un autre paquet du NAS utilise 8443 ou
  8080.
- Relancer la commande avec `--public-port 9443` (la box redirige alors 443 vers 9443) ou `--lan-port 8090`.
- Ou changer `PUBLIC_PORT` / `LAN_PORT` dans `nas.env`, puis `sh app/restart.sh`.

---

## 9. Plan B si CGNAT : Tailscale Funnel

**Tailscale Funnel** ouvre le site sur Internet sans redirection de port : le trafic passe par les serveurs de
Tailscale, qui le renvoient au NAS.

- **Ce que l'ami fait en plus** : rien de plus que les 3 étapes. La commande d'installation porte `--funnel`, et je
  saisis moi-même la clé Tailscale. Pas de redirection de port.
- **De mon côté** :
  1. dans ma console Tailscale : HTTPS et MagicDNS activés, attribut `funnel` pour `tag:anime`, clé à usage unique ;
  2. adresse du site : `https://anime.<mon-tailnet>.ts.net`.
- **Limite à connaître** : Tailscale annonce une « bande passante limitée, non configurable », sans chiffre (vérifié,
  [doc Funnel](https://tailscale.com/docs/features/tailscale-funnel.md)), et Funnel est en bêta. **Mesurer le débit
  avec 3 lectures en même temps avant d'inviter tout le monde.**
- **Autre piste, souvent plus simple** : demander au fournisseur une IPv4 complète (« full-stack », « dédiée »),
  puis revenir à la redirection de port.
