// e2e — Postgres + Redis + Kafka 통합 시나리오 (Testcontainers)
plugins {
    java
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencies {
    testImplementation(project(":billing-bootstrap"))
    // bootstrap 이 implementation 으로 가리고 있어 e2e 에서 직접 import 하려면 명시 필요
    testImplementation(project(":billing-domain"))
    testImplementation(project(":billing-application"))
    testImplementation(project(":billing-adapter-out"))
    testImplementation(project(":billing-adapter-in"))
    // bootstrap implementation 의존을 e2e 컴파일에 노출 (JpaRepository, Modulith annotation 등)
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.modulith:spring-modulith-api")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("com.redis:testcontainers-redis:2.2.4")
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
}

kotlin {
    compilerOptions {
        // null-safety strict — 다른 모듈과 동일.
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
