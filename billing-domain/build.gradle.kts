// 순수 도메인. Spring 의존성 0. JPA 어노테이션도 0. (헥사고날 핵심)
// jakarta.validation 만 허용 — Bean Validation 어노테이션은 표준이고 프레임워크 비의존.
// Phase 6 부터 Kotlin 도입 — audit sub-package 시범 마이그레이션. plugin.spring 은
// 도메인엔 불필요 (Spring 의존성 0 원칙 유지) — kotlin("jvm") 만.
plugins {
    `java-library`
    kotlin("jvm")
    // 커버리지 측정 — 루트가 koverXmlReport 로 merge 한다.
    id("org.jetbrains.kotlinx.kover")
}

dependencies {
    api("jakarta.validation:jakarta.validation-api")
    // Spring Modulith @NamedInterface 어노테이션만 (compileOnly — 런타임 의존성 0).
    compileOnly("org.springframework.modulith:spring-modulith-api")
    testImplementation("org.junit.jupiter:junit-jupiter")
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
