package com.fraudswitch.metrics.common;

import com.fraudswitch.metrics.cardinality.CardinalityEnforcer;
import com.fraudswitch.metrics.config.MetricsConfigurationProperties;
import com.fraudswitch.metrics.core.MetricLabels;
import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Common request metrics for RED (Rate, Errors, Duration) pattern.
 * 
 * <p>Provides standard metrics for all services:
 * - Request rate (RPS)
 * - Error rate
 * - Request duration (latency)
 * 
 * <p>Thread-safe and optimized for high-throughput scenarios.
 * 
 * @version 1.0.0
 * @since 2025-10-14
 */
@Slf4j
public class RequestMetrics {

    private final MeterRegistry meterRegistry;
    private final CardinalityEnforcer cardinalityEnforcer;
    private final MetricsConfigurationProperties config;
    private final String metricNamePrefix;

    // Pre-registered meters for performance
    private final Counter requestCounter;
    private final Counter errorCounter;
    private final Timer requestTimer;

    public RequestMetrics(
            MeterRegistry meterRegistry,
            CardinalityEnforcer cardinalityEnforcer,
            MetricsConfigurationProperties config,
            String metricNamePrefix) {
        
        this.meterRegistry = meterRegistry;
        this.cardinalityEnforcer = cardinalityEnforcer;
        this.config = config;
        this.metricNamePrefix = metricNamePrefix;

        // Pre-register base counters
        this.requestCounter = Counter.builder(metricNamePrefix + ".requests_total")
                .description("Total number of requests")
                .tags(getCommonTags())
                .register(meterRegistry);

        this.errorCounter = Counter.builder(metricNamePrefix + ".errors_total")
                .description("Total number of errors")
                .tags(getCommonTags())
                .register(meterRegistry);

        // Pre-register timer with service-specific buckets
        double[] buckets = config.getHistogram().getBucketsForService(config.getServiceName());
        this.requestTimer = Timer.builder(metricNamePrefix + ".request_duration_ms")
                .description("Request duration in milliseconds")
                .tags(getCommonTags())
                .publishPercentiles(0.50, 0.90, 0.95, 0.99)
                .publishPercentileHistogram()
                .serviceLevelObjectives(convertBucketsToSLOs(buckets))
                .register(meterRegistry);

        log.debug("RequestMetrics initialized for prefix: {}", metricNamePrefix);
    }

    /**
     * Record a successful request with duration.
     * 
     * @param durationMs Request duration in milliseconds
     * @param labels Additional labels as key-value pairs
     */
    public void recordRequest(long durationMs, String... labels) {
        String metricName = metricNamePrefix + ".requests_total";
        
        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            log.debug("Dropped metric due to cardinality limits: {}", metricName);
            return;
        }

        // Record request count
        requestCounter.increment();
        
        // Record duration
        if (shouldSampleHistogram()) {
            Tags tags = createTags(labels);
            requestTimer.record(durationMs, TimeUnit.MILLISECONDS);
            Timer.builder(metricNamePrefix + ".request_duration_ms")
                    .tags(tags)
                    .register(meterRegistry)
                    .record(durationMs, TimeUnit.MILLISECONDS);
        }

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    /**
     * Record a failed request with error details.
     * 
     * @param durationMs Request duration in milliseconds
     * @param errorType Type of error (e.g., TimeoutException, ValidationError)
     * @param labels Additional labels as key-value pairs
     */
    public void recordError(long durationMs, String errorType, String... labels) {
        String metricName = metricNamePrefix + ".errors_total";
        
        // Add error_type to labels
        String[] errorLabels = appendLabels(labels, MetricLabels.Common.ERROR_TYPE, errorType);
        
        if (!cardinalityEnforcer.canRecordMetric(metricName, errorLabels)) {
            log.debug("Dropped error metric due to cardinality limits: {}", metricName);
            return;
        }

        // Record error count
        errorCounter.increment();
        Tags tags = createTags(errorLabels);
        Counter.builder(metricNamePrefix + ".errors_total")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        // Still record duration for error requests
        if (shouldSampleHistogram()) {
            requestTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }

        cardinalityEnforcer.recordMetric(metricName, errorLabels);
    }

    /**
     * Record request with outcome (success/failure).
     * 
     * @param durationMs Request duration in milliseconds
     * @param outcome Request outcome (success, failure, timeout)
     * @param labels Additional labels as key-value pairs
     */
    public void recordWithOutcome(long durationMs, String outcome, String... labels) {
        String[] outcomeLabels = appendLabels(labels, MetricLabels.Common.OUTCOME, outcome);
        
        if ("success".equalsIgnoreCase(outcome)) {
            recordRequest(durationMs, outcomeLabels);
        } else {
            recordError(durationMs, outcome, labels);
        }
    }

    /**
     * Create a timer sample for manual timing.
     * Use with try-with-resources or manually stop.
     * 
     * <pre>
     * Timer.Sample sample = requestMetrics.startTimer();
     * try {
     *     // ... do work ...
     *     sample.stop(requestMetrics.getRequestTimer());
     * } catch (Exception e) {
     *     requestMetrics.recordError(sample.stop(requestMetrics.getRequestTimer()), e.getClass().getSimpleName());
     * }
     * </pre>
     */
    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * Get the request timer for manual timing.
     */
    public Timer getRequestTimer() {
        return requestTimer;
    }

    /**
     * Record throughput (requests per second).
     */
    public void recordThroughput(double rps, String... labels) {
        String metricName = metricNamePrefix + ".throughput_rps";
        
        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            return;
        }

        Tags tags = createTags(labels);
        Gauge.builder(metricName, () -> rps)
                .description("Current throughput in requests per second")
                .tags(tags)
                .register(meterRegistry);

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    private Tags createTags(String... labels) {
        Tags tags = Tags.of(getCommonTags());
        
        // Add custom labels
        for (int i = 0; i < labels.length - 1; i += 2) {
            tags = tags.and(labels[i], labels[i + 1]);
        }
        
        return tags;
    }

    private Tags getCommonTags() {
        Tags tags = Tags.of(
                MetricLabels.Common.SERVICE, config.getServiceName(),
                MetricLabels.Common.REGION, config.getRegion(),
                MetricLabels.Common.ENVIRONMENT, config.getEnvironment()
        );

        // Add configured common labels
        config.getCommonLabels().forEach((key, value) -> 
            tags.and(key, value)
        );

        return tags;
    }

    private String[] appendLabels(String[] existingLabels, String key, String value) {
        String[] newLabels = new String[existingLabels.length + 2];
        System.arraycopy(existingLabels, 0, newLabels, 0, existingLabels.length);
        newLabels[existingLabels.length] = key;
        newLabels[existingLabels.length + 1] = value;
        return newLabels;
    }

    private boolean shouldSampleHistogram() {
        if (!config.getSampling().isEnabled()) {
            return true;
        }
        return Math.random() < config.getSampling().getHistogramSampleRate();
    }

    private Duration[] convertBucketsToSLOs(double[] buckets) {
        Duration[] slos = new Duration[buckets.length];
        for (int i = 0; i < buckets.length; i++) {
            slos[i] = Duration.ofMillis((long) buckets[i]);
        }
        return slos;
    }
}
