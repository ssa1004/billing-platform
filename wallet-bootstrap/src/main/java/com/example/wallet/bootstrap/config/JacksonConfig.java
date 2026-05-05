package com.example.wallet.bootstrap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    /** Jackson 에 java.time + Kotlin 모듈 등록. */
    @Bean
    public com.fasterxml.jackson.databind.Module javaTimeModule() {
        return new JavaTimeModule();
    }

    @Bean
    public com.fasterxml.jackson.databind.Module kotlinModule() {
        return new KotlinModule.Builder().build();
    }
}
