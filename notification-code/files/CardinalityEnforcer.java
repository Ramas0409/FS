package com.fraudswitch.metrics.cardinality;

import com.fraudswitch.metrics.config.MetricsConfigurationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime cardinality enforcement to prevent metric explosion.
 * 
 * <p>Tracks unique label combinations per metric and enforces configured limits.
 * When limits are exceeded, takes action based on configuration:
 * - LOG: Log warning and continue
 * - DROP: Drop the metric
 * - CIRCUIT_BREAK: Open circuit breaker temporarily
 * 
 * <p>Thread-safe implementation using ConcurrentHashMap.
 * 
 * @version 1.0.0
 * @since 2025-10-14
 */
@Slf4j
@Component
public class CardinalityEnforcer {

    private final MetricsConfigurationProperties config;
    private final Map<String, MetricCardinalityTracker> metricTrackers = new ConcurrentHashMap<>();
    private final AtomicLong lastCheckTime = new AtomicLong(System.currentTimeMillis());
    private final CircuitBreakerState circuitBreaker = new CircuitBreakerState();

    public CardinalityEnforcer(MetricsConfigurationProperties config) {
        this.config = config;
        log.info("CardinalityEnforcer initialized with config: maxLabelsPerMetric={}, maxValuesPerLabel={}, action={}",
                config.getCardinality().getMaxLabelsPerMetric(),
                config.getCardinality().getMaxValuesPerLabel(),
                config.getCardinality().getAction());
    }

    /**
     * Check if metric with given labels can be recorded.
     * 
     * @param metricName The metric name
     * @param labels The label key-value pairs
     * @return true if metric should be recorded, false if should be dropped
     */
    public boolean canRecordMetric(String metricName, String... labels) {
        if (!config.getCardinality().isEnforcementEnabled()) {
            return true;
        }

        // Check circuit breaker
        if (circuitBreaker.isOpen()) {
            return false;
        }

        // Periodic check for cardinality violations
        performPeriodicCheck();

        MetricCardinalityTracker tracker = metricTrackers.computeIfAbsent(
                metricName, 
                k -> new MetricCardinalityTracker(metricName)
        );

        return tracker.canAddLabels(labels);
    }

    /**
     * Record that a metric was successfully recorded.
     */
    public void recordMetric(String metricName, String... labels) {
        if (!config.getCardinality().isEnforcementEnabled()) {
            return;
        }

        MetricCardinalityTracker tracker = metricTrackers.get(metricName);
        if (tracker != null) {
            tracker.recordLabels(labels);
        }
    }

    /**
     * Get current cardinality statistics.
     */
    public CardinalityStats getStats() {
        CardinalityStats stats = new CardinalityStats();
        stats.totalMetrics = metricTrackers.size();
        
        metricTrackers.values().forEach(tracker -> {
            stats.totalLabelCombinations += tracker.getLabelCombinationCount();
            stats.maxLabelCombinations = Math.max(stats.maxLabelCombinations, tracker.getLabelCombinationCount());
        });
        
        stats.circuitBreakerState = circuitBreaker.getState();
        stats.violationsCount = circuitBreaker.getViolationCount();
        
        return stats;
    }

    /**
     * Reset all cardinality tracking (for testing).
     */
    public void reset() {
        metricTrackers.clear();
        circuitBreaker.reset();
        lastCheckTime.set(System.currentTimeMillis());
    }

    private void performPeriodicCheck() {
        long now = System.currentTimeMillis();
        long lastCheck = lastCheckTime.get();
        long checkIntervalMs = config.getCardinality().getCheckInterval().toMillis();

        if (now - lastCheck > checkIntervalMs) {
            if (lastCheckTime.compareAndSet(lastCheck, now)) {
                checkCardinalityLimits();
            }
        }
    }

    private void checkCardinalityLimits() {
        int maxLabelsPerMetric = config.getCardinality().getMaxLabelsPerMetric();
        
        metricTrackers.forEach((metricName, tracker) -> {
            int combinationCount = tracker.getLabelCombinationCount();
            
            if (combinationCount > maxLabelsPerMetric) {
                handleCardinalityViolation(metricName, combinationCount, maxLabelsPerMetric);
            }
        });
    }

    private void handleCardinalityViolation(String metricName, int actual, int limit) {
        MetricsConfigurationProperties.CardinalityAction action = config.getCardinality().getAction();
        
        log.warn("Cardinality violation detected for metric '{}': {} combinations exceeds limit of {}. Action: {}",
                metricName, actual, limit, action);

        switch (action) {
            case LOG:
                // Just log, already done above
                break;
                
            case DROP:
                // Remove the metric tracker to stop recording
                metricTrackers.remove(metricName);
                log.warn("Dropped metric '{}' due to cardinality violation", metricName);
                break;
                
            case CIRCUIT_BREAK:
                circuitBreaker.recordViolation();
                if (circuitBreaker.shouldOpen()) {
                    circuitBreaker.open();
                    log.error("Circuit breaker OPENED due to cardinality violations");
                }
                break;
        }
    }

    /**
     * Tracks cardinality for a single metric.
     */
    private class MetricCardinalityTracker {
        private final String metricName;
        private final Set<String> labelCombinations = ConcurrentHashMap.newKeySet();
        private final Map<String, Set<String>> labelValues = new ConcurrentHashMap<>();
        private final AtomicInteger droppedCount = new AtomicInteger(0);

        MetricCardinalityTracker(String metricName) {
            this.metricName = metricName;
        }

        boolean canAddLabels(String... labels) {
            String combination = createLabelCombination(labels);
            
            // If we've seen this combination before, allow it
            if (labelCombinations.contains(combination)) {
                return true;
            }

            // Check if adding this would exceed limits
            int maxCombinations = config.getCardinality().getMaxLabelsPerMetric();
            if (labelCombinations.size() >= maxCombinations) {
                droppedCount.incrementAndGet();
                return false;
            }

            // Check individual label value limits
            Map<String, String> labelMap = parseLabelPairs(labels);
            for (Map.Entry<String, String> entry : labelMap.entrySet()) {
                Set<String> values = labelValues.computeIfAbsent(entry.getKey(), k -> ConcurrentHashMap.newKeySet());
                if (!values.contains(entry.getValue()) && 
                    values.size() >= config.getCardinality().getMaxValuesPerLabel()) {
                    droppedCount.incrementAndGet();
                    return false;
                }
            }

            return true;
        }

        void recordLabels(String... labels) {
            String combination = createLabelCombination(labels);
            labelCombinations.add(combination);

            Map<String, String> labelMap = parseLabelPairs(labels);
            labelMap.forEach((key, value) -> 
                labelValues.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(value)
            );
        }

        int getLabelCombinationCount() {
            return labelCombinations.size();
        }

        private String createLabelCombination(String... labels) {
            return String.join("|", labels);
        }

        private Map<String, String> parseLabelPairs(String... labels) {
            Map<String, String> map = new HashMap<>();
            for (int i = 0; i < labels.length - 1; i += 2) {
                map.put(labels[i], labels[i + 1]);
            }
            return map;
        }
    }

    /**
     * Circuit breaker state management.
     */
    private class CircuitBreakerState {
        private volatile State state = State.CLOSED;
        private final AtomicInteger violationCount = new AtomicInteger(0);
        private volatile Instant openedAt;
        private volatile Instant halfOpenedAt;

        enum State {
            CLOSED, OPEN, HALF_OPEN
        }

        boolean isOpen() {
            if (!config.getCircuitBreaker().isEnabled()) {
                return false;
            }

            if (state == State.OPEN) {
                long openDurationMs = config.getCircuitBreaker().getOpenDuration().toMillis();
                if (System.currentTimeMillis() - openedAt.toEpochMilli() > openDurationMs) {
                    transitionToHalfOpen();
                    return false;
                }
                return true;
            }

            if (state == State.HALF_OPEN) {
                long halfOpenDurationMs = config.getCircuitBreaker().getHalfOpenDuration().toMillis();
                if (System.currentTimeMillis() - halfOpenedAt.toEpochMilli() > halfOpenDurationMs) {
                    close();
                    return false;
                }
            }

            return false;
        }

        void recordViolation() {
            violationCount.incrementAndGet();
        }

        boolean shouldOpen() {
            return violationCount.get() >= config.getCircuitBreaker().getFailureThreshold();
        }

        void open() {
            state = State.OPEN;
            openedAt = Instant.now();
            log.error("Circuit breaker OPENED at {}", openedAt);
        }

        void transitionToHalfOpen() {
            state = State.HALF_OPEN;
            halfOpenedAt = Instant.now();
            violationCount.set(0);
            log.info("Circuit breaker transitioned to HALF_OPEN at {}", halfOpenedAt);
        }

        void close() {
            state = State.CLOSED;
            violationCount.set(0);
            log.info("Circuit breaker CLOSED");
        }

        State getState() {
            return state;
        }

        int getViolationCount() {
            return violationCount.get();
        }

        void reset() {
            state = State.CLOSED;
            violationCount.set(0);
            openedAt = null;
            halfOpenedAt = null;
        }
    }

    /**
     * Cardinality statistics.
     */
    public static class CardinalityStats {
        public int totalMetrics;
        public int totalLabelCombinations;
        public int maxLabelCombinations;
        public CircuitBreakerState.State circuitBreakerState;
        public int violationsCount;

        @Override
        public String toString() {
            return String.format("CardinalityStats{totalMetrics=%d, totalLabelCombinations=%d, " +
                    "maxLabelCombinations=%d, circuitBreakerState=%s, violationsCount=%d}",
                    totalMetrics, totalLabelCombinations, maxLabelCombinations, 
                    circuitBreakerState, violationsCount);
        }
    }
}
