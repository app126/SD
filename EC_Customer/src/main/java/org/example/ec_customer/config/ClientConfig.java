package org.example.ec_customer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for client-specific settings.
 */
@Configuration
public class ClientConfig {

    @Value("${client.id}")
    private String clientId;

    /**
     * Creates a dynamic Kafka topic name based on the client ID.
     *
     * @return the dynamic Kafka topic name
     */
    @Bean
    public String clientIdTopic() {
        return "taxi-requests-" + clientId;  // Dynamic topic based on the client ID
    }
}