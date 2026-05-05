// Outbound adapter — Persistence(JPA + JOOQ), PG client, Cache(Redis+Caffeine), Messaging(Kafka producer + Outbox)
plugins {
    `java-library`
}

dependencies {
    implementation(project(":wallet-application"))
    compileOnly("org.springframework.modulith:spring-modulith-api")

    // Persistence (Write — JPA, Read — JOOQ)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // PG client
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign:4.2.0")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("io.github.resilience4j:resilience4j-feign:2.2.0")

    // Cache (L1 Caffeine + L2 Redis)
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Messaging (Outbox + Kafka)
    implementation("org.springframework.kafka:spring-kafka")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Tracing
    implementation("io.micrometer:micrometer-tracing")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("com.redis:testcontainers-redis:2.2.2")
}
