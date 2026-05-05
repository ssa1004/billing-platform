# ADR-0001: Spring Modulith 기반 모듈러 모놀리스 (MSA 대신)

## 상태
적용

## 배경
초기 결제 시스템에 MSA 도입 검토. 한 팀(< 10명) 규모에서 마이크로서비스의 운영 비용(분산 트랜잭션, 서비스 메시, 다중 배포 파이프라인)이 너무 큼. 그렇다고 단일 클래스 / 단일 패키지 거대 monolith 도 변경 영향 분석 어려움.

## 결정
**모듈러 모놀리스** + Spring Modulith. 하나의 Spring Boot 앱 안에 도메인별 모듈 (wallet/order/payment/refund/ledger) 을 두고, Spring Modulith 가 컴파일 외 모듈 의존 그래프를 verify.

## 결과
- 운영 단순 (단일 배포, 단일 DB 트랜잭션)
- 모듈 경계 위반은 빌드 실패 (`ApplicationModules.verify()`)
- 향후 특정 모듈만 추출해 MSA 전환 가능 (이미 의존 끊겨있음)
- (단점) 한 모듈 OOM 시 전체 앱 영향
- (단점) 팀별 독립 배포 불가 (한 팀 규모에선 plus 지만)
