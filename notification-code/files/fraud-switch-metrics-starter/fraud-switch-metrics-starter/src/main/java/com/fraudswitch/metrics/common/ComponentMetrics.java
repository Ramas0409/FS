package com.fraudswitch.metrics.common;

import com.fraudswitch.metrics.cardinality.CardinalityEnforcer;
import com.fraudswitch.metrics.config.MetricsConfigurationProperties;
import com.fraudswitch.metrics.core.MetricLabels;
import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Common component metrics for infrastructure components.
 * 
 * <p>Provides metrics for:
 * - Database connection pools
 * - Redis/Cache operations
 * - Circuit breakers
 * - HTTP client calls
 * - Thread pools
 * 
 * <p>Thread-safe and optimized for high-throughput scenarios.
 * 
 * @version 1.0.0
 * @since 2025-10-22
 */
@Slf4j
public class ComponentMetrics {

    private final MeterRegistry meterRegistry;
    private final CardinalityEnforcer cardinalityEnforcer;
    private final MetricsConfigurationProperties config;
    private final String metricNamePrefix;

    public ComponentMetrics(
            MeterRegistry meterRegistry,
            CardinalityEnforcer cardinalityEnforcer,
            MetricsConfigurationProperties config,
            String metricNamePrefix) {
        
        this.meterRegistry = meterRegistry;
        this.cardinalityEnforcer = cardinalityEnforcer;
        this.config = config;
        this.metricNamePrefix = metricNamePrefix;
        
        log.debug("ComponentMetrics initialized for prefix: {}", metricNamePrefix);
    }

    // ==================== Database Connection Pool Metrics ====================

    /**
     * Record database connection pool metrics.
     * 
     * @param poolName Name of the connection pool
     * @param totalConnections Total connections in pool
     * @param activeConnections Active connections
     * @param idleConnections Idle connections
     * @param waitingThreads Threads waiting for connection
     */
    public void recordConnectionPoolMetrics(String poolName, int totalConnections, 
                                           int activeConnections, int idleConnections, 
                                           int waitingThreads) {
        String[] labels = {
                MetricLabels.Database.POOL_NAME, poolName
        };

        if (!cardinalityEnforcer.canRecordMetric(metricNamePrefix + ".db_connection_pool", labels)) {
            return;
        }

        Tags tags = createTags(labels);

        // Total connections
        Gauge.builder(metricNamePrefix + ".db_connection_pool_size", () -> totalConnections)
                .description("Total size of database connection pool")
                .tags(tags)
                .register(meterRegistry);

        // Active connections
        Gauge.builder(metricNamePrefix + ".db_connection_pool_active", () -> activeConnections)
                .description("Number of active connections in pool")
                .tags(tags)
                .register(meterRegistry);

        // Idle connections
        Gauge.builder(metricNamePrefix + ".db_connection_pool_idle", () -> idleConnections)
                .description("Number of idle connections in pool")
                .tags(tags)
                .register(meterRegistry);

        // Waiting threads
        Gauge.builder(metricNamePrefix + ".db_connection_pool_waiting", () -> waitingThreads)
                .description("Number of threads waiting for connection")
                .tags(tags)
                .register(meterRegistry);

        cardinalityEnforcer.recordMetric(metricNamePrefix + ".db_connection_pool", labels);
    }

    /**
     * Record database connection wait time.
     * 
     * @param poolName Name of the connection pool
     * @param waitTimeMs Time waited for connection in milliseconds
     */
    public void recordConnectionWaitTime(String poolName, long waitTimeMs) {
        String metricName = metricNamePrefix + ".db_connection_wait_time_ms";
        String[] labels = {
                MetricLabels.Database.POOL_NAME, poolName
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            return;
        }

        Tags tags = createTags(labels);
        Timer.builder(metricName)
                .description("Time spent waiting for database connection")
                .tags(tags)
                .register(meterRegistry)
                .record(waitTimeMs, TimeUnit.MILLISECONDS);

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    /**
     * Record database query execution.
     * 
     * @param operation Database operation (SELECT, INSERT, UPDATE, DELETE)
     * @param tableName Table name
     * @param durationMs Query duration in milliseconds
     * @param success Whether query was successful
     */
    public void recordDatabaseQuery(String operation, String tableName, 
                                   long durationMs, boolean success) {
        String metricName = metricNamePrefix + ".db_query_total";
        String[] labels = {
                MetricLabels.Database.DB_OPERATION, operation,
                MetricLabels.Database.TABLE_NAME, tableName,
                MetricLabels.Common.STATUS, success ? "success" : "error"
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            return;
        }

        Tags tags = createTags(labels);

        // Query count
        Counter.builder(metricName)
                .description("Total database queries executed")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        // Query duration
        Timer.builder(metricNamePrefix + ".db_query_duration_ms")
                .description("Database query execution time")
                .tags(tags)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    // ==================== Redis/Cache Metrics ====================

    /**
     * Record cache operation.
     * 
     * @param cacheName Name of the cache
     * @param operation Cache operation (get, put, evict)
     * @param hit Whether operation was a hit or miss
     * @param durationMs Operation duration in milliseconds
     */
    public void recordCacheOperation(String cacheName, String operation, 
                                    boolean hit, long durationMs) {
        String metricName = metricNamePrefix + ".cache_operations_total";
        String[] labels = {
                MetricLabels.Cache.CACHE_NAME, cacheName,
                MetricLabels.Cache.CACHE_OPERATION, operation,
                MetricLabels.Cache.HIT_TYPE, hit ? MetricLabels.Cache.HIT : MetricLabels.Cache.MISS
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            return;
        }

        Tags tags = createTags(labels);

        // Operation count
        Counter.builder(metricName)
                .description("Total cache operations")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        // Operation duration
        Timer.builder(metricNamePrefix + ".cache_operation_duration_ms")
                .description("Cache operation duration")
                .tags(tags)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    /**
     * Record cache hit rate metrics.
     * 
     * @param cacheName Name of the cache
     */
    public void recordCacheHit(String cacheName) {
        recordCacheOperation(cacheName, MetricLabels.Cache.OP_GET, true, 0);
    }

    /**
     * Record cache miss metrics.
     * 
     * @param cacheName Name of the cache
     */
    public void recordCacheMiss(String cacheName) {
        recordCacheOperation(cacheName, MetricLabels.Cache.OP_GET, false, 0);
    }

    /**
     * Record Redis command execution.
     * 
     * @param command Redis command (GET, SET, DEL, etc.)
     * @param durationMs Command duration in milliseconds
     * @param success Whether command was successful
     */
    public void recordRedisCommand(String command, long durationMs, boolean success) {
        String metricName = metricNamePrefix + ".redis_commands_total";
        String[] labels = {
                "command", command,
                MetricLabels.Common.STATUS, success ? "success" : "error"
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            return;
        }

        Tags tags = createTags(labels);

        // Command count
        Counter.builder(metricName)
                .description("Total Redis commands executed")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        // Command duration
        Timer.builder(metricNamePrefix + ".redis_command_duration_ms")
                .description("Redis command execution time")
                .tags(tags)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    /**
     * Record current Redis connection count.
     * 
     * @param activeConnections Number of active Redis connections
     */
    public void recordRedisConnections(Supplier<Integer> activeConnections) {
        String metricName = metricNamePrefix + ".redis_connections_active";
        
        Gauge.builder(metricName, activeConnections, Supplier::get)
                .description("Number of active Redis connections")
                .tags(createTags())
                .register(meterRegistry);
    }

    // ==================== Circuit Breaker Metrics ====================

    /**
     * Record circuit breaker state change.
     * 
     * @param circuitBreakerName Name of the circuit breaker
     * @param state Current state (CLOSED, OPEN, HALF_OPEN)
     */
    public void recordCircuitBreakerState(String circuitBreakerName, String state) {
        String metricName = metricNamePrefix + ".circuit_breaker_state";
        String[] labels = {
                MetricLabels.CircuitBreaker.CIRCUIT_BREAKER_NAME, circuitBreakerName,
                MetricLabels.CircuitBreaker.STATE, state
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            return;
        }

        Tags tags = createTags(labels);
        
        // State as gauge (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
        int stateValue = "CLOSED".equals(state) ? 0 : "OPEN".equals(state) ? 1 : 2;
        Gauge.builder(metricName, () -> stateValue)
                .description("Circuit breaker state")
                .tags(tags)
                .register(meterRegistry);

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    /**
     * Record circuit breaker call.
     * 
     * @param circuitBreakerName Name of the circuit breaker
     * @param callType Type of call (SUCCESS, FAILURE, TIMEOUT)
     * @param durationMs Call duration in milliseconds
     */
    public void recordCircuitBreakerCall(String circuitBreakerName, String callType, 
                                        long durationMs) {
        String metricName = metricNamePrefix + ".circuit_breaker_calls_total";
        String[] labels = {
                MetricLabels.CircuitBreaker.CIRCUIT_BREAKER_NAME, circuitBreakerName,
                MetricLabels.CircuitBreaker.CALL_TYPE, callType
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            return;
        }

        Tags tags = createTags(labels);

        // Call count
        Counter.builder(metricName)
                .description("Total circuit breaker calls")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        // Call duration
        Timer.builder(metricNamePrefix + ".circuit_breaker_call_duration_ms")
                .description("Circuit breaker call duration")
                .tags(tags)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    // ==================== HTTP Client Metrics ====================

    /**
     * Record HTTP client call.
     * 
     * @param targetService Target service name
     * @param httpMethod HTTP method (GET, POST, etc.)
     * @param statusCode HTTP status code
     * @param durationMs Request duration in milliseconds
     */
    public void recordHttpClientCall(String targetService, String httpMethod, 
                                    int statusCode, long durationMs) {
        String metricName = metricNamePrefix + ".http_client_calls_total";
        String[] labels = {
                "target_service", targetService,
                MetricLabels.Http.HTTP_METHOD, httpMethod,
                MetricLabels.Http.HTTP_STATUS, String.valueOf(statusCode)
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            return;
        }

        Tags tags = createTags(labels);

        // Call count
        Counter.builder(metricName)
                .description("Total HTTP client calls")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        // Call duration
        Timer.builder(metricNamePrefix + ".http_client_call_duration_ms")
                .description("HTTP client call duration")
                .tags(tags)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    // ==================== Thread Pool Metrics ====================

    /**
     * Record thread pool metrics.
     * 
     * @param poolName Name of the thread pool
     * @param activeThreads Number of active threads
     * @param poolSize Total pool size
     * @param queueSize Queue size
     */
    public void recordThreadPoolMetrics(String poolName, int activeThreads, 
                                       int poolSize, int queueSize) {
        String[] labels = {
                "pool_name", poolName
        };

        if (!cardinalityEnforcer.canRecordMetric(metricNamePrefix + ".thread_pool", labels)) {
            return;
        }

        Tags tags = createTags(labels);

        // Active threads
        Gauge.builder(metricNamePrefix + ".thread_pool_active", () -> activeThreads)
                .description("Number of active threads in pool")
                .tags(tags)
                .register(meterRegistry);

        // Pool size
        Gauge.builder(metricNamePrefix + ".thread_pool_size", () -> poolSize)
                .description("Total thread pool size")
                .tags(tags)
                .register(meterRegistry);

        // Queue size
        Gauge.builder(metricNamePrefix + ".thread_pool_queue_size", () -> queueSize)
                .description("Thread pool queue size")
                .tags(tags)
                .register(meterRegistry);

        cardinalityEnforcer.recordMetric(metricNamePrefix + ".thread_pool", labels);
    }

    /**
     * Record retry attempt.
     * 
     * @param operation Operation being retried
     * @param attemptNumber Retry attempt number
     * @param success Whether retry was successful
     */
    public void recordRetryAttempt(String operation, int attemptNumber, boolean success) {
        String metricName = metricNamePrefix + ".retry_attempts_total";
        String[] labels = {
                "operation", operation,
                "attempt", String.valueOf(attemptNumber),
                MetricLabels.Common.STATUS, success ? "success" : "failure"
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            return;
        }

        Tags tags = createTags(labels);
        Counter.builder(metricName)
                .description("Total retry attempts")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    // ==================== Helper Methods ====================

    private Tags createTags(String... labels) {
        Tags tags = Tags.of(
                MetricLabels.Common.SERVICE, config.getServiceName(),
                MetricLabels.Common.REGION, config.getRegion(),
                MetricLabels.Common.ENVIRONMENT, config.getEnvironment()
        );

        for (int i = 0; i < labels.length - 1; i += 2) {
            tags = tags.and(labels[i], labels[i + 1]);
        }

        return tags;
    }
}
