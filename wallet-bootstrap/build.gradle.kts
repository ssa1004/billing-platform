// Spring Boot 진입점. main + 통합 config + Spring Modulith 검증.
plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":wallet-domain"))
    implementation(project(":wallet-application"))
    implementation(project(":wallet-adapter-in"))
    implementation(project(":wallet-adapter-out"))
    implementation(project(":wallet-batch"))

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

    // Spring Modulith (헥사고날/모듈 경계 강제 + 진단). events 패키지는 우리 Outbox 와 중복이라 제외.
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    runtimeOnly("org.springframework.modulith:spring-modulith-actuator")

    // Spring Cloud (OpenFeign 의존성을 transitively 끌어옴)
    implementation(platform("org.springframework.cloud:spring-cloud-dependencies:2024.0.0"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
}

tasks.named("bootJar") {
    enabled = true
}

// e2e-tests 가 WalletApplication 클래스를 import 할 수 있도록 plain jar 도 활성화.
// (Spring Boot 3 의 jar/bootJar 공존 — bootJar 가 실행파일, jar 가 라이브러리)
tasks.named<Jar>("jar") {
    enabled = true
    archiveClassifier.set("")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveClassifier.set("boot")
}
