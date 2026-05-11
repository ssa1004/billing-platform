// Spring Batch — 일일 정산 / Outbox 아카이브 등 야간 배치
plugins {
    `java-library`
}

dependencies {
    implementation(project(":billing-application"))
    implementation(project(":billing-adapter-out"))

    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // ShedLock — multi-instance 환경에서 @Scheduled 가 한 인스턴스에서만 실행되도록
    implementation("net.javacrumbs.shedlock:shedlock-spring:5.16.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.16.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.batch:spring-batch-test")
}
