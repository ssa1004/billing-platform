package com.example.billing.application.port.out

import java.util.Optional

/**
 * 멱등성 키 (같은 요청이 두 번 와도 한 번만 처리되게 막는 키) 저장소.
 * 운영에서는 Redis 의 SETNX (key 가 없을 때만 set, 있으면 실패) 로 구현, 로컬에서는
 * in-memory 폴백.
 *
 * **세 종류의 상태를 같이 다룸**:
 *  - **점유 lock**: [acquireOrThrow] 가 false 면 같은 키로 중복 요청이 들어왔다는 의미 →
 *    [DuplicateRequestException] 을 던집니다.
 *  - **응답 캐시**: [cacheResponse] 로 저장 → [findCachedResponse] 로 조회.
 *    결제 API 표준 패턴 — 같은 idempotencyKey 로 재시도 시 처음 응답 그대로 24h 반환.
 *    client 가 timeout 으로 retry 해도 두 번째 요청이 똑같은 응답을 받아야 정합. ADR-0024
 *    참고.
 *  - **요청 본문 fingerprint**: [recordRequestFingerprint] 로 첫 요청의 body SHA-256
 *    prefix 를 저장 → [findRequestFingerprint] 로 재요청 시 비교. 같은 키로 다른 body
 *    가 오면 client bug (같은 멱등 키로 다른 의도의 요청) — 422 INCOMPATIBLE_PARAMS 로 즉시
 *    검출. ADR-0028 참고.
 *
 * **롤백 시 release**: 트랜잭션이 rollback 되면 도메인 변경은 사라지지만 Redis 락은
 * 남아 있어, 같은 키로 재시도하면 [DuplicateRequestException] 만 계속 떨어집니다.
 * application service 는 lock 획득 직후
 * [com.example.billing.application.service.IdempotentExecution] 으로 rollback 훅을
 * 등록해, 트랜잭션이 실패 (예: 도메인 검증 실패 / 외부 PG 실패) 하면 키도 같이 풀어줘야
 * 합니다.
 */
interface IdempotencyKeyStore {

    fun acquireOrThrow(key: String)

    /**
     * 점유 해제. 일반적으로는 호출하지 않습니다 — TTL 로 만료. 단,
     *  - 같은 트랜잭션이 rollback 되어 도메인 변경이 안 되었을 때
     *  - 운영자가 수동으로 풀어야 할 때
     *
     * 호출됩니다. 이미 없는 키여도 예외 없이 통과 (idempotent).
     */
    fun release(key: String)

    /**
     * 결과 캐싱 — 같은 키 재호출 시 같은 응답 반환.
     *
     * 본문이 [MAX_BODY_BYTES] 를 넘으면 호출자가 미리 cap (잘라내거나 cache skip).
     * 구현체는 cap 보다 큰 입력에 대해 조용히 무시 하지 않고 호출자가 결정한 본문을 그대로
     * 저장합니다.
     */
    fun cacheResponse(key: String, httpStatus: Int, body: String?)

    /**
     * 캐시된 응답 조회. 점유만 된 (response 가 아직 안 박힌) 키는 [Optional.empty].
     */
    fun findCachedResponse(key: String): Optional<CachedResponse>

    /**
     * 첫 요청의 body fingerprint 를 저장. 재요청 시 [findRequestFingerprint] 로 비교해 다른
     * body 인지 검출.
     *
     * 같은 키로 두 번 호출 시 두 번째 호출은 덮어쓰지 않고 무시 — 첫 호출이 진실. [acquireOrThrow]
     * 와 같은 의미 단위라 동시 호출 race 시에도 첫 호출이 점유 + fingerprint 박음
     * + 응답 캐시 박음.
     *
     * @param fingerprint body 의 SHA-256 prefix (16 byte = 32 hex chars 권장 — ADR-0028).
     */
    fun recordRequestFingerprint(key: String, fingerprint: String)

    /**
     * 첫 요청의 body fingerprint 조회. 점유만 된 (fingerprint 가 아직 안 박힌) 키는
     * [Optional.empty] — 첫 요청 본문이 너무 컸거나, 다른 인스턴스가 처리 중인 race window.
     */
    fun findRequestFingerprint(key: String): Optional<String>

    @JvmRecord
    data class CachedResponse(val status: Int, val body: String?)

    class DuplicateRequestException(key: String) :
        RuntimeException("duplicate request: idempotencyKey=$key")

    /**
     * 같은 idempotency 키로 다른 body 가 들어왔을 때 — client bug 즉시 검출. 결제 API 의 통상
     * 메시지 (`"Idempotency-Key already used with different parameters"`) 와 같은 의도.
     */
    class IncompatibleRequestException(key: String) :
        RuntimeException("idempotency key reused with different request body: idempotencyKey=$key")

    companion object {
        /**
         * 응답 본문 캐시 상한 — 16KB. 이를 넘는 응답은 cache skip (처리 중 응답 그대로 통과시킴).
         *
         * 왜 16KB 인가: 결제 API 응답 본문은 보통 1~2KB 수준. 16KB 면 거의 모든 정상 응답을
         * cover 하면서 대형 streaming 응답 (PDF, CSV) 을 자연스럽게 우회.
         */
        const val MAX_BODY_BYTES: Int = 16 * 1024

        /**
         * 요청 body fingerprint 계산 시 비교할 최대 body 크기 — 1MB. 이보다 큰 body 는
         * fingerprint skip (멱등 키 동일성만 보고 처리). 정상 결제 / 환불 요청은 < 4KB 라 cap 영향
         * 없음. 거대한 multipart 업로드 등은 우회.
         */
        const val MAX_FINGERPRINT_BODY_BYTES: Int = 1024 * 1024
    }
}
