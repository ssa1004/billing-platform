// Spring Boot 진입점. main + 통합 config + Spring Modulith 검증.
plugins {
    java
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":billing-domain"))
    implementation(project(":billing-application"))
    implementation(project(":billing-adapter-in"))
    implementation(project(":billing-adapter-out"))
    implementation(project(":billing-batch"))

    // Kotlin runtime — reflect 는 Spring proxy / @ConfigurationProperties / open class 분석에 필요.
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Bootstrap 자체에서 사용하는 starter 들
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")    // PersistenceConfig
    implementation("org.springframework.boot:spring-boot-starter-validation")  // WalletProperties @Validated
    implementation("org.springframework.boot:spring-boot-starter-web")          // RestClient
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // Resilience4j ThreadPoolBulkhead 에 MdcContextPropagator 빈을 등록 (ADR-0027).
    // billing-adapter-out 에 이미 들어 있어 runtime 에는 가능하지만, bootstrap 의 @Configuration
    // 클래스가 직접 ThreadPoolBulkheadConfigCustomizer / ContextPropagator 타입을 참조하므로
    // compile time 에도 필요.
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.3.0")

    // Spring Modulith (헥사고날/모듈 경계 강제 + 진단). events 패키지는 우리 Outbox 와 중복이라 제외.
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    runtimeOnly("org.springframework.modulith:spring-modulith-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

kotlin {
    compilerOptions {
        // null-safety strict — 다른 모듈과 동일.
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.named("bootJar") {
    enabled = true
}

// e2e-tests 가 BillingApplication 클래스를 import 할 수 있도록 plain jar 도 활성화.
// (Spring Boot 3 의 jar/bootJar 공존 — bootJar 가 실행파일, jar 가 라이브러리)
tasks.named<Jar>("jar") {
    enabled = true
    archiveClassifier.set("")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveClassifier.set("boot")
}
