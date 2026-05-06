package com.example.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulith;

/**
 * Billing Platform 진입점.
 *
 * <p>Spring Modulith 가 모듈 경계를 verify — {@link com.example.billing.BillingApplicationModulithTest} 가
 * 빌드 시 모듈 의존 방향을 검증한다.</p>
 */
@SpringBootApplication(scanBasePackages = "com.example.billing")
@ConfigurationPropertiesScan(basePackages = "com.example.billing.bootstrap.config")
@Modulith(systemName = "billing-platform")
public class BillingApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingApplication.class, args);
    }
}
