package com.fraudswitch.fraudrouter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Kafka Event DTOs for async processing and analytics
 */
public class KafkaEvents {
    
    /**
     * Fraud Event - Published to fraud.events topic
     * Used for real-time fraud monitoring and analytics
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FraudEvent {
        @JsonProperty("event_id")
        private String eventId;
        
        @JsonProperty("transaction_id")
        private String transactionId;
        
        @JsonProperty("event_type")
        private String eventType; // fraud_check_completed, fraud_detected
        
        @JsonProperty("merchant_id")
        private String merchantId;
        
        @JsonProperty("fraud_decision")
        private String fraudDecision;
        
        @JsonProperty("fraud_score")
        private Double fraudScore;
        
        @JsonProperty("provider")
        private String provider;
        
        private BigDecimal amount;
        private String currency;
        
        @JsonProperty("processing_time_ms")
        private Long processingTimeMs;
        
        @Builder.Default
        private Instant timestamp = Instant.now();
    }
    
    /**
     * Async Enrichment Request - Published to async.enrichment.requests
     * For non-blocking fraud scoring enrichment
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AsyncEnrichmentRequest {
        @JsonProperty("request_id")
        private String requestId;
        
        @JsonProperty("transaction_id")
        private String transactionId;
        
        @JsonProperty("enrichment_type")
        private String enrichmentType; // device_fingerprint, velocity_check, geo_location
        
        @JsonProperty("provider")
        private String provider;
        
        @JsonProperty("priority")
        private String priority; // high, normal, low
        
        private String payload;
        
        @Builder.Default
        private Instant timestamp = Instant.now();
    }
    
    /**
     * Transaction Record - Published to fs.transactions
     * For analytics pipeline (Kafka Connect -> S3 -> CDP -> Snowflake)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionRecord {
        @JsonProperty("transaction_id")
        private String transactionId;
        
        @JsonProperty("merchant_id")
        private String merchantId;
        
        private BigDecimal amount;
        private String currency;
        
        @JsonProperty("fraud_decision")
        private String fraudDecision;
        
        @JsonProperty("fraud_score")
        private Double fraudScore;
        
        @JsonProperty("provider")
        private String provider;
        
        @JsonProperty("routing_path")
        private String routingPath;
        
        @JsonProperty("card_bin")
        private String cardBin;
        
        @JsonProperty("bin_country")
        private String binCountry;
        
        @JsonProperty("payment_method")
        private String paymentMethod;
        
        @JsonProperty("ip_country")
        private String ipCountry;
        
        @JsonProperty("processing_time_ms")
        private Long processingTimeMs;
        
        @JsonProperty("created_at")
        @Builder.Default
        private Instant createdAt = Instant.now();
    }
}
