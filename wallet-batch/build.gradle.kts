// Spring Batch — 일일 정산 / Outbox 아카이브 등 야간 배치
plugins {
    `java-library`
}

dependencies {
    implementation(project(":wallet-application"))
    implementation(project(":wallet-adapter-out"))

    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.batch:spring-batch-test")
}
