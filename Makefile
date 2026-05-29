# billing-platform — 자주 쓰는 명령 단일 진입점
#
#   make up        인프라(Postgres/Redis/Kafka/Wiremock) 기동
#   make ps        컨테이너 상태
#   make logs      인프라 로그 follow
#   make run       billing-platform 앱 호스트 실행 (:8080, H2 + Mock PG)
#   make demo      로컬 데모 (결제 + 사용량→청구→정산, 앱이 떠 있어야 함)
#   make down      인프라 정지 (볼륨 유지)
#   make clean     인프라 정지 + 볼륨 삭제 (옛 데이터 제거)
#   make build     전체 gradle 빌드 (테스트 제외)
#   make test      전체 테스트
#
# 앱은 호스트에서 ./gradlew :billing-bootstrap:bootRun 으로 띄운다 — H2 + Mock PG 라
# 외부 의존성 없이도 돈다. Kafka 까지 붙이는 통합 데모는 README "통합 데모" 절 참고
# (호스트에서는 localhost:9092 EXTERNAL listener 로 붙는다).

COMPOSE := docker compose -f infrastructure/docker-compose.yml
GRADLE  := ./gradlew

.DEFAULT_GOAL := help
.PHONY: help up ps logs run demo down clean build test

help: ## 이 도움말
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

up: ## 인프라 기동 (Postgres/Redis/Kafka/Wiremock)
	$(COMPOSE) up -d

ps: ## 컨테이너 상태
	$(COMPOSE) ps

logs: ## 인프라 로그 follow
	$(COMPOSE) logs -f --tail=100

run: ## billing-platform 앱 호스트 실행 (:8080, H2 + Mock PG)
	$(GRADLE) :billing-bootstrap:bootRun

demo: ## 로컬 데모 실행 (앱이 떠 있어야 함)
	./scripts/demo.sh

down: ## 인프라 정지 (볼륨 유지)
	$(COMPOSE) down

clean: ## 인프라 정지 + 볼륨 삭제 (다음 기동 시 깨끗한 상태)
	$(COMPOSE) down -v

build: ## 전체 gradle 빌드 (테스트 제외)
	$(GRADLE) build -x test

test: ## 전체 테스트
	$(GRADLE) test
