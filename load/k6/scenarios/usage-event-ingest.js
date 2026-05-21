// POST /api/v1/usage 사용량 이벤트 수신 부하 — metering throughput 측정.
//
// 시나리오 의도:
//   - billing-platform 의 ingest path 는 가장 hot 한 write — bid-ask-marketplace 의 거래
//     체결, gpu-job-orchestrator 의 job 완료 등 다른 레포가 발사한 usage event 가 모두
//     이 endpoint 로 모인다. eventId 가 PK 겸 UNIQUE 제약이라 한 INSERT + (있다면)
//     UNIQUE constraint check 만의 가벼운 write path.
//   - constant 500 req/s — 단일 노드에서 의미 있는 metering throughput 의 상한 근처를
//     본다. ResourceType 4 종을 round-robin 하여 단일 type 의 인덱스 hot-spot 도 피한다.
//
// thresholds:
//   - http_req_duration p95 < 50ms — write-heavy 인 만큼 INSERT + 멱등성 검사만의 빠른 path.
//                                    HikariCP 풀 + virtual thread 가 정상이면 충분.
//   - http_req_failed rate < 1% — 일부 duplicate eventId (재시도 모사) 는 202 + accepted=false
//                                 로 떨어지므로 4xx/5xx 자체는 거의 0 이어야 한다.
//   - usage_ingest_accepted rate > 99% — accepted=true 비율. 새 eventId 라 99%+ 가 정상.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL, pickCustomer, pickResourceType, nowIso } from '../lib/config.js';
import { authHeader, newEventId } from '../lib/auth.js';

const acceptedRate = new Rate('usage_ingest_accepted');

export const options = {
  scenarios: {
    usage_ingest: {
      executor: 'constant-arrival-rate',
      rate: 500,                 // 초당 500 req — 가장 hot 한 write path
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 100,
      maxVUs: 400,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<50', 'p(99)<150'],
    usage_ingest_accepted: ['rate>0.99'],
  },
};

export default function () {
  const customer = pickCustomer(__VU, __ITER);
  const resourceType = pickResourceType(__ITER);
  const eventId = newEventId();

  // quantity 는 ResourceType 별로 의미가 다르지만 시나리오는 metering throughput 측정이
  // 목적이라 1 로 고정 — 단가 산정의 의미는 정산 시나리오에서 본다.
  const payload = JSON.stringify({
    eventId: eventId,
    customerId: customer,
    resourceType: resourceType,
    quantity: 1,
    occurredAt: nowIso(),
  });

  const res = http.post(`${BASE_URL}/api/v1/usage`, payload, {
    headers: authHeader(),
    tags: { name: 'usage-event-ingest' },
  });

  // 정상 응답은 202 Accepted + body { eventId, accepted: true|false }.
  // accepted=false 는 같은 eventId 가 이미 있었다는 신호 (재시도 모사) — 4xx 가 아니라 202 라
  // http_req_failed 에는 잡히지 않는다.
  let accepted = false;
  if (res.status === 202) {
    const body = res.body || '';
    accepted = body.includes('"accepted":true');
  }
  acceptedRate.add(accepted);

  check(res, {
    'status 202': (r) => r.status === 202,
    'body has eventId': (r) => (r.body || '').includes('eventId'),
  });

  sleep(0.01);
}
