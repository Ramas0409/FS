package com.fraudswitch.fraudrouter.service;

import com.fraudswitch.fraudrouter.dto.FraudCheckRequest;
import com.fraudswitch.fraudrouter.dto.FraudCheckResponse;
import com.fraudswitch.fraudrouter.dto.KafkaEvents;
import com.fraudswitch.metrics.common.RequestMetrics;
import com.fraudswitch.metrics.services.FraudRouterMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Fraud Routing Service
 * Core business logic for routing fraud checks with comprehensive metrics
 * 
 * Architecture Flow:
 * 1. Receive request from ETS
 * 2. Parallel calls: Boarding check + Rules evaluation + BIN lookup
 * 3. Route to provider (Ravelin/Signifyd) via adapter
 * 4. Publish events to Kafka
 * 5. Return decision
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FraudRoutingService {
    
    private final FraudProviderClient providerClient;
    private final KafkaProducerService kafkaProducer;
    private final RequestMetrics requestMetrics;
    private final FraudRouterMetrics fraudRouterMetrics;
    
    /**
     * Process fraud check - Synchronous flow
     * NFR: p99 < 100ms for FraudSight, p99 < 350ms for GuaranteedPayment
     */
    public FraudCheckResponse processFraudCheck(FraudCheckRequest request) {
        long overallStartTime = System.currentTimeMillis();
        String endpoint = "/fraud/check";
        
        try {
            log.info("Processing fraud check: transactionId={}, merchantId={}, amount={}", 
                    request.getTransactionId(), request.getMerchantId(), request.getAmount());
            
            // Record request received
            requestMetrics.recordRequest(endpoint, "POST");
            
            // Step 1: Determine routing path based on transaction type and merchant config
            String routingPath = determineRoutingPath(request);
            String provider = selectProvider(request, routingPath);
            
            log.debug("Routing decision: path={}, provider={}", routingPath, provider);
            
            // Record routing decision metric
            fraudRouterMetrics.recordRoutingDecision(
                    request.getTransactionType(),
                    request.getPaymentMethod(),
                    routingPath,
                    provider,
                    "primary",
                    0L // Decision made instantly
            );
            
            // Step 2: Parallel calls (simulated - in production use CompletableFuture.allOf)
            long parallelStartTime = System.currentTimeMillis();
            
            // Boarding check
            boolean boardingPassed = performBoardingCheck(request);
            long boardingDuration = System.currentTimeMillis() - parallelStartTime;
            fraudRouterMetrics.recordParallelCall("boarding", boardingDuration);
            
            // Rules evaluation
            boolean rulesPassed = evaluateRules(request);
            long rulesDuration = System.currentTimeMillis() - parallelStartTime;
            fraudRouterMetrics.recordParallelCall("rules", rulesDuration);
            
            // BIN lookup
            String binCountry = performBinLookup(request.getCardBin());
            long binDuration = System.currentTimeMillis() - parallelStartTime;
            fraudRouterMetrics.recordParallelCall("bin_lookup", binDuration);
            
            log.debug("Parallel checks completed: boarding={}, rules={}, bin={}", 
                    boardingPassed, rulesPassed, binCountry);
            
            // Step 3: Call fraud provider
            FraudProviderClient.FraudProviderResponse providerResponse;
            
            if ("Ravelin".equals(provider)) {
                providerResponse = providerClient.callRavelin(request);
            } else {
                providerResponse = providerClient.callSignifyd(request);
            }
            
            // Step 4: Build response
            FraudCheckResponse response = buildResponse(
                    request,
                    providerResponse,
                    routingPath,
                    binCountry
            );
            
            long totalDuration = System.currentTimeMillis() - overallStartTime;
            response.setResponseTimeMs(totalDuration);
            
            // Step 5: Record business metrics
            fraudRouterMetrics.recordProviderCall(
                    provider,
                    routingPath,
                    providerResponse.isSuccess() ? "success" : "failure",
                    providerResponse.getResponseTimeMs()
            );
            
            fraudRouterMetrics.recordFraudDecision(
                    request.getTransactionType(),
                    provider,
                    response.getFraudDecision().name(),
                    request.getAmount().doubleValue(),
                    response.getFraudScore()
            );
            
            // Step 6: Publish events to Kafka (async)
            publishEventsAsync(request, response, providerResponse);
            
            // Record successful request
            requestMetrics.recordResponse(endpoint, "POST", "200", totalDuration);
            
            log.info("Fraud check completed: transactionId={}, decision={}, score={}, duration={}ms",
                    request.getTransactionId(), response.getFraudDecision(), 
                    response.getFraudScore(), totalDuration);
            
            return response;
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - overallStartTime;
            requestMetrics.recordResponse(endpoint, "POST", "500", duration);
            
            log.error("Fraud check failed: transactionId={}", request.getTransactionId(), e);
            
            return FraudCheckResponse.builder()
                    .transactionId(request.getTransactionId())
                    .fraudDecision(FraudCheckResponse.FraudDecision.ERROR)
                    .responseTimeMs(duration)
                    .build();
        }
    }
    
    /**
     * Process fraud check - Asynchronous flow
     * For non-blocking enrichment
     */
    public FraudCheckResponse processFraudCheckAsync(FraudCheckRequest request) {
        log.info("Processing async fraud check: transactionId={}", request.getTransactionId());
        
        // Publish to async enrichment queue
        KafkaEvents.AsyncEnrichmentRequest enrichmentRequest = KafkaEvents.AsyncEnrichmentRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .transactionId(request.getTransactionId())
                .enrichmentType("fraud_scoring")
                .provider(selectProvider(request, "fraud_sight"))
                .priority("normal")
                .payload(request.toString())
                .build();
        
        kafkaProducer.publishAsyncEnrichment(enrichmentRequest);
        
        // Return immediate response
        return FraudCheckResponse.builder()
                .transactionId(request.getTransactionId())
                .fraudDecision(FraudCheckResponse.FraudDecision.REVIEW)
                .processingMode("async")
                .responseTimeMs(5L)
                .build();
    }
    
    /**
     * Determine routing path: fraud_sight vs guaranteed_payment
     */
    private String determineRoutingPath(FraudCheckRequest request) {
        // Business logic to determine routing
        // In production, this would query merchant configuration
        
        if ("capture".equals(request.getTransactionType())) {
            return "guaranteed_payment";
        }
        return "fraud_sight";
    }
    
    /**
     * Select provider based on routing path and merchant preference
     */
    private String selectProvider(FraudCheckRequest request, String routingPath) {
        if (request.getProviderPreference() != null) {
            return request.getProviderPreference();
        }
        
        // Default routing logic
        return "fraud_sight".equals(routingPath) ? "Ravelin" : "Signifyd";
    }
    
    /**
     * Perform boarding check (merchant onboarding status)
     */
    private boolean performBoardingCheck(FraudCheckRequest request) {
        try {
            Thread.sleep(10); // Simulate DB lookup
            return true; // Merchant is boarded
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * Evaluate fraud rules
     */
    private boolean evaluateRules(FraudCheckRequest request) {
        try {
            Thread.sleep(15); // Simulate rules evaluation
            // Example rule: Decline if amount > $10,000
            return request.getAmount().doubleValue() <= 10000.0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * Perform BIN lookup to get card country
     */
    private String performBinLookup(String cardBin) {
        try {
            Thread.sleep(8); // Simulate BIN lookup
            return "US"; // Simulated result
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "UNKNOWN";
        }
    }
    
    /**
     * Build fraud check response
     */
    private FraudCheckResponse buildResponse(
            FraudCheckRequest request,
            FraudProviderClient.FraudProviderResponse providerResponse,
            String routingPath,
            String binCountry) {
        
        return FraudCheckResponse.builder()
                .transactionId(request.getTransactionId())
                .fraudDecision(providerResponse.getDecision())
                .fraudScore(providerResponse.getFraudScore())
                .providerUsed(providerResponse.getProvider())
                .routingPath(routingPath)
                .processingMode("sync")
                .reasonCodes(providerResponse.getReasonCodes())
                .metadata(FraudCheckResponse.ResponseMetadata.builder()
                        .binCountry(binCountry)
                        .rulesEvaluated(5)
                        .parallelCallsMade(3)
                        .providerLatencyMs(providerResponse.getResponseTimeMs())
                        .build())
                .build();
    }
    
    /**
     * Publish events to Kafka asynchronously
     */
    private void publishEventsAsync(
            FraudCheckRequest request,
            FraudCheckResponse response,
            FraudProviderClient.FraudProviderResponse providerResponse) {
        
        CompletableFuture.runAsync(() -> {
            try {
                // Publish fraud event
                KafkaEvents.FraudEvent fraudEvent = KafkaEvents.FraudEvent.builder()
                        .eventId(kafkaProducer.generateEventId())
                        .transactionId(request.getTransactionId())
                        .eventType("fraud_check_completed")
                        .merchantId(request.getMerchantId())
                        .fraudDecision(response.getFraudDecision().name())
                        .fraudScore(response.getFraudScore())
                        .provider(response.getProviderUsed())
                        .amount(request.getAmount())
                        .currency(request.getCurrency())
                        .processingTimeMs(response.getResponseTimeMs())
                        .build();
                
                kafkaProducer.publishFraudEvent(fraudEvent);
                
                // Publish transaction record for analytics
                KafkaEvents.TransactionRecord transactionRecord = KafkaEvents.TransactionRecord.builder()
                        .transactionId(request.getTransactionId())
                        .merchantId(request.getMerchantId())
                        .amount(request.getAmount())
                        .currency(request.getCurrency())
                        .fraudDecision(response.getFraudDecision().name())
                        .fraudScore(response.getFraudScore())
                        .provider(response.getProviderUsed())
                        .routingPath(response.getRoutingPath())
                        .cardBin(request.getCardBin())
                        .binCountry(response.getMetadata().getBinCountry())
                        .paymentMethod(request.getPaymentMethod())
                        .processingTimeMs(response.getResponseTimeMs())
                        .build();
                
                kafkaProducer.publishTransactionRecord(transactionRecord);
                
            } catch (Exception e) {
                log.error("Failed to publish Kafka events: transactionId={}", 
                        request.getTransactionId(), e);
            }
        });
    }
}
