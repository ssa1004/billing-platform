# ADR-0011: 조회 캐시 전략

## 상태
부분 적용

## 배경
`GET /wallet` (잔액 조회) 가 hot path — 사용자가 자주 호출. 매번 DB 가면 (a) DB 부하 ↑ (b) P99 latency ↑.

- Redis only — 네트워크 hop 1번(ms 단위), 모든 인스턴스가 공유.
- Caffeine only — process 내부(마이크로초 단위), 인스턴스 간 일관성 없음.

## 결정
Spring Cache 의 `@Cacheable("wallets")` 인터페이스를 유지한다.

- local/dev: `billing.cache.redis-enabled=false` 이므로 Caffeine CacheManager 사용.
- prod: `billing.cache.redis-enabled=true` 와 `spring.cache.type=redis` 로 Redis CacheManager 사용.

두 캐시를 동시에 묶는 2단계 캐시는 아직 구현하지 않는다. 현재 변경 흐름은 지갑 잔액의 정합성을
도메인/DB 락으로 보장하고, local/dev 조회 캐시는 짧은 TTL 로 stale 위험을 제한한다.

## 결과
- local/dev 는 외부 Redis 없이 빠르게 실행 가능.
- prod 는 여러 인스턴스가 Redis 캐시를 공유한다.
- (한계) Caffeine L1 + Redis L2 조합은 아직 없다.
- (한계) 명시적 evict 흐름이 늘어나면 stale 여부를 별도 테스트로 검증해야 한다.
