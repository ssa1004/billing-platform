# ADR-0028: Idempotency 키 + 본문 fingerprint 검증 (Stripe 식 422)

## 상태
적용

## 배경

ADR-0006 의 idempotency 키 + ADR-0024 의 응답 캐싱은 *같은 키로 같은 요청을 재시도하는* 정상
케이스를 cover. 그러나 *같은 키로 다른 의도의 요청* — 즉 client bug — 는 검출이 안 됩니다.

### 실제 사고 시나리오

모바일 결제 흐름의 client 코드 일부:

```javascript
// 사용자가 결제 화면을 나갔다가 다시 들어왔을 때.
const idempotencyKey = await loadCachedKey() ?? generateNewKey();
await api.payments.create({
    idempotencyKey,
    amount: cart.total,            // ← 이 값이 화면 갱신 후 변경되었을 수 있음
    orderId: order.id,
});
```

- 사용자가 첫 결제 화면에서 `amount=10000` 으로 키 `k-1` 발급.
- 네트워크 timeout 으로 응답 못 받음. 사용자가 화면 갱신 → cart 가 바뀌어 `amount=15000` 인데,
  client 가 *같은 `k-1` 키* 로 다시 호출.
- 서버는 ADR-0024 의 응답 캐시 hit → 첫 요청의 응답 (`amount=10000` 으로 처리됨) 그대로 반환.
- 사용자는 `15000` 결제했다고 생각하지만 실제로는 `10000` 이 처리됨 → CS 분쟁.

이게 *idempotency key 의 오용* — 같은 키는 *완전히 동일한 요청* 의 재시도여야 한다는 명세를
client 가 위반한 사례. 서버는 이걸 *침묵으로 받아들임* — 캐시 hit 만 보고 응답하니까.

### 업계 표준 — Stripe 의 422

Stripe 는 같은 idempotency key 로 *다른 parameters* 가 오면:

```
HTTP/1.1 422 Unprocessable Entity
{
  "error": {
    "type": "idempotency_error",
    "message": "Idempotency-Key already used with different parameters"
  }
}
```

→ client 가 *즉시* bug 를 인지. 새 키로 재시도하면 정상 처리. GitHub / Square / Adyen 도 동일
명세.

## 결정

**첫 요청의 body 의 SHA-256 prefix 16-byte (= 32 hex chars) 를 fingerprint 로 저장. 같은 키
재요청 시 fingerprint 비교 — 다르면 422 INCOMPATIBLE_PARAMS.**

### 흐름

```
[POST /api/v1/payments  Idempotency-Key: k-1, body: {...}]
        ↓
IdempotencyResponseCacheFilter
        ├─ body bytes 읽어서 SHA-256 prefix 계산 → fp(req)
        │
        ├─ store.findRequestFingerprint(k-1) ─→ existing
        │   ├─ existing 없음            → 첫 호출 path 진행
        │   ├─ existing == fp(req)      → 정상 (응답 캐시 path 진행)
        │   └─ existing != fp(req)      → IncompatibleRequestException → 422
        │
        ├─ 응답 캐시 lookup (ADR-0024)
        │
        └─ 첫 호출 path: store.recordRequestFingerprint(k-1, fp(req)) → chain.doFilter
```

### 왜 SHA-256 prefix 16 byte 인가

- **SHA-256 자체** 는 해시 충돌 확률 사실상 0 (2^-128). full hash 는 32 byte (64 hex chars).
- **16-byte prefix (128 bit)** 면 충돌 확률 ~ 2^-64. 같은 customer 의 24h 안의 모든 결제 요청을
  모아도 충돌 발생 확률 무시 가능 (10^9 요청에 대해 ~10^-19 충돌 확률).
- **저장 비용** — 32 hex chars 는 64 byte (UTF-8). full hash 는 128 byte. Redis 메모리 절약.
- **비교 비용** — 일정 길이 32 chars 비교는 < 1us.

→ 16 byte prefix 가 *정확성-비용* 의 sweet spot.

### 왜 full body 비교가 아닌가

같은 의도로 일부 시스템은 *first request body 자체* 를 Redis 에 저장 후 byte-by-byte 비교.

- 1MB body 라면 Redis 저장 비용 + GET 으로 1MB 가져오는 비용 + 비교 비용 모두 큼.
- SHA-256 는 *상수 비용* (32 hex chars) 이라 body 크기 무관.
- 충돌 확률 무시 가능 (위 계산).

→ fingerprint 가 명백한 우위.

### Timing-safe 비교 (`MessageDigest.isEqual`)

```kotlin
fun matches(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
```

`String.equals` 는 첫 mismatch byte 에서 즉시 false → timing attack 으로 부분 매칭 정보 유출 가능.
`MessageDigest.isEqual` 는 *전체 byte 끝까지* 비교 → 일정 시간.

본 use-case 는 *공격* 이 아닌 *client bug 검출* 이라 timing attack 위험은 낮지만, 표준 보안
패턴 따르기 + 향후 secret 비교 (HMAC, JWT 등) 에 동일 utility 재사용 여지를 고려.

### 1MB body cap

`IdempotencyKeyStore.MAX_FINGERPRINT_BODY_BYTES = 1MB`. 이보다 큰 body 는 fingerprint skip
(같은 키 재시도 시 응답 캐시만으로 정합 보장).

근거: 정상 결제 / 환불 요청은 < 4KB. 1MB 면 *모든* 정상 요청을 cover하면서 비정상 multipart
업로드 등은 자연스럽게 우회.

### Rollback 시 fingerprint 도 release

ADR-0024 의 release 시나리오 — 첫 요청이 검증 실패로 rollback. 다음 retry 가 *고친 body* 를
보내면:

- fingerprint 가 남아있으면 → mismatch → 422 → 사용자 경험 깨짐 (정상 흐름인데 client bug 처럼 보임).
- fingerprint 도 같이 release → 다음 retry 가 정상 처리.

`IdempotencyKeyStore.release` 가 lock + fingerprint 모두 제거하도록 구현.

### Race window — record 의 idempotency

같은 키로 *동시 두 호출* 이 와서 둘 다 fingerprint 가 비어 있다고 봤을 때:

- 둘 다 record 시도 → InMemory `putIfAbsent`, Redis `setIfAbsent` — 한 쪽만 이김.
- 두 호출의 body 가 같으면 → 둘 다 정상 처리되어 응답 캐시 박힘 (ADR-0024).
- 두 호출의 body 가 다르면 → 한 쪽만 fingerprint 박힘. 진 쪽은 422 받음 (acquireOrThrow 도
  같이 동작해 Duplicate 가 먼저 떨어지는 경우도 있음 — 실제 흐름은 둘 다 client bug 신호).

→ Race window 에서도 안전하게 first-write-wins.

### 왜 Filter 단에서 검증인가 (controller / service 가 아닌)

- *모든* idempotency key endpoint 가 같은 검증 받아야 함 — controller 마다 코드 중복 안 됨.
- request body 를 두 번 읽기 위한 wrapping 이 필요 — Spring MVC 의 `@RequestBody` 는 stream 한 번
  소진. wrapping 은 filter 의 자연스런 책임.
- Filter 단에서 *fast-fail* — service 까지 진입 안 하고 422 → application service / DB 부하 절감.

### Body 의미적 동등성은 검증 안 함 (raw byte 비교만)

```json
// 이 두 body 는 *다른 fingerprint* 가 됨 — 의도된 동작.
{"amount":1000}
{"amount": 1000}    ← 공백 추가
```

같은 의미인데 byte 가 다르면 다른 fingerprint. 이게 우리 정책:

- *완전히 동일한 byte* 의 retry 만 같은 처리 보장.
- 의미적 동등성 (semantic equality) 까지 검증하려면 body 정규화 (canonical JSON) 필요한데:
  - JSON 키 순서, 공백, 숫자 표현 (`1000` vs `1.0e3`) 등 normalization 복잡.
  - 가짜 동등성 (의도는 같은데 형식 약간 다른) 으로 *진짜 다른 의도* 가 통과되는 위험.
  - Stripe / Square 도 raw byte 만 비교.

→ Client 가 같은 키로 retry 할 때는 *원본 byte 그대로* 보내야 한다는 명세를 client 측에서 유지.
대부분의 SDK (Stripe SDK 포함) 가 retry 시 같은 직렬화를 보장.

## 대안 검토

- **Body 자체를 Redis 에 저장 + byte 비교**: 위에서 거부 (저장/비교 비용 큼).
- **Hash 만 저장하지만 full SHA-256 (32 byte)**: 32 byte vs 16 byte 의 충돌 확률 차이는 의미 없는
  수준 (둘 다 무시 가능). 저장 비용만 2배. 16 byte 로 충분.
- **MD5 / SHA-1 prefix**: 충돌 확률 좀 더 높지만 여전히 무시 가능. 그러나 *deprecated* 알고리즘은
  보안 audit 에서 flag 됨 — SHA-256 가 표준.
- **DB 컬럼으로 저장** (idempotency_keys 테이블): ADR-0024 의 응답 캐시도 Redis 라 일관성 유지.
  DB 추가하면 트랜잭션 / TTL 관리 복잡.
- **Body 의 의미적 hash** (canonical JSON 후 hash): normalization 복잡 + 가짜 동등성 위험.
  거부.
- **HMAC-keyed hash (server secret 포함)**: timing attack 방어가 더 강하지만, 우리 use-case 는
  공격 방어가 아닌 client bug 검출. SHA-256 만으로 충분.

## 결과

- 같은 키로 *다른 body* 재요청이 422 INCOMPATIBLE_PARAMS 로 즉시 검출.
- Stripe 표준에 호환 — 외부 통합 시 client SDK 가 같은 에러 처리 흐름 사용 가능.
- Race window 에서도 first-write-wins 로 안전.
- Filter 단 fast-fail — service / DB 까지 진입 안 함.
- (단점) Body byte 비교만 — JSON 공백 등 형식 차이도 mismatch. Client SDK 의 retry 직렬화 일관성
  의존.
- (단점) 1MB 초과 body 는 fingerprint skip — 정상 운영 상관없으나, 향후 큰 multipart 결제 (예:
  영수증 첨부) 등이 추가되면 별도 검토.
- (단점) Hash 충돌 (~ 2^-64) 시 *false negative* — 다른 body 인데 같은 fingerprint 로 판정되어
  통과. 천문학적 확률이라 무시 가능하나, 100% 보장은 아님.

## 후속 후보

- Cache miss 시 점유 lock + fingerprint + cache 의 atomic 한 묶음 — Redis Lua script 로 SET NX +
  fingerprint set 을 한 번에 (현재는 둘이 별도 호출이라 race window 가 짧게 존재). ADR-0024 의
  후속과 합치면 좋음.
- `Idempotency-Key` 헤더 형식 검증 (UUID-like, length cap) 추가 — client 가 무작위 키를 안 만들고
  short 한 키를 재사용하는 사고 방지.
- Mismatch 시 *어느 부분이 다른지* 응답에 hint — JSON diff 같은 것. 운영 디버깅에 도움. 다만
  body 자체를 응답에 노출하면 보안 / privacy 위험.
- Metric 노출 — `idempotency_fingerprint_mismatch_total{path,customer}` 로 client SDK 별 미스매치율
  모니터링.
