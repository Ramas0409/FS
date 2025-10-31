package com.fraudswitch.fraudrouter.service;

import com.fraudswitch.fraudrouter.dto.FraudCheckRequest;
import com.fraudswitch.fraudrouter.dto.FraudCheckResponse;
import com.fraudswitch.metrics.common.ComponentMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Random;

/**
 * External Fraud Provider Client
 * Makes REST API calls to external fraud providers (Ravelin, Signifyd)
 * with circuit breaker and metrics tracking
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FraudProviderClient {
    
    private final WebClient webClient;
    private final ComponentMetrics componentMetrics;
    private final Random random = new Random();
    
    @Value("${fraud-providers.ravelin.base-url}")
    private String ravelinBaseUrl;
    
    @Value("${fraud-providers.ravelin.timeout-ms}")
    private int ravelinTimeoutMs;
    
    @Value("${fraud-providers.signifyd.base-url}")
    private String signifydBaseUrl;
    
    @Value("${fraud-providers.signifyd.timeout-ms}")
    private int signifydTimeoutMs;
    
    /**
     * Call Ravelin for fraud scoring (FraudSight path)
     * NFR: p99 < 100ms
     */
    public FraudProviderResponse callRavelin(FraudCheckRequest request) {
        long startTime = System.currentTimeMillis();
        String provider = "Ravelin";
        String endpoint = "/v2/fraud-score";
        
        try {
            log.debug("Calling Ravelin: transactionId={}", request.getTransactionId());
            
            // Simulate external API call (replace with actual WebClient call in production)
            FraudProviderResponse response = simulateProviderCall(
                    provider, 
                    request, 
                    50, 120 // Simulate 50-120ms latency
            );
            
            long duration = System.currentTimeMillis() - startTime;
            
            // Record metrics
            componentMetrics.recordExternalCall(
                    "fraud_provider",
                    provider,
                    endpoint,
                    response.isSuccess() ? "200" : "500",
                    duration
            );
            
            log.debug("Ravelin response: score={}, duration={}ms", 
                    response.getFraudScore(), duration);
            
            return response;
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordExternalCall(
                    "fraud_provider",
                    provider,
                    endpoint,
                    "error",
                    duration
            );
            log.error("Ravelin call failed: transactionId={}", request.getTransactionId(), e);
            
            return FraudProviderResponse.builder()
                    .provider(provider)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }
    
    /**
     * Call Signifyd for guaranteed payment (GuaranteedPayment path)
     * NFR: p99 < 350ms
     */
    public FraudProviderResponse callSignifyd(FraudCheckRequest request) {
        long startTime = System.currentTimeMillis();
        String provider = "Signifyd";
        String endpoint = "/v3/guarantees";
        
        try {
            log.debug("Calling Signifyd: transactionId={}", request.getTransactionId());
            
            // Simulate external API call
            FraudProviderResponse response = simulateProviderCall(
                    provider,
                    request,
                    200, 350 // Simulate 200-350ms latency
            );
            
            long duration = System.currentTimeMillis() - startTime;
            
            // Record metrics
            componentMetrics.recordExternalCall(
                    "fraud_provider",
                    provider,
                    endpoint,
                    response.isSuccess() ? "200" : "500",
                    duration
            );
            
            log.debug("Signifyd response: score={}, duration={}ms", 
                    response.getFraudScore(), duration);
            
            return response;
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordExternalCall(
                    "fraud_provider",
                    provider,
                    endpoint,
                    "error",
                    duration
            );
            log.error("Signifyd call failed: transactionId={}", request.getTransactionId(), e);
            
            return FraudProviderResponse.builder()
                    .provider(provider)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }
    
    /**
     * Example of actual WebClient REST call (commented for demo)
     * Uncomment and configure for production use
     */
    @SuppressWarnings("unused")
    private Mono<Map<String, Object>> makeActualProviderCall(
            String baseUrl, 
            String endpoint,
            FraudCheckRequest request,
            int timeoutMs) {
        
        return webClient.post()
                .uri(baseUrl + endpoint)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer YOUR_API_KEY")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(timeoutMs));
    }
    
    /**
     * Simulate provider response for demo purposes
     * In production, replace with actual REST API calls
     */
    private FraudProviderResponse simulateProviderCall(
            String provider, 
            FraudCheckRequest request,
            int minLatencyMs,
            int maxLatencyMs) {
        
        try {
            // Simulate network latency
            int latency = minLatencyMs + random.nextInt(maxLatencyMs - minLatencyMs);
            Thread.sleep(latency);
            
            // Simulate fraud score calculation
            double fraudScore = random.nextDouble();
            
            // Determine decision based on score
            FraudCheckResponse.FraudDecision decision;
            if (fraudScore < 0.3) {
                decision = FraudCheckResponse.FraudDecision.APPROVE;
            } else if (fraudScore < 0.7) {
                decision = FraudCheckResponse.FraudDecision.REVIEW;
            } else {
                decision = FraudCheckResponse.FraudDecision.DECLINE;
            }
            
            return FraudProviderResponse.builder()
                    .provider(provider)
                    .success(true)
                    .fraudScore(fraudScore)
                    .decision(decision)
                    .responseTimeMs((long) latency)
                    .reasonCodes(generateReasonCodes(fraudScore))
                    .build();
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Provider call interrupted", e);
        }
    }
    
    private String[] generateReasonCodes(double score) {
        if (score > 0.7) {
            return new String[]{"HIGH_RISK_IP", "VELOCITY_CHECK_FAILED"};
        } else if (score > 0.3) {
            return new String[]{"REVIEW_REQUIRED"};
        } else {
            return new String[]{"LOW_RISK"};
        }
    }
    
    /**
     * Provider Response DTO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FraudProviderResponse {
        private String provider;
        private boolean success;
        private Double fraudScore;
        private FraudCheckResponse.FraudDecision decision;
        private Long responseTimeMs;
        private String[] reasonCodes;
        private String errorMessage;
    }
}
