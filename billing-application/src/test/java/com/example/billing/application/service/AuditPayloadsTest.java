package com.example.billing.application.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditPayloads} — audit before/after JSON 빌더의 escape 정확성 검증.
 *
 * <p>핵심은 외부 문자열 (customerId, PG errorMessage 등) 에 JSON 메타문자가 섞여도
 * 만들어진 페이로드가 깨지지 않는 것. 이게 깨지면 forensic / 회계 감사 단계에서
 * 페이로드를 파싱하지 못한다.</p>
 */
class AuditPayloadsTest {

    @Test
    void flatObject_buildsExpectedJson() {
        String json = AuditPayloads.object()
                .put("customerId", "acme-corp")
                .put("amount", new BigDecimal("1500.00"))
                .put("period", YearMonth.of(2026, 5))
                .build();

        assertThat(json).isEqualTo(
                "{\"customerId\":\"acme-corp\",\"amount\":\"1500.00\",\"period\":\"2026-05\"}");
    }

    @Test
    void nullStringValue_becomesJsonNullLiteral() {
        // null 은 "null" 문자열이 아니라 JSON null 리터럴 (따옴표 없음) 이어야 한다.
        String json = AuditPayloads.object()
                .put("validUntil", (String) null)
                .build();

        assertThat(json).isEqualTo("{\"validUntil\":null}");
    }

    @Test
    void nullObjectValue_becomesJsonNullLiteral() {
        String json = AuditPayloads.object()
                .put("validUntil", (Object) null)
                .build();

        assertThat(json).isEqualTo("{\"validUntil\":null}");
    }

    @Test
    void doubleQuoteInValue_isEscaped() {
        // PG errorMessage 류 — 큰따옴표가 섞이면 escape 없이는 JSON 이 깨진다.
        String json = AuditPayloads.object()
                .put("errorMessage", "card \"declined\" by issuer")
                .build();

        assertThat(json).isEqualTo("{\"errorMessage\":\"card \\\"declined\\\" by issuer\"}");
    }

    @Test
    void backslashInValue_isEscaped() {
        String json = AuditPayloads.object()
                .put("path", "C:\\temp\\x")
                .build();

        assertThat(json).isEqualTo("{\"path\":\"C:\\\\temp\\\\x\"}");
    }

    @Test
    void controlCharactersInValue_areEscaped() {
        // 개행 / 탭 / 그 외 제어문자 (여기선 NUL) 모두 escape 되어야 한다.
        String json = AuditPayloads.object()
                .put("msg", "line1\nline2\tend\0")
                .build();

        assertThat(json).isEqualTo("{\"msg\":\"line1\\nline2\\tend\\u0000\"}");
    }

    @Test
    void escapedValue_roundTripsBackToOriginal() {
        // escape → unescape 가 원문과 일치하는지 (실제 JSON 파서가 읽었을 때의 값).
        String raw = "quote=\" backslash=\\ newline=\n tab=\t";
        String json = AuditPayloads.object().put("v", raw).build();

        // {"v":"..."} 형태에서 값 부분만 떼어 수동 unescape.
        String body = json.substring("{\"v\":".length(), json.length() - 1);
        assertThat(unescapeJsonString(body)).isEqualTo(raw);
    }

    @Test
    void emptyObject_buildsEmptyBraces() {
        assertThat(AuditPayloads.object().build()).isEqualTo("{}");
    }

    /** 테스트 전용 최소 JSON 문자열 unescape — {@code "..."} 한 개를 원문으로 되돌린다. */
    private static String unescapeJsonString(String quoted) {
        String inner = quoted.substring(1, quoted.length() - 1);
        StringBuilder sb = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char next = inner.charAt(++i);
            switch (next) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(inner.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("unexpected escape: \\" + next);
            }
        }
        return sb.toString();
    }
}
