# ADR-0031: API 버전 routing — path-based v1 / v2

## 상태
적용

## 배경

운영 중인 REST API 의 응답 / 요청 schema 가 변경되면 *사용 중인 client* 가 한 번에 못 따라옵니다.
모바일 앱은 사용자가 OS 업데이트를 안 하거나 앱 업데이트를 미루기도 하고, 외부 통합 client 는
*우리 일정* 에 맞춰 코드 배포를 못 하는 경우도 흔합니다. 한쪽에서 schema 를 깬 채로 배포하면
다른 쪽 client 의 요청이 *모두 4xx / 5xx* 로 떨어지는 사고가 납니다.

### 시나리오 — schema 가 깨지는 흔한 경우

- 응답에 *필드 추가* — 대체로 OK. JSON parser 가 모르는 키를 무시하면 client 영향 없음
  (단, 엄격 mode 의 parser 는 깨질 수 있음).
- 응답에서 *필드 삭제* — BREAKING. 그 필드를 읽던 client 가 NPE / null 처리 누락.
- 필드의 *타입 변경* (`string → object`) — BREAKING. Parser 가 즉시 실패.
- request 에 *필수 필드 추가* — BREAKING. 그 필드를 안 보내던 client 가 4xx.
- 한 endpoint 의 *경로 변경* — BREAKING. 옛날 client 는 404.

### 우리 상황

지금까지 운영 중에 schema 변경이 한 번도 없었어요. 그래서 v2 의 필요성이 즉각적이지는 않습니다.
그러나:

- v2 도입 *인프라* (filter / DTO 분리 / 모니터링 metric) 가 갖춰져 있지 않으면, 첫 schema 변경
  요청 (다음 분기쯤?) 이 들어왔을 때 *모든 client 가 v1 을 hardcode 한 상태* 라 cutover 에
  몇 달이 더 걸립니다.
- 운영 중에 *처음으로* v1/v2 routing 을 도입하려 하면, 모든 controller 를 한꺼번에 수정해야
  하는 큰 작업이 되어 *변경 위험* 자체가 큽니다.

업계에 자리 잡은 관행 — 외부 client 가 붙는 공개 REST API 는 *처음부터 path-based versioning*
을 쓰는 경우가 일반적. 필요 없을 때도 `/v1/` 을 박아두는 게 기본. 미리 갖춰두는 비용은 path
prefix 한 줄, 얻는 건 schema 변경 시점의 *grace 6개월 마이그레이션 여유*.

## 결정

### Path-based versioning — `/api/v1/`, `/api/v2/`

```
/api/v1/invoices/{id}        → InvoiceController (v1, unchanged)
/api/v2/invoices/{id}        → InvoiceV2Controller (v2)
```

v1 과 v2 는 *별도 controller, 별도 DTO*. 같은 도메인 객체를 각자의 DTO 로 매핑. v1 controller
는 *unchanged* — 기존 client 영향 0.

### Path-based vs header-based 비교

| 기준 | path-based (`/api/v1/`) | header-based (`Accept: application/vnd.api.v1+json`) |
|---|---|---|
| 가시성 | URL 에 박혀 운영자 / 로그 / 브라우저 DevTools 에서 즉시 보임 | 헤더라 별도 inspect 필요 |
| CDN cache | URL 단위라 v1/v2 가 자동 분리 cache | Vary: Accept 헤더 필요, cache 운영 까다로움 |
| OpenAPI 문서 | 두 spec 으로 자연 분리 | content negotiation 표현이 어색 |
| client 디버깅 | curl 로 즉시 재현 | curl + 헤더 옵션 필요 |
| 깔끔함 (idealist) | URL 에 *resource 가 아닌* 정보 (버전) 가 박힘 | Accept 헤더의 본래 의도와 부합 |

철학에 따라 갈리는 문제지만, *운영 편의 + 업계 관례* 가 path-based 손을 들어주고 있습니다.
실제로 헤더 기반 versioning 을 쓰다 path 기반으로 옮긴 공개 API 사례가 여럿 있고, "헤더 기반은
디버깅이 괴롭다" 가 운영 1년 차의 공통된 결론.

### v2 도입 시범 — invoice 단건/목록 조회

v2 를 도입한다고 *모든* endpoint 를 일괄 v2 화 하지 않습니다. 가장 *변화 요구가 잦은* endpoint
부터:

- `/api/v2/invoices/{id}` — 응답에 `appliedCredit`, `amountDue` 추가, `Money` 를 객체로 통일.
- `/api/v2/invoices?customerId=...&currency=KRW` — 추가 필터 query param 도입.

v1 의 `/api/v1/invoices` 는 그대로 — 기존 client 가 깨지지 않음.

### v1 deprecation 시그널 — `Deprecation` / `Sunset` 헤더

v1 응답에 RFC 8594 / 9745 표준 헤더 자동 부착:

```
HTTP/1.1 200 OK
Deprecation: true
Sunset: Wed, 01 Jan 2027 00:00:00 GMT
Link: </api/v2/invoices/123>; rel="successor-version"
```

`Sunset` 시점은 *공식 deprecation 결정* 이 내려진 후에 설정. 그 전까지는 v1 도 1급 시민이라
헤더 안 부착. 빈 값일 때 filter 가 자동 skip.

### 실제 cutover 시점 결정 — `api.version.usage` metric

filter 가 모든 v1/v2 호출에 카운터 증가:

```
api_version_usage_total{version="v1", resource="/api/v1/invoices"} 12453
api_version_usage_total{version="v2", resource="/api/v2/invoices"}    87
```

운영 표준 cutover 절차:

1. v2 도입 + 공식 deprecation 공지 → `Sunset` 헤더 활성 (예: 6개월 뒤 시점).
2. 6개월 grace — v1 사용량 metric 으로 client 별 마이그레이션 추적 (가능하면 caller-id 별).
3. v1 사용량이 *충분히* 떨어지면 (예: < 10 req/day) v1 controller 코드 제거.
4. Sunset 시점 이후 v1 호출은 410 Gone 반환 (코드 제거 후엔 자동으로 404, 그 전엔 명시).

### Cardinality 폭발 방지

metric 의 `resource` 라벨은 path *prefix* (resource level) 만 — `/api/v1/invoices/abc-123/pdf`
같은 id 별 분기를 라벨에 안 박음. id 별로 cardinality 가 폭발하면 Prometheus 가 망가집니다.

```kotlin
private fun parseVersionAndResource(uri: String): Pair<String?, String> {
    val parts = uri.trim('/').split('/')
    if (parts.size < 2 || parts[0] != "api") return null to ""
    val v = parts[1]
    if (v != "v1" && v != "v2") return null to ""
    val resource = if (parts.size >= 3) "/api/$v/${parts[2]}" else "/api/$v"
    return v to resource
}
```

## 트레이드오프

### "왜 over-spec 아닌가"

- *지금* schema 변경 요구가 없어도 *언젠가* 들어옴. 그때 controller / DTO 분리를 *처음으로*
  도입하면 *모든 endpoint 를 동시에 손* 봐야 합니다. 변경 위험이 큰 작업이 또 큰 작업으로 묶임.
- 미리 갖춰둔 비용 — 새 controller 한 개, 새 DTO 한 세트, filter 두 개. 한 PR.
- 얻는 효과 — 첫 BREAKING change 요청이 들어왔을 때 *2주 안에* v2 endpoint 추가 + 6개월
  grace 시작. v1 깨짐 0.

### v1 / v2 코드 중복

같은 도메인 객체를 두 DTO 로 매핑하는 boilerplate (단순 반복 코드) 가 늘어요. 의도된 비용 —
*v1 응답을 절대 안 깨겠다* 는 약속의 가격입니다. v1 / v2 가 *공유* 코드 (도메인 객체) 와 *각자
의* 코드 (DTO + controller) 로 깔끔히 분리되어 있는 한 OK.

`MapStruct` 같은 mapper 라이브러리로 boilerplate 를 줄일 수 있지만, 매퍼가 *암묵적으로* 필드를
빠뜨리는 사고가 더 큰 손해라 직접 작성 유지.

### v1 의 변경은 절대 없는가

- 응답에 *추가 필드* — 허용 (BREAKING 이 아님). 다만 client 가 *추가될 수도 있다* 는 가정 하에
  파싱하고 있어야 함. 우리 응답 정책: *허용*, 단 OpenAPI spec 의 example 갱신.
- response 의 *기본값 변경* — *주의*. 명시적 필드를 client 가 검증하지 않는 한 OK.
- 기타 BREAKING — *금지*. v2 로.

### 왜 모든 endpoint 를 한꺼번에 v2 화 하지 않는가

도입 시점엔 schema 변경이 *실제로 필요한* endpoint 만 v2 화. 변화가 없는 endpoint 는 v1 그대로
유지하는 게 *변경 risk 와 코드 양 모두 최소*. 시간이 지나 필요한 endpoint 들이 전부 v2 로
이전됐을 때 v1 통째 sunset.

REST → GraphQL 같은 큰 전환을 단행한 공개 API 들도 이 패턴을 따랐습니다 — 구 버전을 수년간
유지한 뒤 점진 sunset.

## 다시 검토할 시점

- 첫 진짜 BREAKING change 요청이 들어와 v2 endpoint 가 *3개 이상* 누적되면, 운영 cutover
  로드맵 (sunset 일정 + client 마이그레이션 가이드) 별도 작성.
- header-based versioning 의 장점이 압도하는 시나리오 (예: 같은 endpoint 의 v1/v2 가 *완전히
  같은 path* 여야 하는 외부 통합 요구) 가 생기면 dual-mode 도입.
- v1 사용량이 0 으로 떨어지고 sunset 이 끝났을 때 v1 controller / DTO 일괄 제거.
