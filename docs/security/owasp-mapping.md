# OWASP API Security Top 10 (2023) — billing-platform 매핑

본 문서는 OWASP API Top 10 (2023) 의 각 항목을 결제 / 청구 / 정산 도메인에서 어떻게
구체적으로 해석하고, 어디서 막고, 어떤 회귀 테스트로 잠그는지 정리한 sweep 보고입니다.

## 범위

- 대상: `billing-platform` 의 모든 REST controller (`billing-adapter-in/src/main/kotlin/.../web`).
- 운영 환경: `prod` 프로필 (`billing.security.jwt.enabled=true`) 기준. dev / 테스트에서는
  [PermissiveSecurityConfig](../../billing-adapter-in/src/main/kotlin/com/example/billing/adapter/web/auth/PermissiveSecurityConfig.kt)
  가 모든 요청을 통과 — 본 문서의 조항은 dev 에서 의도적으로 비활성화 됩니다.

## 한눈에 보기

| ID | 항목 | 도메인 의미 | 막은 곳 | 회귀 테스트 |
|---|---|---|---|---|
| API1 | BOLA | customer A 가 customer B 의 invoice / wallet / refund 접근 | `Caller.requireOwnerOrAdmin` 모든 read/write controller | `InvoiceV2ControllerTest` (BOPLA 회귀), `WebhookEndpointTest` |
| API2 | Broken Authentication | JWT 미검증 endpoint | `SecurityConfig` (prod) — `/api/**` authenticated, `denyAll` else | `OrderControllerSliceTest` 만으로는 부족 — bootstrap 통합 테스트에서 |
| API3 | Broken Object Property Auth | invoice 의 sensitive field (총액, applied credit) 가 다른 customer 에 흘러나감 | v2 controller 의 currency 필터 + BOLA 검사 | `InvoiceV2ControllerTest.listByCustomer filters by currency` |
| API4 | Unrestricted Resource Consumption | `?limit=1000000`, 100k items batch, `quantity=1e18` | `coerceIn(1, MAX_LIMIT)`, `@Size(max = 100)`, `@Max(1_000_000_000L)` | 도메인 단위 테스트로 lockdown 안 됨 — DTO @Size 만 |
| API5 | Broken Function Level Auth | settlement run, audit query, aged receivables | `@PreAuthorize("hasRole('admin')")` controller 단위 | (인증된 통합 테스트 추가 필요) |
| API6 | Unrestricted Sensitive Flow | 다른 사람 결제 환불 / 결제 abuse | controller 에서 `order.buyerId` 매칭 + 도메인 invariant (PAID → REFUNDED) | `RefundServiceTest` (state transition) + 새 BOLA 검사 |
| API7 | SSRF | webhook URL 이 `https://169.254.169.254` 같은 metadata IP | `WebhookEndpoint.validateUrl` 사설 / metadata / link-local 차단 | `WebhookEndpointTest.register_rejectsHttpsCloudMetadataAndPrivateRanges` |
| API8 | Security Misconfiguration | PG secret 평문 / CORS 과허용 | Helm `existingSecret`, application.yml 에 평문 secret 없음, CORS 미설정 = 동일 origin only | (인프라 검토) |
| API9 | Improper Inventory | v1 / v2 deprecation 정책 부재 | ADR-0031, `ApiV1DeprecationFilter` (`Deprecation` / `Sunset` 헤더) | `ApiV1DeprecationFilterTest`, `ApiVersionMetricsFilterTest` |
| API10 | Unsafe API Consumption | PG / Wallet 응답을 그대로 신뢰 | Resilience4j Circuit Breaker + Retry, 응답 status enum 정상화 | `RestClientPgClientWiremockIT` |

## 항목별 상세

### API1 — BOLA (Broken Object Level Authorization) ★

**위협**: B2B SaaS 에서 가장 흔한 사고. customer A 가 customer B 의 invoice id 를 추측 /
enumeration / 로그 누출로 얻은 뒤 `GET /api/v1/invoices/{B-invoice-id}` 로 조회하면, 도메인
객체가 그대로 반환됩니다. 결제 / 청구처럼 한 row 가 곧 다른 회사의 매출 정보인 도메인에서
이건 그대로 시장 정보 유출.

**막은 방식**:

- [Caller.requireOwnerOrAdmin](../../billing-adapter-in/src/main/kotlin/com/example/billing/adapter/web/auth/Caller.kt)
  로 한 줄로 검사. `owner == requestedOwner` 또는 admin 일 때만 통과 — 그 외는
  `AccessDeniedException` → 403.
- path-variable 만 노출된 endpoint (예: `GET /invoices/{id}`) 는 도메인 객체를 먼저 로드해
  `invoice.customerId()` 와 caller 를 매칭. resource 가 없으면 404, 있으면 owner 검사 →
  403 / 200.

**적용된 controller**:

| Controller | Endpoint | 보호 방식 |
|---|---|---|
| `InvoiceController` | `GET /invoices/{id}`, `GET /invoices`, `GET /invoices/{id}/pdf` | invoice.customerId 매칭 |
| `InvoiceV2Controller` | `GET /v2/invoices/{id}`, `GET /v2/invoices` | invoice.customerId 매칭 |
| `CreditController` | `POST /apply`, `GET /balance`, `GET /`, `GET /expiring` | req.customerId 매칭, `POST /` (grant) 는 admin 전용 |
| `BudgetAlertController` | 전 endpoint | rule 단건은 로드 후 매칭, customerId 쿼리는 직접 매칭 |
| `WebhookController` | endpoint / delivery 전체 | endpoint.customerId 매칭, replay 는 admin 전용 |
| `UsageController` | `POST /usage`, `GET /forecast` | req.customerId 매칭 |
| `PaymentController` | `POST /payments` | order.buyerId 매칭 |
| `RefundController` | `POST /refunds` | payment → order.buyerId 매칭 |

**Wallet / Order** 는 이미 `Caller.from(jwt).owner` 로 caller 자신 자원만 접근 — 별도 검사 불필요.

**dev 환경**: `Caller.owner == ANONYMOUS` 인 경우 (PermissiveSecurityConfig 활성) 는 통과
시킵니다 — 로컬 테스트가 깨지지 않게.

### API2 — Broken Authentication

**위협**: JWT 미검증 / 잘못된 signing key / missing audience 등.

**막은 방식**:

- prod 는 [SecurityConfig](../../billing-adapter-in/src/main/kotlin/com/example/billing/adapter/web/auth/SecurityConfig.kt)
  가 OAuth2 Resource Server (JWT) 활성. `/api/**` 는 authenticated, 매핑 안 된 endpoint 는
  `denyAll`. CSRF 비활성 (REST API), session stateless.
- JWT converter 는 `sub` 를 principal 로, realm role 을 `ROLE_<role>` 권한으로 매핑.
- dev / 테스트는 [PermissiveSecurityConfig](../../billing-adapter-in/src/main/kotlin/com/example/billing/adapter/web/auth/PermissiveSecurityConfig.kt)
  로 자동 전환 — 운영 보안 invariant 와 분리.

**이번 sweep 에서 잡은 버그**:

- `Caller.ADMIN_ROLE = "ROLE_admin"` 과 `DlqAdminController` 의 `hasRole('ADMIN')` 이
  대소문자 불일치. Spring 의 `hasRole('admin')` 은 내부에서 `ROLE_admin` 으로 비교 — 둘이
  대소문자를 같이 맞추지 않으면 한쪽 코드가 통과해도 다른 쪽이 거절. `Caller.hasAdmin` 의
  비교를 `ignoreCase = true` 로 통일, 새로 추가한 `@PreAuthorize` 는 모두 `hasRole('admin')`
  으로 통일.

### API3 — Broken Object Property Authorization

**위협**: response 에 ownership 검사 없이 sensitive field 가 섞여 나가는 사고. invoice 의
`appliedCredit`, `amountDue` 같은 v2 필드가 다른 customer 의 invoice 응답으로 흘러가면 매출
정보 유출.

**막은 방식**:

- BOLA (API1) 가 row 수준 격리를 하면 BOPLA 는 자동으로 막힘 — invoice row 자체가
  caller 자원이 아니면 403 으로 떨어지기 때문.
- v2 의 currency 필터는 응답 셋에 다른 통화 invoice 가 섞이지 않는다는 추가 보장. 회귀
  테스트는 `InvoiceV2ControllerTest`.

### API4 — Unrestricted Resource Consumption

**위협**: `?limit=10000000` / 100k items / `quantity=1e18` 등 단일 요청으로 DB / 메모리 /
다운스트림 (결제 / forecast) 를 통째로 흔드는 자원 고갈.

**막은 방식**:

- 모든 `?limit` 쿼리 파라미터를 `coerceIn(1, MAX_LIMIT)` 으로 cap (대부분 200, audit 은 500).
  서비스 메서드는 그대로 두고 controller 가 입구에서 자름.
- `PlaceOrderRequest.items` 에 `@Size(max = 100)` 추가 — 단일 주문 100 라인이 운영 상한.
- `IngestUsageRequest.quantity` 에 `@Max(1_000_000_000L)` 추가 — 정상 이벤트의 quantity 는
  분당 호출 수 (수만) 수준. 비현실적 값은 forecast / billing 계산에서 BigDecimal overflow
  로 이어짐.
- DLQ replay max 도 1000 으로 cap.
- 외부 호출은 별도로 Resilience4j ThreadPool Bulkhead (ADR-0026) 가 격리.

**아직 안 한 것**:

- bucket-level rate limiting (사용자 / IP 별 RPS cap). 운영 ingress (NGINX / Envoy) 에서
  처리하는 게 통례라 application 단에 두지 않음.

### API5 — Broken Function Level Auth

**위협**: 일반 사용자가 admin endpoint (settlement run, audit query, aged receivables) 를
직접 호출.

**막은 방식**:

- `@PreAuthorize("hasRole('admin')")` 을 controller class 단위로 적용:
  - `SettlementController` — `POST /settlement/run`
  - `AuditController` — `GET /audit`
  - `AgedReceivablesController` — `GET /aged-receivables`
- 부분 admin endpoint (controller 안에서 일부 만 admin):
  - `CreditController.grant` — credit 발급은 운영자 / 마케팅
  - `WebhookController.replay` — DLQ replay 와 같이 운영자 전용
  - `DlqAdminController.replay` — 이미 존재했음 (단, 대소문자 통일)
- `WebhookController.listDeliveries(status=...)` — endpoint 필터 없이 status 만 주면
  customer 격리가 불가능 (status 는 customer 와 무관) → admin 전용으로 분리.

### API6 — Unrestricted Sensitive Flow ★

**위협**:

1. customer A 가 customer B 의 `paymentId` 로 `POST /refunds` → B 의 결제가 환불됨.
   금전 손실 + B 사용자 분쟁.
2. 같은 결제를 여러 번 환불 시도.
3. 다른 사람 order 에 대한 결제 trigger.

**막은 방식**:

- 1 / 3 — controller 에서 ownership 검사 (BOLA 와 같은 메커니즘):
  - `PaymentController.process` 는 `order.buyerId` 매칭.
  - `RefundController.refund` 는 `payment → order.buyerId` 매칭.
- 2 — 도메인 invariant. `Order` 의 상태 전이 (`PAID → REFUNDED`) 가 `OrderStatus.canTransitionTo`
  에 의해 한 방향. 두 번째 환불 시도는 `IllegalOrderTransitionException` 으로 떨어져 409
  Conflict.

**왜 idempotencyKey 만으로 부족했나**: idempotencyKey 는 "같은 키 → 같은 응답" 을 보장할 뿐,
서로 다른 키로 같은 paymentId 를 두 번 환불 시도하는 것은 차단하지 못함. 도메인 invariant
+ controller ownership 검사 두 겹.

### API7 — SSRF

**위협**: customer 가 webhook endpoint URL 로 `https://169.254.169.254/latest/meta-data/`
(AWS IMDS), `https://metadata.google.internal/...` (GCP), `https://10.0.0.5/admin`
(내부 서비스) 등을 등록하면, 우리가 webhook 발송할 때마다 그 URL 에 HMAC 서명 헤더와 함께
HTTP POST 가 박힘. 우리 서비스 IAM 자격증명이 IMDS 에서 새는 경로 / 내부 admin 서비스 침투.

**막은 방식**:

- [WebhookEndpoint.validateUrl](../../billing-domain/src/main/kotlin/com/example/billing/domain/webhook/WebhookEndpoint.kt)
  의 도메인 검사 강화:
  - host 가 IPv4 리터럴이면 RFC 1918 (10/8, 172.16/12, 192.168/16), RFC 1122 loopback
    (127/8), RFC 3927 link-local (169.254/16), RFC 6598 CGNAT (100.64/10), 0/8 (this
    network), 224/4 (multicast) 차단.
  - host 가 IPv6 리터럴이면 `::1` loopback, `fc00::/7` unique-local, `fe80::/10` link-local
    차단.
  - host 가 DNS 이름이면 `localhost`, `*.localhost`, `metadata.google.internal`, `metadata`,
    `instance-data` 차단.
- 외부 DNS 가 사설 IP 로 답하는 경우 (rebinding) 는 도메인 단계에서 잡을 수 없음 — 운영
  egress NetworkPolicy 가 link-local / RFC 1918 outbound 를 막아야 (Helm `networkPolicy.egressTo`).
- `http://localhost` 는 기존대로 dev exception (host 정확히 `localhost` 또는 `127.0.0.1`
  일 때만).

**회귀 테스트**: `WebhookEndpointTest.register_rejectsHttpsCloudMetadataAndPrivateRanges` —
AWS IMDS / GCP metadata / RFC 1918 / IPv6 loopback / link-local 전부 throw 검증.

**PG callback URL 은**: 우리가 받는 쪽 — PG → 우리. PG 측에 등록된 URL 은 PG console 에서만
바뀌고 우리 application.yml 의 `billing.pg.base-url` 은 우리가 호출하는 쪽 (운영자가 설정).
사용자가 임의 host 를 박을 수 있는 vector 는 webhook 뿐.

### API8 — Security Misconfiguration

**위협**: PG / DB secret 의 평문 노출, CORS 과허용, 비활성화 안 된 actuator endpoint.

**현황**:

- Helm secret — `values.yaml` 의 placeholder 는 dev 전용 (`changeme-in-secret`). prod 는
  `values-prod.yaml` 이 `existingSecret` 만 사용해 chart 가 secret 을 만들지 않게 강제.
- application.yml 에 평문 secret 없음. 모든 sensitive value 는 환경 변수 (`${DB_PASSWORD}`
  / `${OAUTH_ISSUER_URI}`).
- CORS — `SecurityConfig` 에 `cors()` 명시 없음. Spring Security 의 기본은 same-origin only.
  외부 통합이 필요해지면 명시적으로 origin allowlist 추가 (현재 시점은 외부 origin 없음).
- Actuator — `/actuator/health/**`, `/actuator/info`, `/actuator/prometheus`, `/actuator/modulith/**`
  만 permitAll. 나머지 (env, beans, configprops 등) 는 인증 후에도 deny — `denyAll` fallback.
- Swagger UI 는 prod 에서 permitAll 인 점은 의도 — 외부 통합 client 가 OpenAPI 를 본다. spec 자체
  에 secret 정보 없음.

### API9 — Improper Inventory Management

**위협**: 옛 v1 endpoint 가 새 v2 와 함께 운영되는데 deprecation / sunset 정책이 없으면
client 가 어느 시점에 마이그레이션 해야 할지 모르고, 운영자도 어느 endpoint 를 언제 제거할
수 있는지 모름. 결과적으로 "혹시 누가 쓸 수도 있다" 가 되어 v1 이 영원히 남음.

**막은 방식 (ADR-0031)**:

- path-based versioning (`/api/v1/`, `/api/v2/`) — 두 번째 깨지는 변경이 생기면 v3 추가.
- v1 응답에 `Deprecation: true` + `Sunset: <HTTP-date>` + `Link: rel="successor-version"`
  헤더 자동 부착 — `ApiV1DeprecationFilter`. `billing.api.v1.sunset-at` 미설정이면 헤더
  부착 안 함 (공식 deprecation 전엔 1급 시민 유지).
- `api_version_usage_total{version, resource}` Prometheus counter — 어느 client 가 v1 을
  계속 쓰는지 추적 (`ApiVersionMetricsFilter`).
- 운영 표준 cutover: v2 도입 + 6개월 grace → metric 으로 v1 사용량 감소 확인 → v1 controller
  제거 → 410 Gone.

### API10 — Unsafe Consumption of APIs

**위협**: PG / Wallet vendor 응답을 그대로 신뢰해 도메인 상태를 천이.

**막은 방식**:

- PG 호출은 [Resilience4j Circuit Breaker + Retry](../../billing-adapter-out/src/main/kotlin/com/example/billing/adapter/out/pg/RestClientPgClient.kt) (`@CircuitBreaker(name="pg")` / `@Retry(name="pg")`).
- 모든 PG 응답은 `AuthorizeResult` / `RefundResult` / `LookupResult` enum 으로 정상화 — 도메인은
  enum 값으로만 분기. PG 가 알 수 없는 status 를 보내도 fallback 메서드가 `rejected` /
  `inProgress` 로 변환.
- timeout — `RestClientWebhookHttpClient` 는 connect 5s / read 10s. PG 도 동일 수준.
- 3-phase 흐름 (`ProcessPaymentService`, `RefundService`) — PG 호출은 트랜잭션 밖. PG 가
  hang 해도 DB connection 점유 없음.
- DB write 는 PG 응답 status 가 매칭되는 경우만. unknown status 는 PENDING 으로 남기고
  reconciler 가 `PgClient.lookup` 으로 재확인 (ADR-0008).
- WiremockIT 로 PG 의 timeout / 5xx / 알 수 없는 body 케이스 회귀 잠금.

## 운영 절차

- 새 controller / endpoint 를 추가할 때:
  1. 자원이 customer-owned 이면 controller 가 `Caller.from(jwt)` 받아서
     `requireOwnerOrAdmin(...)` 호출.
  2. 운영자 전용이면 `@PreAuthorize("hasRole('admin')")` (class 단위 권장).
  3. 페이지네이션 / batch / quantity 같이 자원 소비가 큰 입력은 controller 에서 cap, DTO
     에서 `@Size` / `@Max`.
- 새 외부 URL 을 받는 endpoint 를 추가할 때:
  1. 도메인 단계에서 host 검사 (`WebhookEndpoint.rejectIfPrivateOrMetadataHost` 참고 패턴).
  2. NetworkPolicy egress 가 사설망 outbound 를 막는지 확인.
- 새 외부 API 를 호출할 때:
  1. Resilience4j (CB + Retry) 적용.
  2. 응답을 도메인 enum 으로 normalize — vendor field 를 그대로 도메인 객체에 박지 말 것.
  3. 3-phase 흐름이 가능한 도메인이면 외부 호출을 트랜잭션 밖으로.
