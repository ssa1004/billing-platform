# ADR-0011: 조회 캐시 전략

## 상태
부분 적용

## 배경
`GET /wallet` (잔액 조회) 가 hot path (자주 호출되는 경로) 입니다. 매번 DB 까지 다녀오면
(a) DB 부하가 올라가고 (b) P99 latency (응답 시간 99 퍼센타일, 가장 느린 1% 의 응답 속도)
가 올라갑니다.

- Redis only — 네트워크 한 번 (밀리초 단위), 모든 인스턴스가 공유.
- Caffeine only — 프로세스 내부 메모리 (마이크로초 단위), 인스턴스 간 일관성 없음.

## 결정
Spring Cache 의 `@Cacheable("wallets")` 인터페이스를 유지한다.

- local/dev: `billing.cache.redis-enabled=false` 이므로 Caffeine CacheManager 사용.
- prod: `billing.cache.redis-enabled=true` 와 `spring.cache.type=redis` 로 Redis CacheManager 사용.

두 캐시를 동시에 묶는 2단계 (L1 process 메모리 + L2 Redis) 캐시는 아직 구현하지 않습니다.
현재 변경 흐름은 지갑 잔액의 정합성을 도메인/DB 락으로 보장하고, local/dev 의 조회 캐시는
짧은 TTL (자동 만료 시간) 로 stale (오래된 값을 응답하는 위험) 노출을 제한합니다.

## 결과
- local/dev 는 외부 Redis 없이 빠르게 실행 가능
- prod 는 여러 인스턴스가 Redis 캐시를 공유
- (한계) Caffeine L1 + Redis L2 조합은 아직 없음
- (한계) 명시적 evict (캐시 무효화) 흐름이 늘어나면 stale 여부를 별도 테스트로 검증해야 함
