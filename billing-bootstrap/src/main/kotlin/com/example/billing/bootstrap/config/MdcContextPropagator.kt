package com.example.billing.bootstrap.config

import io.github.resilience4j.core.ContextPropagator
import org.slf4j.MDC
import java.util.Optional
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * Resilience4j [ContextPropagator] 구현 — caller thread 의 SLF4J [MDC] 를
 * ThreadPoolBulkhead worker thread 로 전파.
 *
 * **왜 필요**: ADR-0026 의 ThreadPoolBulkhead 는 외부 호출 (PG / webhook / audit-export)
 * 을 별도 worker pool 에서 실행. MDC 는 [ThreadLocal] 기반이라 worker thread 에는
 * 자동으로 안 따라갑니다. 결과:
 * - caller thread (servlet / 가상 스레드) 의 traceId / spanId / requestId / customerId 가
 *   worker 에 비어있음.
 * - `RestClientPgClient`, `RestClientWebhookHttpClient` 가 찍는 PG 호출 로그가 분산 추적
 *   join 에서 빠짐 — Grafana 에서 한 결제 trace 를 따라가다가 PG 호출 레인이 끊기는 현상.
 * - 같은 이유로 Sentry / Datadog 의 error tagging 도 끊김.
 *
 * **Resilience4j 의 ContextPropagator 명세**:
 * - [retrieve] — caller thread 에서 호출. 전파할 값을 추출 (snapshot).
 * - [copy] — worker thread 진입 시 호출. snapshot 으로 worker 의 ThreadLocal 셋업.
 * - [clear] — worker 작업 끝나면 호출. ThreadLocal 정리 (memory leak 방지).
 *
 * 세 단계가 분리되어 있어 thread reuse (worker 가 풀에 반환 후 다음 작업에 재사용) 시
 * 이전 작업의 MDC 가 남아 오염되는 문제를 막을 수 있습니다.
 *
 * **왜 전체 MDC 를 통째로 전파하는가**: 특정 키만 골라 (whitelist) 옮기는 방식은 빠르지만
 * 새로 추가되는 MDC 키 (예: customerId, tenantId, sagaId) 마다 propagator 를 수정해야 함 —
 * 운영 중 누락 위험 큼. 통째 전파는 코드 수정 없이 새 MDC 키도 자동으로 worker 에 따라가는
 * 패턴이라 운영 안전. snapshot 비용은 보통 < 10us (Map.copyOf) 라 무시 가능.
 *
 * **주의 — Optional Map 라는 wrapping**: Resilience4j 의 인터페이스는
 * `Supplier<Optional<T>>`. caller MDC 가 비어있으면 ([MDC.getCopyOfContextMap] 가
 * null 반환) [Optional.empty] 를 돌려줘야 합니다 — 비어있는 Map 을 그대로 넘기면 worker
 * 가 빈 MDC 로 덮어쓰기 해버려 (clear 단계에서) thread reuse 에 노이즈.
 *
 * **cleanup 의 의미**: worker 작업 끝나면 원래 worker 가 갖고 있던 MDC 로 복원 — 즉
 * propagator 가 cell 단위로 set 한 키만 정리. 단순화를 위해 우리는 worker 의 MDC 자체를 통째로
 * clear — caller 에서 받은 MDC snapshot 을 그대로 set 했으니, 끝나면 같은 키들을 remove 하면
 * 됩니다. Worker 가 작업 시작 전부터 갖고 있던 MDC (Spring 이 worker thread 에 기본으로 박은
 * 값 등) 가 있을 수 있지만, ThreadPoolBulkhead 의 worker 는 IO 격리용이라 자체 MDC 를 갖고
 * 있지 않다는 것이 본 구현의 가정. 검증된 가정 — Java 21 가상 스레드 / executor worker 모두
 * 빈 MDC 로 시작.
 *
 * ADR-0026 후속 — ADR-0027.
 */
class MdcContextPropagator : ContextPropagator<Map<String, String>> {

    override fun retrieve(): Supplier<Optional<Map<String, String>>> = Supplier {
        // null 일 수 있음 — caller thread 가 MDC 비워둔 상태.
        val ctx: Map<String, String>? = MDC.getCopyOfContextMap()
        if (ctx == null || ctx.isEmpty()) Optional.empty()
        // 불변 복사 — caller 가 이후에 MDC 를 변경해도 worker 가 보는 snapshot 은 고정.
        else Optional.of(java.util.Map.copyOf(ctx))
    }

    override fun copy(): Consumer<Optional<Map<String, String>>> = Consumer { optCtx ->
        optCtx.ifPresent { ctx ->
            // worker 진입 시점 — MDC.setContextMap 은 worker 의 기존 MDC 를 통째 교체.
            // 빈 MDC 로 시작하는 worker 가정이 맞다면, 이걸로 traceId/requestId 등이 그대로 따라옴.
            MDC.setContextMap(ctx)
        }
    }

    override fun clear(): Consumer<Optional<Map<String, String>>> = Consumer {
        // worker 작업 끝 — 우리가 set 한 키만 정리하지 않고 통째로 비움. ThreadPoolBulkhead 의
        // worker 가 thread reuse 패턴이라 다음 작업 시작 시 또 caller 의 새 MDC 가 들어올 것.
        // 남기면 이전 작업의 traceId 가 다음 작업에 노출되는 흔한 사고가 발생.
        MDC.clear()
    }
}
