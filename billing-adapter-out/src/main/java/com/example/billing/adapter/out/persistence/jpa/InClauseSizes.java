package com.example.billing.adapter.out.persistence.jpa;

import java.util.ArrayList;
import java.util.List;

/**
 * 동적 IN clause 의 collection size 를 고정 단계 로 padding 해 Hibernate / PG 의 query plan
 * cache miss 를 줄이기 위한 도구 (ADR-0032).
 *
 * <p>운영 환경에서 IN clause 의 항목 수가 매번 달라지면 (3개, 7개, 12개, 20개...) 각 size 가
 * 다른 SQL 로 컴파일되어 plan cache 가 빠르게 LRU eviction 됩니다. Hibernate 의 {@code
 * query.in_clause_parameter_padding} 옵션이 (1, 2, 4, 8, 16, 32, ...) 단위로 자동 padding 해
 * 주지만, 그건 Hibernate 가 만든 SQL 에만 적용 — NativeQuery 나 직접 만든 IN clause 는 호출자가
 * 명시적으로 padding 해야 합니다.</p>
 *
 * <p>사용 예:
 * <pre>{@code
 * List<UUID> ids = ...;
 * List<UUID> padded = InClauseSizes.padPow2(ids, ids.get(ids.size() - 1));
 * jpaQuery.setParameter("ids", padded);   // size 가 1/2/4/8/.../128 중 하나로 고정됨
 * }</pre>
 *
 * <p>Padding 값은 마지막 원소 반복 — 실 의미는 그대로 유지하면서 SQL 의 parameter 개수 만
 * 고정. duplicate 가 IN 의 결과에 영향 없음 (IN 은 set-membership).</p>
 */
public final class InClauseSizes {

    /** 운영에서 자주 만나는 IN clause 크기 — 2 의 거듭제곱. 위로 padding. */
    private static final int[] BUCKETS = { 1, 2, 4, 8, 16, 32, 64, 128 };

    private InClauseSizes() {}

    /**
     * 입력 list 를 가장 작은 bucket 으로 올림 padding. 마지막 원소를 반복해 채움.
     * 입력 size 가 가장 큰 bucket (128) 보다 크면 그대로 반환 — 그 정도 큰 IN 은 도메인
     * 자체를 다시 봐야 하는 신호 (chunking / batch processing 으로 분리).
     */
    public static <T> List<T> padPow2(List<T> input, T padValue) {
        if (input == null || input.isEmpty()) return List.of();
        int target = bucketFor(input.size());
        if (target <= input.size()) return List.copyOf(input);
        List<T> padded = new ArrayList<>(target);
        padded.addAll(input);
        for (int i = input.size(); i < target; i++) {
            padded.add(padValue);
        }
        return List.copyOf(padded);
    }

    /** size 이상의 가장 작은 bucket. size 가 가장 큰 bucket 보다 크면 size 자체 반환. */
    public static int bucketFor(int size) {
        for (int b : BUCKETS) {
            if (b >= size) return b;
        }
        return size;
    }
}
