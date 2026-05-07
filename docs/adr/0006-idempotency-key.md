# ADR-0006: 멱등성 키 — Redis NX 기반

## 상태
적용

## 배경
모바일 클라이언트의 결제 요청은 네트워크 retry 가 빈번합니다. 같은 요청이 두 번 도착하면?
서버가 알아서 중복 제거 (dedup) — 예: 5초 내 같은 사용자 같은 금액 — 하는 방식은 false
positive (실제로는 정상 재구매인데 중복으로 거절) 위험이 있습니다.

## 결정
**Client-driven Idempotency-Key** (클라이언트가 같은 요청 두 번 와도 한 번만 처리되게 막는
키를 직접 발급). 클라이언트가 UUID 를 발급해서 `Idempotency-Key: <uuid>` 헤더로 보냅니다.
서버는 Redis NX (SETNX, key 가 없을 때만 set, 있으면 실패) 로 key 를 확보합니다 — 실패하면
`409 DUPLICATE_REQUEST` 를 반환합니다. TTL (Time To Live, 자동 만료 시간) 24시간.

**이중 방어:** Redis NX (분산 lock) + DB unique 제약 (Payment.idempotency_key 컬럼).
Redis 장애 시에도 DB 가 마지막 방어선이 됩니다.

## 결과
- 네트워크 재시도에 안전 — 같은 키로 들어오면 서버가 한 번만 처리
- 클라이언트가 자율적으로 키 발급 — false positive 없음
- Redis 장애 시 DB unique 가 잡아냄 (조금 느리지만 안전)
- (단점) 클라이언트가 UUID 생성을 책임짐 — 개발 가이드 필요
- (단점) 응답 캐싱 옵션 없이는 같은 요청 두 번째에 응답을 못 줌 (현재는 단순 reject)
