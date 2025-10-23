package com.fraudswitch.fraudrouter.service;

import com.fraudswitch.fraudrouter.dto.KafkaEvents;
import com.fraudswitch.metrics.common.KafkaMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Kafka Producer Service
 * Publishes events to Kafka topics with metrics tracking
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaProducerService {
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaMetrics kafkaMetrics;
    
    @Value("${kafka.topics.fraud-events}")
    private String fraudEventsTopic;
    
    @Value("${kafka.topics.async-enrichment}")
    private String asyncEnrichmentTopic;
    
    @Value("${kafka.topics.fraud-transactions}")
    private String transactionsTopic;
    
    /**
     * Publish fraud event for real-time monitoring
     */
    public void publishFraudEvent(KafkaEvents.FraudEvent event) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.debug("Publishing fraud event: transactionId={}, decision={}", 
                    event.getTransactionId(), event.getFraudDecision());
            
            CompletableFuture<SendResult<String, Object>> future = 
                    kafkaTemplate.send(fraudEventsTopic, event.getTransactionId(), event);
            
            future.whenComplete((result, ex) -> {
                long duration = System.currentTimeMillis() - startTime;
                
                if (ex == null) {
                    kafkaMetrics.recordPublish(
                            fraudEventsTopic,
                            "fraud_event",
                            "success",
                            duration
                    );
                    log.debug("Fraud event published successfully: partition={}, offset={}", 
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                } else {
                    kafkaMetrics.recordPublish(
                            fraudEventsTopic,
                            "fraud_event",
                            "failure",
                            duration
                    );
                    log.error("Failed to publish fraud event: transactionId={}", 
                            event.getTransactionId(), ex);
                }
            });
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            kafkaMetrics.recordPublish(fraudEventsTopic, "fraud_event", "error", duration);
            log.error("Error publishing fraud event: transactionId={}", 
                    event.getTransactionId(), e);
            throw new RuntimeException("Failed to publish fraud event", e);
        }
    }
    
    /**
     * Publish async enrichment request for non-blocking processing
     */
    public void publishAsyncEnrichment(KafkaEvents.AsyncEnrichmentRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.debug("Publishing async enrichment: requestId={}, type={}", 
                    request.getRequestId(), request.getEnrichmentType());
            
            CompletableFuture<SendResult<String, Object>> future = 
                    kafkaTemplate.send(asyncEnrichmentTopic, request.getRequestId(), request);
            
            future.whenComplete((result, ex) -> {
                long duration = System.currentTimeMillis() - startTime;
                
                if (ex == null) {
                    kafkaMetrics.recordPublish(
                            asyncEnrichmentTopic,
                            request.getEnrichmentType(),
                            "success",
                            duration
                    );
                } else {
                    kafkaMetrics.recordPublish(
                            asyncEnrichmentTopic,
                            request.getEnrichmentType(),
                            "failure",
                            duration
                    );
                    log.error("Failed to publish async enrichment: requestId={}", 
                            request.getRequestId(), ex);
                }
            });
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            kafkaMetrics.recordPublish(asyncEnrichmentTopic, "enrichment", "error", duration);
            log.error("Error publishing async enrichment: requestId={}", 
                    request.getRequestId(), e);
        }
    }
    
    /**
     * Publish transaction record for analytics pipeline
     */
    public void publishTransactionRecord(KafkaEvents.TransactionRecord record) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.debug("Publishing transaction record: transactionId={}", 
                    record.getTransactionId());
            
            CompletableFuture<SendResult<String, Object>> future = 
                    kafkaTemplate.send(transactionsTopic, record.getTransactionId(), record);
            
            future.whenComplete((result, ex) -> {
                long duration = System.currentTimeMillis() - startTime;
                
                if (ex == null) {
                    kafkaMetrics.recordPublish(
                            transactionsTopic,
                            "transaction_record",
                            "success",
                            duration
                    );
                    log.debug("Transaction record published: partition={}, offset={}", 
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                } else {
                    kafkaMetrics.recordPublish(
                            transactionsTopic,
                            "transaction_record",
                            "failure",
                            duration
                    );
                    log.error("Failed to publish transaction record: transactionId={}", 
                            record.getTransactionId(), ex);
                }
            });
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            kafkaMetrics.recordPublish(transactionsTopic, "transaction_record", "error", duration);
            log.error("Error publishing transaction record: transactionId={}", 
                    record.getTransactionId(), e);
        }
    }
    
    /**
     * Create a unique event ID
     */
    public String generateEventId() {
        return UUID.randomUUID().toString();
    }
}
