package com.fraudswitch.metrics.common;

import com.fraudswitch.metrics.cardinality.CardinalityEnforcer;
import com.fraudswitch.metrics.config.MetricsConfigurationProperties;
import com.fraudswitch.metrics.core.MetricLabels;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ComponentMetrics.
 */
@ExtendWith(MockitoExtension.class)
class ComponentMetricsTest {

    private MeterRegistry meterRegistry;
    private CardinalityEnforcer cardinalityEnforcer;
    private MetricsConfigurationProperties config;
    private ComponentMetrics componentMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        config = createTestConfig();
        cardinalityEnforcer = new CardinalityEnforcer(config);
        componentMetrics = new ComponentMetrics(
                meterRegistry,
                cardinalityEnforcer,
                config,
                "fraud_switch.test_service"
        );
    }

    // ==================== Database Connection Pool Tests ====================

    @Test
    void shouldRecordConnectionPoolMetrics() {
        // When
        componentMetrics.recordConnectionPoolMetrics(
                "main-pool",
                20,  // total
                10,  // active
                8,   // idle
                2    // waiting
        );

        // Then
        Gauge poolSize = meterRegistry.find("fraud_switch.test_service.db_connection_pool_size")
                .tag(MetricLabels.Database.POOL_NAME, "main-pool")
                .gauge();
        assertThat(poolSize).isNotNull();
        assertThat(poolSize.value()).isEqualTo(20.0);

        Gauge activeGauge = meterRegistry.find("fraud_switch.test_service.db_connection_pool_active")
                .gauge();
        assertThat(activeGauge).isNotNull();
        assertThat(activeGauge.value()).isEqualTo(10.0);

        Gauge idleGauge = meterRegistry.find("fraud_switch.test_service.db_connection_pool_idle")
                .gauge();
        assertThat(idleGauge).isNotNull();
        assertThat(idleGauge.value()).isEqualTo(8.0);

        Gauge waitingGauge = meterRegistry.find("fraud_switch.test_service.db_connection_pool_waiting")
                .gauge();
        assertThat(waitingGauge).isNotNull();
        assertThat(waitingGauge.value()).isEqualTo(2.0);
    }

    @Test
    void shouldRecordConnectionWaitTime() {
        // When
        componentMetrics.recordConnectionWaitTime("main-pool", 150L);

        // Then
        Timer timer = meterRegistry.find("fraud_switch.test_service.db_connection_wait_time_ms")
                .tag(MetricLabels.Database.POOL_NAME, "main-pool")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void shouldRecordDatabaseQuery() {
        // When
        componentMetrics.recordDatabaseQuery(
                MetricLabels.Database.OP_SELECT,
                "merchants",
                25L,
                true
        );

        // Then
        Counter counter = meterRegistry.find("fraud_switch.test_service.db_query_total")
                .tag(MetricLabels.Database.DB_OPERATION, MetricLabels.Database.OP_SELECT)
                .tag(MetricLabels.Database.TABLE_NAME, "merchants")
                .tag(MetricLabels.Common.STATUS, "success")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer timer = meterRegistry.find("fraud_switch.test_service.db_query_duration_ms")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThan(0);
    }

    // ==================== Cache Tests ====================

    @Test
    void shouldRecordCacheHit() {
        // When
        componentMetrics.recordCacheHit("merchant-cache");

        // Then
        Counter counter = meterRegistry.find("fraud_switch.test_service.cache_operations_total")
                .tag(MetricLabels.Cache.CACHE_NAME, "merchant-cache")
                .tag(MetricLabels.Cache.HIT_TYPE, MetricLabels.Cache.HIT)
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void shouldRecordCacheMiss() {
        // When
        componentMetrics.recordCacheMiss("merchant-cache");

        // Then
        Counter counter = meterRegistry.find("fraud_switch.test_service.cache_operations_total")
                .tag(MetricLabels.Cache.CACHE_NAME, "merchant-cache")
                .tag(MetricLabels.Cache.HIT_TYPE, MetricLabels.Cache.MISS)
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void shouldRecordCacheOperation() {
        // When
        componentMetrics.recordCacheOperation(
                "merchant-cache",
                MetricLabels.Cache.OP_GET,
                true,
                5L
        );

        // Then
        Counter counter = meterRegistry.find("fraud_switch.test_service.cache_operations_total")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer timer = meterRegistry.find("fraud_switch.test_service.cache_operation_duration_ms")
                .timer();
        assertThat(timer).isNotNull();
    }

    @Test
    void shouldRecordRedisCommand() {
        // When
        componentMetrics.recordRedisCommand("GET", 3L, true);

        // Then
        Counter counter = meterRegistry.find("fraud_switch.test_service.redis_commands_total")
                .tag("command", "GET")
                .tag(MetricLabels.Common.STATUS, "success")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer timer = meterRegistry.find("fraud_switch.test_service.redis_command_duration_ms")
                .timer();
        assertThat(timer).isNotNull();
    }

    @Test
    void shouldRecordRedisConnections() {
        // When
        componentMetrics.recordRedisConnections(() -> 5);

        // Then
        Gauge gauge = meterRegistry.find("fraud_switch.test_service.redis_connections_active")
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(5.0);
    }

    // ==================== Circuit Breaker Tests ====================

    @Test
    void shouldRecordCircuitBreakerState() {
        // When
        componentMetrics.recordCircuitBreakerState(
                "ravelin-client",
                MetricLabels.CircuitBreaker.STATE_CLOSED
        );

        // Then
        Gauge gauge = meterRegistry.find("fraud_switch.test_service.circuit_breaker_state")
                .tag(MetricLabels.CircuitBreaker.CIRCUIT_BREAKER_NAME, "ravelin-client")
                .tag(MetricLabels.CircuitBreaker.STATE, MetricLabels.CircuitBreaker.STATE_CLOSED)
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0.0); // CLOSED = 0
    }

    @Test
    void shouldRecordCircuitBreakerOpenState() {
        // When
        componentMetrics.recordCircuitBreakerState(
                "ravelin-client",
                MetricLabels.CircuitBreaker.STATE_OPEN
        );

        // Then
        Gauge gauge = meterRegistry.find("fraud_switch.test_service.circuit_breaker_state")
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(1.0); // OPEN = 1
    }

    @Test
    void shouldRecordCircuitBreakerCall() {
        // When
        componentMetrics.recordCircuitBreakerCall(
                "ravelin-client",
                MetricLabels.CircuitBreaker.CALL_SUCCESS,
                45L
        );

        // Then
        Counter counter = meterRegistry.find("fraud_switch.test_service.circuit_breaker_calls_total")
                .tag(MetricLabels.CircuitBreaker.CIRCUIT_BREAKER_NAME, "ravelin-client")
                .tag(MetricLabels.CircuitBreaker.CALL_TYPE, MetricLabels.CircuitBreaker.CALL_SUCCESS)
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer timer = meterRegistry.find("fraud_switch.test_service.circuit_breaker_call_duration_ms")
                .timer();
        assertThat(timer).isNotNull();
    }

    // ==================== HTTP Client Tests ====================

    @Test
    void shouldRecordHttpClientCall() {
        // When
        componentMetrics.recordHttpClientCall(
                "rules-service",
                "POST",
                200,
                75L
        );

        // Then
        Counter counter = meterRegistry.find("fraud_switch.test_service.http_client_calls_total")
                .tag("target_service", "rules-service")
                .tag(MetricLabels.Http.HTTP_METHOD, "POST")
                .tag(MetricLabels.Http.HTTP_STATUS, "200")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer timer = meterRegistry.find("fraud_switch.test_service.http_client_call_duration_ms")
                .timer();
        assertThat(timer).isNotNull();
    }

    @Test
    void shouldRecordHttpClientError() {
        // When
        componentMetrics.recordHttpClientCall(
                "rules-service",
                "POST",
                500,
                120L
        );

        // Then
        Counter counter = meterRegistry.find("fraud_switch.test_service.http_client_calls_total")
                .tag(MetricLabels.Http.HTTP_STATUS, "500")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ==================== Thread Pool Tests ====================

    @Test
    void shouldRecordThreadPoolMetrics() {
        // When
        componentMetrics.recordThreadPoolMetrics(
                "async-executor",
                15,  // active
                20,  // pool size
                5    // queue size
        );

        // Then
        Gauge activeGauge = meterRegistry.find("fraud_switch.test_service.thread_pool_active")
                .tag("pool_name", "async-executor")
                .gauge();
        assertThat(activeGauge).isNotNull();
        assertThat(activeGauge.value()).isEqualTo(15.0);

        Gauge sizeGauge = meterRegistry.find("fraud_switch.test_service.thread_pool_size")
                .gauge();
        assertThat(sizeGauge).isNotNull();
        assertThat(sizeGauge.value()).isEqualTo(20.0);

        Gauge queueGauge = meterRegistry.find("fraud_switch.test_service.thread_pool_queue_size")
                .gauge();
        assertThat(queueGauge).isNotNull();
        assertThat(queueGauge.value()).isEqualTo(5.0);
    }

    // ==================== Retry Tests ====================

    @Test
    void shouldRecordRetryAttempt() {
        // When
        componentMetrics.recordRetryAttempt("api-call", 1, false);
        componentMetrics.recordRetryAttempt("api-call", 2, true);

        // Then
        Counter failedCounter = meterRegistry.find("fraud_switch.test_service.retry_attempts_total")
                .tag("operation", "api-call")
                .tag("attempt", "1")
                .tag(MetricLabels.Common.STATUS, "failure")
                .counter();
        assertThat(failedCounter).isNotNull();
        assertThat(failedCounter.count()).isEqualTo(1.0);

        Counter successCounter = meterRegistry.find("fraud_switch.test_service.retry_attempts_total")
                .tag("operation", "api-call")
                .tag("attempt", "2")
                .tag(MetricLabels.Common.STATUS, "success")
                .counter();
        assertThat(successCounter).isNotNull();
        assertThat(successCounter.count()).isEqualTo(1.0);
    }

    @Test
    void shouldIncludeCommonTags() {
        // When
        componentMetrics.recordCacheHit("test-cache");

        // Then
        Counter counter = meterRegistry.find("fraud_switch.test_service.cache_operations_total")
                .tag(MetricLabels.Common.SERVICE, "test-service")
                .tag(MetricLabels.Common.REGION, "us-ohio-1")
                .tag(MetricLabels.Common.ENVIRONMENT, "test")
                .counter();
        assertThat(counter).isNotNull();
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
