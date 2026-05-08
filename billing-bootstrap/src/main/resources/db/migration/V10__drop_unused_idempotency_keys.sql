-- V1 의 idempotency_keys 테이블은 "Redis 장애 시 마지막 방어선" 으로 만들어졌지만 실제로
-- 어떤 코드도 읽거나 쓰지 않습니다 (현행 IdempotencyKeyStore 는 Redis / in-memory 두 구현
-- 만 사용). YAGNI — 사용 안 하는 스키마는 제거. 추후 DB 폴백을 진짜 도입하게 되면 그때
-- 새로 추가하는 게 더 명확합니다.

DROP TABLE IF EXISTS idempotency_keys;
