FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 DATA_DIR=/data MEISTER_PORT=8080 SPIELER_PORT=8081 TZ=Europe/Vienna
COPY --from=build /src/target/heldenhort.jar /app/heldenhort.jar
EXPOSE 8080 8081
VOLUME /data
ENV PUID=99 PGID=100 UMASK=0002
COPY --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh
# Startet als root, setzt die Rechte der eigenen Ordner und läuft dann als PUID:PGID.
# user.home liegt im Volume, damit die Einstellungen der helden.jar Updates überleben.
ENTRYPOINT ["/app/docker-entrypoint.sh"]
