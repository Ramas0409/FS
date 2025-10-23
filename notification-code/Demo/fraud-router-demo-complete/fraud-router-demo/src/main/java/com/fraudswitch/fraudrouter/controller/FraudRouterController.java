package com.fraudswitch.fraudrouter.controller;

import com.fraudswitch.fraudrouter.dto.FraudCheckRequest;
import com.fraudswitch.fraudrouter.dto.FraudCheckResponse;
import com.fraudswitch.fraudrouter.service.FraudRoutingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Fraud Router REST API Controller
 * 
 * Endpoints:
 * - POST /api/fraud/check - Synchronous fraud check
 * - POST /api/fraud/check/async - Asynchronous fraud check
 * - GET /api/fraud/health - Health check
 */
@Slf4j
@RestController
@RequestMapping("/api/fraud")
@RequiredArgsConstructor
public class FraudRouterController {
    
    private final FraudRoutingService fraudRoutingService;
    
    /**
     * Synchronous fraud check endpoint
     * 
     * Example request:
     * POST /api/fraud/check
     * {
     *   "transaction_id": "txn_123456",
     *   "merchant_id": "merch_001",
     *   "amount": 99.99,
     *   "currency": "USD",
     *   "transaction_type": "auth",
     *   "card_bin": "424242",
     *   "card_last_four": "4242",
     *   "payment_method": "stripe",
     *   "customer_email": "customer@example.com"
     * }
     */
    @PostMapping("/check")
    public ResponseEntity<FraudCheckResponse> checkFraud(
            @Valid @RequestBody FraudCheckRequest request) {
        
        log.info("Received fraud check request: transactionId={}", request.getTransactionId());
        
        try {
            FraudCheckResponse response = fraudRoutingService.processFraudCheck(request);
            
            HttpStatus status = switch (response.getFraudDecision()) {
                case APPROVE -> HttpStatus.OK;
                case DECLINE -> HttpStatus.OK;
                case REVIEW -> HttpStatus.OK;
                case ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
            
            return ResponseEntity.status(status).body(response);
            
        } catch (Exception e) {
            log.error("Error processing fraud check: transactionId={}", 
                    request.getTransactionId(), e);
            
            FraudCheckResponse errorResponse = FraudCheckResponse.builder()
                    .transactionId(request.getTransactionId())
                    .fraudDecision(FraudCheckResponse.FraudDecision.ERROR)
                    .build();
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse);
        }
    }
    
    /**
     * Asynchronous fraud check endpoint
     * Returns immediately, processes fraud check in background
     * 
     * Example request:
     * POST /api/fraud/check/async
     * {
     *   "transaction_id": "txn_123457",
     *   "merchant_id": "merch_001",
     *   "amount": 49.99,
     *   "currency": "USD",
     *   "transaction_type": "auth",
     *   "async_mode": true
     * }
     */
    @PostMapping("/check/async")
    public ResponseEntity<FraudCheckResponse> checkFraudAsync(
            @Valid @RequestBody FraudCheckRequest request) {
        
        log.info("Received async fraud check request: transactionId={}", 
                request.getTransactionId());
        
        try {
            FraudCheckResponse response = fraudRoutingService.processFraudCheckAsync(request);
            return ResponseEntity.accepted().body(response);
            
        } catch (Exception e) {
            log.error("Error processing async fraud check: transactionId={}", 
                    request.getTransactionId(), e);
            
            FraudCheckResponse errorResponse = FraudCheckResponse.builder()
                    .transactionId(request.getTransactionId())
                    .fraudDecision(FraudCheckResponse.FraudDecision.ERROR)
                    .processingMode("async")
                    .build();
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse);
        }
    }
    
    /**
     * Health check endpoint
     * GET /api/fraud/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "fraud-router");
        health.put("version", "1.0.0");
        health.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.ok(health);
    }
    
    /**
     * Global exception handler
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception e) {
        log.error("Unhandled exception", e);
        
        Map<String, String> error = new HashMap<>();
        error.put("error", "Internal server error");
        error.put("message", e.getMessage());
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
