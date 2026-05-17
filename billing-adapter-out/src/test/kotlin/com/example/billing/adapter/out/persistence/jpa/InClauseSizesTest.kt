package com.example.billing.adapter.out.persistence.jpa

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
    fun bucketFor_returnsSmallestPowerOfTwoAtOrAbove() {
        assertThat(InClauseSizes.bucketFor(1)).isEqualTo(1)
        assertThat(InClauseSizes.bucketFor(2)).isEqualTo(2)
        assertThat(InClauseSizes.bucketFor(3)).isEqualTo(4)
        assertThat(InClauseSizes.bucketFor(7)).isEqualTo(8)
        assertThat(InClauseSizes.bucketFor(8)).isEqualTo(8)
        assertThat(InClauseSizes.bucketFor(9)).isEqualTo(16)
        assertThat(InClauseSizes.bucketFor(33)).isEqualTo(64)
        assertThat(InClauseSizes.bucketFor(128)).isEqualTo(128)
    }

    @Test
    fun bucketFor_aboveLargestBucket_returnsSizeItself() {
        assertThat(InClauseSizes.bucketFor(200)).isEqualTo(200)
    }

    @Test
    fun padPow2_emptyList_returnsEmpty() {
        assertThat(InClauseSizes.padPow2(emptyList(), "x")).isEmpty()
    }

    @Test
    fun padPow2_nullList_returnsEmpty() {
        assertThat(InClauseSizes.padPow2<String>(null, "x")).isEmpty()
    }

    @Test
    fun padPow2_threeElements_paddedTo4() {
        val input = listOf("a", "b", "c")
        val out = InClauseSizes.padPow2(input, "c")

        assertThat(out).hasSize(4)
        assertThat(out).containsExactly("a", "b", "c", "c")
    }

    @Test
    fun padPow2_alreadyPow2_unchanged() {
        val input = listOf("a", "b", "c", "d")
        val out = InClauseSizes.padPow2(input, "x")
        assertThat(out).containsExactlyElementsOf(input)
    }

    @Test
    fun padPow2_aboveLargestBucket_unchanged() {
        val input = (0 until 200).toList()
        val out = InClauseSizes.padPow2(input, -1)
        assertThat(out).hasSize(200)
        assertThat(out).doesNotContain(-1)
    }
}
