package com.example.billing.bootstrap.config

import com.example.billing.bootstrap.config.properties.BillingProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
@ConditionalOnProperty(name = ["billing.pg.enabled"], havingValue = "true")
class PgRestClientConfig {

    @Bean
    fun pgRestClient(props: BillingProperties): RestClient =
        RestClient.builder()
            .baseUrl(props.pg.baseUrl)
            .requestFactory(reactiveTimeoutFactory(Duration.ofSeconds(5)))
            .build()

    private fun reactiveTimeoutFactory(timeout: Duration): JdkClientHttpRequestFactory {
        val factory = JdkClientHttpRequestFactory()
        factory.setReadTimeout(timeout)
        return factory
    }
}
