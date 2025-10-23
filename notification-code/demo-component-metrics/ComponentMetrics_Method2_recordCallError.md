# ComponentMetrics Method 2: recordCallError()

## 📋 Method Signature
```java
public void recordCallError(String targetComponent, long durationMs, String errorType, String... labels)
```

## 🎯 Purpose
Records a **failed service-to-service call**. Tracks when your service tries to call another internal microservice but the call fails.

---

## 📖 What is This For?

Service-to-service calls can fail for many reasons:
- **Timeout** - Target service didn't respond in time
- **Connection refused** - Target service is down
- **HTTP 500** - Target service returned server error
- **HTTP 400** - Bad request / validation error
- **Circuit breaker open** - Too many failures, circuit breaker tripped
- **Network errors** - Network connectivity issues
- **Service unavailable** - Target service overloaded

`recordCallError()` tracks these failures so you can:
- Monitor failure rates between services
- Alert on service dependency issues
- Identify which services are unreliable
- Track different error types
- Measure impact of failures
- Debug cascade failures

---

## 📖 Parameters Explained

### 1. `targetComponent` (String)
- **Which service** you were trying to call
- Examples: "rules_service", "bin_lookup_service", "fraudsight_adapter"
- Must use **canonical service names**

### 2. `durationMs` (long)
- How long before the call failed
- From start to error occurrence
- Even failed calls have duration!

### 3. `errorType` (String)
- **What type of error occurred**
- Examples: "TimeoutException", "HttpServerError", "CircuitBreakerOpen"
- Best practice: Use exception class name or HTTP status

### 4. `labels` (String... varargs)
- Optional additional labels (key-value pairs)
- Must be even number of strings
- Common labels: "operation", "http_status", "retry_attempt"

---

## 💻 Basic Usage Examples

### Example 1: Fraud Router Calling Rules Service with Error Handling

```java
@Service
@Slf4j
public class FraudRouterService {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private RulesServiceClient rulesServiceClient;
    
    public FraudResponse processFraudCheck(FraudRequest request) {
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Call Rules Service
            RulesResponse rulesResponse = rulesServiceClient.checkRules(
                request.getMerchantId(),
                request.getEventType()
            );
            
            // ✅ Success
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCall(
                "rules_service",
                duration,
                "operation", "check_rules"
            );
            
            return processFraudCheck(request, rulesResponse);
            
        } catch (TimeoutException e) {
            // ❌ Timeout error
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "rules_service",
                duration,
                "TimeoutException",
                "operation", "check_rules"
            );
            
            log.error("Rules service timeout: merchantId={}, duration={}ms", 
                     request.getMerchantId(),
                     duration,
                     e);
            throw e;
            
        } catch (Exception e) {
            // ❌ Other errors
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "rules_service",
                duration,
                e.getClass().getSimpleName(),
                "operation", "check_rules"
            );
            
            log.error("Rules service error: merchantId={}", 
                     request.getMerchantId(),
                     e);
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_fraud_router_component_call_errors_total{
  service="fraud-router",
  region="us-ohio-1",
  target_component="rules_service",
  error_type="TimeoutException",
  operation="check_rules"
} 1
```

---

### Example 2: BIN Lookup with HTTP Error Status

```java
@Service
@Slf4j
public class FraudRouterService {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private RestTemplate restTemplate;
    
    public BinData lookupBin(String cardNumber) {
        String bin = cardNumber.substring(0, 6);
        long startTime = System.currentTimeMillis();
        
        try {
            // Call BIN Lookup Service
            ResponseEntity<BinData> response = restTemplate.getForEntity(
                "http://bin-lookup-service/api/bin/" + bin,
                BinData.class
            );
            
            // ✅ Success
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCall(
                "bin_lookup_service",
                duration,
                "operation", "lookup"
            );
            
            return response.getBody();
            
        } catch (HttpServerErrorException e) {
            // ❌ HTTP 5xx error
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "bin_lookup_service",
                duration,
                "HttpServerError",
                "operation", "lookup",
                "http_status", String.valueOf(e.getStatusCode().value())
            );
            
            log.error("BIN lookup server error: bin={}, status={}, duration={}ms", 
                     bin,
                     e.getStatusCode(),
                     duration,
                     e);
            throw e;
            
        } catch (HttpClientErrorException e) {
            // ❌ HTTP 4xx error
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "bin_lookup_service",
                duration,
                "HttpClientError",
                "operation", "lookup",
                "http_status", String.valueOf(e.getStatusCode().value())
            );
            
            log.error("BIN lookup client error: bin={}, status={}, duration={}ms", 
                     bin,
                     e.getStatusCode(),
                     duration,
                     e);
            throw e;
            
        } catch (ResourceAccessException e) {
            // ❌ Connection/timeout error
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "bin_lookup_service",
                duration,
                "ConnectionError",
                "operation", "lookup"
            );
            
            log.error("BIN lookup connection error: bin={}, duration={}ms", 
                     bin,
                     duration,
                     e);
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
# Server error
fraud_switch_fraud_router_component_call_errors_total{
  target_component="bin_lookup_service",
  error_type="HttpServerError",
  operation="lookup",
  http_status="503"
} 1

# Client error
fraud_switch_fraud_router_component_call_errors_total{
  target_component="bin_lookup_service",
  error_type="HttpClientError",
  operation="lookup",
  http_status="400"
} 1
```

---

### Example 3: Adapter Call with Circuit Breaker

```java
@Service
@Slf4j
public class FraudRouterService {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private FraudSightAdapterClient fraudSightClient;
    
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;
    
    public FraudResponse callFraudSightAdapter(FraudRequest request) {
        
        long startTime = System.currentTimeMillis();
        
        // Get circuit breaker for this adapter
        CircuitBreaker circuitBreaker = circuitBreakerRegistry
            .circuitBreaker("fraudsight_adapter");
        
        try {
            // Call with circuit breaker protection
            FraudSightResponse response = circuitBreaker.executeSupplier(
                () -> fraudSightClient.checkFraud(request)
            );
            
            // ✅ Success
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCall(
                "fraudsight_adapter",
                duration,
                "event_type", request.getEventType()
            );
            
            return convertToFraudResponse(response);
            
        } catch (CallNotPermittedException e) {
            // ❌ Circuit breaker is OPEN
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "fraudsight_adapter",
                duration,
                "CircuitBreakerOpen",
                "event_type", request.getEventType(),
                "circuit_state", circuitBreaker.getState().toString()
            );
            
            log.error("FraudSight circuit breaker open: txn={}, state={}", 
                     request.getTransactionId(),
                     circuitBreaker.getState(),
                     e);
            
            // Return fallback response
            return createFallbackResponse();
            
        } catch (TimeoutException e) {
            // ❌ Timeout
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "fraudsight_adapter",
                duration,
                "TimeoutException",
                "event_type", request.getEventType()
            );
            
            log.error("FraudSight timeout: txn={}, duration={}ms", 
                     request.getTransactionId(),
                     duration,
                     e);
            throw e;
            
        } catch (Exception e) {
            // ❌ Other errors
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "fraudsight_adapter",
                duration,
                e.getClass().getSimpleName(),
                "event_type", request.getEventType()
            );
            
            log.error("FraudSight error: txn={}", 
                     request.getTransactionId(),
                     e);
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_fraud_router_component_call_errors_total{
  target_component="fraudsight_adapter",
  error_type="CircuitBreakerOpen",
  event_type="beforeAuthenticationSync",
  circuit_state="OPEN"
} 1
```

---

### Example 4: Retry Logic with Error Tracking

```java
@Service
@Slf4j
public class FraudRouterService {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private RulesServiceClient rulesServiceClient;
    
    private static final int MAX_RETRIES = 3;
    
    public RulesResponse callRulesServiceWithRetry(String merchantId, String eventType) {
        
        int attempt = 0;
        Exception lastException = null;
        
        while (attempt < MAX_RETRIES) {
            attempt++;
            long startTime = System.currentTimeMillis();
            
            try {
                // Attempt call
                RulesResponse response = rulesServiceClient.checkRules(merchantId, eventType);
                
                // ✅ Success
                long duration = System.currentTimeMillis() - startTime;
                componentMetrics.recordCall(
                    "rules_service",
                    duration,
                    "operation", "check_rules",
                    "retry_attempt", String.valueOf(attempt)
                );
                
                log.info("Rules service call succeeded: merchantId={}, attempt={}", 
                        merchantId, 
                        attempt);
                return response;
                
            } catch (TimeoutException e) {
                // ❌ Timeout - retryable
                lastException = e;
                long duration = System.currentTimeMillis() - startTime;
                
                componentMetrics.recordCallError(
                    "rules_service",
                    duration,
                    "TimeoutException",
                    "operation", "check_rules",
                    "retry_attempt", String.valueOf(attempt)
                );
                
                log.warn("Rules service timeout: merchantId={}, attempt={}/{}", 
                        merchantId, 
                        attempt, 
                        MAX_RETRIES);
                
                if (attempt < MAX_RETRIES) {
                    sleep(100 * attempt);  // Exponential backoff
                }
                
            } catch (HttpClientErrorException e) {
                // ❌ Client error - NOT retryable
                long duration = System.currentTimeMillis() - startTime;
                
                componentMetrics.recordCallError(
                    "rules_service",
                    duration,
                    "HttpClientError",
                    "operation", "check_rules",
                    "retry_attempt", String.valueOf(attempt),
                    "http_status", String.valueOf(e.getStatusCode().value())
                );
                
                log.error("Rules service client error: merchantId={}, status={}", 
                         merchantId,
                         e.getStatusCode(),
                         e);
                
                // Don't retry client errors
                throw e;
            }
        }
        
        // All retries exhausted
        log.error("Rules service - max retries exhausted: merchantId={}, attempts={}", 
                 merchantId, 
                 MAX_RETRIES);
        throw new ServiceUnavailableException("Rules service unavailable", lastException);
    }
    
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

**Metrics created:**
```prometheus
# First attempt failed
fraud_switch_fraud_router_component_call_errors_total{
  target_component="rules_service",
  error_type="TimeoutException",
  operation="check_rules",
  retry_attempt="1"
} 1

# Second attempt failed
fraud_switch_fraud_router_component_call_errors_total{
  target_component="rules_service",
  error_type="TimeoutException",
  operation="check_rules",
  retry_attempt="2"
} 1

# Third attempt succeeded (tracked by recordCall)
fraud_switch_fraud_router_component_calls_total{
  target_component="rules_service",
  operation="check_rules",
  retry_attempt="3"
} 1
```

---

### Example 5: Fallback Pattern with Error Tracking

```java
@Service
@Slf4j
public class FraudRouterService {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private RulesServiceClient rulesServiceClient;
    
    public RulesResponse getRulesWithFallback(String merchantId, String eventType) {
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Try primary call
            RulesResponse response = rulesServiceClient.checkRules(merchantId, eventType);
            
            // ✅ Success
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCall(
                "rules_service",
                duration,
                "operation", "check_rules",
                "fallback", "false"
            );
            
            return response;
            
        } catch (Exception e) {
            // ❌ Error - Record and use fallback
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "rules_service",
                duration,
                e.getClass().getSimpleName(),
                "operation", "check_rules",
                "fallback", "true"
            );
            
            log.warn("Rules service failed, using fallback: merchantId={}, error={}", 
                    merchantId,
                    e.getMessage(),
                    e);
            
            // Return fallback response (e.g., default rules)
            return createFallbackRulesResponse(merchantId, eventType);
        }
    }
    
    private RulesResponse createFallbackRulesResponse(String merchantId, String eventType) {
        // Return default/cached rules
        return RulesResponse.builder()
            .merchantId(merchantId)
            .eventType(eventType)
            .decision("allow")  // Default to allow
            .rules(Collections.emptyList())
            .fallback(true)
            .build();
    }
}
```

---

## 🎯 When to Use recordCallError()

### ✅ Use recordCallError() for:

1. **Any internal service call failure**
   - HTTP errors (4xx, 5xx)
   - Timeouts
   - Connection refused
   - Network errors
   - Circuit breaker open

2. **In catch blocks**
   - After service call fails
   - Before throwing exception
   - Before using fallback

3. **Error monitoring**
   - Track failure rates between services
   - Alert on dependency issues
   - Identify unreliable services

---

### ❌ DON'T use recordCallError() for:

1. **Successful calls**
   ```java
   // DON'T do this:
   callService();
   componentMetrics.recordCallError("service", duration, "Success");  // ❌ Wrong method!
   
   // DO this:
   callService();
   componentMetrics.recordCall("service", duration);  // ✅ Use recordCall()
   ```

2. **External API failures**
   - Ravelin API errors → Use provider metrics
   - Signifyd API errors → Use provider metrics

3. **Before the call attempt**
   - Only record AFTER the failure occurs

---

## 📊 What Metrics Are Created

When you call:
```java
componentMetrics.recordCallError("rules_service", 87, "TimeoutException", "operation", "check_rules")
```

**Metric created:**
```prometheus
fraud_switch_fraud_router_component_call_errors_total{
  service="fraud-router",
  region="us-ohio-1",
  target_component="rules_service",
  error_type="TimeoutException",
  operation="check_rules"
} 1
```

**This is a COUNTER** - it only goes up, never down.

---

## 📈 Prometheus Queries

### Error Rate (Errors per second)
```promql
rate(fraud_switch_fraud_router_component_call_errors_total{target_component="rules_service"}[5m])
```

### Error Rate by Error Type
```promql
sum by (error_type) (
  rate(fraud_switch_fraud_router_component_call_errors_total{target_component="rules_service"}[5m])
)
```

### Error Rate by Target Component
```promql
sum by (target_component) (
  rate(fraud_switch_fraud_router_component_call_errors_total[5m])
)
```

### Error Percentage (Success vs Error)
```promql
(
  rate(fraud_switch_fraud_router_component_call_errors_total{target_component="rules_service"}[5m])
  /
  (
    rate(fraud_switch_fraud_router_component_calls_total{target_component="rules_service"}[5m]) +
    rate(fraud_switch_fraud_router_component_call_errors_total{target_component="rules_service"}[5m])
  )
) * 100
```

### Top 5 Error Types
```promql
topk(5,
  sum by (error_type) (
    fraud_switch_fraud_router_component_call_errors_total
  )
)
```

### Most Unreliable Services
```promql
topk(5,
  sum by (target_component) (
    rate(fraud_switch_fraud_router_component_call_errors_total[5m])
  )
)
```

### Alert: High Error Rate
```promql
# Alert if error rate > 5 per second
rate(fraud_switch_fraud_router_component_call_errors_total{target_component="rules_service"}[5m]) > 5
```

### Alert: Error Percentage > 10%
```promql
(
  rate(fraud_switch_fraud_router_component_call_errors_total[5m])
  /
  (
    rate(fraud_switch_fraud_router_component_calls_total[5m]) +
    rate(fraud_switch_fraud_router_component_call_errors_total[5m])
  )
) * 100 > 10
```

---

## ⚠️ Common Mistakes

### ❌ MISTAKE 1: Using generic error message
```java
// WRONG - Too generic
catch (Exception e) {
    componentMetrics.recordCallError("rules_service", duration, "Error");  // ❌
}

// CORRECT - Use exception class name
catch (Exception e) {
    componentMetrics.recordCallError("rules_service", duration, e.getClass().getSimpleName());  // ✅
}
```

### ❌ MISTAKE 2: Including error details in error type
```java
// WRONG - High cardinality!
catch (TimeoutException e) {
    componentMetrics.recordCallError(
        "rules_service",
        duration,
        e.getMessage()  // ❌ "Timeout after 5000ms for merchant_123"
    );
}

// CORRECT - Use exception class
catch (TimeoutException e) {
    componentMetrics.recordCallError(
        "rules_service",
        duration,
        "TimeoutException"  // ✅ Limited set of error types
    );
}
```

### ❌ MISTAKE 3: Not recording error on failure
```java
// WRONG - No error metrics
long startTime = System.currentTimeMillis();
try {
    callService();
    componentMetrics.recordCall("service", duration);
} catch (Exception e) {
    log.error("Failed", e);  // ❌ No metrics recorded!
    throw e;
}

// CORRECT - Record error
try {
    callService();
    componentMetrics.recordCall("service", duration);
} catch (Exception e) {
    componentMetrics.recordCallError("service", duration, e.getClass().getSimpleName());  // ✅
    throw e;
}
```

### ❌ MISTAKE 4: Recording both success and error
```java
// WRONG - Recording both!
try {
    callService();
    componentMetrics.recordCall("service", duration);  // ✅ Success recorded
} catch (Exception e) {
    componentMetrics.recordCall("service", duration);  // ❌ Still recording success!
    componentMetrics.recordCallError("service", duration, "Error");
    throw e;
}

// CORRECT - Only one or the other
try {
    callService();
    componentMetrics.recordCall("service", duration);  // ✅ Only on success
} catch (Exception e) {
    componentMetrics.recordCallError("service", duration, e.getClass().getSimpleName());  // ✅ Only on error
    throw e;
}
```

---

## 📝 Best Practices

### ✅ DO:

1. **Use exception class name or HTTP status**
   ```java
   componentMetrics.recordCallError("rules_service", duration, "TimeoutException");
   componentMetrics.recordCallError("bin_lookup_service", duration, "HttpServerError", "http_status", "503");
   ```

2. **Add context labels**
   ```java
   componentMetrics.recordCallError(
       "rules_service",
       duration,
       "TimeoutException",
       "operation", "check_rules",
       "retry_attempt", String.valueOf(attempt)
   );
   ```

3. **Track different error types**
   ```java
   catch (TimeoutException e) {
       recordCallError("service", duration, "TimeoutException");
   } catch (HttpServerErrorException e) {
       recordCallError("service", duration, "HttpServerError");
   }
   ```

4. **Keep error types to limited set**
   - TimeoutException ✅
   - HttpServerError ✅
   - HttpClientError ✅
   - ConnectionError ✅
   - CircuitBreakerOpen ✅
   - "Timeout after 5000ms" ❌

---

### ❌ DON'T:

1. Don't use error messages (high cardinality)
2. Don't forget to record errors
3. Don't use generic error types
4. Don't record both success and error
5. Don't include sensitive data in labels

---

## ✅ Quick Checklist

Before using recordCallError():

- [ ] Service call failed
- [ ] Using exception class name (not message)
- [ ] Called in catch block
- [ ] Added meaningful context labels
- [ ] Error types limited (< 20 types)
- [ ] Not recording success metric too
- [ ] No PII/sensitive data in labels
- [ ] Calculated actual duration before error

---

## 🎯 Summary

**recordCallError()** tracks failed internal service-to-service calls:

```java
componentMetrics.recordCallError(targetComponent, durationMs, errorType, labels...);
```

**Use for:**
- Internal service call failures
- In catch blocks
- HTTP errors, timeouts, connection errors
- Circuit breaker open states

**Creates:**
```prometheus
fraud_switch_{service}_component_call_errors_total{
  target_component="...",
  error_type="TimeoutException",
  operation="..."
} 1
```

**Best Practices:**
- Use exception class name
- Add context labels
- Track different error types
- Keep error types limited

---

**Do you understand recordCallError()?** 

Type **"next"** for a **Complete ComponentMetrics Summary** with all methods and examples! 🚀
