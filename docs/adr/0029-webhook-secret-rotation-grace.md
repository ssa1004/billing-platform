# ADR-0029: Webhook secret rotation 의 grace window

## 상태
적용

## 배경

ADR-0022 의 webhook 발신은 endpoint 마다 secret 을 두고 HMAC-SHA256 으로 본문에 서명합니다.
customer 는 자기가 보관한 같은 secret 으로 검증해 *진짜 우리* 가 보낸 webhook 인지 판정.

Secret 갱신 (rotation) 은 *분실 / 노출 / 정기 갱신* 시 운영 표준 흐름. 기존 구현은:

```java
public void rotateSecret(Clock clock) {
    this.secret = generateSecret();
    this.updatedAt = clock.instant();
}
```

→ 새 secret 을 즉시 활성, 이전 secret 즉시 무효. *간단하지만 운영에 위험*.

### 문제 — rotation 직후의 brief downtime

운영 흐름:

1. 운영자가 `POST /webhooks/{id}/rotate-secret` 호출. 응답으로 새 secret 받음.
2. 운영자가 customer 측 시스템에 새 secret 을 반영하기 시작.
3. 그 사이에 우리 시스템에서 다음 webhook 발송 → *새 secret 으로 서명*. 그러나 customer 측은
   *아직 이전 secret 으로 검증 시도* → 검증 실패 → webhook drop.
4. customer 가 새 secret 을 반영 완료할 때까지 짧은 시간 (수 분 ~ 수 시간) 동안 *모든 webhook 검증 실패*.

이게 *deployment overlap* 으로 알려진 흔한 사고. 검증 실패한 webhook 은 우리 dead-letter 로
떨어지지만, customer 입장에서는 *이벤트 누락* — invoice 발급 / 결제 완료 알림이 안 옴.

### 업계 표준 — grace window

Stripe / GitHub / 토스페이먼츠 / 카카오워크 모두 같은 패턴:

- Rotation 시 새 secret 을 *current* 로 활성, 이전 secret 을 *previousSecret* 으로 demote.
- 24h grace 동안 *두 secret 모두 유효* — 발신 측은 두 secret 으로 각각 서명한 두 값을 같은
  헤더에 콤마로 결합해 보냄. customer 가 *어느 한 쪽이라도* 자기 secret 과 일치하면 검증 통과.
- 24h 후 previousSecret 자동 만료. 그 시점에 customer 는 새 secret 으로 업데이트되어 있어야 함.

Stripe 의 헤더 형식 참고:

```
Stripe-Signature: t=1612480200,v1=NEW_HASH,v1=OLD_HASH
```

같은 `v1=` prefix 의 두 값이 콤마로 이어짐. customer 는 모든 v1 값에 대해 검증 시도.

## 결정

### 도메인 변경 — `WebhookEndpoint`

```java
public final class WebhookEndpoint {
    private String secret;                         // 현재 활성
    private String previousSecret;                 // grace 안의 직전 secret (null = grace 밖)
    private Instant previousSecretValidUntil;      // previousSecret 만료 시각
    public static final Duration DEFAULT_ROTATION_GRACE = Duration.ofHours(24);

    public void rotateSecret(Clock clock) {
        rotateSecret(clock, DEFAULT_ROTATION_GRACE);
    }

    public void rotateSecret(Clock clock, Duration graceWindow) {
        this.previousSecret = this.secret;                              // demote
        this.previousSecretValidUntil = clock.instant().plus(graceWindow);
        this.secret = generateSecret();                                 // 새 secret 활성
        this.updatedAt = clock.instant();
    }

    public List<String> activeSecrets(Clock clock) {
        if (previousSecret != null && clock.instant().isBefore(previousSecretValidUntil)) {
            return List.of(secret, previousSecret);
        }
        return List.of(secret);
    }

    public boolean expirePreviousSecretIfDue(Clock clock) { /* lazy cleanup */ }
}
```

### 발신 측 — 두 서명을 콤마로 결합

```java
String signatureHeader = endpoint.activeSecrets(clock).stream()
        .map(secret -> WebhookSignature.sign(secret, timestamp, payload))
        .reduce((a, b) -> a + "," + b)
        .orElseThrow();
```

`activeSecrets(clock)` 가 grace 안이면 [new, old], 밖이면 [new]. 결과 헤더:

- grace 밖: `X-Webhook-Signature: sha256=NEW_HASH`
- grace 안: `X-Webhook-Signature: sha256=NEW_HASH,sha256=OLD_HASH`

customer 측 검증 코드는 *콤마 split → 각각 hex decode → 자기 secret 으로 다시 계산해 어느 하나라도
일치하면 통과* 가 되도록 작성. 표준 SDK (있다면) 가 이를 자동 처리.

### 영속화 — V12 migration

```sql
ALTER TABLE webhook_endpoints
    ADD COLUMN previous_secret              VARCHAR(128),
    ADD COLUMN previous_secret_valid_until  TIMESTAMP;

ALTER TABLE webhook_endpoints
    ADD CONSTRAINT chk_webhook_endpoint_previous_secret_pair
        CHECK (
            (previous_secret IS NULL AND previous_secret_valid_until IS NULL)
         OR (previous_secret IS NOT NULL AND previous_secret_valid_until IS NOT NULL)
        );
```

CHECK 제약으로 *둘 다 NULL or 둘 다 set* invariant 를 DB 레벨에서 보장. 한쪽만 set 인 row 는
운영 사고 신호 (코드 bug / 수동 SQL) 라 INSERT/UPDATE 자체를 거부.

`prod` (Postgres) 에서는 V12_1 으로 partial index 적용 — 활성 grace 가 있는 endpoint 는 보통 전체의
< 1% 라 partial 이 작고 빠름. H2 는 partial 미지원이라 dev 는 일반 인덱스.

### 두 번 rotate 안에서의 의도

grace 안 (24h 안) 에서 또 rotate 하면 — 가운데 secret 은 사라지고 *방금 직전* secret 이 새
previousSecret 이 됨. 3개 이상 동시 활성은 운영 복잡도만 키우고 의미 없음. 보안적으로도 안전
(덮어씌워진 secret 도 무효).

운영 시그널: 짧은 사이 두 번 rotate 가 발생했다면 *secret 이 의심스러운 상황* — 운영 대시보드
알람 띄우기 좋은 지점.

### Lazy cleanup — `expirePreviousSecretIfDue`

만료된 previousSecret 정리는 두 방식 결합:

1. **활성 검증 시 자동 제외** — `activeSecrets(clock)` 가 grace 만료 secret 을 자동으로 빼버림.
   영속 row 에는 남아있지만 *검증/서명에는 영향 없음*. 보안적으로 안전.
2. **명시적 cleanup** — cron / on-demand 로 `expirePreviousSecretIfDue` 호출해 row 정리.
   필수는 아니고 *운영 위생* 목적.

이중 layer 라 cleanup 작업이 한 번 빠져도 보안 사고 안 남.

### Secret 의 운영 노출 — 응답 mask

기존: `register` / `rotate-secret` 응답에서 새 secret 한 번 평문 노출. 이후 GET 으로는 노출 안 함.
변경 없음 — `previousSecret` 도 응답에 노출 *안 함* (운영자가 알 필요 없음 — grace 자동 처리).

### 기본 grace 24h 가 적절한가

- **24h 미만 (1h, 6h)**: customer 운영팀이 야간 / 주말에 secret 교체 못 하는 케이스 발견. customer
  CS 부담 증가.
- **24h**: Stripe / GitHub 표준. customer 가 *영업일 안에 한 번만* 작업하면 충분.
- **24h 초과 (72h, 7d)**: secret 노출 시나리오에서 *오래 활성 유지* 가 위험. 24h 가 균형점.

긴급 노출 케이스에는 `rotateSecret(clock, Duration.ofMinutes(5))` 같이 짧은 grace 호출 (테스트 / 운영
긴급) — API 는 별도 검토 (현재 미적용).

## 대안 검토

- **즉시 invalidate** (현재 정책): 위에서 거부. deployment overlap 이슈.
- **이전 secret 을 영원히 유지** (chain 식): 보안적으로 위험. 분실 secret 이 만료되지 않으면
  공격자가 그 secret 으로 영원히 위조 webhook 보낼 수 있음.
- **두 secret 을 별도 *헤더* 로**: `X-Webhook-Signature` 와 `X-Webhook-Signature-Old`. 동작은
  같지만 customer 측에서 두 헤더를 따로 처리해야 — 표준 SDK 와 호환 깨짐. Stripe 식 *콤마 결합*
  이 한 헤더라 코드 단순.
- **AsymmetricKey (Ed25519) 기반 서명**: customer 가 우리 *공개키* 로만 검증 — secret 갱신 시
  공개키만 교체. 그러나 Ed25519 라이브러리 의존성 / customer 측 구현 부담 증가. webhook 표준은
  여전히 HMAC.
- **Ephemeral secret 으로 매 webhook 마다 새 secret**: 과한 복잡도 + customer 가 매번 새 secret
  을 받아 적용해야 함. 비현실.

## 결과

- Rotation 의 *deployment overlap* 사라짐 — customer 가 24h 안에 새 secret 반영하면 끊김 없음.
- Stripe / GitHub 표준에 호환 — customer 가 다른 SaaS 와 같은 검증 코드 패턴 사용 가능.
- DB CHECK 제약으로 invariant 강제 — 한쪽 컬럼만 set 인 row 자체를 거부.
- Lazy cleanup + 자동 제외 이중 layer — cleanup 작업 미실행 시에도 보안 안전.
- (단점) Webhook 헤더 크기 증가 — 두 서명 = 약 200 byte. 정상 운영 영향 없음.
- (단점) 두 번 HMAC 계산 — < 100us 로 무시 가능. 다만 audit-export 같은 대량 fan-out 에서는
  운영 metric 모니터링.
- (단점) Customer 측 검증 코드가 *콤마 split + multi-secret 시도* 를 지원해야 함. 우리 자체 SDK
  배포 시점에 함께 업그레이드 필요.

## 후속 후보

- `expirePreviousSecretIfDue` 자동 호출 — Spring Scheduler 로 매 시간 만료 row cleanup.
- `previousSecret` 의 raw 값 로깅 / 응답 노출 차단 검증 — coverage 테스트.
- KMS / Vault 기반 envelope encryption — DB 의 secret 평문 저장 자체를 한 단계 더 감쌈. 이건
  ADR-0029 의 후속이 아닌 별도 ADR (운영 인프라).
- 짧은 grace (긴급 노출 케이스) 의 API 노출 — `POST /webhooks/{id}/rotate-secret?grace-hours=1`.
- Customer 측 SDK 업그레이드 — multi-secret 검증 / migration 가이드 문서.
- Webhook 검증 *수신 측* (예: 외부 PG 가 우리에게 보낸 webhook 검증) 의 secret rotation grace —
  같은 패턴이지만 *수신* 방향. 본 ADR 은 발신만.
