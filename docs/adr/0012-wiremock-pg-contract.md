# ADR-0012: Wiremock으로 PG contract 테스트

## 상태
적용

## 배경
PG 사 호출은 외부 의존성 — 단위 테스트 mock 으로 가능하지만, *"실제 PG 의 정확한 응답 포맷"* 이 코드와 일치하는지 검증 어려움. 운영에서 PG 응답 형식 변경 시 우리 코드가 깨짐.

## 결정
**Wiremock** + **record/replay** 패턴. 운영 환경에서 PG 응답을 한 번 record → JSON 파일 (`__files/`) 로 저장 → 테스트에서 Wiremock 으로 stub 재생.

```java
@WireMockTest(httpPort = 8090)
class PgClientWiremockIT {
    @Test
    void authorize_handlesPgResponseFormat(WireMockRuntimeInfo info) {
        stubFor(post("/v1/payments/authorize")
            .willReturn(jsonResponse("...recorded.json...")));
        // FeignPgClient 호출 → 우리 코드가 PG 응답 정확히 파싱하는지
    }
}
```

본 v0.1 에선 MockPgClient 만 — Wiremock 도입은 PG 사 결정 후 (다음 이터레이션).

## 결과
- 외부 PG 형식 변경 시 테스트가 catch
- 결정적 테스트 (실 PG 호출 X)
- 컨슈머 driven contract 와 결합 가능 (예: Pact)
- (단점) stub 유지 비용 — PG 사 변경 시 stub 도 갱신
- (단점) Wiremock 자체가 별도 프로세스 — 테스트 시작 비용 ↑ (200ms~)
