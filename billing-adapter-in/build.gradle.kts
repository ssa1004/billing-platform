// Inbound adapter — REST controllers + Kafka consumers (Kotlin).
// Application 의 UseCase 인터페이스만 호출.
plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":billing-application"))

    // Web (Kotlin)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Bulkhead full → 503 매핑용. 호출 자체는 adapter-out 의 BulkheadedPgClient 에서.
    implementation("io.github.resilience4j:resilience4j-bulkhead:2.3.0")

    // Kafka consumers
    implementation("org.springframework.kafka:spring-kafka")

    // OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")

    // Tracing
    implementation("io.micrometer:micrometer-tracing")
    // Metrics — ApiVersionMetricsFilter (ADR-0031). spring-boot-starter-actuator 는 bootstrap
    // 모듈에만 있으므로 여기는 micrometer-core 만 직접 의존.
    implementation("io.micrometer:micrometer-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
