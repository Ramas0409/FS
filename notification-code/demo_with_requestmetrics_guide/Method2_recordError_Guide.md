# RequestMetrics Method 2: recordError()

## 📋 Method Signature
```java
public void recordError(long durationMs, String errorType, String... labels)
```

## 🎯 Purpose
Records a **FAILED** request with error details. This is the "Error" part of the RED pattern (Rate, Errors, Duration).

---

## 📖 Parameters Explained

### 1. `durationMs` (long)
- How long the request took **before it failed**
- Measured from request start to error occurrence
- Important: Even failed requests have duration!

### 2. `errorType` (String)
- **What type of error occurred**
- Examples: "TimeoutException", "ValidationError", "DatabaseError", "ProviderUnavailable"
- Best practice: Use exception class name or business error code

### 3. `labels` (String... varargs)
- Optional additional labels (key-value pairs)
- Same as recordRequest - must be even number of strings
- Can include endpoint, method, merchant_id, etc.

---

## 📊 What Metrics Are Created

When you call: 
```java
requestMetrics.recordError(87, "TimeoutException", "endpoint", "/api/fraud", "method", "POST")
```

### 1. **Error Counter** (incremented)
```prometheus
fraud_switch_fraud_router_errors_total{
  service="fraud-router",
  region="us-ohio-1",
  endpoint="/api/fraud",
  method="POST",
  error_type="TimeoutException"
} 1
```

### 2. **Duration Still Recorded** (for failed requests too)
```prometheus
fraud_switch_fraud_router_request_duration_ms_bucket{
  service="fraud-router",
  endpoint="/api/fraud",
  le="100"
} 1
```

**Why record duration for errors?**
- Need to know: "Do timeouts happen at 50ms or 5000ms?"
- Helps debug: "Are errors fast-fails or slow-fails?"

---

## 💻 Basic Usage Examples

### Example 1: Simple Error Handling
```java
@GetMapping("/user/{id}")
public User getUser(@PathVariable String id) {
    long startTime = System.currentTimeMillis();
    
    try {
        return userService.findById(id);
        
    } catch (Exception e) {
        long duration = System.currentTimeMillis() - startTime;
        
        // Record the error with exception type
        requestMetrics.recordError(
            duration,
            e.getClass().getSimpleName()  // "UserNotFoundException"
        );
        
        throw e;
    }
}
```

**What happens:**
- ✅ Error counter increments
- ✅ Duration recorded (shows how long before it failed)
- ✅ Error type tracked: `error_type="UserNotFoundException"`

---

### Example 2: With Endpoint Labels
```java
@PostMapping("/process-payment")
public PaymentResponse processPayment(@RequestBody PaymentRequest request) {
    long startTime = System.currentTimeMillis();
    
    try {
        return paymentService.process(request);
        
    } catch (Exception e) {
        long duration = System.currentTimeMillis() - startTime;
        
        // Record error with context
        requestMetrics.recordError(
            duration,
            e.getClass().getSimpleName(),
            "endpoint", "/api/process-payment",
            "method", "POST"
        );
        
        throw e;
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_fraud_router_errors_total{
  service="fraud-router",
  endpoint="/api/process-payment",
  method="POST",
  error_type="PaymentProcessingException"
} 1
```

**Now you can query:** "How many payment processing errors per minute?"

---

### Example 3: Different Error Types
```java
@PostMapping("/fraud-check")
public FraudCheckResponse checkFraud(@RequestBody FraudCheckRequest request) {
    long startTime = System.currentTimeMillis();
    
    try {
        return fraudService.check(request);
        
    } catch (TimeoutException e) {
        // Provider timeout
        recordError(startTime, "TimeoutException");
        throw e;
        
    } catch (ValidationException e) {
        // Request validation failed
        recordError(startTime, "ValidationError");
        throw e;
        
    } catch (ProviderUnavailableException e) {
        // Provider is down
        recordError(startTime, "ProviderUnavailable");
        throw e;
        
    } catch (Exception e) {
        // Unexpected error
        recordError(startTime, "UnexpectedError");
        throw e;
    }
}

private void recordError(long startTime, String errorType) {
    long duration = System.currentTimeMillis() - startTime;
    requestMetrics.recordError(
        duration,
        errorType,
        "endpoint", "/api/fraud-check",
        "method", "POST"
    );
}
```

**Why this is useful:**
```prometheus
# Different error types tracked separately
fraud_switch_fraud_router_errors_total{error_type="TimeoutException"} 45
fraud_switch_fraud_router_errors_total{error_type="ValidationError"} 12
fraud_switch_fraud_router_errors_total{error_type="ProviderUnavailable"} 8
fraud_switch_fraud_router_errors_total{error_type="UnexpectedError"} 2
```

**Can now answer:** "Are we getting more timeouts or validation errors?"

---

### Example 4: With Business Context
```java
@PostMapping("/fraud-check")
public FraudCheckResponse checkFraud(@RequestBody FraudCheckRequest request) {
    long startTime = System.currentTimeMillis();
    
    try {
        return fraudService.check(request);
        
    } catch (Exception e) {
        long duration = System.currentTimeMillis() - startTime;
        
        // Record with rich business context
        requestMetrics.recordError(
            duration,
            e.getClass().getSimpleName(),
            "endpoint", "/api/fraud-check",
            "method", "POST",
            "transaction_type", request.getTransactionType(),
            "payment_method", request.getPaymentMethod(),
            "provider", "Ravelin"  // Which provider failed
        );
        
        throw e;
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_fraud_router_errors_total{
  service="fraud-router",
  endpoint="/api/fraud-check",
  transaction_type="auth",
  payment_method="stripe",
  provider="Ravelin",
  error_type="ProviderTimeoutException"
} 1
```

**Now you can ask:** "Are Ravelin timeouts more common for Stripe or Adyen?"

---

## 🔍 Code Walkthrough: What Happens Inside

Let's look at the actual code:

```java
public void recordError(long durationMs, String errorType, String... labels) {
    String metricName = metricNamePrefix + ".errors_total";
    
    // STEP 1: Add error_type to labels
    String[] errorLabels = appendLabels(labels, MetricLabels.Common.ERROR_TYPE, errorType);
    
    // STEP 2: Check cardinality
    if (!cardinalityEnforcer.canRecordMetric(metricName, errorLabels)) {
        log.debug("Dropped error metric due to cardinality limits: {}", metricName);
        return;
    }

    // STEP 3: Increment error counter
    errorCounter.increment();
    Tags tags = createTags(errorLabels);
    Counter.builder(metricNamePrefix + ".errors_total")
            .tags(tags)
            .register(meterRegistry)
            .increment();

    // STEP 4: Still record duration for error requests
    if (shouldSampleHistogram()) {
        requestTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    // STEP 5: Track cardinality
    cardinalityEnforcer.recordMetric(metricName, errorLabels);
}
```

### Step-by-Step Breakdown:

#### **STEP 1: Add error_type to labels**
```java
String[] errorLabels = appendLabels(labels, "error_type", errorType);
```

**Input:**
```java
recordError(87, "TimeoutException", "endpoint", "/api/fraud")
```

**After appendLabels:**
```java
errorLabels = ["endpoint", "/api/fraud", "error_type", "TimeoutException"]
```

**Why?** Automatically adds `error_type` label so you don't have to.

---

#### **STEP 2: Check cardinality**
```java
if (!cardinalityEnforcer.canRecordMetric(metricName, errorLabels)) {
    return;  // Skip if too many unique combinations
}
```

**Protects against metric explosion:**
```
TOO MANY error types → Circuit breaker activates → Stops recording
```

---

#### **STEP 3: Increment error counter**
```java
errorCounter.increment();  // Pre-registered base counter

Counter.builder(metricNamePrefix + ".errors_total")
        .tags(tags)  // With your labels
        .register(meterRegistry)
        .increment();
```

**Creates TWO counters:**

1. **Base counter** (no custom labels)
   ```prometheus
   fraud_switch_fraud_router_errors_total{
     service="fraud-router",
     region="us-ohio-1"
   } 1
   ```

2. **Tagged counter** (with your labels)
   ```prometheus
   fraud_switch_fraud_router_errors_total{
     service="fraud-router",
     endpoint="/api/fraud",
     error_type="TimeoutException"
   } 1
   ```

**Same pattern as recordRequest!** Two counters for different query purposes.

---

#### **STEP 4: Record duration for errors too**
```java
if (shouldSampleHistogram()) {
    requestTimer.record(durationMs, TimeUnit.MILLISECONDS);
}
```

**Important:** Errors still update duration histograms!

**Why?** You need to know:
- "Do timeouts happen after 50ms or 5000ms?"
- "Are validation errors fast-fails (5ms) or slow (500ms)?"
- "What's the latency distribution for failed requests?"

**Example scenario:**
```
Fast fails (validation errors): 5-10ms → Good!
Slow fails (timeouts): 5000ms → Need to reduce timeout threshold
```

---

#### **STEP 5: Track cardinality**
```java
cardinalityEnforcer.recordMetric(metricName, errorLabels);
```

Tracks unique combinations to prevent metric explosion.

---

## 🎯 Real-World Example: Fraud Check Service

```java
@Service
public class FraudCheckService {
    
    @Autowired
    private RequestMetrics requestMetrics;
    
    @Autowired
    private FraudProviderClient providerClient;
    
    public FraudCheckResponse checkFraud(FraudCheckRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            // Call external provider
            FraudProviderResponse response = providerClient.callRavelin(request);
            
            // Success - use recordRequest (covered in Method 1)
            long duration = System.currentTimeMillis() - startTime;
            requestMetrics.recordRequest(
                duration,
                "endpoint", "/fraud/check",
                "provider", "Ravelin"
            );
            
            return buildResponse(response);
            
        } catch (TimeoutException e) {
            // Provider timed out
            long duration = System.currentTimeMillis() - startTime;
            
            requestMetrics.recordError(
                duration,
                "ProviderTimeout",
                "endpoint", "/fraud/check",
                "provider", "Ravelin",
                "transaction_type", request.getTransactionType()
            );
            
            // Fall back to secondary provider or return error
            throw new FraudCheckException("Provider timeout", e);
            
        } catch (ProviderUnavailableException e) {
            // Provider is down
            long duration = System.currentTimeMillis() - startTime;
            
            requestMetrics.recordError(
                duration,
                "ProviderUnavailable",
                "endpoint", "/fraud/check",
                "provider", "Ravelin"
            );
            
            throw new FraudCheckException("Provider unavailable", e);
            
        } catch (ValidationException e) {
            // Request validation failed
            long duration = System.currentTimeMillis() - startTime;
            
            requestMetrics.recordError(
                duration,
                "ValidationError",
                "endpoint", "/fraud/check",
                "validation_field", e.getFieldName()
            );
            
            throw e;
            
        } catch (Exception e) {
            // Unexpected error
            long duration = System.currentTimeMillis() - startTime;
            
            requestMetrics.recordError(
                duration,
                "UnexpectedError",
                "endpoint", "/fraud/check",
                "exception_class", e.getClass().getSimpleName()
            );
            
            throw new FraudCheckException("Unexpected error", e);
        }
    }
}
```

**Metrics created:**
```prometheus
# Error counts by type
fraud_switch_fraud_router_errors_total{error_type="ProviderTimeout"} 23
fraud_switch_fraud_router_errors_total{error_type="ProviderUnavailable"} 5
fraud_switch_fraud_router_errors_total{error_type="ValidationError"} 12
fraud_switch_fraud_router_errors_total{error_type="UnexpectedError"} 1

# Error durations
fraud_switch_fraud_router_request_duration_ms{
  error_type="ProviderTimeout",
  quantile="0.99"
} 5000  # Timeouts at 5 seconds

fraud_switch_fraud_router_request_duration_ms{
  error_type="ValidationError",
  quantile="0.99"
} 8  # Fast fails at 8ms
```

---

## 📊 Prometheus Queries for Errors

### Error Rate (Errors per second)
```promql
rate(fraud_switch_fraud_router_errors_total[5m])
```

### Error Rate by Type
```promql
sum by (error_type) (
  rate(fraud_switch_fraud_router_errors_total[5m])
)
```

### Error Percentage
```promql
(
  rate(fraud_switch_fraud_router_errors_total[5m])
  / 
  rate(fraud_switch_fraud_router_requests_total[5m])
) * 100
```

### Top 5 Error Types
```promql
topk(5,
  sum by (error_type) (
    fraud_switch_fraud_router_errors_total
  )
)
```

### Duration of Failed Requests (P99)
```promql
histogram_quantile(0.99,
  rate(fraud_switch_fraud_router_request_duration_ms_bucket{
    error_type!=""
  }[5m])
)
```

---

## ⚠️ Common Mistakes

### ❌ MISTAKE 1: Not recording duration
```java
// WRONG - Duration is 0
catch (Exception e) {
    requestMetrics.recordError(0, e.getClass().getSimpleName());
}

// CORRECT - Calculate actual duration
catch (Exception e) {
    long duration = System.currentTimeMillis() - startTime;
    requestMetrics.recordError(duration, e.getClass().getSimpleName());
}
```

### ❌ MISTAKE 2: Too specific error types
```java
// WRONG - High cardinality
requestMetrics.recordError(duration, e.getMessage());
// Could be thousands of unique messages!

// CORRECT - Use exception class
requestMetrics.recordError(duration, e.getClass().getSimpleName());
// Limited set of exception types
```

### ❌ MISTAKE 3: Including stack traces
```java
// WRONG - Way too much data
requestMetrics.recordError(duration, e.toString());

// CORRECT - Just the type
requestMetrics.recordError(duration, e.getClass().getSimpleName());
```

### ❌ MISTAKE 4: Using recordRequest for errors
```java
// WRONG - Errors should use recordError
catch (Exception e) {
    requestMetrics.recordRequest(duration);  // ❌
}

// CORRECT
catch (Exception e) {
    requestMetrics.recordError(duration, e.getClass().getSimpleName());  // ✅
}
```

---

## 📝 Best Practices

### ✅ Good Error Types (Low Cardinality)
- `TimeoutException`
- `ValidationError`
- `DatabaseError`
- `ProviderUnavailable`
- `AuthenticationFailed`
- `RateLimitExceeded`

### ❌ Bad Error Types (High Cardinality)
- `e.getMessage()` - Could be anything
- `e.toString()` - Includes details
- Transaction IDs
- User-specific error messages
- Stack traces

---

## 🎯 When to Use recordError()

✅ **Use recordError() for:**
- Exceptions caught in try-catch
- Validation failures
- External API failures
- Timeout errors
- Authentication/authorization failures
- Database errors
- Any request that didn't complete successfully

❌ **Don't use recordError() for:**
- Successful requests → Use `recordRequest()`
- Business logic "soft failures" (might still return 200) → Use custom labels in recordRequest

---

## ✅ Quick Checklist

Before using recordError():

- [ ] Calculate duration from start time
- [ ] Use exception class name (not message)
- [ ] Keep error types to limited set (< 50 types)
- [ ] Include business context labels
- [ ] Don't include sensitive data (PCI)
- [ ] Make sure labels are key-value pairs

---

**Ready for the next method?** Type "next" to learn about **Method 3: `recordWithOutcome()`**!
