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
RUN ./gradlew bootJar --parallel -x test --no-daemon

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre

RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg python3 curl && \
    curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp && \
    chmod a+rx /usr/local/bin/yt-dlp && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /workspace/build/libs/localloom-api-*.jar app.jar

RUN groupadd -r appuser && useradd -r -g appuser appuser && \
    mkdir -p /app/data/audio && chown -R appuser:appuser /app

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
