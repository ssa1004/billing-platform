# ADR-0006: 멱등성 키 — Redis NX 기반

## 상태
적용

## 배경
모바일 클라이언트의 결제 요청 — 네트워크 retry 가 빈번. 같은 요청이 두 번 도착하면? 서버 측 dedup (예: 5초 내 같은 사용자 같은 금액) 은 false positive (정상 재구매도 중복으로 거절) 위험.

## 결정
**Client-driven Idempotency-Key.** 클라이언트가 UUID 발급 → `Idempotency-Key: <uuid>` 헤더로 전송. 서버는 Redis NX (SETNX) 로 key 확보 — 실패 시 `409 DUPLICATE_REQUEST` 반환. 24시간 TTL.

**이중 방어:** Redis NX (분산 lock) + DB unique constraint (Payment.idempotency_key). Redis 장애 시에도 DB 가 마지막 방어선.

## 결과
- 네트워크 재시도 안전 — 같은 키로 들어오면 서버가 한 번만 처리
- 클라이언트 자율 — false positive 없음
- Redis 장애 시 DB unique 가 catch (조금 느리지만 안전)
- (단점) 클라이언트가 UUID 생성 책임 — 개발 가이드 필요
- (단점) 응답 캐싱 (옵션) 안 하면 같은 요청 두 번째에 응답 못 줌 (현재는 단순 reject)
