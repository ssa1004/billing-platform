package com.example.billing

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

/**
 * Spring Modulith 모듈 경계 검증 + 자동 문서 생성.
 *
 * [ApplicationModules.verify] 가 모듈 의존 그래프 위반을 catch — 빌드 실패.
 * [Documenter] 가 PUML 다이어그램 + 모듈 canvas 를 docs 디렉터리로 출력.
 */
class BillingApplicationModulithTest {

    private val modules: ApplicationModules = ApplicationModules.of(BillingApplication::class.java)

    @Test
    fun verifiesModulesAreCompliant() {
        modules.verify()
    }

    @Test
    fun writesModuleDocumentation() {
        Documenter(modules)
            .writeDocumentation()
            .writeIndividualModulesAsPlantUml()
    }
}
