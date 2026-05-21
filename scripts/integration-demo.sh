#!/usr/bin/env bash
# Cross-repo 통합 시연.
#
# 사전 조건:
#   1. docker compose -f infrastructure/docker-compose.integration.yml up -d --wait
#      (kafka / auth-stub / notification-stub)
#   2. 옆 터미널에서 billing-platform 을 outbox-relay on + kafka 연결로 띄움:
#        BILLING_OUTBOX_RELAY_ENABLED=true \
#        SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
#        ./gradlew :billing-bootstrap:bootRun
#
# 이 스크립트가 하는 일:
#   1. mock JWT 발급 — auth-service 가 발급할 모양의 RS256 토큰을 직접 서명.
#      auth-stub 의 JWK Set 에도 같은 public key 를 주입해서 "billing-platform
#      이 verify 하려고 한다면 통과" 하는 상태로 만든다 (실제 verify 는 dev
#      프로필이라 skip 됨 — 시연은 인터페이스 모양 위주).
#   2. usage event 발사 — bid-ask-marketplace + gpu-job-orchestrator 가 보낸
#      모양으로 5건씩 (도메인 ResourceType enum 에 매핑해서 전송).
#   3. forecast → 정산 트리거 → invoice 발행. 발행 outbox 가 kafka 로 흘러
#      notification-stub 가 받는다.
#   4. 결제 (MockPgClient 자동 승인) → payment.completed outbox → 알림.
#   5. notification-stub 의 stdout 을 dump 해서 실제로 흘러갔는지 확인.
#
# 정리:
#   docker compose -f infrastructure/docker-compose.integration.yml down -v
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE="docker compose -f $ROOT_DIR/infrastructure/docker-compose.integration.yml"
BASE="${BASE:-http://localhost:8080}"
AUTH_STUB="${AUTH_STUB:-http://localhost:8081}"
CUSTOMER="${CUSTOMER:-acme-corp}"
PERIOD="${PERIOD:-$(date +%Y-%m)}"

say() { printf "\n\033[1;36m▶ %s\033[0m\n" "$*"; }
note() { printf "  \033[2m· %s\033[0m\n" "$*"; }
fail() { printf "\n\033[1;31m✗ %s\033[0m\n" "$*"; exit 1; }

#
# 0. 사전 점검
#
say "0. compose 상태 확인"
$COMPOSE ps
note "billing-platform health 대기"
for _ in $(seq 1 30); do
  if curl -fsS "$BASE/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -fsS "$BASE/actuator/health" | jq -r '.status' | xargs -I{} echo "  billing status = {}"
curl -fsS "$AUTH_STUB/.well-known/openid-configuration" | jq -r '.issuer' | xargs -I{} echo "  auth-stub issuer = {}"

#
# 1. mock JWT 발급 — auth-service 가 발급한 모양으로 RS256 서명
#
say "1. mock JWT 발급"
TMP_DIR="$(mktemp -d -t billing-integration-XXXX)"
trap 'rm -rf "$TMP_DIR"' EXIT

note "RSA 2048 키 페어 생성 ($TMP_DIR)"
openssl genrsa -out "$TMP_DIR/private.pem" 2048 >/dev/null 2>&1
openssl rsa -in "$TMP_DIR/private.pem" -pubout -out "$TMP_DIR/public.pem" >/dev/null 2>&1

note "RSA modulus / exponent 추출 + JWK 조립 (python3 + openssl)"
KID="demo-key-1"
JWKS_JSON=$(python3 - "$TMP_DIR/private.pem" "$KID" <<'PY'
import sys, json, base64, subprocess, re

priv_path, kid = sys.argv[1], sys.argv[2]

# openssl text dump 에서 modulus / publicExponent 추출 — cryptography 의존 회피
out = subprocess.check_output(
    ["openssl", "rsa", "-in", priv_path, "-noout", "-modulus"]
).decode().strip()
modulus_hex = out.split("=", 1)[1]                # "Modulus=00ABCD..."
modulus = bytes.fromhex(modulus_hex)
if modulus[0] == 0:                                # leading 0 제거 (DER 표기)
    modulus = modulus[1:]

text = subprocess.check_output(
    ["openssl", "rsa", "-in", priv_path, "-noout", "-text"]
).decode()
m = re.search(r"publicExponent:\s*(\d+)", text)
e_int = int(m.group(1))
e_bytes = e_int.to_bytes((e_int.bit_length() + 7) // 8, "big")

def b64u(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()

jwk = {
    "kty": "RSA", "use": "sig", "alg": "RS256", "kid": kid,
    "n": b64u(modulus), "e": b64u(e_bytes),
}
print(json.dumps({"keys": [jwk]}))
PY
) || fail "JWK 조립 실패 — python3 + openssl 이 필요합니다"

note "auth-stub 의 /.well-known/jwks.json 에 admin API 로 주입"
MAPPING=$(jq -nc --argjson jwks "$JWKS_JSON" '{
  request: {method: "GET", url: "/.well-known/jwks.json"},
  response: {
    status: 200,
    headers: {"Content-Type": "application/json"},
    jsonBody: $jwks
  }
}')
curl -fsS -X POST "$AUTH_STUB/__admin/mappings" \
  -H 'Content-Type: application/json' \
  -d "$MAPPING" >/dev/null

note "JWT (RS256) 서명 — header.payload 를 openssl 로 sha256+RSA"
NOW=$(date +%s)
EXP=$((NOW + 3600))
HEADER_B64=$(printf '{"alg":"RS256","typ":"JWT","kid":"%s"}' "$KID" \
  | openssl base64 -e -A | tr '+/' '-_' | tr -d '=')
PAYLOAD_B64=$(jq -nc \
    --arg sub "$CUSTOMER" \
    --arg tenant "$CUSTOMER" \
    --argjson iat "$NOW" --argjson exp "$EXP" \
    '{iss:"http://auth-stub:8080", sub:$sub, aud:"billing-platform",
      iat:$iat, exp:$exp, scope:"billing:write billing:read", tenant:$tenant}' \
  | openssl base64 -e -A | tr '+/' '-_' | tr -d '=')
SIGNING_INPUT="$HEADER_B64.$PAYLOAD_B64"
SIG_B64=$(printf '%s' "$SIGNING_INPUT" \
  | openssl dgst -sha256 -sign "$TMP_DIR/private.pem" \
  | openssl base64 -e -A | tr '+/' '-_' | tr -d '=')
JWT="$SIGNING_INPUT.$SIG_B64"
note "kid=$KID, len=${#JWT}"

note "auth-stub JWK Set 다시 조회 — 주입된 JWK 확인"
curl -fsS "$AUTH_STUB/.well-known/jwks.json" | jq '.keys[0] | {kty, alg, kid}'

note "토큰 payload (검증은 dev 프로필에서 skip — 시연은 모양 위주)"
echo "$JWT" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null | jq

#
# 2. usage event — bid-ask-marketplace + gpu-job-orchestrator
#
say "2. usage event 발사 (다른 두 레포가 보낸 모양)"
# 본 레포 도메인 ResourceType enum 은 API_CALL / STORAGE_GB_HOUR /
# ACTIVE_USER_SEAT / DATA_TRANSFER_GB 4종. 두 producer 의 실세계 단위를
# 가장 가까운 enum 으로 매핑한다 — 시연 의도는 *서로 다른 두 외부 서비스가
# 같은 ingest endpoint 를 친다* 이지 새 enum 도입이 아님.
note "bid-ask-marketplace → API_CALL × 5 (거래 1건 = 1 호출)"
for i in 1 2 3 4 5; do
  curl -fsS -X POST "$BASE/api/v1/usage" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $JWT" \
    -d "{
      \"eventId\":\"$(uuidgen)\",
      \"customerId\":\"$CUSTOMER\",
      \"resourceType\":\"API_CALL\",
      \"quantity\":1,
      \"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
    }" | jq -c '{eventId, accepted}'
done

note "gpu-job-orchestrator → STORAGE_GB_HOUR × 5 (GPU 1시간 = 1 GB·hour 로 환산)"
for i in 1 2 3 4 5; do
  curl -fsS -X POST "$BASE/api/v1/usage" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $JWT" \
    -d "{
      \"eventId\":\"$(uuidgen)\",
      \"customerId\":\"$CUSTOMER\",
      \"resourceType\":\"STORAGE_GB_HOUR\",
      \"quantity\":1,
      \"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
    }" | jq -c '{eventId, accepted}'
done

#
# 3. metering → 정산 → invoice (notification-stub 로 흘러야 함)
#
say "3. forecast / 정산 트리거 / invoice"
note "월간 forecast"
curl -fsS "$BASE/api/v1/usage/forecast?customerId=$CUSTOMER" \
  -H "Authorization: Bearer $JWT" \
  | jq '{customerId, period, projectedTotalCost, currency}'

note "정산 수동 트리거 ($CUSTOMER × $PERIOD)"
SETTLE_HTTP=$(curl -sw "%{http_code}" -o /tmp/settle.json -X POST \
  "$BASE/api/v1/settlement/run?customerId=$CUSTOMER&period=$PERIOD" \
  -H "Authorization: Bearer $JWT") || true
echo "  HTTP=$SETTLE_HTTP"
[ -s /tmp/settle.json ] && jq . /tmp/settle.json || true

note "발행된 invoice"
INVOICE=$(curl -fsS "$BASE/api/v1/invoices?customerId=$CUSTOMER&limit=1" \
  -H "Authorization: Bearer $JWT")
echo "$INVOICE" | jq '.[0] | {id, status, amountDue, currency, period}'
INVOICE_ID=$(echo "$INVOICE" | jq -r '.[0].id // empty')

#
# 4. 결제 → settlement → payment.completed 알림
#
say "4. 결제 (MockPgClient 자동 승인)"
note "주문 + 결제 한 사이클"
ORDER=$(curl -fsS -X POST "$BASE/api/v1/orders" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $JWT" \
  -H 'Idempotency-Key: integration-order-001' \
  -d '{"currency":"KRW","items":[{"sku":"INTG-1","quantity":1,"unitPrice":50000}]}')
ORDER_ID=$(echo "$ORDER" | jq -r .id)
note "ORDER_ID=$ORDER_ID"

PAY=$(curl -fsS -X POST "$BASE/api/v1/payments" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $JWT" \
  -H 'Idempotency-Key: integration-pay-001' \
  -d "{\"orderId\":\"$ORDER_ID\",\"method\":\"CARD\"}")
echo "$PAY" | jq '{id, status, amount, currency}'

#
# 5. notification-stub 가 받았는지 확인
#
say "5. notification-stub 수신 확인 (outbox-relay → kafka → consumer)"
note "outbox relay polling 한 사이클 + kafka 전송 대기 (최대 10초)"
sleep 10

echo "  최근 notification-stub 로그 (마지막 30 줄):"
$COMPOSE logs --tail=30 notification-stub | sed 's/^/    /'

note "billing.invoice.* / billing.payment.* / billing.outbox 카운트"
$COMPOSE logs notification-stub 2>/dev/null \
  | grep -oE 'billing\.[a-z_]+\.[a-z_]+' \
  | sort | uniq -c \
  || echo "  (notification-stub 가 아직 메시지를 못 받았다면 옆 터미널의 bootRun 로그에서"
echo "   'outbox' / 'OutboxRelay' 검색해 outbox-relay 가 활성화됐는지 확인)"

echo
echo "통합 데모 완료."
echo "  - billing-platform Swagger: $BASE/swagger"
echo "  - auth-stub admin:          $AUTH_STUB/__admin"
echo "  - 정리:                     $COMPOSE down -v"
