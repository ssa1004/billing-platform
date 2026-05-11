/**
 * Webhook (우리가 customer 서버 URL 로 HTTP POST 를 쏴서 알리는 push 통신) 전송 시스템.
 * 도메인 이벤트가 발생하면 customer 서버로 HTTP POST 를 보냅니다.
 *
 * <p><b>실세계 비유</b>: PG 사 (외부 결제 게이트웨이) 가 결제 완료를 가맹점에 알리는 방식과
 * 똑같습니다. 여기선 우리가 발신자 입니다. customer 가 자기 서버 URL 을 등록해두면 invoice
 * / payment / refund 같은 도메인 이벤트가 발생할 때 그 URL 로 서명된 HTTP POST 가 갑니다.</p>
 *
 * <p><b>두 aggregate (한 트랜잭션으로 같이 저장되는 도메인 객체 묶음)</b>:
 * <ul>
 *   <li>{@link com.example.billing.domain.webhook.WebhookEndpoint} — customer 가 등록한 정보 (URL, 비밀 키, 구독 이벤트 종류, ACTIVE/PAUSED 상태).</li>
 *   <li>{@link com.example.billing.domain.webhook.WebhookDelivery} — 이벤트 1건을 endpoint 1개로 보내는 한 번의 전송 기록 (재시도 라이프사이클 포함).</li>
 * </ul>
 */
@org.springframework.modulith.NamedInterface("webhook")
package com.example.billing.domain.webhook;
