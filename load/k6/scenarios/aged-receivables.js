// GET /api/v1/aged-receivables 미수금 집계 조회 부하 — 집계 쿼리 부하.
//
// 시나리오 의도:
//   - 운영 / 회계 화면용 read endpoint — (customer × currency) 별로 aging bucket
//     (current / 31-60 / 61-90 / 90+ 일) 미수 금액을 집계. multi-currency 분리 집계라
//     같은 customer 가 KRW / USD / JPY 청구서를 모두 갖고 있으면 응답에 3 row 가 떨어진다.
//   - constant 50 req/s — 집계 쿼리이므로 invoice-query 보다 부하 모델 자체가 낮다.
//     production 의 운영자 대시보드 polling 빈도 (수 초 ~ 수 분) 와도 비슷한 수치.
//   - **read-replica 라우팅 ([ADR-0025])** 가 정상이면 read 가 master DB 의 결제 write
//     트래픽과 자원 경합이 없어야 한다. 같은 시점에 invoice-issue / payment-charge 가
//     함께 돌 때 본 시나리오의 p95 가 흔들리지 않으면 라우팅이 의도대로 동작하는 신호.
//
//   * task 명세의 endpoint 는 `/api/v1/receivables/aged?asOf=...` 지만, 실제
//     AgedReceivablesController 는 `/api/v1/aged-receivables` 에 매핑되어 있고 query
//     param 도 받지 않는다 (asOf 는 service 가 Instant.now() 로 계산). 실제 endpoint
//     기준으로 시나리오를 짠다.
//
// thresholds:
//   - http_req_duration p95 < 300ms — 집계 쿼리 (Invoice 전체 스캔 + bucket 계산).
//                                     invoice 테이블이 N 만 row 이상으로 커지면 인덱스
//                                     + read-replica 라우팅이 필요.
//   - http_req_failed rate < 1%
//   - aged_currency_diversity > 1 — 한 응답에 currency 가 2 종 이상 분리되어 나오는
//                                   호출의 비율. multi-currency 부하가 의도대로 데이터에
//                                   반영됐는지 sanity check.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { BASE_URL } from '../lib/config.js';
import { authHeader } from '../lib/auth.js';

const currencyDiversity = new Rate('aged_currency_diversity');
const rowCount = new Trend('aged_response_rows');

export const options = {
  scenarios: {
    aged_receivables: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 15,
      maxVUs: 80,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<300', 'p(99)<800'],
  },
};

export default function () {
  // 본 endpoint 는 query param 없이 전체 미수 집계를 돌려준다 (asOf 는 server 시점).
  const res = http.get(`${BASE_URL}/api/v1/aged-receivables`, {
    headers: authHeader(),
    tags: { name: 'aged-receivables' },
  });

  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'has asOf field': (r) => (r.body || '').includes('asOf'),
    'has rows array': (r) => (r.body || '').includes('rows'),
  });

  if (ok) {
    const body = res.body || '';
    // currency 종류 카운트 — multi-currency 데이터가 정상으로 들어왔다면 KRW/USD/JPY 중
    // 2 종 이상이 떨어진다. 정규식으로 `"currency":"XXX"` 추출 후 unique 개수 비교.
    const matches = body.match(/"currency"\s*:\s*"[A-Z]{3}"/g) || [];
    const unique = new Set(matches.map((m) => m.match(/"([A-Z]{3})"/)[1]));
    currencyDiversity.add(unique.size > 1);

    // row 수 — Trend 로 분포 추적. 너무 작으면 seed 가 부족, 너무 크면 페이지네이션
    // 도입이 필요한 신호.
    const rowMatches = body.match(/"customerId"\s*:/g) || [];
    rowCount.add(rowMatches.length);
  }

  // 50 req/s 라 sleep 부담이 낮다. 운영자 대시보드 polling 모사라 0.3s 간격 정도가 자연스럽다.
  sleep(0.3);
}
