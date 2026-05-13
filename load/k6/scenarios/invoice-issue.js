// POST /api/v1/settlement/run?customerId=...&period=... 정산 트리거 → invoice 발행 부하.
//
// 시나리오 의도:
//   - billing-platform 의 핵심 write — 한 (customer, period) 정산이 advisory lock
//     (`pg_advisory_xact_lock(settlement:cust:202605)`) 으로 직렬화되고, 그 안에서
//     SELECT aggregated_usage + PricingPlan → INSERT invoice (PricingSnapshot 포함) →
//     Outbox INSERT 가 한 트랜잭션에 들어간다.
//   - **운영자 수동 트리거 endpoint** 가 부하의 진입점 (평소는 Spring Batch
//     MonthlySettlementJob 이 트리거). 같은 (customer, period) 의 동시 호출은 advisory
//     lock 으로 직렬화되고, lock 보유자 외 호출은 lock 해제까지 대기한다. ramping VU
//     0 → 100 으로 그 대기 시간 분포를 본다.
//
//   * 본 시나리오는 endpoint 가 invoice "발행 호출" 의 진입점이기 때문에 task 명세의
//     "POST /api/v1/invoices" 와 동일 의미로 본다. 실제 InvoiceController 는 v1 에서
//     GET 만 노출 — invoice 의 발행은 정산 path 안에서만 일어난다 (ADR-0013/0015 의 핵심
//     설계 — 사용량 → 집계 → snapshot → invoice 가 한 트랜잭션 안에서 일관 묶음).
//
// thresholds:
//   - http_req_duration p95 < 500ms — advisory lock 획득 + SELECT 집계 + INSERT invoice +
//                                     Outbox INSERT 의 합. 같은 (cust, period) 가 mostly
//                                     hot path 면 lock 대기로 p95 가 올라가는 게 정상.
//   - http_req_failed rate < 2% — 일부 4xx (SETTLEMENT_ALREADY_FINALIZED 등) 는 정상 응답이고,
//                                 advisory lock 대기 timeout 만 5xx 로 떨어지면 안 된다.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { BASE_URL, pickSettlementCustomer, PERIOD } from '../lib/config.js';
import { authHeader } from '../lib/auth.js';

const invoiceIssued = new Counter('invoice_issued_count');
const alreadyFinalized = new Counter('invoice_already_finalized_count');
const advisoryLockTimeout = new Counter('invoice_advisory_lock_timeout');

export const options = {
  scenarios: {
    invoice_issue: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 25 },
        { duration: '20s', target: 50 },
        { duration: '15s', target: 100 },
        { duration: '20s', target: 100 },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<500', 'p(99)<1500'],
    invoice_advisory_lock_timeout: ['count<10'],
  },
};

export default function () {
  const customer = pickSettlementCustomer(__VU, __ITER);
  // period 는 환경변수 K6_PERIOD 로 통일 — 같은 (customer, period) 에 부하를 의도적으로
  // 몰아 advisory lock 동작을 측정.
  const period = PERIOD;

  const url = `${BASE_URL}/api/v1/settlement/run?customerId=${encodeURIComponent(customer)}&period=${encodeURIComponent(period)}`;

  // POST body 는 없음 — query param 만으로 정산 호출 (운영자 manual trigger 의 모양).
  const res = http.post(url, null, {
    headers: authHeader(),
    tags: { name: 'invoice-issue' },
  });

  // 응답:
  //   - 200 + SettlementResult — 첫 정산이 성공해 invoice 발행됨
  //   - 200 + status=ALREADY_FINALIZED — 같은 (cust, period) 가 이미 정산 완료 (idempotent)
  //   - 4xx — 검증 실패 (정산 대상 usage 없음, 잘못된 period 형식)
  //   - 5xx — 본 시나리오의 회귀 신호 (advisory lock 대기 timeout 만 5xx 로 떨어질 수 있음)
  if (res.status === 200) {
    const body = res.body || '';
    if (body.includes('ALREADY_FINALIZED') || body.includes('already')) {
      alreadyFinalized.add(1);
    } else if (body.includes('invoice') || body.includes('settlementRunId') || body.includes('id')) {
      invoiceIssued.add(1);
    }
  } else if (res.status >= 500) {
    // 5xx 의 일부는 advisory lock 대기 timeout 일 수 있어 별도 카운터로 분리.
    advisoryLockTimeout.add(1);
  }

  check(res, {
    'status 200 or 4xx (no 5xx)': (r) => r.status < 500,
    'response has body': (r) => (r.body || '').length > 0,
  });

  // ramping VU 가 늘면 같은 (cust, period) 가 advisory lock 에 부딪쳐 자연스럽게
  // 직렬화되므로 sleep 은 짧게. 의도적으로 lock 경합을 만든다.
  sleep(0.1);
}
