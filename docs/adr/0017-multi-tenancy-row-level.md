# ADR-0017: 멀티테넌시 — Row-Level Isolation (`customer_id`)

## 상태
적용

## 배경

B2B SaaS 빌링 플랫폼은 여러 고객사 (tenant) 의 데이터를 한 시스템에서 처리. 데이터
격리 모델 결정 필요.

선택지:

| 모델 | 설명 | 장점 | 단점 |
|---|---|---|---|
| **Database-per-tenant** | tenant 마다 별도 DB | 강한 격리, 백업/복원 단순 | 인프라 폭증 (수백 tenant 시 수백 DB), 마이그레이션 비용 큼 |
| **Schema-per-tenant** | 같은 DB, schema 분리 | 격리도 + 마이그레이션 적당 | tenant 수 증가 시 schema 폭증 (Postgres 기준 수만 까지는 OK) |
| **Row-level** | 모든 테이블에 `customer_id` 컬럼 + 인덱스 | 단순, 무한 확장, 운영 쉬움 | application 레벨 격리 (실수로 누락 시 cross-tenant 노출 위험) |

## 결정

**Row-level isolation** 채택 + 모든 query 에 `customer_id` 필터 강제.

### 이유

1. **tenant 수 가변** — B2B SaaS 는 1년에 tenant 수가 10배 늘 수도 있음. schema-per-tenant
   는 마이그레이션 / monitoring 부담이 nonlinear 증가
2. **billing 도메인 특성** — invoice / settlement / aged receivables 같은 cross-tenant
   집계가 필요 (예: 운영 dashboard 의 전체 미수금 합계)
3. **운영 단순성** — 백업 / 모니터링 / 마이그레이션이 tenant 무관하게 1번만 수행

### 안전장치

application 레이어에서 다음을 강제:

1. **모든 query 에 `customerId` 명시** — `*Repository.findBy(customerId, ...)` 형태
2. **Caller context 가 customer 를 보유** — REST endpoint 진입 시 JWT 또는 헤더에서
   추출, 그 이후 service / repository 가 자동 적용
3. **운영 dashboard 만 tenant-bypass** — admin 권한이 명시적으로 있는 endpoint 만
   `findUnpaid()` 같은 cross-tenant 조회 가능

### 미적용 항목

- **DB-level Row Security (RLS)** — Postgres 의 RLS 를 활성화하면 application 버그가
  있어도 cross-tenant 노출 방지 가능. 운영 DB 설정 복잡도 증가 + connection pool 의
  `SET LOCAL app.tenant_id` 설정 필요. 본 단계에서는 미적용. tenant 수 증가 시 도입 검토

## 결과

- 무한 확장 가능 (tenant 수 제약 없음)
- 운영 단순성 유지
- (위험) application 버그로 cross-tenant 노출 가능 — 코드 리뷰에서 query 의 `customer_id`
  필터 누락 체크가 critical
- (개선 가능) RLS 도입 시 위 위험 제거. cost 는 운영 복잡도

## 향후 검토

- tenant 수 100+ 도달 시 RLS 도입 결정
- Enterprise tier 고객 (별도 격리 요구) 발생 시 schema-per-tenant 옵션 추가 가능 — 같은
  application 코드로 처리하려면 multi-tenant connection 분리 필요 (Hibernate
  MultiTenantConnectionProvider)
