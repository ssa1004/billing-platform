# ADR-0002: 헥사고날 아키텍처, package-by-feature

## 상태
적용

## 배경
도메인 로직이 Spring/JPA/Kafka 같은 인프라 의존성에 묶이면, (a) 단위 테스트가 무거워지고
(b) 인프라를 교체할 때 도메인까지 손대야 하고 (c) DDD (도메인 주도 설계) 의 불변 조건
(invariant, 항상 만족해야 하는 도메인 규칙) 이 어디서 깨지는지 추적이 어려워집니다.

## 결정
**헥사고날 아키텍처** (도메인을 가운데 두고 외부 시스템은 port/adapter 로 끼워넣는 구조)
+ multi-module Gradle. `billing-domain` 은 Spring 의존성을 0으로 유지합니다.
`billing-application` 이 바깥으로 나가는 인터페이스 (outbound port) 를 정의하고,
`billing-adapter-out` 이 JPA/Kafka/Redis 등 실제 구현 (adapter) 을 맡습니다. 컨트롤러는
`billing-adapter-in` 에 둡니다.

의존 방향: `adapter-in → application → domain ← adapter-out`. application 은 도메인을
알지만 어댑터는 모릅니다. 어댑터는 application 의 port 만 구현합니다. 도메인은 누구도
모릅니다.

## 결과
- 도메인 단위 테스트가 milliseconds — Spring context 를 띄우지 않음
- 어댑터 교체 자유 (예: JPA → JOOQ, Kafka → RabbitMQ — port 인터페이스만 같으면 됨)
- Spring Modulith verify 가 의존 방향 위반을 잡아냄
- (단점) entity ↔ domain 매핑 보일러플레이트가 생김 — Lombok + 정적 mapper 로 완화
- (단점) 신규 개발자 학습 곡선 (port/adapter 용어에 익숙해질 때까지)
