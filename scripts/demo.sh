#!/usr/bin/env bash
# Billing Platform 데모 — 두 흐름을 한 번에 보여줍니다.
#   A. 실시간 결제   : 주문 → 결제 → 환불
#   B. 사용량 청구   : 사용량 ingest → 월말 forecast → 정산 트리거 → invoice 조회
#                    → 미수금 (aged receivables) 리포트
#
# 먼저 다른 터미널에서: ./gradlew :billing-bootstrap:bootRun
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
CUSTOMER="${CUSTOMER:-acme-corp}"
PERIOD="${PERIOD:-$(date +%Y-%m)}"

say() { printf "\n\033[1;36m▶ %s\033[0m\n" "$*"; }
note() { printf "  \033[2m· %s\033[0m\n" "$*"; }

say "0. 헬스 체크"
curl -s "$BASE/actuator/health" | jq -r '.status' | xargs -I{} echo "  status = {}"

#
# A. 실시간 결제 흐름 — Wallet / Order / Payment / Refund
#
say "A1. 주문 생성 (Idempotency-Key 필수)"
ORDER=$(curl -s -X POST "$BASE/api/v1/orders" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-order-001' \
  -d '{"currency":"KRW","items":[{"sku":"SKU-1","quantity":2,"unitPrice":1000}]}')
echo "$ORDER" | jq
ORDER_ID=$(echo "$ORDER" | jq -r .id)
note "ORDER_ID=$ORDER_ID"

say "A2. 같은 Idempotency-Key 재요청 → 409 DUPLICATE_REQUEST"
curl -s -X POST "$BASE/api/v1/orders" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-order-001' \
  -d '{"currency":"KRW","items":[{"sku":"SKU-1","quantity":2,"unitPrice":1000}]}' | jq

say "A3. 결제 (MockPgClient → 자동 승인)"
PAY=$(curl -s -X POST "$BASE/api/v1/payments" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-pay-001' \
  -d "{\"orderId\":\"$ORDER_ID\",\"method\":\"CARD\"}")
echo "$PAY" | jq
PAYMENT_ID=$(echo "$PAY" | jq -r .id)

say "A4. 결제 실패 시뮬 (Idempotency-Key 가 FAIL_ 로 시작 → MockPgClient reject)"
ORDER2=$(curl -s -X POST "$BASE/api/v1/orders" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-order-002' \
  -d '{"currency":"KRW","items":[{"sku":"SKU-2","quantity":1,"unitPrice":500}]}' | jq -r .id)
curl -s -X POST "$BASE/api/v1/payments" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: FAIL_demo-pay-002' \
  -d "{\"orderId\":\"$ORDER2\",\"method\":\"CARD\"}" \
  | jq '{status, errorCode, errorMessage}'

say "A5. 환불"
curl -s -X POST "$BASE/api/v1/refunds" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-refund-001' \
  -d "{\"paymentId\":\"$PAYMENT_ID\",\"reason\":\"customer request\"}" | jq

#
# B. 사용량 청구 흐름 — UsageEvent → AggregatedUsage → Invoice → SettlementRun
#
say "B1. 사용량 이벤트 5건 ingest (eventId 멱등)"
note "customerId=$CUSTOMER, period=$PERIOD"
for i in $(seq 1 5); do
  EVT_ID="$(uuidgen)"
  curl -s -X POST "$BASE/api/v1/usage" \
    -H 'Content-Type: application/json' \
    -d "{
      \"eventId\":\"$EVT_ID\",
      \"customerId\":\"$CUSTOMER\",
      \"resourceType\":\"API_CALL\",
      \"quantity\":3000,
      \"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
    }" | jq -c
done

say "B2. 같은 eventId 재전송 → DB UNIQUE 제약으로 1건만 기록"
DUP_ID="$(uuidgen)"
for _ in 1 2; do
  curl -s -X POST "$BASE/api/v1/usage" \
    -H 'Content-Type: application/json' \
    -d "{
      \"eventId\":\"$DUP_ID\",
      \"customerId\":\"$CUSTOMER\",
      \"resourceType\":\"API_CALL\",
      \"quantity\":1,
      \"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
    }" | jq -c
done

say "B3. 월말 사용량/예상 비용 forecast"
curl -s "$BASE/api/v1/usage/forecast?customerId=$CUSTOMER" \
  | jq '{customerId, period, projectedTotalCost, currency, resources}'

say "B4. 정산 수동 트리거 ($CUSTOMER × $PERIOD)"
note "PricingPlan 시드가 없으면 500 (no pricing plan), invoice 가 이미 있으면 skipped 응답"
SETTLE=$(curl -sw "\nHTTP=%{http_code}" -X POST \
  "$BASE/api/v1/settlement/run?customerId=$CUSTOMER&period=$PERIOD") || true
echo "$SETTLE"

say "B5. 발행된 invoice 목록"
curl -s "$BASE/api/v1/invoices?customerId=$CUSTOMER&limit=5" | jq

say "B6. 미수금 (aged receivables) 리포트 — 0-30 / 31-60 / 61-90 / 90+ 일 bucket"
curl -s "$BASE/api/v1/aged-receivables" | jq

#
# 운영 진단
#
say "C1. Modulith 모듈 진단"
curl -s "$BASE/actuator/modulith" | jq

say "C2. 메트릭 (HTTP / HikariCP)"
curl -s "$BASE/actuator/prometheus" | grep -E "http_server_requests|hikaricp" | head

echo
echo "데모 완료. Swagger UI: $BASE/swagger"
