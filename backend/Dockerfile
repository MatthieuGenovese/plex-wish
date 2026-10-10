# Contexte de construction : la RACINE du dépôt (docker build -f backend/Dockerfile .), filtré par la liste
# blanche de /.dockerignore. L'image embarque l'application déjà compilée : rien n'est compilé au démarrage.

# --- Build : Maven + JDK 21 -------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dépendances d'abord : cette couche reste en cache tant que pom.xml ne change pas.
COPY backend/pom.xml .
RUN mvn -B -q dependency:go-offline

COPY backend/src/main ./src/main
# Les tests ont besoin de Docker (Dev Services) : ils se lancent hors image (mvnw test).
RUN mvn -B -q package -DskipTests

# --- ffmpeg / ffprobe : version figée, compilés avec le strict nécessaire (ARCHITECTURE §22.1) -------------
# Source officielle, empreinte vérifiée. Seulement : lecture de fichiers, tous les conteneurs en lecture (démuxeurs),
# MKV / MP4 / HLS (lecteur web) / sortie nulle en écriture, les filtres de flux (mpeg4_unpack_bframes…), les décodeurs
# courants, et pour la conversion pour le navigateur (phase 10.3, docs/WEB-PLAYER.md §4.5) : encodeurs AAC (natif) et
# H.264 (libx264), filtres de mise à l'échelle et de format, code assembleur (nasm : plusieurs fois plus rapide).
# Ni réseau, ni autre bibliothèque externe que zlib et x264 (paquet d'Ubuntu, signé par l'archive ; Ubuntu ne fournit
# plus la version statique : la bibliothèque partagée est recopiée dans l'image finale) : ~25 Mo au lieu de ~600 Mo
# pour le paquet ffmpeg d'Ubuntu.
# Licence : avec x264, le binaire ffmpeg est sous GPL 2 ou ultérieure (sources : docs/DEPLOIEMENT.md, « À propos »).
# Compilation : 3 à 5 min sur un PC, ~15-20 min sur le NAS (une seule fois, ensuite en cache tant que ce bloc ne
# change pas).
FROM eclipse-temurin:21-jre AS ffmpeg
ARG FFMPEG_VERSION=7.1.5
ARG FFMPEG_SHA256=de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f
RUN apt-get update -qq \
 && apt-get install -y -qq --no-install-recommends build-essential xz-utils curl ca-certificates pkg-config zlib1g-dev \
      nasm libx264-dev > /dev/null
WORKDIR /src
RUN curl -fsSL -o ffmpeg.tar.xz "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" \
 && echo "${FFMPEG_SHA256}  ffmpeg.tar.xz" | sha256sum -c - \
 && tar xf ffmpeg.tar.xz --strip-components=1
RUN ./configure --prefix=/opt/ffmpeg --disable-everything --disable-autodetect --disable-doc --disable-debug \
      --disable-ffplay --disable-network --enable-static --disable-shared --enable-zlib \
      --enable-gpl --enable-libx264 \
      --enable-protocol=file,pipe --enable-demuxers --enable-parsers --enable-bsfs \
      --enable-muxer=matroska,null,mp4,webvtt,ass,hls \
      --enable-decoder=h264,hevc,mpeg4,msmpeg4v1,msmpeg4v2,msmpeg4v3,mpeg1video,mpeg2video,vp8,vp9,mjpeg,vc1,wmv3,theora \
      --enable-decoder=aac,aac_latm,mp1,mp2,mp3,ac3,eac3,dca,truehd,mlp,vorbis,opus,flac,alac,pcm_s16le,pcm_s24le,pcm_s16be,wmav2 \
      --enable-decoder=ass,ssa,subrip,srt,webvtt,movtext,dvdsub,pgssub,dvbsub \
      --enable-encoder=webvtt,ass,subrip,aac,libx264 \
      --enable-filter=scale,format,aformat,aresample,null,anull \
 && make -j"$(nproc)" > /dev/null && make install > /dev/null \
 && strip /opt/ffmpeg/bin/ffmpeg /opt/ffmpeg/bin/ffprobe

# --- Exécution : JRE 21 + ffmpeg / ffprobe ----------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Fichiers copiés en 644/755 : lisibles par l'utilisateur non-root qui fait tourner le serveur.
COPY --from=build /build/target/quarkus-app/ /app/
COPY --from=ffmpeg /opt/ffmpeg/bin/ffmpeg /opt/ffmpeg/bin/ffprobe /usr/local/bin/
# Bibliothèque x264 de l'étape ffmpeg (même base Ubuntu), puis vérification que ffmpeg démarre et encode.
COPY --from=ffmpeg /usr/lib/x86_64-linux-gnu/libx264.so.* /usr/local/lib/
RUN ldconfig && ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libx264
COPY --chmod=755 backend/docker/entrypoint.sh /app/entrypoint.sh
# Fichiers de déploiement (compose, scripts d'installation et de maintenance) : l'installateur les recopie sur le NAS,
# qui n'a pas le dépôt. Aucun secret dedans.
COPY --chmod=755 deploy/ /app/deploy/
COPY VERSION /app/VERSION

ARG APP_VERSION=dev
ARG GIT_COMMIT=unknown
LABEL org.opencontainers.image.title="anime-server-backend" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_COMMIT}"

ENV JAVA_OPTS="-Xmx512m" \
    QUARKUS_PROFILE=prod \
    ANIME_APP_VERSION=${APP_VERSION}
EXPOSE 8080
# Utilisateur par défaut non-root. Le déploiement sur le NAS démarre le conteneur en root : entrypoint.sh lit alors
# les secrets puis abandonne root pour l'utilisateur qui peut lire le dossier média (choisi par le conteneur init).
USER 1000:1000

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/q/health/ready > /dev/null || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
