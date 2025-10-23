# RequestMetrics Method 3: recordWithOutcome()

## 📋 Method Signature
```java
public void recordWithOutcome(long durationMs, String outcome, String... labels)
```

## 🎯 Purpose
**Convenience method** that automatically calls either `recordRequest()` or `recordError()` based on the outcome string. Simplifies your code when you want to decide success vs error dynamically.

---

## 📖 Parameters Explained

### 1. `durationMs` (long)
- Request duration in milliseconds
- Same as other methods

### 2. `outcome` (String)
- **Determines which method gets called**
- If `"success"` → calls `recordRequest()`
- If anything else → calls `recordError()` with outcome as error type

### 3. `labels` (String... varargs)
- Optional additional labels
- Same as other methods

---

## 🔍 How It Works - The Code

```java
public void recordWithOutcome(long durationMs, String outcome, String... labels) {
    // Add outcome to labels
    String[] outcomeLabels = appendLabels(labels, MetricLabels.Common.OUTCOME, outcome);
    
    // Decide which method to call
    if ("success".equalsIgnoreCase(outcome)) {
        recordRequest(durationMs, outcomeLabels);
    } else {
        recordError(durationMs, outcome, labels);
    }
}
```

### Breaking It Down:

#### Step 1: Add outcome label
```java
String[] outcomeLabels = appendLabels(labels, "outcome", outcome);
```

**Before:**
```java
labels = ["endpoint", "/api/test"]
outcome = "success"
```

**After:**
```java
outcomeLabels = ["endpoint", "/api/test", "outcome", "success"]
```

#### Step 2: Check if success
```java
if ("success".equalsIgnoreCase(outcome)) {
```
- Case-insensitive check
- "success", "Success", "SUCCESS" all work

#### Step 3: Route to appropriate method
```java
if ("success".equalsIgnoreCase(outcome)) {
    recordRequest(durationMs, outcomeLabels);  // ✅ Success path
} else {
    recordError(durationMs, outcome, labels);   // ❌ Error path
}
```

---

## 💻 Basic Usage Examples

### Example 1: Simple Success/Failure
```java
@GetMapping("/user/{id}")
public User getUser(@PathVariable String id) {
    long startTime = System.currentTimeMillis();
    String outcome;
    
    try {
        User user = userService.findById(id);
        outcome = "success";
        return user;
        
    } catch (Exception e) {
        outcome = e.getClass().getSimpleName();
        throw e;
        
    } finally {
        long duration = System.currentTimeMillis() - startTime;
        
        // Single method call handles both success and error!
        requestMetrics.recordWithOutcome(
            duration,
            outcome,
            "endpoint", "/api/user",
            "method", "GET"
        );
    }
}
```

**What happens:**
- Success: `outcome = "success"` → calls `recordRequest()`
- Error: `outcome = "UserNotFoundException"` → calls `recordError()`

---

### Example 2: External API Call
```java
public FraudProviderResponse callExternalProvider(FraudCheckRequest request) {
    long startTime = System.currentTimeMillis();
    String outcome;
    
    try {
        FraudProviderResponse response = webClient
            .post()
            .uri("/fraud-score")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(FraudProviderResponse.class)
            .block(Duration.ofMillis(300));
        
        outcome = "success";
        return response;
        
    } catch (WebClientResponseException e) {
        outcome = "HttpError_" + e.getStatusCode().value();
        throw e;
        
    } catch (TimeoutException e) {
        outcome = "Timeout";
        throw e;
        
    } catch (Exception e) {
        outcome = "UnexpectedError";
        throw e;
        
    } finally {
        long duration = System.currentTimeMillis() - startTime;
        
        requestMetrics.recordWithOutcome(
            duration,
            outcome,
            "provider", "Ravelin",
            "endpoint", "/fraud-score"
        );
    }
}
```

**Outcomes tracked:**
- `"success"` - API call succeeded
- `"HttpError_500"` - Server error
- `"HttpError_429"` - Rate limited
- `"Timeout"` - Request timed out
- `"UnexpectedError"` - Other errors

---

### Example 3: Database Query
```java
public List<Transaction> getTransactions(String merchantId) {
    long startTime = System.currentTimeMillis();
    String outcome = "success";
    
    try {
        List<Transaction> transactions = jdbcTemplate.query(
            "SELECT * FROM transactions WHERE merchant_id = ?",
            new Object[]{merchantId},
            transactionRowMapper
        );
        
        return transactions;
        
    } catch (DataAccessException e) {
        outcome = "DatabaseError";
        throw e;
        
    } finally {
        long duration = System.currentTimeMillis() - startTime;
        
        requestMetrics.recordWithOutcome(
            duration,
            outcome,
            "operation", "db_query",
            "table", "transactions"
        );
    }
}
```

---

### Example 4: Business Logic with Multiple Outcomes
```java
public PaymentResult processPayment(PaymentRequest request) {
    long startTime = System.currentTimeMillis();
    String outcome;
    
    try {
        // Validate request
        if (!isValid(request)) {
            outcome = "ValidationFailed";
            throw new ValidationException("Invalid request");
        }
        
        // Check merchant balance
        if (!hasSufficientBalance(request.getMerchantId())) {
            outcome = "InsufficientBalance";
            throw new InsufficientBalanceException();
        }
        
        // Process payment
        PaymentResult result = paymentGateway.charge(request);
        
        if (result.isDeclined()) {
            outcome = "PaymentDeclined";
        } else {
            outcome = "success";
        }
        
        return result;
        
    } catch (Exception e) {
        if (outcome == null) {
            outcome = e.getClass().getSimpleName();
        }
        throw e;
        
    } finally {
        long duration = System.currentTimeMillis() - startTime;
        
        requestMetrics.recordWithOutcome(
            duration,
            outcome,
            "endpoint", "/api/payment",
            "merchant_id", request.getMerchantId(),
            "payment_method", request.getPaymentMethod()
        );
    }
}
```

**Multiple outcomes:**
- `"success"` - Payment approved
- `"ValidationFailed"` - Bad request
- `"InsufficientBalance"` - Merchant issue
- `"PaymentDeclined"` - Card declined
- Other exceptions

---

## 🎯 When to Use recordWithOutcome()

### ✅ Good Use Cases:

1. **Try-Finally Pattern**
   ```java
   String outcome;
   try {
       // work
       outcome = "success";
   } catch (Exception e) {
       outcome = e.getClass().getSimpleName();
   } finally {
       recordWithOutcome(duration, outcome);
   }
   ```

2. **Dynamic Outcomes**
   ```java
   String outcome = response.isSuccess() ? "success" : "failure";
   recordWithOutcome(duration, outcome);
   ```

3. **Multiple Error Types**
   ```java
   String outcome;
   if (valid) outcome = "success";
   else if (timeout) outcome = "Timeout";
   else if (rateLimit) outcome = "RateLimited";
   else outcome = "UnknownError";
   
   recordWithOutcome(duration, outcome);
   ```

### ❌ When NOT to Use:

1. **When you already know success/error**
   ```java
   // DON'T do this:
   try {
       result = doWork();
       recordWithOutcome(duration, "success");  // ❌ Just use recordRequest!
   } catch (Exception e) {
       recordWithOutcome(duration, "error");     // ❌ Just use recordError!
   }
   
   // DO this instead:
   try {
       result = doWork();
       requestMetrics.recordRequest(duration);   // ✅ Direct call
   } catch (Exception e) {
       requestMetrics.recordError(duration, e.getClass().getSimpleName());  // ✅ Direct call
   }
   ```

2. **When you need more control**
   - If success and error need different labels
   - If you want to handle errors differently
   - Use direct methods instead

---

## 📊 Metrics Created

### Success Outcome
```java
recordWithOutcome(87, "success", "endpoint", "/api/test")
```

**Creates:**
```prometheus
fraud_switch_fraud_router_requests_total{
  service="fraud-router",
  endpoint="/api/test",
  outcome="success"
} 1

fraud_switch_fraud_router_request_duration_ms_bucket{
  service="fraud-router",
  endpoint="/api/test",
  outcome="success",
  le="100"
} 1
```

### Error Outcome
```java
recordWithOutcome(87, "TimeoutException", "endpoint", "/api/test")
```

**Creates:**
```prometheus
fraud_switch_fraud_router_errors_total{
  service="fraud-router",
  endpoint="/api/test",
  error_type="TimeoutException",
  outcome="TimeoutException"
} 1

fraud_switch_fraud_router_request_duration_ms_bucket{
  service="fraud-router",
  endpoint="/api/test",
  le="100"
} 1
```

**Notice:** Adds `outcome` label to help track in Prometheus.

---

## 🔄 Comparison: Direct Methods vs recordWithOutcome()

### Pattern 1: Direct Methods (More Control)
```java
try {
    result = doWork();
    
    // Success path
    requestMetrics.recordRequest(
        duration,
        "endpoint", "/api/test",
        "method", "POST",
        "extra_label", "value"
    );
    
} catch (TimeoutException e) {
    // Error path - different labels
    requestMetrics.recordError(
        duration,
        "TimeoutException",
        "endpoint", "/api/test",
        "method", "POST"
        // No extra_label here
    );
}
```

**Pros:**
- ✅ Full control over labels
- ✅ Can add different labels for success vs error
- ✅ Clear and explicit

**Cons:**
- ⚠️ More code
- ⚠️ Need try-catch

---

### Pattern 2: recordWithOutcome() (Less Code)
```java
String outcome;
try {
    result = doWork();
    outcome = "success";
} catch (Exception e) {
    outcome = e.getClass().getSimpleName();
} finally {
    requestMetrics.recordWithOutcome(
        duration,
        outcome,
        "endpoint", "/api/test",
        "method", "POST"
    );
}
```

**Pros:**
- ✅ Less code
- ✅ Single method call
- ✅ Works well with finally block
- ✅ Adds outcome label automatically

**Cons:**
- ⚠️ Same labels for success and error
- ⚠️ Less explicit

---

## 🎓 Advanced Example: Retry Logic

```java
public ApiResponse callWithRetry(ApiRequest request, int maxRetries) {
    long startTime = System.currentTimeMillis();
    String outcome = null;
    int attemptCount = 0;
    
    while (attemptCount < maxRetries) {
        attemptCount++;
        
        try {
            ApiResponse response = apiClient.call(request);
            outcome = "success";
            return response;
            
        } catch (TimeoutException e) {
            if (attemptCount >= maxRetries) {
                outcome = "TimeoutAfterRetries";
                throw e;
            }
            // Retry
            Thread.sleep(100);
            
        } catch (ServerException e) {
            if (attemptCount >= maxRetries) {
                outcome = "ServerErrorAfterRetries";
                throw e;
            }
            // Retry
            Thread.sleep(100);
            
        } catch (Exception e) {
            // Don't retry for other exceptions
            outcome = e.getClass().getSimpleName();
            throw e;
        }
    }
    
    // This should never be reached
    outcome = "UnexpectedRetryExit";
    throw new IllegalStateException("Retry loop exited unexpectedly");
}

// Always record in finally block of caller
public ApiResponse callApiWithMetrics(ApiRequest request) {
    long startTime = System.currentTimeMillis();
    String outcome;
    
    try {
        ApiResponse response = callWithRetry(request, 3);
        outcome = "success";
        return response;
        
    } catch (Exception e) {
        outcome = e.getClass().getSimpleName();
        throw e;
        
    } finally {
        long duration = System.currentTimeMillis() - startTime;
        
        requestMetrics.recordWithOutcome(
            duration,
            outcome,
            "operation", "api_call_with_retry",
            "max_retries", "3"
        );
    }
}
```

---

## 📈 Prometheus Queries

### Success Rate
```promql
rate(fraud_switch_fraud_router_requests_total{outcome="success"}[5m])
```

### Failure Rate by Outcome
```promql
sum by (outcome) (
  rate(fraud_switch_fraud_router_errors_total[5m])
)
```

### Success Percentage
```promql
(
  rate(fraud_switch_fraud_router_requests_total{outcome="success"}[5m])
  /
  (
    rate(fraud_switch_fraud_router_requests_total[5m]) +
    rate(fraud_switch_fraud_router_errors_total[5m])
  )
) * 100
```

### Latency by Outcome
```promql
histogram_quantile(0.99,
  sum by (outcome, le) (
    rate(fraud_switch_fraud_router_request_duration_ms_bucket[5m])
  )
)
```

---

## ⚠️ Common Mistakes

### ❌ MISTAKE 1: Not using "success" exactly
```java
// WRONG - Won't trigger success path
recordWithOutcome(duration, "Success");   // Capital S still works (case-insensitive)
recordWithOutcome(duration, "ok");        // ❌ Won't work! Not "success"
recordWithOutcome(duration, "passed");    // ❌ Won't work!

// CORRECT
recordWithOutcome(duration, "success");   // ✅
recordWithOutcome(duration, "Success");   // ✅ Case-insensitive
recordWithOutcome(duration, "SUCCESS");   // ✅ Case-insensitive
```

### ❌ MISTAKE 2: Forgetting to initialize outcome
```java
// WRONG - outcome might be null
String outcome;
try {
    doWork();
    outcome = "success";
} catch (Exception e) {
    // Forgot to set outcome here!
} finally {
    recordWithOutcome(duration, outcome);  // ❌ NullPointerException!
}

// CORRECT
String outcome = "UnknownError";  // Default value
try {
    doWork();
    outcome = "success";
} catch (Exception e) {
    outcome = e.getClass().getSimpleName();
} finally {
    recordWithOutcome(duration, outcome);  // ✅ Always has value
}
```

### ❌ MISTAKE 3: Using when direct method is clearer
```java
// WRONG - Unnecessary complexity
try {
    result = doWork();
    recordWithOutcome(duration, "success");
} catch (Exception e) {
    recordWithOutcome(duration, e.getClass().getSimpleName());
}

// CORRECT - More direct
try {
    result = doWork();
    requestMetrics.recordRequest(duration);
} catch (Exception e) {
    requestMetrics.recordError(duration, e.getClass().getSimpleName());
}
```

---

## 📝 Best Practices

### ✅ DO:
1. Use with try-finally pattern
2. Initialize outcome with default value
3. Use "success" (any case) for success
4. Use exception class names for errors
5. Keep outcome values to limited set

### ❌ DON'T:
1. Use when direct methods are clearer
2. Create too many unique outcomes
3. Use arbitrary success strings ("ok", "passed", etc.)
4. Forget to set outcome in catch block

---

## 🎯 Decision Tree: Which Method to Use?

```
Do you know success/error at call time?
├─ YES → Use direct methods
│   ├─ Success → recordRequest()
│   └─ Error → recordError()
│
└─ NO (determined dynamically)
    └─ Do success and error need same labels?
        ├─ YES → recordWithOutcome() ✅
        └─ NO → Use direct methods
```

---

## 📊 Summary Table

| Method | When to Use | Outcome |
|--------|-------------|---------|
| `recordRequest()` | Know it's successful | Always success |
| `recordError()` | Know it's failed | Always error |
| `recordWithOutcome()` | Determined at runtime | Based on outcome string |

---

## ✅ Quick Checklist

Before using recordWithOutcome():

- [ ] Outcome determined dynamically
- [ ] Same labels for success and error
- [ ] Using try-finally pattern
- [ ] Initialized outcome variable with default
- [ ] Using "success" for success cases
- [ ] Limited set of outcome values (< 50)

---

**Ready for the next method?** Type "next" for **Method 4: `startTimer()` and manual timing**!
