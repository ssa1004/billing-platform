// GET /api/v1/invoices?customerId=...&limit=... 청구서 목록 조회 부하 — read-side.
//
// 시나리오 의도:
//   - 운영자 대시보드 / 고객사 self-service 화면이 가장 자주 치는 read endpoint.
//     `(customer_id, created_at DESC)` 인덱스가 잡혀 있어 read-replica + 캐시 없이도
//     빠른 path. constant 300 req/s 로 read-heavy 트래픽의 정상 path 를 측정한다.
//   - cursor pagination — v1 InvoiceController 는 `limit` 만 받는 단순 형태고
//     (`findByCustomer(customerId, limit)`), v2 InvoiceV2Controller 는 `currency` 추가
//     필터 까지만. 본 시나리오는 v1 + v2 두 경로의 latency 를 비교 측정한다 (커서가
//     명시 노출되진 않지만 limit=20 의 첫 페이지가 운영 실 사용 패턴 — Stripe API 의
//     `starting_after` 같은 커서는 후속 개선 항목).
//
//   * task 명세의 "cursor pagination" 은 본 시점 endpoint 가 노출하지 않으므로 limit
//     기반 첫 페이지 latency 측정으로 대체. 정합성 회귀 가드는 v2 에서 currency 필터링
//     분기가 들어간 응답이 200 인지로 본다.
//
// thresholds:
//   - http_req_duration p95 < 100ms — 인덱스 (cust, created_at DESC) hit + Jackson 직렬화.
//                                     캐시 없이도 충분히 빠른 분기.
//   - http_req_failed rate < 1%
//   - invoice_query_v1_ok / invoice_query_v2_ok rate > 99%

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL, pickCustomer, pickCurrency } from '../lib/config.js';
import { authHeader } from '../lib/auth.js';

const v1Ok = new Rate('invoice_query_v1_ok');
const v2Ok = new Rate('invoice_query_v2_ok');

export const options = {
  scenarios: {
    invoice_query: {
      executor: 'constant-arrival-rate',
      rate: 300,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 60,
      maxVUs: 200,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<100', 'p(99)<300'],
    invoice_query_v1_ok: ['rate>0.99'],
    invoice_query_v2_ok: ['rate>0.99'],
  },
};

export default function () {
  const customer = pickCustomer(__VU, __ITER);
  const currency = pickCurrency(__ITER);

  // 매 iteration 마다 v1 + v2 두 endpoint 를 모두 호출 — 같은 부하 모델 안에서
  // 두 응답 schema 분기 (v1: amount/currency 분리, v2: MoneyV2 객체 + currency 필터링)
  // 의 latency 를 함께 측정.
  const v1Url = `${BASE_URL}/api/v1/invoices?customerId=${encodeURIComponent(customer)}&limit=20`;
  const v1Res = http.get(v1Url, {
    headers: authHeader(),
    tags: { name: 'invoice-query-v1' },
  });
  v1Ok.add(v1Res.status === 200);
  check(v1Res, {
    'v1 status 200': (r) => r.status === 200,
    'v1 body is JSON array': (r) => {
      const body = r.body || '';
      return body.startsWith('[') || body === '[]';
    },
  });

  // v2 — currency 필터링 분기. seed 가 없는 customer 는 빈 [] 가 정상.
  const v2Url = `${BASE_URL}/api/v2/invoices?customerId=${encodeURIComponent(customer)}&limit=20&currency=${encodeURIComponent(currency)}`;
  const v2Res = http.get(v2Url, {
    headers: authHeader(),
    tags: { name: 'invoice-query-v2' },
  });
  v2Ok.add(v2Res.status === 200);
  check(v2Res, {
    'v2 status 200': (r) => r.status === 200,
  });

  sleep(0.05);
}
