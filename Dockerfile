FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends tzdata && rm -rf /var/lib/apt/lists/*
ENV DATA_DIR=/data MEISTER_PORT=8080 SPIELER_PORT=8081 TZ=Europe/Vienna
COPY --from=build /src/target/heldenhort.jar /app/heldenhort.jar
EXPOSE 8080 8081
VOLUME /data
# user.home liegt im Volume, damit die Einstellungen der helden.jar Updates überleben
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-Duser.home=/data/home", "-jar", "/app/heldenhort.jar"]
