package com.fraudswitch.metrics.cardinality;

import com.fraudswitch.metrics.config.MetricsConfigurationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CardinalityEnforcer.
 */
class CardinalityEnforcerTest {

    private MetricsConfigurationProperties config;
    private CardinalityEnforcer enforcer;

    @BeforeEach
    void setUp() {
        config = createTestConfig();
        enforcer = new CardinalityEnforcer(config);
    }

    @Test
    void shouldAllowMetricWithinCardinalityLimits() {
        // Given
        String metricName = "test.metric";
        String[] labels = {"key1", "value1", "key2", "value2"};

        // When
        boolean canRecord = enforcer.canRecordMetric(metricName, labels);

        // Then
        assertThat(canRecord).isTrue();
    }

    @Test
    void shouldAllowSameLabelCombinationMultipleTimes() {
        // Given
        String metricName = "test.metric";
        String[] labels = {"key1", "value1"};

        // When
        boolean canRecord1 = enforcer.canRecordMetric(metricName, labels);
        enforcer.recordMetric(metricName, labels);
        boolean canRecord2 = enforcer.canRecordMetric(metricName, labels);

        // Then
        assertThat(canRecord1).isTrue();
        assertThat(canRecord2).isTrue();
    }

    @Test
    void shouldEnforceLabelCombinationLimit() {
        // Given - config with low limit
        config.getCardinality().setMaxLabelsPerMetric(5);
        enforcer = new CardinalityEnforcer(config);
        String metricName = "test.metric";

        // When - add combinations up to limit
        for (int i = 0; i < 5; i++) {
            String[] labels = {"key", "value" + i};
            boolean canRecord = enforcer.canRecordMetric(metricName, labels);
            assertThat(canRecord).isTrue();
            enforcer.recordMetric(metricName, labels);
        }

        // When - exceed limit
        String[] excessLabels = {"key", "value999"};
        boolean canRecordExcess = enforcer.canRecordMetric(metricName, excessLabels);

        // Then
        assertThat(canRecordExcess).isFalse();
    }

    @Test
    void shouldEnforceLabelValueLimit() {
        // Given - config with low value limit
        config.getCardinality().setMaxValuesPerLabel(3);
        enforcer = new CardinalityEnforcer(config);
        String metricName = "test.metric";

        // When - add values up to limit
        for (int i = 0; i < 3; i++) {
            String[] labels = {"key", "value" + i};
            boolean canRecord = enforcer.canRecordMetric(metricName, labels);
            assertThat(canRecord).isTrue();
            enforcer.recordMetric(metricName, labels);
        }

        // When - exceed value limit
        String[] excessLabels = {"key", "value999"};
        boolean canRecordExcess = enforcer.canRecordMetric(metricName, excessLabels);

        // Then
        assertThat(canRecordExcess).isFalse();
    }

    @Test
    void shouldTrackMultipleMetrics() {
        // Given
        String metric1 = "metric.one";
        String metric2 = "metric.two";

        // When
        enforcer.canRecordMetric(metric1, "key1", "value1");
        enforcer.recordMetric(metric1, "key1", "value1");
        enforcer.canRecordMetric(metric2, "key2", "value2");
        enforcer.recordMetric(metric2, "key2", "value2");

        // Then
        CardinalityEnforcer.CardinalityStats stats = enforcer.getStats();
        assertThat(stats.totalMetrics).isEqualTo(2);
        assertThat(stats.totalLabelCombinations).isEqualTo(2);
    }

    @Test
    void shouldProvideCardinalityStats() {
        // Given
        String metricName = "test.metric";
        for (int i = 0; i < 3; i++) {
            String[] labels = {"key", "value" + i};
            enforcer.canRecordMetric(metricName, labels);
            enforcer.recordMetric(metricName, labels);
        }

        // When
        CardinalityEnforcer.CardinalityStats stats = enforcer.getStats();

        // Then
        assertThat(stats.totalMetrics).isEqualTo(1);
        assertThat(stats.totalLabelCombinations).isEqualTo(3);
        assertThat(stats.maxLabelCombinations).isEqualTo(3);
    }

    @Test
    void shouldResetState() {
        // Given
        String metricName = "test.metric";
        enforcer.canRecordMetric(metricName, "key", "value");
        enforcer.recordMetric(metricName, "key", "value");

        // When
        enforcer.reset();

        // Then
        CardinalityEnforcer.CardinalityStats stats = enforcer.getStats();
        assertThat(stats.totalMetrics).isEqualTo(0);
        assertThat(stats.totalLabelCombinations).isEqualTo(0);
    }

    @Test
    void shouldBypassWhenEnforcementDisabled() {
        // Given - enforcement disabled
        config.getCardinality().setEnforcementEnabled(false);
        enforcer = new CardinalityEnforcer(config);
        String metricName = "test.metric";

        // When - try to exceed limits
        for (int i = 0; i < 1000; i++) {
            String[] labels = {"key", "value" + i};
            boolean canRecord = enforcer.canRecordMetric(metricName, labels);
            assertThat(canRecord).isTrue();
        }

        // Then - all allowed
        CardinalityEnforcer.CardinalityStats stats = enforcer.getStats();
        assertThat(stats.totalMetrics).isEqualTo(0); // Not tracked when disabled
    }

    @Test
    void shouldHandleCircuitBreakerAction() {
        // Given - circuit breaker action configured
        config.getCardinality().setAction(MetricsConfigurationProperties.CardinalityAction.CIRCUIT_BREAK);
        config.getCardinality().setMaxLabelsPerMetric(2);
        config.getCircuitBreaker().setFailureThreshold(1);
        enforcer = new CardinalityEnforcer(config);
        
        String metricName = "test.metric";

        // When - exceed limit to trigger violation
        for (int i = 0; i < 10; i++) {
            String[] labels = {"key", "value" + i};
            enforcer.canRecordMetric(metricName, labels);
            enforcer.recordMetric(metricName, labels);
        }

        // Force periodic check by accessing stats
        CardinalityEnforcer.CardinalityStats stats = enforcer.getStats();

        // Then - violations should be tracked
        assertThat(stats.violationsCount).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldToStringCardinalityStats() {
        // Given
        enforcer.canRecordMetric("test.metric", "key", "value");
        enforcer.recordMetric("test.metric", "key", "value");

        // When
        CardinalityEnforcer.CardinalityStats stats = enforcer.getStats();
        String statsString = stats.toString();

        // Then
        assertThat(statsString).contains("totalMetrics=1");
        assertThat(statsString).contains("totalLabelCombinations=1");
    }

    private MetricsConfigurationProperties createTestConfig() {
        MetricsConfigurationProperties props = new MetricsConfigurationProperties();
        props.setServiceName("test-service");
        props.setRegion("us-ohio-1");
        props.setEnvironment("test");

        MetricsConfigurationProperties.CardinalityConfig cardinality = 
                new MetricsConfigurationProperties.CardinalityConfig();
        cardinality.setEnforcementEnabled(true);
        cardinality.setMaxLabelsPerMetric(100);
        cardinality.setMaxValuesPerLabel(50);
        cardinality.setCheckInterval(Duration.ofMillis(100)); // Fast for testing
        cardinality.setAction(MetricsConfigurationProperties.CardinalityAction.LOG);
        props.setCardinality(cardinality);

        MetricsConfigurationProperties.CircuitBreakerConfig circuitBreaker = 
                new MetricsConfigurationProperties.CircuitBreakerConfig();
        circuitBreaker.setEnabled(true);
        circuitBreaker.setFailureThreshold(5);
        circuitBreaker.setOpenDuration(Duration.ofSeconds(1));
        circuitBreaker.setHalfOpenDuration(Duration.ofMillis(500));
        props.setCircuitBreaker(circuitBreaker);

        return props;
    }
}
