# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
RUN chmod +x gradlew

# Dependency layer (cached unless build files change)
COPY build.gradle.kts settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon

# Source layer (changes frequently)
COPY src src
RUN ./gradlew bootJar --parallel -x test --no-daemon && \
    cp build/libs/*-SNAPSHOT.jar build/libs/app.jar

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre

RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg python3 python3-pip curl && \
    pip install --break-system-packages yt-dlp && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /workspace/build/libs/app.jar app.jar

RUN groupadd -r appuser && useradd -r -g appuser appuser && \
    mkdir -p /app/data/audio && chown -R appuser:appuser /app

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
