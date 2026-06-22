// 루트 빌드 — 공통 conventions. 각 모듈이 상속받는 공유 설정.
plugins {
    java
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.spring") version "2.0.21" apply false
    kotlin("plugin.jpa") version "2.0.21" apply false
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    // OpenAPI spec build-time export — 실제 적용은 bootstrap 모듈.
    id("org.springdoc.openapi-gradle-plugin") version "1.9.0" apply false
    // 커버리지 집계 — 루트에 적용하고 모듈별 측정값을 merge (아래 dependencies 블록).
    id("org.jetbrains.kotlinx.kover") version "0.8.3"
}

allprojects {
    group = "com.example.billing"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

// 루트에도 dependency-management 적용 — Kover 가 모듈 런타임 classpath 를 root 의
// koverExternalArtifacts 로 끌어올 때, 버전 없는 의존성(spring-context 등)을 BOM 으로
// 해석하기 위함. 모듈 build 와 동일한 BOM 좌표.
apply(plugin = "io.spring.dependency-management")
the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
        mavenBom("org.springframework.modulith:spring-modulith-bom:2.1.0")
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
            mavenBom("org.springframework.modulith:spring-modulith-bom:2.1.0")
        }
    }

    dependencies {
        // Gradle 8+ 부터 launcher 가 transitively 안 끌려옴 → 명시
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all"))
        options.encoding = "UTF-8"
    }
}

// ─── Coverage (Kover) ───────────────────────────────────────────────
// 인프라(Postgres/Kafka/Docker) 없이 돌는 순수 단위 모듈(domain + application)만 merge.
// adapter 슬라이스 테스트·e2e(Testcontainers)·batch 는 Docker 가 필요해 측정에서 제외 —
// 헥사고날 핵심(도메인 규칙 + 유스케이스)의 커버리지를 인프라 비의존으로 측정하는 것이 목적.
// CI 의 unit job 에서 `./gradlew koverXmlReport koverHtmlReport` 로 산출된다.
dependencies {
    kover(project(":billing-domain"))
    kover(project(":billing-application"))
}

kover {
    reports {
        filters {
            excludes {
                // Spring 설정 클래스는 측정 대상이 아님.
                annotatedBy("org.springframework.context.annotation.Configuration")
            }
        }
    }
}
