# ADR-0012: Wiremock으로 PG contract 테스트

## 상태
적용

## 배경
PG (Payment Gateway, 외부 결제 게이트웨이) 호출은 외부 의존성입니다. 단위 테스트 mock 으로
대체할 수는 있지만, "실제 PG 가 보내는 정확한 응답 포맷" 과 우리 코드가 맞는지 검증이
어렵습니다. 운영에서 PG 응답 형식이 바뀌면 우리 코드가 깨집니다.

## 결정
**Wiremock** (HTTP 응답을 stub 으로 흉내내는 라이브러리) + **record/replay** 패턴
(운영에서 한 번 녹음, 테스트에서 그대로 재생). 운영 환경에서 PG 응답을 한 번 record
→ JSON 파일 (`__files/`) 로 저장 → 테스트에서 Wiremock 으로 stub 재생.

```java
@WireMockTest(httpPort = 8090)
class PgClientWiremockIT {
    @Test
    void authorize_handlesPgResponseFormat(WireMockRuntimeInfo info) {
        stubFor(post("/v1/payments/authorize")
            .willReturn(jsonResponse("...recorded.json...")));
        // RestClientPgClient 호출 → 우리 코드가 PG 응답을 정확히 파싱하는지
    }
}
```

본 v0.1 에선 MockPgClient 만 사용합니다. Wiremock 도입은 실제 PG 사가 결정된 뒤 다음
이터레이션에서 진행합니다.

## 결과
- 외부 PG 형식 변경 시 테스트가 잡아냄
- 매번 같은 결과가 나오는 결정적 테스트 (실 PG 호출 X)
- 컨슈머 driven contract (소비자가 기대하는 계약을 정의해서 검증) 와 결합 가능 (예: Pact)
- (단점) stub 유지 비용 — PG 사 변경 시 stub 도 갱신
- (단점) Wiremock 자체가 별도 프로세스 — 테스트 시작 비용이 200ms 정도 추가
