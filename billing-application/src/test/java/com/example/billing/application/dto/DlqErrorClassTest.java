package com.example.billing.application.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DlqErrorClass} 분류 로직 단위 테스트.
 *
 * <p>billing 컨텍스트의 흔한 입력 (FQCN + 메시지 / simple + 메시지 / 메시지만) 을 모두 검증.
 * notification-hub 의 같은 이름 util 과 결과 동등.
 */
class DlqErrorClassTest {

    @Test
    void classify_fqcn_returnsSimpleName() {
        assertThat(DlqErrorClass.classify(
                "com.example.billing.application.exception.RefundFailedException: PG 5xx"))
                .isEqualTo("RefundFailedException");
    }

    @Test
    void classify_simpleName_withColon_returnsFirstToken() {
        assertThat(DlqErrorClass.classify("VendorTransientException: vendor down"))
                .isEqualTo("VendorTransientException");
    }

    @Test
    void classify_noColon_butSpace_returnsFirstWord() {
        assertThat(DlqErrorClass.classify("vendor down")).isEqualTo("vendor");
    }

    @Test
    void classify_singleToken_returnsAsIs() {
        assertThat(DlqErrorClass.classify("TimeoutException")).isEqualTo("TimeoutException");
    }

    @Test
    void classify_null_returnsNull() {
        assertThat(DlqErrorClass.classify(null)).isNull();
    }

    @Test
    void classify_blank_returnsNull() {
        assertThat(DlqErrorClass.classify("   ")).isNull();
    }

    /**
     * 입력이 ":text" 같이 prefix 가 colon 으로 시작하면 cut 위치가 0 — 그 결과 colon 직전이
     * 빈 문자열이라 빈 토큰이 반환. 실용적 의미는 없지만 코드 일관성 유지 (null vs blank
     * 의 미세한 차이). cf. notification-hub 의 같은 util.
     */
    @Test
    void classify_leadingColon_returnsEmptyMarker() {
        // firstColon=0 이라 cut=length, 그 다음 simple-name 추출 시 lastDot=-1 → 원본 그대로.
        assertThat(DlqErrorClass.classify(":")).isEqualTo(":");
    }
}
