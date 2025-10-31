package com.fraudswitch.fraudrouter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Fraud Check Response DTO
 * Returned to client with fraud decision and metadata
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudCheckResponse {
    
    @JsonProperty("transaction_id")
    private String transactionId;
    
    @JsonProperty("fraud_decision")
    private FraudDecision fraudDecision; // APPROVE, DECLINE, REVIEW
    
    @JsonProperty("fraud_score")
    private Double fraudScore; // 0.0 - 1.0
    
    @JsonProperty("provider_used")
    private String providerUsed; // Ravelin, Signifyd
    
    @JsonProperty("routing_path")
    private String routingPath; // fraud_sight, guaranteed_payment
    
    @JsonProperty("processing_mode")
    private String processingMode; // sync, async
    
    @JsonProperty("response_time_ms")
    private Long responseTimeMs;
    
    @JsonProperty("reason_codes")
    private String[] reasonCodes;
    
    @JsonProperty("risk_flags")
    private String[] riskFlags;
    
    @Builder.Default
    private Instant timestamp = Instant.now();
    
    @JsonProperty("metadata")
    private ResponseMetadata metadata;
    
    public enum FraudDecision {
        APPROVE,
        DECLINE,
        REVIEW,
        ERROR
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseMetadata {
        @JsonProperty("bin_country")
        private String binCountry;
        
        @JsonProperty("card_type")
        private String cardType;
        
        @JsonProperty("rules_evaluated")
        private Integer rulesEvaluated;
        
        @JsonProperty("parallel_calls_made")
        private Integer parallelCallsMade;
        
        @JsonProperty("provider_latency_ms")
        private Long providerLatencyMs;
    }
}
