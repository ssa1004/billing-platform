// k6 시나리오에서 Authorization 헤더에 붙일 토큰을 만든다.
//
// billing-platform 은 prod 프로필에서 OAuth2 Resource Server (auth-service 발급 JWT) 를
// 강제하지만, local/dev 프로필 (`WALLET_SECURITY_JWT_ENABLED=false`) 에서는 검증을 끄고
// 통과시킨다 (PermissiveSecurityConfig). 따라서 두 경로를 모두 지원한다:
//   1) K6_TOKEN env 가 비어 있으면 Authorization 헤더 없이 — dev / 통합 compose 에서 그대로 통과.
//   2) K6_TOKEN 이 있으면 Bearer 로 부착 — auth-stub / auth-service 발급 토큰을 외부에서 주입.
//
// 부하 시나리오의 목적은 JWT 라이프사이클 검증이 아니라 결제 / 청구 / 정산 endpoint 의
// throughput / latency 측정이라, dev 프로필 + 빈 헤더 만으로도 충분히 covered 된다.

const ENV_TOKEN = __ENV.K6_TOKEN || '';

/**
 * 공통 헤더 — Content-Type + Authorization (있을 때만).
 *
 * `Idempotency-Key` 는 endpoint 마다 의미가 달라 (POST /payments 는 24h 응답 캐싱 대상,
 * POST /usage 는 eventId 가 자체 멱등 키) 호출 측에서 직접 붙인다.
 */
export function authHeader() {
  const headers = {
    'Content-Type': 'application/json',
  };
  if (ENV_TOKEN) {
    headers['Authorization'] = `Bearer ${ENV_TOKEN}`;
  }
  return headers;
}

/**
 * 토큰 raw 값 — 진단용.
 */
export function rawToken() {
  return ENV_TOKEN;
}

/**
 * Idempotency-Key 생성 — RFC4122 v4 random uuid 형태. k6 stdlib 에 uuid 가 없어
 * hex 조합으로 흉내. payment-charge 시나리오에서는 *같은 키 재호출* 을 의도적으로
 * 만들어 24h 응답 캐시 hit 을 확인하므로, fixed key 가 필요하면 `fixedIdempotencyKey` 사용.
 */
export function newIdempotencyKey(prefix = 'k6') {
  const hex = (n) => Math.floor((1 + Math.random()) * 16 ** n).toString(16).slice(1);
  const uuid = `${hex(8)}-${hex(4)}-4${hex(3)}-${(8 + Math.floor(Math.random() * 4)).toString(16)}${hex(3)}-${hex(12)}`;
  return `${prefix}-${uuid}`;
}

/**
 * 결정적 (deterministic) 멱등 키 — 같은 (vuId, slot) 조합에 항상 같은 키를 만들어
 * payment-charge 시나리오에서 응답 캐시 hit 비율을 측정할 수 있게 한다.
 */
export function fixedIdempotencyKey(prefix, vuId, slot) {
  return `${prefix}-vu${vuId}-slot${slot}`;
}

/**
 * eventId — UUID v4 모양. POST /api/v1/usage 가 eventId 를 PK 겸 멱등 키로 쓴다.
 * 매 호출마다 새 값이 정상 — 같은 eventId 가 두 번 들어오면 두 번째는 INSERT IGNORE
 * 처럼 무시되어 응답 `accepted=false` 로 떨어진다 (의도된 동작).
 */
export function newEventId() {
  const hex = (n) => Math.floor((1 + Math.random()) * 16 ** n).toString(16).slice(1);
  return `${hex(8)}-${hex(4)}-4${hex(3)}-${(8 + Math.floor(Math.random() * 4)).toString(16)}${hex(3)}-${hex(12)}`;
}
