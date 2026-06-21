<!--
PR 제목은 Conventional Commits 를 따른다: feat: / fix: / refactor: / test: / docs: / chore: / ci: / build:
돈을 다루는 서비스라 "왜 안전한가" 를 설명하는 게 핵심이다.
-->

## 무엇을 / 왜

<!-- 이 변경이 무엇을 하고 왜 필요한지. 관련 이슈가 있으면 Closes #123. -->

## 변경 범위

- [ ] 도메인 / 애플리케이션 (결제·정산 로직)
- [ ] 어댑터 (in/out)
- [ ] 배치
- [ ] CI / Helm / k8s / Docker (배포 surface)
- [ ] 문서

## 검증

<!-- 실제로 돌린 것을 적는다. 해당 없으면 줄을 지운다. -->

- [ ] `./gradlew test` 통과
- [ ] 통합 테스트 (Testcontainers) 통과
- [ ] Helm 변경 시: `helm lint` + `helm template | kubeconform` 통과
- [ ] Dockerfile 변경 시: `hadolint` 통과
- [ ] 워크플로 변경 시: `actionlint` 통과

## money path 영향 / 마이그레이션

<!--
in-flight 트랜잭션 / 부분 결제 / 미정산 위험이 있는가?
DB 마이그레이션 (Flyway) 이 있으면 rollback 가능 여부와 순서를 적는다.
없으면 "없음" 이라고 명시.
-->

## 롤백 계획

<!-- 문제 시 어떻게 되돌리는가. 단순 revert 로 충분한지, 데이터 정합성 보정이 필요한지. -->
