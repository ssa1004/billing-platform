package com.example.billing.bootstrap.config;

import com.example.billing.bootstrap.config.properties.BillingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "billing.pg.enabled", havingValue = "true")
public class PgRestClientConfig {

    @Bean
    public RestClient pgRestClient(BillingProperties props) {
        return RestClient.builder()
                .baseUrl(props.pg().baseUrl())
                .requestFactory(reactiveTimeoutFactory(Duration.ofSeconds(5)))
                .build();
    }

    private static org.springframework.http.client.JdkClientHttpRequestFactory reactiveTimeoutFactory(Duration timeout) {
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory();
        factory.setReadTimeout(timeout);
        return factory;
    }
}
