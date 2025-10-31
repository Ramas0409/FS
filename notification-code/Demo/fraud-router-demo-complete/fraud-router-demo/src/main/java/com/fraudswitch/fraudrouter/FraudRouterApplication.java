package com.fraudswitch.fraudrouter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Fraud Router Demo Application
 * 
 * Demonstrates integration of the centralized metrics library with:
 * - REST API endpoints
 * - Kafka messaging
 * - External REST API calls to fraud providers
 * 
 * Access metrics at: http://localhost:8080/actuator/prometheus
 */
@SpringBootApplication
@EnableKafka
@EnableAsync
public class FraudRouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudRouterApplication.class, args);
        System.out.println("\n" +
                "================================================================================\n" +
                "  Fraud Router Demo Application Started Successfully!\n" +
                "================================================================================\n" +
                "  REST API:        http://localhost:8080/api/fraud\n" +
                "  Prometheus:      http://localhost:8080/actuator/prometheus\n" +
                "  Health Check:    http://localhost:8080/actuator/health\n" +
                "  All Metrics:     http://localhost:8080/actuator/metrics\n" +
                "================================================================================\n");
    }

    /**
     * WebClient for calling external fraud providers
     */
    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(1024 * 1024)) // 1MB buffer
                .build();
    }
}
