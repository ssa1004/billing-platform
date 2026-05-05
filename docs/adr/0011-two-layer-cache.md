# ADR-0011: Caffeine L1 + Redis L2 두 단계 캐시

## 상태
적용

## 배경
`GET /wallet` (잔액 조회) 가 hot path — 사용자가 자주 호출. 매번 DB 가면 (a) DB 부하 ↑ (b) P99 latency ↑.

Redis only — 네트워크 hop 1번 (L1 ms 단위), 모든 인스턴스가 공유.
Caffeine only — process 내부 (μs 단위), 인스턴스 간 일관성 X.

## 결정
**2단계 캐시.** L1 = Caffeine (process local, TTL 30s), L2 = Redis (cluster shared, TTL 5min). Spring Cache 의 `@Cacheable("wallets")` 어노테이션으로 일관 인터페이스. 변경 시 `@CacheEvict` 로 양쪽 동시 무효화.

본 v0.1 은 Caffeine L1 only — Redis L2 는 운영 프로필에서 별도 `RedisCacheManager` 활성 (`wallet.cache.redis-enabled=true`).

write-through invalidation: 잔액 변경 후 evict.

## 결과
- 99% read 가 Caffeine hit (μs 응답)
- 1% miss 도 Redis hit (ms 응답) — DB 까지 거의 안 감
- 변경 빈도 낮은 데이터 (예: 사용자 quota) 에 매우 효과적
- (단점) L1 cache 는 인스턴스마다 별도 — write 후 다른 인스턴스의 L1 은 stale (TTL 짧게 30s 로 완화)
- (단점) 캐시 일관성 디버깅 어려움 — 명시적 evict 안 하면 stale 위험
