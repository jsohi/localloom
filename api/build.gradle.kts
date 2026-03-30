plugins {
    java
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.4.0"
}

group = "com.localloom"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

configurations {
    all {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.0-M4")
        mavenBom("org.testcontainers:testcontainers-bom:2.0.4")
    }
}

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-log4j2")

    // Spring AI
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-chroma")

    // Database
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-chromadb")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testImplementation("org.springframework.ai:spring-ai-test")
    testImplementation("org.springframework.ai:spring-ai-spring-boot-testcontainers")
    testImplementation("org.springframework.ai:spring-ai-starter-model-transformers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

spotless {
    java {
        importOrder("java", "jakarta", "org.springframework", "com.localloom", "")
        removeUnusedImports()
        googleJavaFormat("1.35.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
