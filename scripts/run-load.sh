#!/usr/bin/env bash
# k6 부하 시나리오 5종 일괄 실행 — 결제 / 청구 / 정산 endpoint 부하 측정.
#
# 단계:
#   1) 본 앱 healthcheck (없으면 bootRun 또는 compose 를 띄우라고 안내)
#   2) k6 실행 경로 결정 — 우선 로컬 k6, 없으면 docker run
#   3) usage-event-ingest → invoice-issue → invoice-query → payment-charge → aged-receivables
#   4) 각 결과는 build/k6-reports/{scenario}.json 에 떨군다
#
# 환경변수:
#   BASE_URL                  — 시나리오의 endpoint base. 기본 http://localhost:8080
#   K6_TOKEN                  — JWT on 일 때만 의미. dev / 빈 헤더 경로면 빈 값
#   K6_CUSTOMERS              — usage / query / aged 의 customer pool (CSV)
#   K6_SETTLEMENT_CUSTOMERS   — invoice-issue 가 advisory lock 경합을 만들 좁힌 풀 (CSV)
#   K6_PERIOD                 — invoice-issue 의 정산 period (`YYYY-MM`). 기본 현재 월
#   K6_PERIODS                — multi-period 분기용 (CSV). 기본은 K6_PERIOD 한 개
#   K6_CURRENCIES             — invoice-query / aged 의 currency 분기 (CSV). 기본 KRW,USD,JPY
#   K6_ORDER_IDS              — payment-charge 의 orderId pool (CSV). seed 가 없으면 404
#                                만 떨어지므로 cache-hit-ratio threshold 가 의미 없어진다.
#   K6_AGED_AS_OF             — aged-receivables 의 asOf 시점 (현재 endpoint 는 server 시점 사용)

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SCENARIO_DIR="${ROOT_DIR}/load/k6/scenarios"
REPORT_DIR="${ROOT_DIR}/build/k6-reports"
mkdir -p "$REPORT_DIR"

BASE_URL="${BASE_URL:-http://localhost:8080}"
K6_TOKEN="${K6_TOKEN:-}"
K6_CUSTOMERS="${K6_CUSTOMERS:-}"
K6_SETTLEMENT_CUSTOMERS="${K6_SETTLEMENT_CUSTOMERS:-}"
K6_PERIOD="${K6_PERIOD:-}"
K6_PERIODS="${K6_PERIODS:-}"
K6_CURRENCIES="${K6_CURRENCIES:-}"
K6_ORDER_IDS="${K6_ORDER_IDS:-}"
K6_AGED_AS_OF="${K6_AGED_AS_OF:-}"

# k6 → Prometheus remote-write (optional). commerce-ops Prometheus 가 떠 있을 때
# `K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write` 를 export.
K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-}"
K6_PROMETHEUS_RW_TREND_STATS="${K6_PROMETHEUS_RW_TREND_STATS:-p(95),p(99),min,max,avg}"
K6_PROMETHEUS_RW_PUSH_INTERVAL="${K6_PROMETHEUS_RW_PUSH_INTERVAL:-5s}"
SERVICE_TAG="billing-platform"

echo "==> base url: $BASE_URL"
if [[ -n "$K6_PROMETHEUS_RW_SERVER_URL" ]]; then
    echo "==> k6 → Prometheus RW: $K6_PROMETHEUS_RW_SERVER_URL (service=$SERVICE_TAG)"
fi

# 1) healthcheck
echo
echo "==> health 확인 ($BASE_URL/actuator/health)"
if ! curl -sf "$BASE_URL/actuator/health" >/dev/null 2>&1; then
    cat <<EOF
ERROR: $BASE_URL 가 응답하지 않습니다.

먼저 본 앱을 띄우세요:

  1) 단독 bootRun (가벼움 — H2 + Mock PG):
       ./gradlew :billing-bootstrap:bootRun
       BASE_URL=http://localhost:8080 ./scripts/run-load.sh

  2) prod 프로필 (Postgres + Redis + Kafka):
       docker compose -f infrastructure/docker-compose.yml up -d postgres redis kafka wiremock
       SPRING_PROFILES_ACTIVE=prod ./gradlew :billing-bootstrap:bootRun

  3) 통합 compose (cross-repo demo — stub 포함):
       docker compose -f infrastructure/docker-compose.integration.yml up -d --wait

또는 BASE_URL 를 staging 등으로 덮어쓰세요 (예: BASE_URL=http://staging:8080).
EOF
    exit 1
fi
echo "    UP"

# 2) k6 실행 경로
if command -v k6 >/dev/null 2>&1; then
    K6_EXEC=("k6")
    echo "==> 로컬 k6 사용 ($(k6 version | head -1))"
elif command -v docker >/dev/null 2>&1; then
    if [[ "$BASE_URL" == *"localhost"* || "$BASE_URL" == *"127.0.0.1"* ]]; then
        BASE_URL_DOCKER="${BASE_URL//localhost/host.docker.internal}"
        BASE_URL_DOCKER="${BASE_URL_DOCKER//127.0.0.1/host.docker.internal}"
    else
        BASE_URL_DOCKER="$BASE_URL"
    fi
    K6_RW_URL_DOCKER="${K6_PROMETHEUS_RW_SERVER_URL//localhost/host.docker.internal}"
    K6_RW_URL_DOCKER="${K6_RW_URL_DOCKER//127.0.0.1/host.docker.internal}"
    K6_EXEC=(docker run --rm -i \
        -v "${ROOT_DIR}/load/k6:/scripts:ro" \
        -e "BASE_URL=${BASE_URL_DOCKER}" \
        -e "K6_TOKEN=${K6_TOKEN}" \
        -e "K6_CUSTOMERS=${K6_CUSTOMERS}" \
        -e "K6_SETTLEMENT_CUSTOMERS=${K6_SETTLEMENT_CUSTOMERS}" \
        -e "K6_PERIOD=${K6_PERIOD}" \
        -e "K6_PERIODS=${K6_PERIODS}" \
        -e "K6_CURRENCIES=${K6_CURRENCIES}" \
        -e "K6_ORDER_IDS=${K6_ORDER_IDS}" \
        -e "K6_AGED_AS_OF=${K6_AGED_AS_OF}" \
        -e "K6_PROMETHEUS_RW_SERVER_URL=${K6_RW_URL_DOCKER}" \
        -e "K6_PROMETHEUS_RW_TREND_STATS=${K6_PROMETHEUS_RW_TREND_STATS}" \
        -e "K6_PROMETHEUS_RW_PUSH_INTERVAL=${K6_PROMETHEUS_RW_PUSH_INTERVAL}" \
        grafana/k6:0.50.0)
    SCRIPT_PREFIX="/scripts/scenarios"
    echo "==> docker run grafana/k6 사용"
else
    echo "ERROR: k6 도 docker 도 없습니다. brew install k6 또는 docker 설치 후 다시 시도하세요." >&2
    exit 1
fi

# 3) 시나리오 실행 — 한 단계 실패해도 다음 단계는 진행
run_scenario() {
    local name="$1"
    local file="$2"

    echo
    echo "==> [$name] start ($(date +%H:%M:%S))"
    local out="${REPORT_DIR}/${name}.json"
    local rc=0

    local rw_opts=()
    if [[ -n "$K6_PROMETHEUS_RW_SERVER_URL" ]]; then
        rw_opts=(-o "experimental-prometheus-rw" \
                 --tag "service=${SERVICE_TAG}" \
                 --tag "scenario=${name}")
    fi

    if [[ "${K6_EXEC[0]}" == "k6" ]]; then
        export BASE_URL K6_TOKEN K6_CUSTOMERS K6_SETTLEMENT_CUSTOMERS K6_PERIOD K6_PERIODS K6_CURRENCIES K6_ORDER_IDS K6_AGED_AS_OF \
               K6_PROMETHEUS_RW_SERVER_URL K6_PROMETHEUS_RW_TREND_STATS K6_PROMETHEUS_RW_PUSH_INTERVAL
        set +e
        "${K6_EXEC[@]}" run "${rw_opts[@]}" --summary-export="$out" "$file"
        rc=$?
        set -e
    else
        local docker_file="${SCRIPT_PREFIX}/$(basename "$file")"
        local docker_out="/scripts/${name}.summary.json"
        set +e
        "${K6_EXEC[@]}" run "${rw_opts[@]}" --summary-export="$docker_out" "$docker_file"
        rc=$?
        set -e
        if [[ -f "${ROOT_DIR}/load/k6/${name}.summary.json" ]]; then
            mv "${ROOT_DIR}/load/k6/${name}.summary.json" "$out"
        fi
    fi

    if [[ $rc -eq 0 ]]; then
        echo "==> [$name] PASSED (report: $out)"
    else
        echo "==> [$name] FAILED rc=$rc (report: $out)"
    fi
}

# 실행 순서:
#   - usage-event-ingest 먼저 — metering 테이블에 데이터 적재 (invoice-issue 의 정산
#     집계 대상이 비어 있지 않게)
#   - invoice-issue — 발행이 한 번이라도 성공해야 invoice-query / aged-receivables 가
#     의미 있는 응답을 본다
#   - invoice-query → payment-charge → aged-receivables 순으로 read + write 섞임
run_scenario "usage-event-ingest"  "${SCENARIO_DIR}/usage-event-ingest.js"
run_scenario "invoice-issue"        "${SCENARIO_DIR}/invoice-issue.js"
run_scenario "invoice-query"        "${SCENARIO_DIR}/invoice-query.js"
run_scenario "payment-charge"       "${SCENARIO_DIR}/payment-charge.js"
run_scenario "aged-receivables"     "${SCENARIO_DIR}/aged-receivables.js"

echo
echo "==> 모든 시나리오 종료. 리포트: $REPORT_DIR"
ls -lah "$REPORT_DIR" 2>/dev/null || true
