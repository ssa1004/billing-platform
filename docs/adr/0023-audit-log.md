# ADR-0023: Audit Log — append-only 감사 로그

## 상태
적용

## 배경

결제 / 청구 / 환불 / 크레딧 발급 같은 *돈이 움직이는* 도메인에서 "누가 / 언제 / 무엇을 / 왜"
의 영구 기록은 *법적 의무에 가까운* 요구사항.

### 유스케이스 (실제 시나리오)

1. **회계 감사 / 국세청 검증**: "이 invoice 가 왜 cancel 됐냐" 라는 질문에 *몇 년 뒤* 답할 수 있어야.
   트랜잭션 로그 / DB 변경 이력 만으론 부족 — *누가 / 왜* 가 도메인에 안 박혀 있어 audit 가 그 빈 곳을 채운다.

2. **PCI-DSS / 정보보호**: 결제 / 카드 정보 접근의 모든 기록.
   data breach 사고 시 forensic 의 1순위 자료.

3. **운영 분쟁 (customer 컴플레인)**: "내가 환불 요청 안 했는데 왜 처리됐냐"
   actor + ipAddress + traceId 가 답.

4. **운영자 오용 추적**: 같은 운영자가 짧은 시간에 비정상적으로 많은 환불 처리 — SIEM 알림.

## 결정

### 데이터 모델

```
AuditEntry (append-only — 절대 UPDATE/DELETE 안 함)
  id              UUID
  actor:
    type          USER / OPERATOR / SYSTEM / EXTERNAL
    id            (userId / operatorId / component / source)
    ipAddress     (HTTP 진입점일 때만)
    userAgent     (HTTP 진입점일 때만)
  action          AuditAction enum (REFUND_APPROVED, CREDIT_GRANTED, ...)
  targetType      "Invoice" / "Refund" / "Credit" 등 도메인 객체 종류
  targetId        UUID 또는 자연 키 — string 통일로 join 단순화
  beforeJson      변경 전 (생성=null, JSON)
  afterJson       변경 후 (삭제=null, JSON)
  reason          자유 텍스트 — "customer requested" 등 nullable
  traceId         분산 추적 — 같은 요청의 모든 audit 동일
  occurredAt      timestamp
```

### 4가지 query 패턴

대부분의 조회는 4가지로 압축. 각각 인덱스 1개씩:

| 패턴 | 인덱스 | 시나리오 |
|---|---|---|
| 객체 timeline | `(target_type, target_id, occurred_at DESC)` | "이 invoice 에 무슨 일이 있었나" |
| 운영자 활동 | `(actor_type, actor_id, occurred_at DESC)` | "운영자 alice 의 어제 행위" |
| 분산 추적 join | `(trace_id)` | "이 요청 traceId 로 일어난 모든 audit" |
| 행위별 시간구간 | `(action, occurred_at DESC)` | "어제 모든 REFUND_APPROVED" (SIEM) |

### Append-only 의 의미

한 번 INSERT 된 row 는 *절대* UPDATE / DELETE 안 함. 도메인 메서드도 setter 없음.
잘못 기록된 항목은 *새 row* (정정 entry) 로 표현 — timeline 에 두 row 다 남는 게 *진실의
전체 모습*. "누군가 audit 를 지웠다" 자체가 forensic 신호.

데이터 보관 정책 (예: 7년) 은 별도 archival 정책으로 — 법정 보관 기간 지난 row 만 cold storage.
*수정/삭제* 는 절대 없음.

### Application 통합 — *명시적 호출*

```java
@Service
class GrantCreditService {
  @Transactional
  public Credit grant(GrantCreditCommand cmd) {
    // ... 도메인 작업 ...
    audit.log(actor, AuditAction.CREDIT_GRANTED, "Credit", credit.id().toString(),
              null, "{\"amount\":...}", cmd.reason());
    return credit;
  }
}
```

도메인 작업 직후 `audit.log(...)` 명시적 호출. 같은 트랜잭션 (`@Transactional(REQUIRED)`)
이라 도메인 변경과 audit 가 같이 commit / 같이 rollback. *audit 누락 데이터 정합 사고* 회피.

### 왜 AOP / Spring Event listener 가 아닌가

가능한 대안: `@After("...credit.grant(..)")` aspect 로 자동 audit. 거부.

- *왜* (사유) 를 잃는다 — aspect 는 메서드 시그니처에서 reason 못 읽어옴
- 어떤 메서드가 audit 대상인지 하나하나 어노테이션 붙여야 — 결국 명시적 호출과 같은 양
- 디버깅 어려움 — "왜 audit 안 찍혔지" 가 AOP 매칭 룰 추적
- 트랜잭션 경계 / propagation 제어가 명시적이지 않음

명시적 호출이 코드량은 약간 늘지만 *이해 / 수정 / 디버깅* 모두 명확.

### traceId 자동 추출

SLF4J MDC 의 `traceId` 키에서 가져옴 — Spring Boot micrometer-tracing 이 자동으로 채워주는
표준 키. application service 가 따로 챙기지 않아도 분산 추적이 audit 에 자연스럽게 들어옴.
MDC 비활성 환경 (테스트 등) 에선 null — audit 자체는 계속 동작.

### 실패 모드

audit 저장 실패 → 호출자 트랜잭션 *전체 rollback*. audit 없이는 "감사 가능성" 이 사라지므로
데이터 정합 깨진 거나 마찬가지. 도메인 작업이 진행됐는데 audit 누락된 row 가 영구히 남는
상황 절대 회피.

## 대안 검토

- **DB CDC (Debezium) 로 row 변경 캡처** — DB 변경을 외부 audit log 시스템으로 stream.
  잘 되는 부분 (DB-level 정합, 무누락) 이 있지만 *왜 / 누가* 가 빠짐. 사용자/운영자 actor
  같은 도메인 의도를 DB row 만 보고 추론 불가. → audit log 와 *별개 채널* 로 둘 다 유효.
- **CloudWatch / Splunk / Datadog 에 push** — audit 를 *외부 SaaS* 로. cost 와 vendor lock-in.
  자체 보유가 회계 감사 (자료 제출) 시 더 단순.
- **Outbox 채널 통합** — outbox 가 이미 이벤트 발행 중이니 거기에 audit 도 같이 흘려보내기.
  거부. outbox 는 *비동기 publish* 가 본질이라 *동기 영속* 인 audit 와 시점 다름.
  outbox 컨슈머가 늦으면 audit 도 늦어지는 게 사고 시 치명적.
- **Hibernate Envers** — JPA entity 변경을 자동 영속. action / reason / actor 같은
  비즈니스 의도가 없어 부족. 별도 audit log 를 envers 위에 또 깔면 의미 중복.

## 결과

- 결제 도메인의 *돈이 움직이는 모든 행위* 가 영구 기록 — 회계 감사 / forensic / 운영 분쟁 대응 base
- 4가지 query 패턴이 인덱스로 cover — 운영자 화면 / SIEM 연동 효율
- 도메인 작업과 같은 트랜잭션 — 정합 사고 회피
- traceId 자동 결합으로 분산 추적 join 자연스러움
- (단점) 모든 행위마다 audit.log(...) 한 줄 명시 — 누락 가능성. 코드 리뷰 / 통합 테스트로 cover.
- (단점) write 부담 +1 (도메인 INSERT 1 + audit INSERT 1). row 수 누적 — 인덱스 4개도 결국 커짐.
  운영 6~12개월 후 partition (월 단위) 도입 검토.

## 후속 후보

- 운영자 dashboard — actor 별 활동 그래프 (이상 패턴 감지)
- SIEM (Splunk / ELK) export — 시간 구간 batch
- 자동 archival — 7년 지난 row cold storage 이동
- Audit JSON schema validation — beforeJson / afterJson 형식 일관성
- 운영자 권한 변경 자체도 audit 대상 (OPERATOR_PERMISSION_CHANGED — enum 에 이미 있음)
- 추가 wiring — Wallet / Webhook endpoint / Budget alert 도 모두 audit (현재는 Credit + Refund 만)
