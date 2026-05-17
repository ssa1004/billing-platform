package com.example.billing.application.exception

/**
 * DLQ 단건 작업이 도메인 상태와 맞지 않아 거절. 호출자가 두 번째 클릭으로 같은 메시지를 다시
 * replay / discard 하려는 경우가 가장 흔한 발생 원인 — controller 에서 409 + ILLEGAL_DLQ_OPERATION
 * 으로 매핑된다 ([com.example.billing.adapter.web.exception.GlobalExceptionHandler]).
 *
 * 멱등성 가드: replay 가 성공하면 해당 메시지는 DLT 에서 사라지므로 (offset commit) 두 번째
 * 호출 시 "이미 처리된 메시지" → 이 예외. discard 도 marker 가 박혀 같은 의미로 가드.
 */
class IllegalDlqOperationException(message: String) : RuntimeException(message)
