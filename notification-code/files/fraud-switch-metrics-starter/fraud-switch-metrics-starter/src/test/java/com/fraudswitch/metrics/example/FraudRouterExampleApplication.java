package com.fraudswitch.metrics.example;

import com.fraudswitch.metrics.core.MetricLabels;
import com.fraudswitch.metrics.services.FraudRouterMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;

import java.util.Random;

/**
 * Example Spring Boot application demonstrating Fraud Switch Metrics Library usage.
 * 
 * <p>This example shows:
 * - Auto-configuration of metrics beans
 * - Recording request metrics with RED pattern
 * - Recording service-specific business metrics
 * - Error handling with metrics
 * - Parallel execution tracking
 * 
 * <p>Start the application and call endpoints:
 * - POST /api/fraud/screen - Fraud screening with metrics
 * - POST /api/fraud/parallel - Parallel execution example
 * - GET /actuator/prometheus - View Prometheus metrics
 * 
 * @version 1.0.0
 * @since 2025-10-14
 */
@Slf4j
@SpringBootApplication
public class FraudRouterExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudRouterExampleApplication.class, args);
        log.info("Fraud Router Example Application started");
        log.info("View metrics at: http://localhost:8080/actuator/prometheus");
        log.info("Test endpoints:");
        log.info("  POST http://localhost:8080/api/fraud/screen");
        log.info("  POST http://localhost:8080/api/fraud/parallel");
    }

    /**
     * Example REST controller demonstrating metrics usage.
     */
    @RestController
    @RequestMapping("/api/fraud")
    @RequiredArgsConstructor
    static class FraudController {

        private final FraudRouterMetrics metrics;
        private final Random random = new Random();

        /**
         * Example fraud screening endpoint with comprehensive metrics.
         * 
         * <p>Demonstrates:
         * - Timer sample for duration tracking
         * - Business metric recording (routing decision)
         * - Error metrics on exceptions
         * - PAN queue publishing metrics
         */
        @PostMapping("/screen")
        public FraudDecision screen(@RequestBody FraudRequest request) {
            Timer.Sample sample = metrics.getRequestMetrics().startTimer();
            
            try {
                log.info("Processing fraud screening request: eventType={}, gateway={}, product={}",
                        request.getEventType(), request.getGateway(), request.getProduct());

                // Simulate fraud screening logic
                FraudDecision decision = performFraudScreening(request);
                
                // Record successful request duration
                long duration = sample.stop(metrics.getRequestMetrics().getRequestTimer());
                
                // Record routing decision (business metric)
                metrics.recordRoutingDecision(
                        request.getEventType(),
                        request.getGateway(),
                        request.getProduct(),
                        decision.getProvider(),
                        decision.getStrategy(),
                        duration
                );
                
                // Record PAN queue publish (if applicable)
                if (request.isTokenizationRequired()) {
                    long panPublishStart = System.currentTimeMillis();
                    boolean published = publishToPanQueue(request.getPan());
                    long panPublishDuration = System.currentTimeMillis() - panPublishStart;
                    metrics.recordPanQueuePublish(published, panPublishDuration);
                }
                
                log.info("Fraud screening completed: decision={}, provider={}, duration={}ms",
                        decision.getDecision(), decision.getProvider(), duration);
                
                return decision;
                
            } catch (TimeoutException e) {
                // Record error with specific error type
                long duration = sample.stop(metrics.getRequestMetrics().getRequestTimer());
                metrics.getRequestMetrics().recordError(
                        duration,
                        "TimeoutException",
                        MetricLabels.Request.EVENT_TYPE, request.getEventType(),
                        MetricLabels.Request.GATEWAY, request.getGateway()
                );
                throw e;
                
            } catch (Exception e) {
                // Record generic error
                long duration = sample.stop(metrics.getRequestMetrics().getRequestTimer());
                metrics.getRequestMetrics().recordError(
                        duration,
                        e.getClass().getSimpleName()
                );
                throw new RuntimeException("Fraud screening failed", e);
            }
        }

        /**
         * Example parallel execution with metrics.
         * 
         * <p>Demonstrates:
         * - Tracking parallel calls (Boarding + Rules + BIN Lookup)
         * - Individual call metrics
         * - Aggregate execution metrics
         */
        @PostMapping("/parallel")
        public ParallelExecutionResult parallel(@RequestBody FraudRequest request) {
            Timer.Sample totalSample = metrics.getRequestMetrics().startTimer();
            
            int successCount = 0;
            int failureCount = 0;
            
            // Parallel call 1: Boarding check
            long boardingStart = System.currentTimeMillis();
            try {
                performBoardingCheck(request);
                long boardingDuration = System.currentTimeMillis() - boardingStart;
                metrics.recordParallelCall("boarding", true, boardingDuration);
                successCount++;
            } catch (Exception e) {
                long boardingDuration = System.currentTimeMillis() - boardingStart;
                metrics.recordParallelCall("boarding", false, boardingDuration, 
                        e.getClass().getSimpleName());
                failureCount++;
            }
            
            // Parallel call 2: Rules evaluation
            long rulesStart = System.currentTimeMillis();
            try {
                evaluateRules(request);
                long rulesDuration = System.currentTimeMillis() - rulesStart;
                metrics.recordParallelCall("rules", true, rulesDuration);
                successCount++;
            } catch (Exception e) {
                long rulesDuration = System.currentTimeMillis() - rulesStart;
                metrics.recordParallelCall("rules", false, rulesDuration,
                        e.getClass().getSimpleName());
                failureCount++;
            }
            
            // Parallel call 3: BIN lookup
            long binStart = System.currentTimeMillis();
            try {
                performBinLookup(request);
                long binDuration = System.currentTimeMillis() - binStart;
                metrics.recordParallelCall("bin_lookup", true, binDuration);
                successCount++;
            } catch (Exception e) {
                long binDuration = System.currentTimeMillis() - binStart;
                metrics.recordParallelCall("bin_lookup", false, binDuration,
                        e.getClass().getSimpleName());
                failureCount++;
            }
            
            // Record aggregate execution metrics
            long totalDuration = totalSample.stop(metrics.getRequestMetrics().getRequestTimer());
            metrics.recordParallelExecution(totalDuration, successCount, failureCount);
            
            return new ParallelExecutionResult(successCount, failureCount, totalDuration);
        }

        /**
         * Example endpoint demonstrating fallback scenarios.
         */
        @PostMapping("/fallback")
        public FraudDecision screenWithFallback(@RequestBody FraudRequest request) {
            String primaryProvider = "Ravelin";
            String fallbackProvider = "Signifyd";
            
            try {
                // Try primary provider
                return callProvider(request, primaryProvider);
            } catch (Exception e) {
                // Record fallback decision
                metrics.recordFallbackDecision(
                        primaryProvider,
                        fallbackProvider,
                        "circuit_open"
                );
                
                // Use fallback provider
                return callProvider(request, fallbackProvider);
            }
        }

        // ==================== Simulated Business Logic ====================

        private FraudDecision performFraudScreening(FraudRequest request) {
            simulateWork(30, 70);
            
            // Simulate random outcomes
            double score = random.nextDouble();
            String decision = score > 0.95 ? "DECLINE" : score > 0.85 ? "REVIEW" : "ACCEPT";
            String provider = random.nextBoolean() ? "Ravelin" : "Signifyd";
            
            return new FraudDecision(decision, provider, "primary", score);
        }

        private void performBoardingCheck(FraudRequest request) {
            simulateWork(10, 20);
            if (random.nextDouble() < 0.05) {
                throw new RuntimeException("Boarding check failed");
            }
        }

        private void evaluateRules(FraudRequest request) {
            simulateWork(5, 15);
            if (random.nextDouble() < 0.03) {
                throw new RuntimeException("Rules evaluation failed");
            }
        }

        private void performBinLookup(FraudRequest request) {
            simulateWork(5, 10);
            if (random.nextDouble() < 0.02) {
                throw new RuntimeException("BIN lookup failed");
            }
        }

        private boolean publishToPanQueue(String pan) {
            simulateWork(15, 30);
            return random.nextDouble() > 0.01; // 99% success rate
        }

        private FraudDecision callProvider(FraudRequest request, String provider) {
            simulateWork(40, 80);
            return new FraudDecision("ACCEPT", provider, "primary", 0.15);
        }

        private void simulateWork(int minMs, int maxMs) {
            try {
                Thread.sleep(minMs + random.nextInt(maxMs - minMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================== DTOs ====================

    record FraudRequest(
            String eventType,
            String gateway,
            String product,
            String pan,
            boolean tokenizationRequired
    ) {}

    record FraudDecision(
            String decision,
            String provider,
            String strategy,
            double confidenceScore
    ) {}

    record ParallelExecutionResult(
            int successCount,
            int failureCount,
            long totalDurationMs
    ) {}

    static class TimeoutException extends RuntimeException {
        public TimeoutException(String message) {
            super(message);
        }
    }
}
