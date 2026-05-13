// k6 시나리오 공통 설정 — BASE URL + customer pool + ResourceType + period 분기.
//
// BASE_URL 은 환경변수로 덮어쓸 수 있다. 기본은 docker-compose 의 노출 포트 8080.
// 통합 compose 가 다른 포트로 노출되면 `BASE_URL=http://localhost:8088` 같이 주입.

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

/**
 * customer pool — 시나리오마다 round-robin. 시연 환경에는 `acme-corp-1..N` 형태의 seed
 * customer + PricingPlan 이 있다고 가정한다 (scripts/integration-demo.sh 가 `acme-corp`
 * 1명을 seed; 부하 환경은 별도 seed 스크립트로 N 명을 미리 만들어 둔다 — 본 시나리오의
 * 책임 영역 바깥).
 *
 * customer 가 너무 적으면 metering / invoice query / aged-receivables 가 모두 같은
 * (customerId, period) 키에 몰려 advisory lock 대기로 부하 신호가 왜곡된다. 풀을 N=8
 * 정도로 잡아 자연스럽게 분산시킨다.
 */
export const CUSTOMERS = (__ENV.K6_CUSTOMERS
  || 'acme-corp-1,acme-corp-2,acme-corp-3,acme-corp-4,acme-corp-5,acme-corp-6,acme-corp-7,acme-corp-8')
  .split(',')
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

/**
 * VU 인덱스 + iteration 기반 customer 선택 — 풀을 고르게 분산.
 */
export function pickCustomer(vuId, iter) {
  if (CUSTOMERS.length === 0) return 'acme-corp';
  return CUSTOMERS[(vuId + iter) % CUSTOMERS.length];
}

/**
 * invoice-issue 시나리오가 advisory lock 대기를 의도적으로 만들기 위해 좁힌 풀.
 * MonthlySettlementJob 의 lock key 는 `settlement:cust:202605` 형태 — 같은 (customer,
 * period) 에 동시 호출이 몰리면 그 lock 에서 직렬화된다.
 */
export const SETTLEMENT_CUSTOMERS = (__ENV.K6_SETTLEMENT_CUSTOMERS
  || 'acme-corp-1,acme-corp-2,acme-corp-3,acme-corp-4')
  .split(',')
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

export function pickSettlementCustomer(vuId, iter) {
  if (SETTLEMENT_CUSTOMERS.length === 0) return 'acme-corp';
  return SETTLEMENT_CUSTOMERS[(vuId + iter) % SETTLEMENT_CUSTOMERS.length];
}

/**
 * ResourceType enum 매핑 — billing-domain 의 metering.ResourceType 과 1:1.
 *   - API_CALL: 거래 1건 / API 호출 1건 (resell-orderbook 발사 모양)
 *   - STORAGE_GB_HOUR: 스토리지 (gpu-job-orchestrator 의 GB·시간 환산)
 *   - ACTIVE_USER_SEAT: 일 단위 활성 사용자
 *   - DATA_TRANSFER_GB: 데이터 전송량
 *
 * usage-event-ingest 시나리오는 4종을 round-robin — 단일 resourceType 에 몰리는
 * Aggregation 인덱스 hot-spot 을 피하면서 ingest path 의 throughput 만 측정.
 */
export const RESOURCE_TYPES = ['API_CALL', 'STORAGE_GB_HOUR', 'ACTIVE_USER_SEAT', 'DATA_TRANSFER_GB'];

export function pickResourceType(iter) {
  return RESOURCE_TYPES[iter % RESOURCE_TYPES.length];
}

/**
 * period 분기 — `YYYY-MM` 형식. 정산 / aged-receivables 시나리오가 사용.
 * 기본은 현재 월 (k6 실행 시점) — 환경변수로 명시 주입 가능.
 *
 * BillingPeriod 는 월 단위이므로 한 시나리오 안에서 N 개의 period 를 분기시키려면
 * 시연용 seed 가 N 개월 분 미리 있어야 한다 — 본 시나리오는 단일 (current month) 만
 * 가정하고, multi-period 분기는 환경변수 `K6_PERIODS` 로 명시 (CSV).
 */
function currentPeriod() {
  const now = new Date();
  const y = now.getUTCFullYear();
  const m = String(now.getUTCMonth() + 1).padStart(2, '0');
  return `${y}-${m}`;
}

export const PERIOD = __ENV.K6_PERIOD || currentPeriod();

export const PERIODS = (__ENV.K6_PERIODS || PERIOD)
  .split(',')
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

export function pickPeriod(iter) {
  if (PERIODS.length === 0) return PERIOD;
  return PERIODS[iter % PERIODS.length];
}

/**
 * payment 시나리오의 통화 풀. AgedReceivables 가 (customer, currency) 별 분리 집계라
 * 같은 customer 가 여러 통화를 만들면 집계 분기 갯수가 늘어난다 — multi-currency 부하의
 * fan-out 인자.
 */
export const CURRENCIES = (__ENV.K6_CURRENCIES || 'KRW,USD,JPY')
  .split(',')
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

export function pickCurrency(iter) {
  return CURRENCIES[iter % CURRENCIES.length];
}

/**
 * payment-charge 시나리오의 orderId pool — payment 처리 전에 미리 발행된 주문 ID 가
 * 필요하다 (PaymentController 가 orderId 를 받아 PG 호출). 부하 환경에서는 N 개의
 * order 를 미리 seed 하고 그 ID 들을 CSV 로 주입.
 *
 * 시연 환경에서 seed 가 없으면 PaymentController 가 404 ORDER_NOT_FOUND 로 떨어진다 —
 * 본 시나리오는 그 분기를 *idempotency cache miss* 와 동일하게 처리해 첫 호출은 404,
 * 같은 키 재호출은 404 응답이 cache hit 으로 떨어지는지 본다 (응답 캐시는 2xx 만 저장이라
 * 404 는 캐시되지 않음 — IdempotencyResponseCacheFilter 의 isSuccess 분기 참고). 실제
 * 24h 캐시 hit 검증은 미리 seed 된 ORDER_IDS 가 있어야 한다.
 */
export const ORDER_IDS = (__ENV.K6_ORDER_IDS || '')
  .split(',')
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

export function pickOrderId(iter) {
  if (ORDER_IDS.length === 0) return null;
  return ORDER_IDS[iter % ORDER_IDS.length];
}

/**
 * ISO-8601 UTC — `occurredAt` 같은 시간 필드에 들어가는 표준 포맷.
 */
export function nowIso() {
  return new Date().toISOString();
}

/**
 * aged-receivables 시나리오의 asOf 시간 — 기본은 현재 시점. 다른 시점을 검사하고 싶다면
 * `K6_AGED_AS_OF=2026-05-01T00:00:00Z` 로 주입.
 */
export const AGED_AS_OF = __ENV.K6_AGED_AS_OF || '';
