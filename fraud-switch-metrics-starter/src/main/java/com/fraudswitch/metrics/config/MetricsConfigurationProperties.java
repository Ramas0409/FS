package com.fraudswitch.metrics.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for Fraud Switch Metrics Library.
 * 
 * <p>Configurable via application.yml:
 * <pre>
 * fraud-switch:
 *   metrics:
 *     enabled: true
 *     service-name: fraud-router
 *     region: us-ohio-1
 *     cardinality:
 *       enforcement-enabled: true
 *       max-labels-per-metric: 10
 * </pre>
 * 
 * @version 1.0.0
 * @since 2025-10-14
 */
@Data
@Validated
@ConfigurationProperties(prefix = "fraud-switch.metrics")
public class MetricsConfigurationProperties {

    /**
     * Enable/disable metrics collection
     */
    private boolean enabled = true;

    /**
     * Service name (canonical name from architecture doc)
     */
    @NotNull
    private String serviceName;

    /**
     * Deployment region (e.g., us-ohio-1, uk-london-1)
     */
    @NotNull
    private String region;

    /**
     * Environment (e.g., dev, staging, prod)
     */
    @NotNull
    private String environment = "prod";

    /**
     * Common labels to add to all metrics
     */
    private Map<String, String> commonLabels = new HashMap<>();

    /**
     * Cardinality enforcement configuration
     */
    @Valid
    private CardinalityConfig cardinality = new CardinalityConfig();

    /**
     * Histogram configuration
     */
    @Valid
    private HistogramConfig histogram = new HistogramConfig();

    /**
     * Sampling configuration (disabled by default)
     */
    @Valid
    private SamplingConfig sampling = new SamplingConfig();

    /**
     * Circuit breaker configuration for metrics
     */
    @Valid
    private CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();

    @Data
    public static class CardinalityConfig {
        /**
         * Enable runtime cardinality enforcement
         */
        private boolean enforcementEnabled = true;

        /**
         * Maximum number of unique label combinations per metric
         */
        @Min(10)
        @Max(10000)
        private int maxLabelsPerMetric = 1000;

        /**
         * Maximum number of unique values per label
         */
        @Min(5)
        @Max(1000)
        private int maxValuesPerLabel = 100;

        /**
         * Check interval for cardinality validation
         */
        private Duration checkInterval = Duration.ofMinutes(1);

        /**
         * Action when cardinality limit exceeded: LOG, DROP, CIRCUIT_BREAK
         */
        private CardinalityAction action = CardinalityAction.LOG;
    }

    @Data
    public static class HistogramConfig {
        /**
         * Service-specific histogram buckets (in milliseconds)
         */
        private Map<String, double[]> buckets = new HashMap<>();

        /**
         * Default histogram buckets if service not configured
         */
        private double[] defaultBuckets = {10, 25, 50, 75, 100, 150, 200, 300, 500, 1000, 2000};

        /**
         * Get buckets for service or return default
         */
        public double[] getBucketsForService(String serviceName) {
            return buckets.getOrDefault(serviceName, defaultBuckets);
        }
    }

    @Data
    public static class SamplingConfig {
        /**
         * Enable metric sampling (disabled by default)
         */
        private boolean enabled = false;

        /**
         * Sample rate for histogram metrics (0.0 to 1.0)
         */
        @Min(0)
        @Max(1)
        private double histogramSampleRate = 1.0;

        /**
         * Sample rate for high-cardinality metrics (0.0 to 1.0)
         */
        @Min(0)
        @Max(1)
        private double highCardinalitySampleRate = 1.0;
    }

    @Data
    public static class CircuitBreakerConfig {
        /**
         * Enable circuit breaker for cardinality violations
         */
        private boolean enabled = true;

        /**
         * Number of violations before opening circuit
         */
        @Min(1)
        @Max(100)
        private int failureThreshold = 5;

        /**
         * Duration circuit stays open
         */
        private Duration openDuration = Duration.ofMinutes(5);

        /**
         * Duration in half-open state before closing
         */
        private Duration halfOpenDuration = Duration.ofMinutes(1);
    }

    public enum CardinalityAction {
        /**
         * Log warning but continue recording metrics
         */
        LOG,
        
        /**
         * Drop metrics that exceed cardinality limits
         */
        DROP,
        
        /**
         * Open circuit breaker and stop recording metrics temporarily
         */
        CIRCUIT_BREAK
    }
}
