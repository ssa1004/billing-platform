// Use Cases + Ports. Spring 의존성은 stereotype + tx 만 (@Service, @Transactional).
// 외부 라이브러리 (DB 드라이버, Kafka, Redis) 직접 의존 금지 — 모두 Port 인터페이스로.
// Phase 7 부터 Kotlin 도입 — 도메인 마이그레이션 직후 application 도 Kotlin 전환.
// @Service / @Transactional 이 있는 일반 class 에 open 변형이 필요해 plugin.spring 도 포함.
plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    api(project(":billing-domain"))
    api("org.springframework:spring-context")        // @Service, @Component
    api("org.springframework:spring-tx")              // @Transactional
    api("org.springframework.boot:spring-boot-starter-cache")   // @Cacheable
    api("org.slf4j:slf4j-api")                       // Lombok @Slf4j
    compileOnly("org.springframework.modulith:spring-modulith-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}

kotlin {
    compilerOptions {
        // null-safety 엄격 — JSR-305 (@Nullable 등) 어노테이션을 strict 로 해석
        freeCompilerArgs.addAll("-Xjsr305=strict")
        // @JvmRecord 사용을 위한 JDK 16+ target. 이 프로젝트는 21.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
