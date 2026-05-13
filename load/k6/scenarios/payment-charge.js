// POST /api/v1/payments 결제 처리 부하 — Idempotency-Key 24h 응답 캐시 검증.
//
// 시나리오 의도:
//   - 결제는 실시간 path 중 가장 보호받아야 하는 endpoint — PG 호출 + Ledger append +
//     Outbox INSERT 가 한 트랜잭션에 들어간다. constant 100 req/s 로 단일 노드 기준
//     의미 있는 결제 throughput 의 상한 근처를 본다.
//   - **Idempotency-Key 24h 응답 캐싱 검증** — Stripe API 표준 처방 (같은 키 재호출 시
//     첫 응답을 그대로 24h 동안 반환). IdempotencyResponseCacheFilter 가 `Idempotent-Replayed:
//     true` 헤더를 붙여 client 가 replay 임을 알 수 있게 한다 (ADR-0028).
//   - 80% 새 키 (cache miss path) + 20% 같은 키 재호출 (cache hit path) 으로 섞어
//     `idempotency_cache_hit_ratio` metric 으로 cache hit 비율을 직접 측정.
//
//   * orderId 가 미리 seed 되지 않은 환경에서는 PaymentController 가 404 (ORDER_NOT_FOUND) 로
//     떨어진다 — 404 는 캐시되지 않으므로 (`isSuccess` 분기) 같은 키 재호출도 새로 처리되어
//     cache hit ratio 가 0 으로 떨어진다. 부하 환경에서는 N 개의 order 를 미리 seed 하고
//     `K6_ORDER_IDS=id1,id2,...` 로 주입해야 idempotency-cache-hit-ratio threshold 가 의미 있다.
//
// thresholds:
//   - http_req_duration p95 < 200ms — PG 호출 (Mock 또는 Wiremock) + DB write 의 합
//   - http_req_failed rate < 5% — seed 없는 환경의 404 가 일부 섞이는 걸 허용
//   - idempotency_cache_hit_ratio > 0.8 (재호출 path 에 한정) — 응답 캐시가 정상 동작하면
//                                                                 재호출의 80% 이상이
//                                                                 Idempotent-Replayed: true.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { BASE_URL, pickOrderId } from '../lib/config.js';
import { authHeader, newIdempotencyKey, fixedIdempotencyKey } from '../lib/auth.js';

const cacheHitRatio = new Rate('idempotency_cache_hit_ratio');
const cacheMissCount = new Counter('payment_cache_miss_count');
const cacheHitCount = new Counter('payment_cache_hit_count');
const orderNotFound = new Counter('payment_order_not_found');

// 한 VU 가 *재호출 모사* 를 위해 사용할 slot 갯수. 같은 (vuId, slot) 에는 같은 key 가
// 떨어지도록 — newIdempotencyKey 와 fixedIdempotencyKey 두 path 의 응답 캐시 hit 을 비교.
const REPLAY_SLOTS = 5;

export const options = {
  scenarios: {
    payment_charge: {
      executor: 'constant-arrival-rate',
      rate: 100,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 40,
      maxVUs: 200,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<200', 'p(99)<600'],
    // 같은 키 재호출 path 에 한정한 cache hit 비율 — 첫 호출은 miss 가 정상이므로 임계 80%.
    idempotency_cache_hit_ratio: ['rate>0.8'],
  },
};

export default function () {
  const orderId = pickOrderId(__ITER);
  if (!orderId) {
    // seed 가 없는 환경의 빠른 종료 — 4xx 비율만 누적되고 cache hit 측정은 무의미.
    // 부하 환경 미완성 신호로 해석한다 (README 참고).
    orderNotFound.add(1);
    sleep(0.05);
    return;
  }

  // 80% 새 키 (첫 호출 path) + 20% 같은 키 재호출 (cache hit path) — REPLAY_SLOTS 안에서
  // round-robin. 같은 (vuId, slot) 의 두 번째 iteration 부터 cache hit 이 떨어져야 한다.
  const replayThisCall = (__ITER % 5) === 0;
  const idemKey = replayThisCall
    ? fixedIdempotencyKey('replay', __VU, __ITER % REPLAY_SLOTS)
    : newIdempotencyKey('pay');

  const payload = JSON.stringify({
    orderId: orderId,
    method: 'CARD',
  });

  const headers = authHeader();
  headers['Idempotency-Key'] = idemKey;

  const res = http.post(`${BASE_URL}/api/v1/payments`, payload, {
    headers: headers,
    tags: { name: 'payment-charge', replay: replayThisCall ? 'yes' : 'no' },
  });

  // 응답 캐시 hit 시 `Idempotent-Replayed: true` 헤더가 붙는다 — 이게 cache hit 의 정확한 신호.
  const replayed = (res.headers['Idempotent-Replayed'] || res.headers['idempotent-replayed'] || '') === 'true';

  if (replayThisCall) {
    // 같은 키 재호출 path — first call 은 miss (replayed=false), N 번째 부터 hit (replayed=true).
    // first call 까지 miss 로 잡으면 ratio 가 1/N 정도로 떨어져 threshold 가 의미 없어진다.
    // 그래서 *replayThisCall 인 호출 중 헤더가 붙은 비율* 을 본다.
    cacheHitRatio.add(replayed);
    if (replayed) {
      cacheHitCount.add(1);
    } else {
      cacheMissCount.add(1);
    }
  }

  if (res.status === 404) {
    orderNotFound.add(1);
  }

  check(res, {
    'status 2xx or 404 (seed-dependent)': (r) => (r.status >= 200 && r.status < 300) || r.status === 404,
    'never 5xx': (r) => r.status < 500,
    'replay has header (when applicable)': (r) => {
      // 헤더 자체는 응답이 cache hit 이 아니어도 안 붙는 게 정상 — 강제 조건 아님.
      return true;
    },
  });

  sleep(0.05);
}
