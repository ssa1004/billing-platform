# ADR-0024: Idempotency-Key 응답 캐싱 (24h)

## 상태
적용

## 배경

ADR-0006 의 Idempotency-Key (같은 요청이 두 번 와도 한 번만 처리되게 막는 키) 는 *점유 lock*
까지만 다룹니다. 같은 키로 두 번째 요청이 오면 `409 DUPLICATE_REQUEST` 만 떨어집니다.

문제는 *클라이언트의 정합 깨짐*. 모바일 결제 흐름에서 흔한 시나리오:

1. 클라이언트가 `POST /api/v1/payments` (Idempotency-Key: k-1) 보냄.
2. 서버가 PG 까지 호출해 결제 *이미 승인*. 응답 직렬화 직전.
3. 모바일 네트워크가 끊겨 클라이언트는 timeout (응답 못 받음).
4. 클라이언트는 같은 키 (k-1) 로 자동 재시도.
5. 서버는 점유 lock 이 잡혀 있어 `409 DUPLICATE_REQUEST` 만 반환.

클라이언트는 *결제가 됐는지 안 됐는지 모름* — 사용자에게 "결제 실패" 화면을 띄우면 사실은
결제는 됐는데 다시 결제하라고 유도하는 사고가 발생합니다. 별도 조회 API 로 상태 확인하는
복잡한 흐름을 클라이언트가 구현해야 함.

결제 API 의 표준 패턴은 **같은 키 재시도 시 처음 응답을 그대로 24h 동안 반환** — 대표 출처로
Stripe API 의 Idempotency-Key 명세 (24h 응답 캐시) 가 잘 알려져 있습니다. 두 번째 요청도 첫
번째와 *완전히 동일한* status / body 를 받아 정합. 클라이언트는 같은 키로 무한 재시도해도 안전.

## 결정

### 흐름

```
[POST /api/v1/payments  Idempotency-Key: k-1]
        ↓
IdempotencyResponseCacheFilter
        ↓
  ┌───────── 캐시 hit?
  ├─ YES → 처음 응답 그대로 reply (status + body) + Idempotent-Replayed: true 헤더, chain 차단
  └─ NO  → 정상 처리 → 응답 캡처 → 2xx 면 cache (24h TTL)
```

같은 키 + 같은 endpoint 의 모든 재시도가 같은 응답을 받습니다.

### 캐시 대상 endpoint (allowlist)

```yaml
billing.idempotency.cached-paths: /api/v1/payments,/api/v1/refunds
```

돈이 움직이는 행위만 cache. 조회 / 운영 endpoint 는 멱등이 자체 의미라 cache 불필요.
GraphQL / 다른 도메인 추가는 별도 검토.

### 16KB cap

응답 본문이 16KB 를 넘으면 cache skip (정상 처리는 그대로). 결제 / 환불 응답은 1~2KB 수준이라
cap 내에서 거의 모두 cover. PDF / CSV 같은 streaming 응답은 자연스럽게 우회.

결제 API 응답 본문은 보통 1~2KB 수준. 16KB cap 이면 충분.

### 24h TTL

- 결제 timeout retry 는 보통 수 초 ~ 수 분 단위 (모바일 push 알림 retry 도 길어야 1시간).
- 24h 면 거의 모든 정상 retry 시나리오 cover.
- TTL 길수록 Redis 메모리 증가 — 결제 1건당 ~ 1~2KB × 24h 트래픽.
- 24h 는 결제 API 의 사실상 기본값 (Stripe Idempotency-Key 명세 등 대표 출처).

### 4xx / 5xx 는 cache 안 함

```
2xx → cache (재시도 시 같은 응답)
4xx → cache 안 함 (client 가 원인 고쳐서 다른 키로 retry 가능)
5xx → cache 안 함 (서버 일시 장애 — 재시도하면 성공할 수 있음)
```

400/422 류 검증 실패는 클라이언트가 본문을 고쳐서 다른 키로 다시 보내야 의미 있음. 같은
응답을 24h 잡고 있으면 client UX 깨짐.

5xx 도 마찬가지 — *우리* 시스템 일시 오류이므로 client 가 같은 키로 재시도하면 다음에는
2xx 로 처리되어 정상 cache 가 박힘.

### Lock 점유와의 관계

- Filter 는 *cache hit* 만 보고 chain 차단. miss 면 도메인 service 까지 흘려보냄.
- Service 안에서는 `IdempotentExecution.acquireAndReleaseOnRollback(key)` 가 lock 점유.
- Lock 충돌 (다른 인스턴스가 이미 처리 중) → `DuplicateRequestException` → `409`.
  GlobalExceptionHandler 가 매핑.
- Filter 가 lock 충돌 시점에는 cache 가 아직 없을 수 있어 (처리 중) `409` 가 그대로 client
  에 도달. 이 경우 client 는 짧은 backoff 후 같은 키로 재시도하면 cache hit 으로 안전.

### 왜 ResponseBodyAdvice / ControllerAdvice 가 아닌가

Spring 의 `ResponseBodyAdvice` 는 (a) response body 를 직렬화 직전에 가로채지만 (b) 최종
status code 와 headers 를 모두 반영하기엔 너무 일찍 호출됨. `OncePerRequestFilter` +
`ContentCachingResponseWrapper` 는 *최종 응답이 다 만들어진 후* hooking 가능 — 여기서 status
+ body 를 같이 캐시.

또 filter 는 *cache hit 시 chain 자체를 차단* 할 수 있어 도메인 service 까지 안 가는 fast
path 를 자연스럽게 표현.

### Replay 헤더 (Idempotent-Replayed: true)

캐시 hit 응답에는 `Idempotent-Replayed: true` 헤더 추가. 결제 API 의 통상적인 명세를 따름.
클라이언트가 "이건 새 처리가 아닌 replay" 라고 인지할 수 있어 metric / 로그 분기 가능.

## 대안 검토

- **클라이언트가 status 별도 조회** (현재 흐름): client 복잡도 + race window 큼. *돈이 움직이는*
  도메인엔 부적합.
- **DB unique 제약 + UPSERT**: idempotency key 컬럼에 unique → 중복 INSERT 면 SQL 에러 → application
  service 가 catch 해서 기존 row 의 응답 재구성. 가능은 하지만 (a) 응답 본문이 도메인에서
  재구성 가능해야 함 (직렬화 형식 변경 시 깨짐) (b) 외부 PG 호출 결과의 raw 응답을 보존하기
  어려움.
- **응답을 도메인 자체에 저장**: Payment 테이블에 `last_response_body` 컬럼. 도메인을 운영
  관심사로 오염시키니 거부.
- **Servlet Filter 대신 Spring HandlerInterceptor**: 가능. Filter 가 request body 도 캡처할
  수 있어 (예: request body diff 비교) 더 일반적. 본 ADR 범위에서는 status + body 만 cache 라
  filter 로 충분.

## 결과

- 클라이언트가 같은 키 재시도해도 *완전히 동일한 응답* 을 받아 정합 사고 회피.
- 클라이언트 코드가 단순해짐 (별도 status 조회 불필요).
- 결제 API 표준 (24h 응답 캐시 + Idempotent-Replayed 헤더) 호환 — 외부 통합 시 친숙.
- 16KB cap 으로 메모리 폭주 차단.
- (단점) Redis 메모리 증가 — 결제/환불 1건당 ~ 1~2KB × 24h 트래픽. 운영 6개월 후 cap 재검토.
- (단점) Filter 가 critical path 위에 추가됨 — cache lookup 1회 (Redis ping) 가 latency 에
  추가. 보통 < 1ms 수준이라 무시 가능.
- (단점) 응답 본문 형식이 바뀌어도 24h 동안 *옛 형식* 그대로 반환 — 응답 schema 변경 후 24h
  는 mixed window. 일반적으로 결제 API 응답 schema 는 안정적이라 큰 문제 아님.

## 후속 후보

- ~~Request body fingerprint 검증 — 같은 키인데 본문이 다르면 *오용* (client bug) 으로 간주,
  422 로 즉시 알림.~~ → ADR-0028 에서 적용.
- Cache miss 시 점유 lock + cache 의 atomic 한 묶음 — Redis Lua script 로 `SET NX` + cache
  를 한 번에. 현재는 둘이 별도 호출이라 race window 가 짧게 존재.
- `Idempotency-Key` 형식 검증 (UUID-like, length cap) 추가.
- Cache hit 의 metric 노출 — `idempotency_cache_hit_total{path,status}`.
