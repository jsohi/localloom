plugins {
    java
    jacoco
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
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Spring AI
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-chroma")
    implementation("org.springframework.ai:spring-ai-rag")

    // JSR-310 module for the Jackson 2 ObjectMapper bean in SpringAiConfig.
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // PDF text extraction
    implementation("org.apache.pdfbox:pdfbox:3.0.5")

    // HTML scraping
    implementation("org.jsoup:jsoup:1.18.3")

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
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Spring Boot 4 + Spring AI + Testcontainers + JaCoCo + bean-context
    // cache need more than Gradle's default 512MB. SourceImportServiceIT
    // (and similar IT slices) hit OOM during context load without this.
    // Setting minHeapSize avoids GC churn while ramping up to maxHeapSize.
    minHeapSize = "512m"
    maxHeapSize = "2g"
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
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
