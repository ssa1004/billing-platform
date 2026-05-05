// Use Cases + Ports. Spring 의존성은 stereotype + tx 만 (@Service, @Transactional).
// 외부 라이브러리 (DB 드라이버, Kafka, Redis) 직접 의존 금지 — 모두 Port 인터페이스로.
plugins {
    `java-library`
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
