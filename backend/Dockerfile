# --- Build : Maven + JDK 21 -------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dépendances d'abord : cette couche reste en cache tant que pom.xml ne change pas.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Les tests ont besoin de Docker (Dev Services) : ils se lancent hors image (mvnw test).
RUN mvn -B -q package -DskipTests

# --- ffmpeg / ffprobe : version figée, compilés avec le strict nécessaire (ARCHITECTURE §22.1) -------------
# Source officielle, empreinte vérifiée. Seulement : lecture de fichiers, tous les conteneurs en lecture (démuxeurs),
# MKV / MP4 / sortie nulle en écriture, les filtres de flux (mpeg4_unpack_bframes…), quelques décodeurs pour que
# ffprobe complète les informations. Ni réseau, ni encodeur vidéo, ni bibliothèque externe (zlib exceptée) :
# ~18 Mo au lieu de ~600 Mo pour le paquet ffmpeg d'Ubuntu. Compilation : 2 à 3 min sur un PC, ~10-15 min sur le NAS
# (une seule fois, ensuite en cache tant que ce bloc ne change pas).
FROM eclipse-temurin:21-jre AS ffmpeg
ARG FFMPEG_VERSION=7.1.5
ARG FFMPEG_SHA256=de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f
RUN apt-get update -qq \
 && apt-get install -y -qq --no-install-recommends build-essential xz-utils curl ca-certificates pkg-config zlib1g-dev > /dev/null
WORKDIR /src
RUN curl -fsSL -o ffmpeg.tar.xz "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" \
 && echo "${FFMPEG_SHA256}  ffmpeg.tar.xz" | sha256sum -c - \
 && tar xf ffmpeg.tar.xz --strip-components=1
RUN ./configure --prefix=/opt/ffmpeg --disable-everything --disable-autodetect --disable-doc --disable-debug \
      --disable-ffplay --disable-network --disable-x86asm --enable-static --disable-shared --enable-zlib \
      --enable-protocol=file,pipe --enable-demuxers --enable-parsers --enable-bsfs \
      --enable-muxer=matroska,null,mp4,webvtt,ass \
      --enable-decoder=h264,hevc,mpeg4,msmpeg4v1,msmpeg4v2,msmpeg4v3,mpeg1video,mpeg2video,vp8,vp9,mjpeg,vc1,wmv3,theora \
      --enable-decoder=aac,aac_latm,mp1,mp2,mp3,ac3,eac3,dca,truehd,mlp,vorbis,opus,flac,alac,pcm_s16le,pcm_s24le,pcm_s16be,wmav2 \
      --enable-decoder=ass,ssa,subrip,srt,webvtt,mov_text,dvdsub,pgssub,dvbsub \
      --enable-encoder=webvtt,ass,subrip \
 && make -j"$(nproc)" > /dev/null && make install > /dev/null \
 && strip /opt/ffmpeg/bin/ffmpeg /opt/ffmpeg/bin/ffprobe

# --- Exécution : JRE 21 + ffmpeg / ffprobe ----------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Fichiers copiés en 644/755 : lisibles par l'utilisateur non-root PUID:PGID
# (défini dans docker-compose.yml).
COPY --from=build /build/target/quarkus-app/ /app/
COPY --from=ffmpeg /opt/ffmpeg/bin/ffmpeg /opt/ffmpeg/bin/ffprobe /usr/local/bin/

ENV JAVA_OPTS="-Xmx512m" \
    QUARKUS_PROFILE=prod
EXPOSE 8080
USER 1000:1000

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/q/health/ready > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
