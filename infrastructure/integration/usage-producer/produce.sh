#!/usr/bin/env sh
# usage-producer stub — resell-orderbook / gpu-job-orchestrator 가
# billing-platform 의 /api/v1/usage 로 발사하는 사용량 이벤트 모양을 흉내냄.
#
# 다른 서비스에서 호출하는 *시점* 과 *모양* 을 보여주는 것이 목적이라
# 두 producer 가 한 컨테이너에서 각각 5건씩 보냄.
#   - resell-orderbook  → resourceType=TRADE_FILL, quantity=거래 건수
#   - gpu-job-orchestrator → resourceType=GPU_SECONDS, quantity=GPU 사용 초
#
# 멱등성 시연 — 마지막 한 건은 같은 eventId 로 두 번 보내 DB UNIQUE 제약이
# 걸리는 것까지 확인.
set -eu

BILLING="${BILLING:-http://billing-platform:8080}"
CUSTOMER="${CUSTOMER:-acme-corp}"
TOKEN="${TOKEN:-}"

AUTH_HEADER=""
[ -n "$TOKEN" ] && AUTH_HEADER="-H Authorization: Bearer $TOKEN"

echo "[usage-producer] waiting for billing-platform..."
until curl -fsS "$BILLING/actuator/health" >/dev/null 2>&1; do
  sleep 2
done
echo "[usage-producer] billing ready. customer=$CUSTOMER"

post_usage() {
  RESOURCE="$1"
  QTY="$2"
  EVT_ID="$3"
  curl -fsS -X POST "$BILLING/api/v1/usage" \
    -H 'Content-Type: application/json' \
    $AUTH_HEADER \
    -d "{
      \"eventId\":\"$EVT_ID\",
      \"customerId\":\"$CUSTOMER\",
      \"resourceType\":\"$RESOURCE\",
      \"quantity\":$QTY,
      \"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
    }" || echo "  (usage POST failed — non-fatal in demo)"
  echo
}

echo "[usage-producer] resell-orderbook 5건 (TRADE_FILL)"
for i in 1 2 3 4 5; do
  post_usage TRADE_FILL 1 "resell-$(date +%s)-$i"
done

echo "[usage-producer] gpu-job-orchestrator 5건 (GPU_SECONDS)"
for i in 1 2 3 4 5; do
  post_usage GPU_SECONDS 3600 "gpu-$(date +%s)-$i"
done

DUP_ID="dup-$(date +%s)"
echo "[usage-producer] 같은 eventId 두 번 발사 — UNIQUE 제약 시연"
post_usage GPU_SECONDS 100 "$DUP_ID"
post_usage GPU_SECONDS 100 "$DUP_ID"

echo "[usage-producer] done."
