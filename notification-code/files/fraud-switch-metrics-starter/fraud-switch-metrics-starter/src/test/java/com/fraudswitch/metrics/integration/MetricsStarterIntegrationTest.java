package com.fraudswitch.metrics.integration;

import com.fraudswitch.metrics.cardinality.CardinalityEnforcer;
import com.fraudswitch.metrics.common.RequestMetrics;
import com.fraudswitch.metrics.config.MetricsConfigurationProperties;
import com.fraudswitch.metrics.core.MetricLabels;
import com.fraudswitch.metrics.services.FraudRouterMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Metrics Starter auto-configuration.
 * 
 * <p>Tests the full Spring Boot integration including:
 * - Auto-configuration activation
 * - Bean creation
 * - Configuration properties binding
 * - End-to-end metric recording
 */
@SpringBootTest(classes = MetricsStarterIntegrationTest.TestConfiguration.class)
@TestPropertySource(properties = {
        "fraud-switch.metrics.enabled=true",
        "fraud-switch.metrics.service-name=fraud-router",
        "fraud-switch.metrics.region=us-ohio-1",
        "fraud-switch.metrics.environment=test"
})
class MetricsStarterIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MetricsConfigurationProperties config;

    @Autowired
    private CardinalityEnforcer cardinalityEnforcer;

    @Autowired(required = false)
    private FraudRouterMetrics fraudRouterMetrics;

    @Test
    void contextLoads() {
        assertThat(meterRegistry).isNotNull();
        assertThat(config).isNotNull();
        assertThat(cardinalityEnforcer).isNotNull();
    }

    @Test
    void shouldLoadConfigurationProperties() {
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getServiceName()).isEqualTo("fraud-router");
        assertThat(config.getRegion()).isEqualTo("us-ohio-1");
        assertThat(config.getEnvironment()).isEqualTo("test");
        assertThat(config.getCardinality().isEnforcementEnabled()).isTrue();
    }

    @Test
    void shouldAutoConfigureFraudRouterMetrics() {
        assertThat(fraudRouterMetrics).isNotNull();
        assertThat(fraudRouterMetrics.getRequestMetrics()).isNotNull();
        assertThat(fraudRouterMetrics.getKafkaMetrics()).isNotNull();
    }

    @Test
    void shouldRecordMetricsEndToEnd() {
        // Given
        RequestMetrics requestMetrics = fraudRouterMetrics.getRequestMetrics();

        // When - record a request
        Timer.Sample sample = requestMetrics.startTimer();
        try {
            Thread.sleep(10); // Simulate work
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long duration = sample.stop(requestMetrics.getRequestTimer());
        requestMetrics.recordRequest(duration,
                MetricLabels.Request.EVENT_TYPE, "auth",
                MetricLabels.Request.GATEWAY, "stripe");

        // Then - metric should be registered
        Counter counter = meterRegistry.find("fraud_switch.fraud_router.requests_total")
                .tag(MetricLabels.Request.EVENT_TYPE, "auth")
                .tag(MetricLabels.Request.GATEWAY, "stripe")
                .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer timer = meterRegistry.find("fraud_switch.fraud_router.request_duration_ms")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThan(0);
    }

    @Test
    void shouldRecordRoutingDecision() {
        // When
        fraudRouterMetrics.recordRoutingDecision(
                "auth",
                "stripe",
                "fraud_sight",
                "Ravelin",
                "primary",
                50L
        );

        // Then
        Counter counter = meterRegistry.find("fraud_switch.fraud_router.routing_decisions_total")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer timer = meterRegistry.find("fraud_switch.fraud_router.routing_duration_ms")
                .timer();
        assertThat(timer).isNotNull();
    }

    @Test
    void shouldRecordParallelExecution() {
        // When - record parallel calls
        fraudRouterMetrics.recordParallelCall("boarding", true, 20L);
        fraudRouterMetrics.recordParallelCall("rules", true, 15L);
        fraudRouterMetrics.recordParallelCall("bin_lookup", true, 10L);

        // Then
        Counter counter = meterRegistry.find("fraud_switch.fraud_router.parallel_calls_total")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(3.0);
    }

    @Test
    void shouldRecordPanQueuePublish() {
        // When
        fraudRouterMetrics.recordPanQueuePublish(true, 25L);

        // Then
        Counter counter = meterRegistry.find("fraud_switch.fraud_router.kafka_publish_total")
                .tag(MetricLabels.Kafka.TOPIC, "pan.queue")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void shouldEnforceCardinalityLimits() {
        // Given
        CardinalityEnforcer.CardinalityStats statsBefore = cardinalityEnforcer.getStats();
        
        // When - record metrics
        RequestMetrics requestMetrics = fraudRouterMetrics.getRequestMetrics();
        for (int i = 0; i < 10; i++) {
            requestMetrics.recordRequest(50L,
                    "iteration", String.valueOf(i));
        }

        // Then
        CardinalityEnforcer.CardinalityStats statsAfter = cardinalityEnforcer.getStats();
        assertThat(statsAfter.totalLabelCombinations)
                .isGreaterThanOrEqualTo(statsBefore.totalLabelCombinations);
    }

    @Test
    void shouldIncludeCommonTagsOnAllMetrics() {
        // When
        fraudRouterMetrics.getRequestMetrics().recordRequest(50L);

        // Then
        Counter counter = meterRegistry.find("fraud_switch.fraud_router.requests_total")
                .tag(MetricLabels.Common.SERVICE, "fraud-router")
                .tag(MetricLabels.Common.REGION, "us-ohio-1")
                .tag(MetricLabels.Common.ENVIRONMENT, "test")
                .counter();
        
        assertThat(counter).isNotNull();
    }

    @Configuration
    static class TestConfiguration {
        // Spring Boot will auto-configure the metrics beans
        // This configuration is just a marker for the test context
    }
}
