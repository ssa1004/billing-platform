package com.example.billing.adapter.out.persistence.jpa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InClauseSizes 단위 테스트 (ADR-0032).
 *
 * 검증 포인트:
 *  - bucket 은 2^n (1, 2, 4, 8, ...) 으로 올림 padding.
 *  - padding 값은 "마지막 원소 반복" — IN 의 set-membership 의미상 무해.
 *  - 빈 list 는 빈 list 반환 (padding 안 함).
 *  - bucket 한계 (128) 초과는 입력 그대로 반환.
 */
class InClauseSizesTest {

    @Test
    void bucketFor_returnsSmallestPowerOfTwoAtOrAbove() {
        assertThat(InClauseSizes.bucketFor(1)).isEqualTo(1);
        assertThat(InClauseSizes.bucketFor(2)).isEqualTo(2);
        assertThat(InClauseSizes.bucketFor(3)).isEqualTo(4);
        assertThat(InClauseSizes.bucketFor(7)).isEqualTo(8);
        assertThat(InClauseSizes.bucketFor(8)).isEqualTo(8);
        assertThat(InClauseSizes.bucketFor(9)).isEqualTo(16);
        assertThat(InClauseSizes.bucketFor(33)).isEqualTo(64);
        assertThat(InClauseSizes.bucketFor(128)).isEqualTo(128);
    }

    @Test
    void bucketFor_aboveLargestBucket_returnsSizeItself() {
        assertThat(InClauseSizes.bucketFor(200)).isEqualTo(200);
    }

    @Test
    void padPow2_emptyList_returnsEmpty() {
        assertThat(InClauseSizes.<String>padPow2(List.of(), "x")).isEmpty();
    }

    @Test
    void padPow2_nullList_returnsEmpty() {
        assertThat(InClauseSizes.<String>padPow2(null, "x")).isEmpty();
    }

    @Test
    void padPow2_threeElements_paddedTo4() {
        List<String> in = List.of("a", "b", "c");
        List<String> out = InClauseSizes.padPow2(in, "c");

        assertThat(out).hasSize(4);
        assertThat(out).containsExactly("a", "b", "c", "c");
    }

    @Test
    void padPow2_alreadyPow2_unchanged() {
        List<String> in = List.of("a", "b", "c", "d");
        List<String> out = InClauseSizes.padPow2(in, "x");
        assertThat(out).containsExactlyElementsOf(in);
    }

    @Test
    void padPow2_aboveLargestBucket_unchanged() {
        List<Integer> in = java.util.stream.IntStream.range(0, 200)
                .boxed()
                .toList();
        List<Integer> out = InClauseSizes.padPow2(in, -1);
        assertThat(out).hasSize(200);
        assertThat(out).doesNotContain(-1);
    }
}
