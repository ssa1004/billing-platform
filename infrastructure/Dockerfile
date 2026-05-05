# ─── build stage ─────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Gradle wrapper + 빌드 스크립트만 먼저 복사 — 의존성 캐시 hit 향상
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY billing-domain/build.gradle.kts billing-domain/
COPY billing-application/build.gradle.kts billing-application/
COPY billing-adapter-in/build.gradle.kts billing-adapter-in/
COPY billing-adapter-out/build.gradle.kts billing-adapter-out/
COPY billing-batch/build.gradle.kts billing-batch/
COPY billing-bootstrap/build.gradle.kts billing-bootstrap/
COPY e2e-tests/build.gradle.kts e2e-tests/
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 복사 + 빌드
COPY billing-domain billing-domain
COPY billing-application billing-application
COPY billing-adapter-in billing-adapter-in
COPY billing-adapter-out billing-adapter-out
COPY billing-batch billing-batch
COPY billing-bootstrap billing-bootstrap
RUN ./gradlew --no-daemon :billing-bootstrap:bootJar -x test

# ─── runtime stage ────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 보안: non-root user
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=builder /workspace/billing-bootstrap/build/libs/billing-bootstrap-*.jar app.jar

# Container-aware JVM + virtual threads native (Loom)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
