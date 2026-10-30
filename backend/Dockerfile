FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 muse && useradd --uid 10001 --gid muse --no-create-home muse \
    && mkdir -p /app/storage && chown muse:muse /app/storage
COPY --from=build /build/target/muse-backend.jar /app/app.jar
ENV PORT=8080 STORAGE_PATH=/app/storage TZ=UTC
USER muse
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
