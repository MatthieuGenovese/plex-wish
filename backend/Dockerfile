# --- Build : Maven + JDK 21 -------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dépendances d'abord : cette couche reste en cache tant que pom.xml ne change pas.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Les tests ont besoin de Docker (Dev Services) : ils se lancent hors image (mvnw test).
RUN mvn -B -q package -DskipTests

# --- Exécution : JRE 21 seul ------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Fichiers copiés en 644/755 : lisibles par l'utilisateur non-root PUID:PGID
# (défini dans docker-compose.yml).
COPY --from=build /build/target/quarkus-app/ /app/

ENV JAVA_OPTS="-Xmx512m" \
    QUARKUS_PROFILE=prod
EXPOSE 8080
USER 1000:1000

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/q/health/ready > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
