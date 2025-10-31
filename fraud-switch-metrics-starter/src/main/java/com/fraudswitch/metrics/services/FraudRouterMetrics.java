package com.fraudswitch.metrics.services;

import com.fraudswitch.metrics.cardinality.CardinalityEnforcer;
import com.fraudswitch.metrics.common.KafkaMetrics;
import com.fraudswitch.metrics.common.RequestMetrics;
import com.fraudswitch.metrics.config.MetricsConfigurationProperties;
import com.fraudswitch.metrics.core.MetricLabels;
import com.fraudswitch.metrics.core.MetricNames;
import io.micrometer.core.instrument.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * Service-specific metrics for Fraud Router.
 * 
 * <p>Provides both common RED metrics and business-specific metrics:
 * - Routing decisions
 * - Parallel execution metrics
 * - Fallback decisions
 * - PAN queue publishing
 * 
 * @version 1.0.0
 * @since 2025-10-14
 */
@Slf4j
@Getter
public class FraudRouterMetrics {

    private final RequestMetrics requestMetrics;
    private final KafkaMetrics kafkaMetrics;
    private final MeterRegistry meterRegistry;
    private final CardinalityEnforcer cardinalityEnforcer;
    private final MetricsConfigurationProperties config;

    public FraudRouterMetrics(
            MeterRegistry meterRegistry,
            CardinalityEnforcer cardinalityEnforcer,
            MetricsConfigurationProperties config) {
        
        this.meterRegistry = meterRegistry;
        this.cardinalityEnforcer = cardinalityEnforcer;
        this.config = config;

        // Initialize common metrics
        this.requestMetrics = new RequestMetrics(
                meterRegistry, 
                cardinalityEnforcer, 
                config, 
                "fraud_switch.fraud_router"
        );

        this.kafkaMetrics = new KafkaMetrics(
                meterRegistry,
                cardinalityEnforcer,
                config,
                "fraud_switch.fraud_router"
        );

        log.info("FraudRouterMetrics initialized for region: {}", config.getRegion());
    }

    // ==================== Routing Decision Metrics ====================

    /**
     * Record a routing decision made by the router.
     * 
     * @param eventType Event type (auth, capture, refund)
     * @param gateway Payment gateway
     * @param product Product type
     * @param provider Routed to provider (Ravelin/Signifyd)
     * @param strategy Routing strategy used
     * @param durationMs Time taken to make decision
     */
    public void recordRoutingDecision(String eventType, String gateway, String product, 
                                      String provider, String strategy, long durationMs) {
        String metricName = MetricNames.FraudRouter.ROUTING_DECISIONS_TOTAL;
        String[] labels = {
                MetricLabels.Request.EVENT_TYPE, eventType,
                MetricLabels.Request.GATEWAY, gateway,
                MetricLabels.Request.PRODUCT, product,
                MetricLabels.Provider.PROVIDER, provider,
                MetricLabels.Decision.ROUTING_STRATEGY, strategy
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            log.debug("Dropped routing decision metric due to cardinality limits");
            return;
        }

        Tags tags = createTags(labels);

        // Record decision count
        Counter.builder(metricName)
                .description("Total routing decisions made")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        // Record routing duration
        Timer.builder(MetricNames.FraudRouter.ROUTING_DURATION_MS)
                .description("Time taken to make routing decision")
                .tags(tags)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    /**
     * Record a fallback decision (when primary provider unavailable).
     * 
     * @param primaryProvider Primary provider that was unavailable
     * @param fallbackProvider Fallback provider used
     * @param reason Reason for fallback
     */
    public void recordFallbackDecision(String primaryProvider, String fallbackProvider, String reason) {
        String metricName = MetricNames.FraudRouter.FALLBACK_DECISIONS_TOTAL;
        String[] labels = {
                "primary_provider", primaryProvider,
                "fallback_provider", fallbackProvider,
                "reason", reason
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            return;
        }

        Tags tags = createTags(labels);
        Counter.builder(metricName)
                .description("Total fallback decisions made")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    // ==================== PAN Queue Metrics ====================

    /**
     * Record PAN published to tokenization queue.
     * 
     * @param success Whether publish was successful
     * @param durationMs Time taken to publish
     */
    public void recordPanQueuePublish(boolean success, long durationMs) {
        recordPanQueuePublish(success, durationMs, null);
    }

    /**
     * Record PAN published to tokenization queue with error details.
     * 
     * @param success Whether publish was successful
     * @param durationMs Time taken to publish
     * @param errorType Type of error if failed
     */
    public void recordPanQueuePublish(boolean success, long durationMs, String errorType) {
        String metricName = success ? 
                MetricNames.FraudRouter.PAN_QUEUE_PUBLISH_TOTAL :
                MetricNames.FraudRouter.PAN_QUEUE_PUBLISH_ERRORS_TOTAL;

        String[] labels = {
                MetricLabels.Common.STATUS, success ? "success" : "error"
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            return;
        }

        Tags tags = createTags(labels);
        Counter.builder(metricName)
                .description(success ? "Total PANs published to queue" : "Total PAN queue publish errors")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        if (!success && errorType != null) {
            tags = tags.and(MetricLabels.Common.ERROR_TYPE, errorType);
            Counter.builder(MetricNames.FraudRouter.PAN_QUEUE_PUBLISH_ERRORS_TOTAL)
                    .tags(tags)
                    .register(meterRegistry)
                    .increment();
        }

        cardinalityEnforcer.recordMetric(metricName, labels);

        // Also use KafkaMetrics for consistency
        kafkaMetrics.recordPublish(
                MetricLabels.Kafka.TOPIC_PAN_QUEUE,
                0, // partition
                durationMs,
                success,
                errorType
        );
    }

    // ==================== Parallel Execution Metrics ====================

    /**
     * Record parallel call execution (Boarding + Rules + BIN Lookup).
     * 
     * @param callType Type of call (boarding, rules, bin_lookup)
     * @param success Whether call was successful
     * @param durationMs Time taken for call
     */
    public void recordParallelCall(String callType, boolean success, long durationMs) {
        recordParallelCall(callType, success, durationMs, null);
    }

    /**
     * Record parallel call execution with error details.
     * 
     * @param callType Type of call (boarding, rules, bin_lookup)
     * @param success Whether call was successful
     * @param durationMs Time taken for call
     * @param errorType Type of error if failed
     */
    public void recordParallelCall(String callType, boolean success, long durationMs, String errorType) {
        String metricName = MetricNames.FraudRouter.PARALLEL_CALLS_TOTAL;
        String[] labels = {
                "call_type", callType,
                MetricLabels.Common.STATUS, success ? "success" : "error"
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            return;
        }

        Tags tags = createTags(labels);

        // Record call count
        Counter.builder(metricName)
                .description("Total parallel calls executed")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        // Record call duration
        Timer.builder(MetricNames.FraudRouter.PARALLEL_CALL_DURATION_MS)
                .description("Time taken for parallel call")
                .tags(tags)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        // Record error if failed
        if (!success) {
            String errorMetric = MetricNames.FraudRouter.PARALLEL_CALL_ERRORS_TOTAL;
            Tags errorTags = errorType != null ? 
                    tags.and(MetricLabels.Common.ERROR_TYPE, errorType) : tags;
            
            Counter.builder(errorMetric)
                    .description("Total parallel call errors")
                    .tags(errorTags)
                    .register(meterRegistry)
                    .increment();
        }

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    /**
     * Record aggregate parallel execution metrics.
     * 
     * @param totalDurationMs Total time for all parallel calls
     * @param successCount Number of successful calls
     * @param failureCount Number of failed calls
     */
    public void recordParallelExecution(long totalDurationMs, int successCount, int failureCount) {
        String metricName = "fraud_switch.fraud_router.parallel_execution_total";
        String[] labels = {
                "total_calls", String.valueOf(successCount + failureCount),
                "success_count", String.valueOf(successCount),
                "failure_count", String.valueOf(failureCount)
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            return;
        }

        Tags tags = createTags(labels);
        
        // Record execution
        Counter.builder(metricName)
                .description("Total parallel executions completed")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        // Record total duration
        Timer.builder("fraud_switch.fraud_router.parallel_execution_duration_ms")
                .description("Total time for parallel execution")
                .tags(tags)
                .register(meterRegistry)
                .record(totalDurationMs, TimeUnit.MILLISECONDS);

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
