package com.example.billing.bootstrap.config

import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JacksonConfig {

    /** Jackson 에 java.time + Kotlin 모듈 등록. */
    @Bean
    fun javaTimeModule(): Module = JavaTimeModule()

    @Bean
    fun kotlinModule(): Module = KotlinModule.Builder().build()
}
