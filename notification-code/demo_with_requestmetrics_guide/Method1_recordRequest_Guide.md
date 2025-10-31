# RequestMetrics Method 1: recordRequest()

## 📋 Method Signature
```java
public void recordRequest(long durationMs, String... labels)
```

## 🎯 Purpose
Records a **SUCCESSFUL** request with its duration. Core method for RED pattern metrics.

---

## 📊 What Metrics Are Created

When you call: `requestMetrics.recordRequest(87, "endpoint", "/api/test", "method", "POST")`

### 1. **Counter** (Rate)
```prometheus
fraud_switch_fraud_router_requests_total{
  service="fraud-router",
  region="us-ohio-1",
  endpoint="/api/test",
  method="POST"
} 1
```
Increments by 1 each time you call the method.

### 2. **Histogram** (Duration Distribution)
```prometheus
fraud_switch_fraud_router_request_duration_ms_bucket{
  service="fraud-router",
  endpoint="/api/test",
  le="50"
} 0

fraud_switch_fraud_router_request_duration_ms_bucket{
  service="fraud-router",
  endpoint="/api/test",
  le="100"
} 1

fraud_switch_fraud_router_request_duration_ms_sum{...} 87
fraud_switch_fraud_router_request_duration_ms_count{...} 1
```

### 3. **Percentiles** (Latency)
```prometheus
fraud_switch_fraud_router_request_duration_ms{
  service="fraud-router",
  endpoint="/api/test",
  quantile="0.50"
} 87

fraud_switch_fraud_router_request_duration_ms{
  service="fraud-router",
  endpoint="/api/test",
  quantile="0.99"
} 87
```

---

## 💻 Basic Usage

### Example 1: Simple Request (No Labels)
```java
@GetMapping("/hello")
public String hello() {
    long startTime = System.currentTimeMillis();
    
    // Do work
    String result = doWork();
    
    // Calculate duration
    long duration = System.currentTimeMillis() - startTime;
    
    // Record metrics
    requestMetrics.recordRequest(duration);
    
    return result;
}
```

**What happens:**
- ✅ Request counter increments
- ✅ Duration recorded: 50ms
- ✅ Automatic labels: `service="fraud-router"`, `region="us-ohio-1"`

---

### Example 2: With Endpoint Labels
```java
@PostMapping("/users")
public String createUser(@RequestBody User user) {
    long startTime = System.currentTimeMillis();
    
    // Create user
    saveUser(user);
    
    long duration = System.currentTimeMillis() - startTime;
    
    // Record with labels
    requestMetrics.recordRequest(
        duration,
        "endpoint", "/api/users",
        "method", "POST"
    );
    
    return "Created";
}
```

**What happens:**
- ✅ Request counter increments with labels: `endpoint="/api/users"`, `method="POST"`
- ✅ Duration recorded with same labels
- ✅ Can now query: "How many POST requests to /api/users?"

---

### Example 3: With Business Context
```java
@PostMapping("/fraud-check")
public FraudCheckResponse checkFraud(@RequestBody FraudCheckRequest request) {
    long startTime = System.currentTimeMillis();
    
    // Process fraud check
    FraudCheckResponse response = processFraudCheck(request);
    
    long duration = System.currentTimeMillis() - startTime;
    
    // Record with business labels
    requestMetrics.recordRequest(
        duration,
        "endpoint", "/api/fraud-check",
        "method", "POST",
        "transaction_type", request.getTransactionType(),  // auth, capture
        "payment_method", request.getPaymentMethod(),      // stripe, adyen
        "provider", response.getProvider()                 // Ravelin, Signifyd
    );
    
    return response;
}
```

**What happens:**
- ✅ Request counter increments with business context
- ✅ Can answer: "How many auth requests via Stripe?"
- ✅ Can answer: "What's p99 latency for Ravelin requests?"

---

## 🔄 Complete Request Flow

```
┌─────────────────────────────────────────────────┐
│ 1. Start Timer                                  │
│    long startTime = System.currentTimeMillis(); │
└────────────────────┬────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│ 2. Execute Business Logic                       │
│    result = doWork();                           │
└────────────────────┬────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│ 3. Calculate Duration                           │
│    long duration = currentTimeMillis() - start; │
└────────────────────┬────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│ 4. Record Metrics                               │
│    requestMetrics.recordRequest(duration, ...); │
└────────────────────┬────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│ 5. Metrics Created in Prometheus:               │
│    • Counter incremented                        │
│    • Duration histogram updated                 │
│    • Percentiles calculated                     │
└─────────────────────────────────────────────────┘
```

---

## ⚠️ Common Mistakes

### ❌ MISTAKE 1: Odd number of labels
```java
// WRONG - "method" has no value
requestMetrics.recordRequest(100, "endpoint", "/api/test", "method");

// CORRECT
requestMetrics.recordRequest(100, "endpoint", "/api/test", "method", "GET");
```

### ❌ MISTAKE 2: Using for errors
```java
// WRONG - Don't use recordRequest for errors
try {
    doWork();
} catch (Exception e) {
    requestMetrics.recordRequest(duration);  // ❌ Wrong!
}

// CORRECT - Use recordError for errors
try {
    doWork();
} catch (Exception e) {
    requestMetrics.recordError(duration, e.getClass().getSimpleName());  // ✅ Correct
}
```

### ❌ MISTAKE 3: High cardinality labels
```java
// WRONG - user_id could have millions of unique values
requestMetrics.recordRequest(100, "user_id", userId);  // ❌ Metric explosion!

// CORRECT - Use limited set of values
requestMetrics.recordRequest(100, "user_type", "premium");  // ✅ Only a few types
```

### ❌ MISTAKE 4: Duration always zero
```java
// WRONG - Duration not calculated
requestMetrics.recordRequest(0);  // ❌ Always 0ms!

// CORRECT
long startTime = System.currentTimeMillis();
doWork();
long duration = System.currentTimeMillis() - startTime;
requestMetrics.recordRequest(duration);  // ✅ Actual duration
```

---

## 📝 Label Best Practices

### ✅ GOOD Labels (Low Cardinality)
- `endpoint` - API path (< 50 unique values)
- `method` - HTTP method (5-10 unique values)
- `transaction_type` - auth, capture, refund (3-5 values)
- `payment_method` - stripe, adyen, braintree (5-10 values)
- `status` - success, failure (2-3 values)
- `provider` - Ravelin, Signifyd (2-5 values)

### ❌ BAD Labels (High Cardinality)
- `user_id` - Could be millions
- `transaction_id` - Unique per request
- `timestamp` - Always unique
- `session_id` - Unique per session
- `ip_address` - Thousands of values
- `email` - Personal data + high cardinality

---

## 🎯 When to Use

✅ **Use recordRequest() for:**
- Successful API requests
- Background job completions
- Database query successes
- External API call successes
- Message processing successes

❌ **Don't use recordRequest() for:**
- Failed requests → Use `recordError()`
- Requests with errors → Use `recordError()`
- Timeouts → Use `recordError()`

---

## 📊 Prometheus Queries

Once you've recorded metrics, query them:

```promql
# Total requests per second
rate(fraud_switch_fraud_router_requests_total[5m])

# P99 latency by endpoint
histogram_quantile(0.99, 
  rate(fraud_switch_fraud_router_request_duration_ms_bucket[5m])
)

# Requests by payment method
sum by (payment_method) (
  fraud_switch_fraud_router_requests_total
)

# Average duration over 5 minutes
rate(fraud_switch_fraud_router_request_duration_ms_sum[5m]) 
/ 
rate(fraud_switch_fraud_router_request_duration_ms_count[5m])
```

---

## ✅ Quick Checklist

Before using recordRequest():

- [ ] Started timer at beginning of request
- [ ] Calculated duration properly
- [ ] Only using for SUCCESSFUL requests
- [ ] Labels are key-value pairs (even number)
- [ ] Label values have low cardinality (< 100 unique values)
- [ ] No sensitive data in labels (PCI compliant)
- [ ] Labels are consistent across service

---

**Ready to move to the next method?** 

Type "next" and I'll explain `recordError()` method with examples!
