#!/usr/bin/env bash
# Billing Platform 데모 — 주문 → 결제 → 환불 한 사이클.
# 먼저 다른 터미널에서: ./gradlew :billing-bootstrap:bootRun
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"

say() { printf "\n\033[1;36m▶ %s\033[0m\n" "$*"; }

say "1. 헬스 체크"
curl -s "$BASE/actuator/health" | jq -r '.status' | xargs -I{} echo "  status = {}"

say "2. 주문 생성 (Idempotency-Key 필수)"
ORDER=$(curl -s -X POST "$BASE/api/v1/orders" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-order-001' \
  -d '{"currency":"KRW","items":[{"sku":"SKU-1","quantity":2,"unitPrice":1000}]}')
echo "$ORDER" | jq
ORDER_ID=$(echo "$ORDER" | jq -r .id)
echo "  ORDER_ID=$ORDER_ID"

say "3. 같은 Idempotency-Key 재요청 → 409 DUPLICATE_REQUEST"
curl -s -X POST "$BASE/api/v1/orders" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-order-001' \
  -d '{"currency":"KRW","items":[{"sku":"SKU-1","quantity":2,"unitPrice":1000}]}' | jq

say "4. 결제 (MockPgClient → 자동 승인)"
PAY=$(curl -s -X POST "$BASE/api/v1/payments" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-pay-001' \
  -d "{\"orderId\":\"$ORDER_ID\",\"method\":\"CARD\"}")
echo "$PAY" | jq
PAYMENT_ID=$(echo "$PAY" | jq -r .id)

say "5. 결제 실패 시뮬 (Idempotency-Key 가 FAIL_ 로 시작 → MockPgClient reject)"
ORDER2=$(curl -s -X POST "$BASE/api/v1/orders" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-order-002' \
  -d '{"currency":"KRW","items":[{"sku":"SKU-2","quantity":1,"unitPrice":500}]}' | jq -r .id)
curl -s -X POST "$BASE/api/v1/payments" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: FAIL_demo-pay-002' \
  -d "{\"orderId\":\"$ORDER2\",\"method\":\"CARD\"}" \
  | jq '{status, errorCode, errorMessage}'

say "6. 환불"
curl -s -X POST "$BASE/api/v1/refunds" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-refund-001' \
  -d "{\"paymentId\":\"$PAYMENT_ID\",\"reason\":\"customer request\"}" | jq

say "7. Modulith 모듈 진단"
curl -s "$BASE/actuator/modulith" | jq

say "8. 메트릭"
curl -s "$BASE/actuator/prometheus" | grep -E "http_server_requests|hikaricp" | head

echo
echo "데모 완료. Swagger UI: $BASE/swagger"
