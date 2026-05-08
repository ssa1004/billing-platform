# ADR-0030: 회계/결제 row 의 soft delete

## 상태
적용

## 배경

운영 중 자주 마주치는 시나리오 — 운영자가 "이 invoice 는 잘못 발급됐으니 삭제해주세요" 라고
요청. 가장 단순한 답: `repository.delete(invoice)` → DB row 가 사라짐. *간단하지만 회계 / 결제
도메인에서는 사고로 가는 길*.

### 시나리오 1 — 회계 감사

3년 전 5월 결산 보고서를 회계 감사인이 검토. "이 달의 invoice 합계가 보고서 합계와 1행만큼
어긋난다. 그 1행은 어디 갔나?" 질문. 우리 답:

> "운영자 alice 가 그때 한 행 삭제했어요. 사유는 메모도 안 남아 있어요."

이게 *최선* 입니다. 물리 삭제 후엔 *원래 그 행이 있었는지* 자체를 증명할 방법이 없어요.
SOX (미국 상장 기업 회계 책임법) / 한국 회계기준 / 사내 감사 모두 *원본 보존* 을 요구합니다.

### 시나리오 2 — PG 와의 정합 깨짐

Payment / Refund row 는 외부 PG (결제 게이트웨이) 의 트랜잭션 ID 가 박혀 있어요. 우리 DB 에
서만 row 를 지웠는데 PG 측엔 그대로 살아있으면:

- 정합 검증 (reconciler) 이 PG 의 transaction 을 보고 *우리 쪽엔 없네* → 다시 INSERT 시도.
  unique key 충돌. 모니터링 알림. 운영자 호출.
- 또는 reconciler 가 *PG 측에서 사라진 건가?* 라고 오판해 잘못된 정합 보정 시도.

### 시나리오 3 — customer 컴플레인

"내가 환불 요청 안 했는데 처리됐다" 같은 분쟁이 한 달 뒤 들어옴. ADR-0023 의 audit log 가
*누가 / 왜* 환불을 처리했는지 답해주는데, 만약 그 사이 누군가 그 Refund row 를 물리 삭제했다면:

- audit 에 "Refund r-1 이 alice 에 의해 SOFT_DELETED 됐다" 는 entry 가 있더라도, 정작 *그 r-1
  row 자체가 사라져* 어떤 환불이었는지 (금액 / 시각 / PG 환불 ID) 알 수 없습니다.

audit log 와 도메인 row 가 *짝* 으로 살아있어야 분쟁 답변이 됩니다.

## 결정

### 핵심 패턴 — `deleted_at` + `deleted_by` 컬럼

Invoice / Payment / Refund 세 테이블에 두 컬럼 추가:

```sql
ALTER TABLE invoices
    ADD COLUMN deleted_at TIMESTAMP,
    ADD COLUMN deleted_by VARCHAR(128);

ALTER TABLE invoices
    ADD CONSTRAINT chk_invoices_soft_delete_pair
        CHECK (
            (deleted_at IS NULL AND deleted_by IS NULL)
         OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL)
        );
```

invariant: 두 컬럼은 항상 짝. 한쪽만 set 인 row 는 운영 사고 신호 (CHECK constraint 가 막음).

### Hibernate 통합 — `@SQLRestriction` + `@SQLDelete`

```java
@Entity
@SQLRestriction("deleted_at IS NULL")
@SQLDelete(sql = "UPDATE invoices SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ?")
public class InvoiceJpaEntity { ... }
```

- **`@SQLRestriction`**: 모든 JPQL / Spring Data 쿼리에 자동으로 `deleted_at IS NULL` 을 AND
  로 끼워줌. *활성 row 만 보이는 게 기본*. 운영자 화면이 삭제된 row 까지 봐야 하면 NativeQuery
  로 우회 (`findByIdIncludingDeleted`).
- **`@SQLDelete`**: 누군가 실수로 `repository.delete(entity)` 를 호출해도 실제 DELETE 는
  발행되지 않고 UPDATE 가 발행됨 — 안전망.

### 명시적 soft delete 흐름 — `SoftDeleteService`

`@SQLDelete` 만으론 *누가* 지웠는지 (deleted_by) 채울 수 없어요. 그래서 정상 흐름은:

```java
@Service
class SoftDeleteService implements SoftDeleteUseCase {

    @Transactional
    public boolean softDeleteInvoice(UUID id, AuditActor actor, String reason) {
        Optional<Invoice> before = invoices.findById(id);
        if (before.isEmpty()) return false;

        boolean affected = invoices.softDelete(id, actor.id());   // UPDATE 1번
        if (!affected) return false;                              // race lost

        auditLogger.log(actor, AuditAction.SOFT_DELETED, "Invoice",
                id.toString(), summarize(before.get()), null, reason);
        return true;
    }
}
```

한 트랜잭션 안에서:
1. 활성 row 조회 (snapshot 확보)
2. `UPDATE ... SET deleted_at = NOW(), deleted_by = ? WHERE id = ? AND deleted_at IS NULL`
3. SOFT_DELETED audit entry 발행 — before=row JSON, after=null

세 단계가 같이 commit / 같이 rollback. row 는 마킹됐는데 audit 는 누락 같은 정합 사고는
이 트랜잭션 경계로 차단.

### 멱등성 — 두 번 호출해도 안전

UPDATE 의 `WHERE deleted_at IS NULL` 절 덕분에 이미 삭제된 row 에 한 번 더 호출하면 0행 영향.
서비스는 boolean 으로 "처음 삭제" 인지 판정해 audit 를 한 번만 발행. timeline 이 어지러워지지
않습니다.

### Postgres partial index — read 비용 감소

```sql
CREATE INDEX idx_invoices_active ON invoices (id) WHERE deleted_at IS NULL;
```

운영에서는 활성 row 가 99%+, 삭제 row 가 1% 미만이라 partial index 가 훨씬 작고 read query
의 hot path 를 cover. H2 가 partial index 를 지원하지 않아 dev 환경은 일반 인덱스로
fallback (V13 / V13_1 두 마이그레이션 분리).

## 트레이드오프

### "왜 over-spec 아닌가"

물리 삭제는 *작은 사고가 큰 데미지* 로 직행하는 코드 — `repository.delete()` 한 줄에 *3년치
회계 정합* 이 깨질 수 있어요. soft delete 는 SQL 컬럼 두 개, 인덱스 한 개, Hibernate 어노
테이션 두 개 — *비용은 거의 0, 보호 효과는 거의 무한대*. 회계 / 결제 도메인을 다루는 어떤
시스템에도 *기본 셋업* 입니다.

### Soft delete 의 알려진 단점 — 솔직히 명시

1. **테이블 비대** — 삭제된 row 가 영원히 누적. 우리는 회계 보관 기간 (7년) 후 cold storage
   archival 로 *이동* 하는 별도 정책이 필요. (지금은 미구현 — 운영 1년 차 전엔 불필요.)
2. **인덱스 효율** — 활성 row 와 삭제 row 가 한 인덱스에 섞이면 selectivity 가 떨어져요.
   partial index 로 해결 (V13_1).
3. **JOIN 의 함정** — Hibernate `@SQLRestriction` 은 JPQL 쿼리에 자동 적용되지만, 다른
   테이블이 FK 로 참조하는 경우 *그 쪽* 에서 deleted row 를 join 해 가져올 수 있어요.
   `payments.order_id → orders.id` 같은 FK 가 있고 orders 에 soft delete 가 들어가면 payments
   join 결과가 *삭제된 order* 까지 끌고 옵니다. 우리는 일단 invoice / payment / refund 세
   테이블만 soft delete 적용 — 위 테이블들은 다른 테이블이 직접 join 하지 않는 *상위 aggregate
   루트* 라 안전.
4. **Unique constraint 의 함정** — `(customer_id, period_year_month)` 같은 unique 가 걸린
   invoices 에서 같은 키로 다시 발급하려면 이전 row 가 soft delete 됐다고 unique 를 우회
   해주지 않아요. 운영 표준은 unique constraint 를 *partial* (Postgres) 또는 *expression*
   (`(customer_id, period_year_month, COALESCE(deleted_at, '9999-12-31'))`) 으로 변환하는
   거지만 *지금은 같은 customer 의 같은 달 invoice 를 한 번만 발급* 이라는 도메인 규칙이
   유지되므로 문제 없음. 이 정책이 바뀔 때 같이 검토.

### 왜 audit log 만으론 충분하지 않은가

ADR-0023 의 audit 가 *누가 / 언제 / 왜* 를 답해줍니다. 그러면 audit + 물리 삭제 조합은 어때요?
거부 사유:

- audit 의 `before_json` 에 row 스냅샷이 박혀있긴 하지만 *JSON 안* 이라 SQL JOIN / 인덱스가
  안 됨. "삭제된 invoice 중에서 customer X 의 것만" 같은 운영 쿼리가 비싸요.
- 정합 검증 (reconciler) 이 audit 까지 보지 않아요. PG-reconciler 는 `payments` 테이블만 보고
  "PG 측에 살아있는데 우리한테 없다" 를 사고로 알림.
- audit 가 망가지면 (운영 사고 / 마이그레이션 실수) 진실을 다시 끌어올 길이 *원본 row* 인데
  그게 사라진 상태.

audit + soft delete 는 *서로 다른 방어선* 이라 둘 다 둡니다.

### 왜 Hibernate `@SoftDelete` (Hibernate 6.4+) 을 안 쓰는가

Hibernate 6.4 의 `@SoftDelete` 어노테이션 하나로 같은 효과를 얻을 수 있긴 합니다. 거부 사유:

- *누가* (deleted_by) 를 못 채워요 — `@SoftDelete` 는 boolean / timestamp 하나만 다뤄요.
  우리는 audit 의 actor 와 짝맞춰 행위 주체를 row 에도 박아야 해요.
- 동작이 깊이 마법 (magic) — `@SQLRestriction` + `@SQLDelete` 는 SQL 한 줄이 그대로 보여
  운영자가 *왜 이 쿼리가 나가는지* 즉답.

## 적용 범위

| 테이블 | soft delete | 이유 |
|---|---|---|
| `invoices` | O | 회계 / 감사 1순위 |
| `payments` | O | PG 매칭 row, 정합 검증 대상 |
| `refunds` | O | PG 매칭 row, customer 분쟁 1순위 |
| `customers` | (지금은 X) | 본 repo 에 customer 테이블이 없어 미적용 — 추후 도입 시 확장 |
| `wallets` / `ledger_entries` | X | 이미 append-only (한 번 적으면 수정/삭제 없음) — 별도 정책 |
| `audit_entries` | X | append-only — soft delete 도 *수정* 의 일종이라 금지 |

## 다시 검토할 시점

- 7년 cold storage archival 정책을 도입할 때 — 이 ADR 의 *테이블 비대* 단점이 실제 문제로
  떠오르는 시점.
- customers 테이블이 추가되어 GDPR (유럽 개인정보 보호규정) 의 *잊혀질 권리* 와 충돌할 때 —
  법적 의무로 *진짜* 물리 삭제가 필요한 row 가 생기면, audit 를 어떻게 보존할지 별도 ADR.
- Invoice 의 unique constraint 가 partial / expression 으로 바뀌어야 할 때 (재발급 정책 변경).
