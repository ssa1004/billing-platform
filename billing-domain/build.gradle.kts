// 순수 도메인. Spring 의존성 0. JPA 어노테이션도 0. (헥사고날 핵심)
// jakarta.validation 만 허용 — Bean Validation 어노테이션은 표준이고 프레임워크 비의존.
plugins {
    `java-library`
}

dependencies {
    api("jakarta.validation:jakarta.validation-api")
    // Spring Modulith @NamedInterface 어노테이션만 (compileOnly — 런타임 의존성 0).
    compileOnly("org.springframework.modulith:spring-modulith-api")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}
