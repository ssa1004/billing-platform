# ADR-0002: 헥사고날 아키텍처, package-by-feature

## 상태
적용

## 배경
도메인 로직이 Spring/JPA/Kafka 같은 인프라 의존성에 묶이면 — (a) 단위 테스트가 무거워지고 (b) 인프라 교체 시 도메인까지 손대야 하고 (c) DDD invariant 가 어디서 깨지는지 추적 어려움.

## 결정
**헥사고날** + multi-module Gradle. `billing-domain` 은 Spring 의존성 0으로 유지한다. `billing-application` 이 outbound port 인터페이스를 정의하고, `billing-adapter-out` 이 JPA/Kafka/Redis 등 실제 구현을 맡는다. 컨트롤러는 `billing-adapter-in` 에 둔다.

의존 방향: `adapter-in → application → domain ← adapter-out`. application 은 도메인을 알지만 어댑터는 모름. 어댑터는 application 의 port 만 구현. 도메인은 누구도 모름.

## 결과
- 도메인 단위 테스트가 milliseconds — Spring context 안 띄움
- 어댑터 교체 자유 (예: JPA → JOOQ, Kafka → RabbitMQ — port 인터페이스만 같으면)
- Spring Modulith verify 가 의존 방향 위반 catch
- (단점) 매핑 보일러플레이트 (entity ↔ domain) — Lombok + 정적 mapper 로 완화
- (단점) 신규 개발자 학습곡선 (port/adapter 용어 익숙해질 때까지)
