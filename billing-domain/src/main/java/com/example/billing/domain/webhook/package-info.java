/**
 * Webhook 전송 시스템 — 도메인 이벤트를 customer 서버로 HTTP POST 로 push.
 *
 * <p><b>실세계 비유</b>: PG (Toss / Stripe) 가 결제 완료를 가맹점에 알리는 방식과 똑같다.
 * 우리는 *발신자* 입장. customer 가 자기 서버 URL 을 등록해두면 invoice / payment / refund
 * 등 도메인 이벤트가 발생할 때 그 URL 로 서명된 HTTP POST 가 간다.</p>
 *
 * <p><b>두 aggregate</b>:
 * <ul>
 *   <li>{@link com.example.billing.domain.webhook.WebhookEndpoint} — customer 의 등록 정보 (URL, secret, 구독 이벤트, ACTIVE/PAUSED).</li>
 *   <li>{@link com.example.billing.domain.webhook.WebhookDelivery} — 한 이벤트의 한 endpoint 로의 전송 기록 (retry 라이프사이클 포함).</li>
 * </ul>
 */
@org.springframework.modulith.NamedInterface("webhook")
package com.example.billing.domain.webhook;
