package com.example.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Spring Modulith 모듈 경계 검증 + 자동 문서 생성.
 *
 * <p>{@link ApplicationModules#verify()} 가 모듈 의존 그래프 위반을 catch — 빌드 실패.
 * {@link Documenter} 가 PUML 다이어그램 + 모듈 canvas 를 docs/modulith/ 로 출력.</p>
 */
class WalletApplicationModulithTest {

    private final ApplicationModules modules = ApplicationModules.of(WalletApplication.class);

    @Test
    void verifiesModulesAreCompliant() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(modules)
                .writeDocumentation()
                .writeIndividualModulesAsPlantUml();
    }
}
