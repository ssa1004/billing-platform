#!/usr/bin/env sh
# usage-producer stub — resell-orderbook / gpu-job-orchestrator 가
# billing-platform 의 /api/v1/usage 로 발사하는 사용량 이벤트 모양을 흉내냄.
#
# 본 레포 도메인 ResourceType enum 은 API_CALL / STORAGE_GB_HOUR /
# ACTIVE_USER_SEAT / DATA_TRANSFER_GB 4종이라, 두 producer 의 실세계 단위를
# 가장 가까운 enum 으로 매핑해서 보낸다.
#   - resell-orderbook  → API_CALL          (거래 1건 = 1 호출)
#   - gpu-job-orchestrator → STORAGE_GB_HOUR (GPU 1시간 = 1 GB·hour 로 환산)
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

# /api/v1/usage 의 eventId 는 UUID 형식만 허용. busybox 환경엔 uuidgen 이 없을
# 수 있으니 /proc/sys/kernel/random/uuid 를 우선 쓰고 awk fallback.
gen_uuid() {
  if [ -r /proc/sys/kernel/random/uuid ]; then
    cat /proc/sys/kernel/random/uuid
  elif command -v uuidgen >/dev/null 2>&1; then
    uuidgen
  else
    awk 'BEGIN{srand(); for(i=0;i<32;i++) printf "%x", int(rand()*16)}' \
      | sed 's/\(........\)\(....\)\(....\)\(....\)\(.*\)/\1-\2-\3-\4-\5/'
  fi
}

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

echo "[usage-producer] resell-orderbook 5건 (API_CALL)"
for i in 1 2 3 4 5; do
  post_usage API_CALL 1 "$(gen_uuid)"
done

echo "[usage-producer] gpu-job-orchestrator 5건 (STORAGE_GB_HOUR)"
for i in 1 2 3 4 5; do
  post_usage STORAGE_GB_HOUR 1 "$(gen_uuid)"
done

DUP_ID="$(gen_uuid)"
echo "[usage-producer] 같은 eventId 두 번 발사 — UNIQUE 제약 시연"
post_usage STORAGE_GB_HOUR 1 "$DUP_ID"
post_usage STORAGE_GB_HOUR 1 "$DUP_ID"

echo "[usage-producer] done."
