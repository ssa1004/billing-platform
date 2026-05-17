package com.example.billing

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.modulith.Modulith

/**
 * Billing Platform 진입점.
 *
 * Spring Modulith 가 모듈 경계를 verify — [com.example.billing.BillingApplicationModulithTest] 가
 * 빌드 시 모듈 의존 방향을 검증한다.
 */
@SpringBootApplication(scanBasePackages = ["com.example.billing"])
@ConfigurationPropertiesScan(basePackages = ["com.example.billing.bootstrap.config"])
@Modulith(systemName = "billing-platform")
class BillingApplication

fun main(args: Array<String>) {
    SpringApplication.run(BillingApplication::class.java, *args)
}
