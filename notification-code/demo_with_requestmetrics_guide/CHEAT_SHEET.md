# RequestMetrics - One-Page Cheat Sheet

## 📚 Six Methods Quick Reference

```java
// 1. ✅ Record successful request
recordRequest(durationMs, "endpoint", "/api/test", "method", "POST")

// 2. ❌ Record failed request
recordError(durationMs, "TimeoutException", "endpoint", "/api/test")

// 3. 🔄 Record with dynamic outcome
recordWithOutcome(durationMs, outcome, "endpoint", "/api/test")

// 4. ⏱️ Start manual timer
Timer.Sample sample = startTimer()

// 5. 📊 Get timer reference
Timer timer = getRequestTimer()

// 6. 📈 Record throughput
recordThroughput(150.5, "service", "fraud-router")
```

---

## 🎯 When to Use Which Method

| Scenario | Method | Code Pattern |
|----------|--------|--------------|
| **Known success** | `recordRequest()` | `try { work(); recordRequest(); }` |
| **Known error** | `recordError()` | `catch (e) { recordError(); }` |
| **Dynamic outcome** | `recordWithOutcome()` | `finally { recordWithOutcome(); }` |
| **Async operation** | `startTimer()` + `getRequestTimer()` | `sample = start(); ... stop(timer);` |
| **Current load** | `recordThroughput()` | `@Scheduled ... recordThroughput();` |

---

## 💻 Common Patterns

### Pattern 1: Standard REST Endpoint (90% of cases)
```java
long start = System.currentTimeMillis();
try {
    Result r = doWork();
    requestMetrics.recordRequest(System.currentTimeMillis() - start);
    return r;
} catch (Exception e) {
    requestMetrics.recordError(System.currentTimeMillis() - start, 
                               e.getClass().getSimpleName());
    throw e;
}
```

### Pattern 2: Try-Finally with Dynamic Outcome
```java
String outcome = "UnknownError";
try {
    doWork();
    outcome = "success";
} catch (Exception e) {
    outcome = e.getClass().getSimpleName();
} finally {
    requestMetrics.recordWithOutcome(duration, outcome);
}
```

### Pattern 3: Async Operation
```java
Timer.Sample sample = requestMetrics.startTimer();
CompletableFuture.supplyAsync(() -> {
    doWork();
    long d = sample.stop(requestMetrics.getRequestTimer());
    requestMetrics.recordRequest(d);
});
```

### Pattern 4: Throughput Tracking
```java
AtomicLong counter = new AtomicLong();

@Scheduled(fixedRate = 10000)
void calc() {
    long count = counter.getAndSet(0);
    double rps = count / 10.0;
    requestMetrics.recordThroughput(rps);
}
```

---

## 📊 Metrics Created

```prometheus
# Counters (Rate)
fraud_switch_fraud_router_requests_total{endpoint="..."} 1000
fraud_switch_fraud_router_errors_total{error_type="..."} 23

# Histograms (Duration)
fraud_switch_fraud_router_request_duration_ms_bucket{le="100"} 950
fraud_switch_fraud_router_request_duration_ms_sum 87000
fraud_switch_fraud_router_request_duration_ms_count 1000

# Percentiles (Latency)
fraud_switch_fraud_router_request_duration_ms{quantile="0.99"} 95

# Gauges (Throughput)
fraud_switch_fraud_router_throughput_rps 150.5
```

---

## ⚠️ Common Mistakes

```java
// ❌ WRONG
recordRequest(0);  // Duration = 0
recordRequest(100, "endpoint");  // Odd number of labels
recordRequest(100, "user_id", userId);  // High cardinality
recordError(100, e.getMessage());  // Use class name
recordThroughput(totalCount);  // Count, not rate

// ✅ CORRECT
long d = System.currentTimeMillis() - start;
recordRequest(d, "endpoint", "/api/test");  // Even labels
recordRequest(d, "user_type", "premium");  // Low cardinality
recordError(d, e.getClass().getSimpleName());  // Class name
recordThroughput(requestsPerSecond);  // Actual RPS
```

---

## 📋 Quick Checklist

- [ ] Calculate duration from start time
- [ ] Even number of label arguments
- [ ] Label cardinality < 100 unique values
- [ ] No PII/sensitive data in labels
- [ ] Use exception class name (not message)
- [ ] Record both success AND error
- [ ] "success" for recordWithOutcome() success
- [ ] Reset counters for throughput calculation

---

## 🎯 Decision Tree

```
Need to record metrics?
    ↓
Synchronous code?
    ├─ YES
    │   ↓
    │   Know success/error at call time?
    │   ├─ YES → recordRequest() or recordError()
    │   └─ NO → recordWithOutcome()
    │
    └─ NO (Async)
        └─ startTimer() + getRequestTimer()

Need current RPS?
    └─ recordThroughput()
```

---

## 🚀 Quick Start Example

```java
@RestController
public class MyController {
    
    @Autowired
    private RequestMetrics requestMetrics;
    
    @GetMapping("/api/test")
    public Result test() {
        long start = System.currentTimeMillis();
        
        try {
            Result result = doWork();
            
            // Record success
            requestMetrics.recordRequest(
                System.currentTimeMillis() - start,
                "endpoint", "/api/test",
                "method", "GET"
            );
            
            return result;
            
        } catch (Exception e) {
            // Record error
            requestMetrics.recordError(
                System.currentTimeMillis() - start,
                e.getClass().getSimpleName(),
                "endpoint", "/api/test",
                "method", "GET"
            );
            throw e;
        }
    }
}
```

---

## 📈 Prometheus Queries

```promql
# Request rate (RPS)
rate(fraud_switch_fraud_router_requests_total[5m])

# Error rate
rate(fraud_switch_fraud_router_errors_total[5m])

# P99 latency
histogram_quantile(0.99, 
  rate(fraud_switch_fraud_router_request_duration_ms_bucket[5m]))

# Error percentage
rate(fraud_switch_fraud_router_errors_total[5m]) 
/ 
rate(fraud_switch_fraud_router_requests_total[5m]) * 100

# Current throughput
fraud_switch_fraud_router_throughput_rps
```

---

## ✅ Best Practices

1. **Simple is better** - Use recordRequest/recordError for 90%
2. **Low cardinality** - Keep unique label values under 100
3. **Consistent naming** - Same labels across service
4. **Always measure** - Both success and error paths
5. **Test first** - Verify metrics in Prometheus
6. **Monitor cost** - Keep overhead < 1% CPU

---

## 🎓 Core Concepts

**RED Pattern**: Rate, Errors, Duration
- **Rate**: How many requests? (counter)
- **Errors**: How many failures? (counter)
- **Duration**: How long? (histogram)

**Cardinality**: Unique combinations of label values
- Keep LOW to avoid metric explosion
- Enforce limits with CardinalityEnforcer

**Sampling**: Record only 10% of histograms
- Reduces CPU/memory by 90%
- Percentiles still accurate

---

**Print this page! Keep it nearby! 📄**

For complete guides, see the individual method documentation files.
