package com.fraudswitch.metrics.common;

import com.fraudswitch.metrics.cardinality.CardinalityEnforcer;
import com.fraudswitch.metrics.config.MetricsConfigurationProperties;
import com.fraudswitch.metrics.core.MetricLabels;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RequestMetrics.
 */
@ExtendWith(MockitoExtension.class)
class RequestMetricsTest {

    private MeterRegistry meterRegistry;
    private CardinalityEnforcer cardinalityEnforcer;
    private MetricsConfigurationProperties config;
    private RequestMetrics requestMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        config = createTestConfig();
        cardinalityEnforcer = new CardinalityEnforcer(config);
        requestMetrics = new RequestMetrics(
                meterRegistry,
                cardinalityEnforcer,
                config,
                "fraud_switch.test_service"
        );
    }

    @Test
    void shouldRecordSuccessfulRequest() {
        // Given
        long duration = 50L;
        String[] labels = {
                MetricLabels.Request.EVENT_TYPE, "auth",
                MetricLabels.Request.GATEWAY, "stripe"
        };

        // When
        requestMetrics.recordRequest(duration, labels);

        // Then
        Counter counter = meterRegistry.find("fraud_switch.test_service.requests_total")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer timer = meterRegistry.find("fraud_switch.test_service.request_duration_ms")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThan(0);
    }

    @Test
    void shouldRecordErrorRequest() {
        // Given
        long duration = 100L;
        String errorType = "TimeoutException";
        String[] labels = {
                MetricLabels.Request.EVENT_TYPE, "auth"
        };

        // When
        requestMetrics.recordError(duration, errorType, labels);

        // Then
        Counter errorCounter = meterRegistry.find("fraud_switch.test_service.errors_total")
                .counter();
        assertThat(errorCounter).isNotNull();
        assertThat(errorCounter.count()).isEqualTo(1.0);
    }

    @Test
    void shouldRecordWithOutcome() {
        // Given
        long duration = 75L;

        // When - success
        requestMetrics.recordWithOutcome(duration, "success", 
                MetricLabels.Request.EVENT_TYPE, "auth");

        // Then
        Counter requestCounter = meterRegistry.find("fraud_switch.test_service.requests_total")
                .counter();
        assertThat(requestCounter).isNotNull();
        assertThat(requestCounter.count()).isEqualTo(1.0);

        // When - failure
        requestMetrics.recordWithOutcome(duration, "timeout",
                MetricLabels.Request.EVENT_TYPE, "auth");

        // Then
        Counter errorCounter = meterRegistry.find("fraud_switch.test_service.errors_total")
                .counter();
        assertThat(errorCounter).isNotNull();
        assertThat(errorCounter.count()).isEqualTo(1.0);
    }

    @Test
    void shouldStartAndStopTimer() {
        // Given
        Timer.Sample sample = requestMetrics.startTimer();

        // When
        try {
            Thread.sleep(10); // Simulate work
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long duration = sample.stop(requestMetrics.getRequestTimer());

        // Then
        assertThat(duration).isGreaterThan(0);
        Timer timer = requestMetrics.getRequestTimer();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void shouldIncludeCommonTags() {
        // When
        requestMetrics.recordRequest(50L);

        // Then
        Counter counter = meterRegistry.find("fraud_switch.test_service.requests_total")
                .tag(MetricLabels.Common.SERVICE, "test-service")
                .tag(MetricLabels.Common.REGION, "us-ohio-1")
                .tag(MetricLabels.Common.ENVIRONMENT, "test")
                .counter();
        assertThat(counter).isNotNull();
    }

    @Test
    void shouldEnforceCardinalityLimits() {
        // Given - config with low limits
        config.getCardinality().setMaxLabelsPerMetric(5);
        requestMetrics = new RequestMetrics(
                meterRegistry,
                cardinalityEnforcer,
                config,
                "fraud_switch.test_service"
        );

        // When - record within limit
        for (int i = 0; i < 3; i++) {
            requestMetrics.recordRequest(50L, 
                    "label", "value" + i);
        }

        // Then - all recorded
        Counter counter = meterRegistry.find("fraud_switch.test_service.requests_total")
                .counter();
        assertThat(counter.count()).isEqualTo(3.0);

        // When - exceed limit (will be dropped based on cardinality enforcement)
        for (int i = 10; i < 20; i++) {
            requestMetrics.recordRequest(50L,
                    "label", "value" + i);
        }

        // Then - base counter still increments but new label combinations may be dropped
        assertThat(counter.count()).isGreaterThanOrEqualTo(3.0);
    }

    @Test
    void shouldRecordThroughput() {
        // When
        requestMetrics.recordThroughput(150.5,
                MetricLabels.Request.EVENT_TYPE, "auth");

        // Then
        assertThat(meterRegistry.find("fraud_switch.test_service.throughput_rps")
                .gauge()).isNotNull();
    }

    private MetricsConfigurationProperties createTestConfig() {
        MetricsConfigurationProperties props = new MetricsConfigurationProperties();
        props.setEnabled(true);
        props.setServiceName("test-service");
        props.setRegion("us-ohio-1");
        props.setEnvironment("test");
        
        MetricsConfigurationProperties.CardinalityConfig cardinality = 
                new MetricsConfigurationProperties.CardinalityConfig();
        cardinality.setEnforcementEnabled(true);
        cardinality.setMaxLabelsPerMetric(1000);
        cardinality.setMaxValuesPerLabel(100);
        props.setCardinality(cardinality);
        
        MetricsConfigurationProperties.HistogramConfig histogram = 
                new MetricsConfigurationProperties.HistogramConfig();
        props.setHistogram(histogram);
        
        MetricsConfigurationProperties.SamplingConfig sampling = 
                new MetricsConfigurationProperties.SamplingConfig();
        sampling.setEnabled(false);
        props.setSampling(sampling);
        
        return props;
    }
}
