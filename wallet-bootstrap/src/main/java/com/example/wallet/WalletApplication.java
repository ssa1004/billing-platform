package com.example.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulith;

/**
 * Wallet Platform 진입점.
 *
 * <p>Spring Modulith 가 모듈 경계를 verify — {@link com.example.wallet.WalletApplicationModulithTest} 가
 * 빌드 시 모듈 의존 방향을 검증한다.</p>
 */
@SpringBootApplication(scanBasePackages = "com.example.wallet")
@ConfigurationPropertiesScan(basePackages = "com.example.wallet.bootstrap.config")
@Modulith(systemName = "wallet-platform")
public class WalletApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletApplication.class, args);
    }
}
