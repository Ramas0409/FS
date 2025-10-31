# 🎉 RequestMetrics - Complete Method Guide

## Congratulations! You've Mastered All 6 Methods!

---

## 📚 All Methods At A Glance

```java
public class RequestMetrics {
    
    // ✅ Method 1: Record successful request
    public void recordRequest(long durationMs, String... labels)
    
    // ❌ Method 2: Record failed request  
    public void recordError(long durationMs, String errorType, String... labels)
    
    // 🔄 Method 3: Record with dynamic outcome
    public void recordWithOutcome(long durationMs, String outcome, String... labels)
    
    // ⏱️ Method 4: Start manual timer
    public Timer.Sample startTimer()
    
    // 📊 Method 5: Get pre-registered timer
    public Timer getRequestTimer()
    
    // 📈 Method 6: Record throughput
    public void recordThroughput(double rps, String... labels)
}
```

---

## 🎯 Quick Selection Guide

```
What do you need to do?
│
├─ Record a completed request?
│  ├─ Success → recordRequest()
│  ├─ Error → recordError()
│  └─ Dynamic → recordWithOutcome()
│
├─ Manual timing needed?
│  └─ startTimer() + getRequestTimer()
│
└─ Track current load?
   └─ recordThroughput()
```

---

## 📊 Method Comparison Table

| Method | Use For | Pattern | Frequency | Complexity |
|--------|---------|---------|-----------|------------|
| `recordRequest()` | Successful requests | Try-catch success | Per request | ⭐ Easy |
| `recordError()` | Failed requests | Catch block | Per error | ⭐ Easy |
| `recordWithOutcome()` | Dynamic outcome | Try-finally | Per request | ⭐⭐ Medium |
| `startTimer()` | Manual timing start | Async/callbacks | When needed | ⭐⭐⭐ Advanced |
| `getRequestTimer()` | Timer reference | With startTimer() | When needed | ⭐⭐⭐ Advanced |
| `recordThroughput()` | Current RPS | Scheduled job | Every 10-30s | ⭐⭐ Medium |

---

## 💻 All Methods - Complete Example

```java
@RestController
@RequestMapping("/api/fraud")
public class CompleteFraudController {
    
    @Autowired
    private RequestMetrics requestMetrics;
    
    @Autowired
    private FraudService fraudService;
    
    // Track requests for throughput calculation
    private final AtomicLong requestCounter = new AtomicLong(0);
    private long lastThroughputCalc = System.currentTimeMillis();
    
    // =========================================================================
    // METHOD 1 & 2: recordRequest() and recordError()
    // =========================================================================
    
    /**
     * Standard synchronous endpoint
     * Uses recordRequest() for success, recordError() for failures
     */
    @PostMapping("/check")
    public FraudCheckResponse checkFraud(@RequestBody FraudCheckRequest request) {
        long startTime = System.currentTimeMillis();
        requestCounter.incrementAndGet();
        
        try {
            // Process fraud check
            FraudCheckResponse response = fraudService.check(request);
            
            // ✅ SUCCESS - Method 1
            long duration = System.currentTimeMillis() - startTime;
            requestMetrics.recordRequest(
                duration,
                "endpoint", "/api/fraud/check",
                "method", "POST",
                "provider", response.getProvider()
            );
            
            return response;
            
        } catch (TimeoutException e) {
            // ❌ ERROR - Method 2
            long duration = System.currentTimeMillis() - startTime;
            requestMetrics.recordError(
                duration,
                "ProviderTimeout",
                "endpoint", "/api/fraud/check",
                "method", "POST"
            );
            throw e;
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            requestMetrics.recordError(
                duration,
                e.getClass().getSimpleName(),
                "endpoint", "/api/fraud/check"
            );
            throw e;
        }
    }
    
    // =========================================================================
    // METHOD 3: recordWithOutcome()
    // =========================================================================
    
    /**
     * Endpoint with try-finally pattern
     * Uses recordWithOutcome() for dynamic outcome determination
     */
    @PostMapping("/check/retry")
    public FraudCheckResponse checkWithRetry(@RequestBody FraudCheckRequest request) {
        long startTime = System.currentTimeMillis();
        String outcome = "UnknownError";
        requestCounter.incrementAndGet();
        
        try {
            // Try with retries
            FraudCheckResponse response = fraudService.checkWithRetry(request, 3);
            outcome = "success";
            return response;
            
        } catch (TimeoutException e) {
            outcome = "TimeoutAfterRetries";
            throw e;
            
        } catch (Exception e) {
            outcome = e.getClass().getSimpleName();
            throw e;
            
        } finally {
            // 🔄 DYNAMIC - Method 3
            long duration = System.currentTimeMillis() - startTime;
            requestMetrics.recordWithOutcome(
                duration,
                outcome,
                "endpoint", "/api/fraud/check/retry",
                "max_retries", "3"
            );
        }
    }
    
    // =========================================================================
    // METHODS 4 & 5: startTimer() and getRequestTimer()
    // =========================================================================
    
    /**
     * Async endpoint with manual timing
     * Uses startTimer() and getRequestTimer() for async operations
     */
    @PostMapping("/check/async")
    public CompletableFuture<FraudCheckResponse> checkAsync(
            @RequestBody FraudCheckRequest request) {
        
        requestCounter.incrementAndGet();
        
        // ⏱️ METHOD 4: Start timer
        Timer.Sample sample = requestMetrics.startTimer();
        
        return fraudService.checkAsync(request)
            .thenApply(response -> {
                // Stop timer and get duration
                long duration = sample.stop(requestMetrics.getRequestTimer());
                
                requestMetrics.recordRequest(
                    duration,
                    "endpoint", "/api/fraud/check/async",
                    "execution", "async"
                );
                
                return response;
            })
            .exceptionally(error -> {
                long duration = sample.stop(requestMetrics.getRequestTimer());
                
                requestMetrics.recordError(
                    duration,
                    error.getClass().getSimpleName(),
                    "endpoint", "/api/fraud/check/async"
                );
                
                throw new RuntimeException(error);
            });
    }
    
    /**
     * Batch processing with per-item timing
     */
    @PostMapping("/check/batch")
    public BatchResponse checkBatch(@RequestBody List<FraudCheckRequest> requests) {
        long overallStart = System.currentTimeMillis();
        List<FraudCheckResponse> responses = new ArrayList<>();
        
        for (FraudCheckRequest request : requests) {
            requestCounter.incrementAndGet();
            
            // ⏱️ METHOD 4 & 5: Time each item individually
            Timer.Sample itemSample = requestMetrics.startTimer();
            
            try {
                FraudCheckResponse response = fraudService.check(request);
                responses.add(response);
                
                // Record individual item timing
                long itemDuration = itemSample.stop(requestMetrics.getRequestTimer());
                requestMetrics.recordRequest(itemDuration, "operation", "batch_item");
                
            } catch (Exception e) {
                long itemDuration = itemSample.stop(requestMetrics.getRequestTimer());
                requestMetrics.recordError(itemDuration, e.getClass().getSimpleName());
                
                responses.add(FraudCheckResponse.error(request.getTransactionId()));
            }
        }
        
        // Record overall batch timing
        long overallDuration = System.currentTimeMillis() - overallStart;
        requestMetrics.recordRequest(
            overallDuration,
            "endpoint", "/api/fraud/check/batch",
            "batch_size", String.valueOf(requests.size())
        );
        
        return new BatchResponse(responses);
    }
    
    // =========================================================================
    // METHOD 6: recordThroughput()
    // =========================================================================
    
    /**
     * Calculate and record throughput every 10 seconds
     */
    @Scheduled(fixedRate = 10000)
    public void calculateThroughput() {
        long now = System.currentTimeMillis();
        long count = requestCounter.getAndSet(0);
        long elapsed = now - lastThroughputCalc;
        
        // Calculate RPS
        double rps = (count * 1000.0) / elapsed;
        
        // 📈 METHOD 6: Record throughput
        requestMetrics.recordThroughput(
            rps,
            "service", "fraud-router"
        );
        
        log.info("Current throughput: {} RPS", String.format("%.2f", rps));
        
        lastThroughputCalc = now;
    }
}
```

---

## 🎓 Method Usage Breakdown

### Method 1: recordRequest() - 90% of cases
```java
// Simple, clear, for successful requests
long startTime = System.currentTimeMillis();
Result result = doWork();
long duration = System.currentTimeMillis() - startTime;
requestMetrics.recordRequest(duration, "endpoint", "/api/test");
```

**When:** Known successful request  
**Creates:** `requests_total` counter + duration histogram  
**Use:** Default choice for success paths

---

### Method 2: recordError() - Error handling
```java
// For all failures and exceptions
catch (Exception e) {
    long duration = System.currentTimeMillis() - startTime;
    requestMetrics.recordError(
        duration,
        e.getClass().getSimpleName(),
        "endpoint", "/api/test"
    );
}
```

**When:** Known failed request  
**Creates:** `errors_total` counter + duration histogram  
**Use:** All catch blocks

---

### Method 3: recordWithOutcome() - Dynamic outcome
```java
// When outcome determined at runtime
String outcome;
try {
    doWork();
    outcome = "success";
} catch (Exception e) {
    outcome = e.getClass().getSimpleName();
} finally {
    requestMetrics.recordWithOutcome(duration, outcome);
}
```

**When:** Try-finally pattern, multiple outcomes  
**Creates:** Calls recordRequest() or recordError() based on outcome  
**Use:** Complex flows with dynamic results

---

### Methods 4 & 5: Manual timing - Advanced cases
```java
// For async operations and callbacks
Timer.Sample sample = requestMetrics.startTimer();

CompletableFuture.supplyAsync(() -> {
    doWork();
    long duration = sample.stop(requestMetrics.getRequestTimer());
    requestMetrics.recordRequest(duration);
});
```

**When:** Async code, callbacks, reactive streams  
**Creates:** Timer samples for flexible timing  
**Use:** When simple timestamps don't work

---

### Method 6: recordThroughput() - Load monitoring
```java
// Calculate and record periodically
@Scheduled(fixedRate = 10000)
public void calculate() {
    long count = counter.getAndSet(0);
    double rps = (count * 1000.0) / elapsed;
    requestMetrics.recordThroughput(rps);
}
```

**When:** Need current RPS gauge  
**Creates:** `throughput_rps` gauge  
**Use:** Real-time load monitoring, capacity planning

---

## 📊 Metrics Created Summary

```prometheus
# Method 1: recordRequest()
fraud_switch_fraud_router_requests_total{...} 1000
fraud_switch_fraud_router_request_duration_ms{...} 87

# Method 2: recordError()
fraud_switch_fraud_router_errors_total{error_type="TimeoutException"} 23
fraud_switch_fraud_router_request_duration_ms{...} 5000

# Method 3: recordWithOutcome() 
# (creates same as Method 1 or 2 based on outcome)
fraud_switch_fraud_router_requests_total{outcome="success"} 950
fraud_switch_fraud_router_errors_total{outcome="TimeoutException"} 50

# Methods 4 & 5: startTimer() + getRequestTimer()
# (used with Methods 1-3 to record timing)

# Method 6: recordThroughput()
fraud_switch_fraud_router_throughput_rps{...} 150.5
```

---

## 🎯 Decision Matrix

| Scenario | Best Method(s) | Why |
|----------|---------------|-----|
| REST API endpoint | 1 + 2 | Clear try-catch |
| Background job | 3 | Try-finally, multiple outcomes |
| Async operation | 4 + 5 + 1/2 | Manual timing needed |
| Kafka consumer | 1 + 2 | Standard pattern |
| Database query | 1 + 2 | Simple success/error |
| Batch processing | 4 + 5 + 1/2 | Per-item timing |
| Load monitoring | 6 | Current throughput tracking |
| External API call | 1 + 2 or 3 | Depends on retry logic |

---

## ✅ Complete Best Practices Checklist

### General (All Methods):
- [ ] Calculate duration from actual start time
- [ ] Keep label cardinality low (< 100 unique values)
- [ ] Use consistent label names across service
- [ ] No sensitive data in labels (PCI compliant)
- [ ] Labels are key-value pairs (even count)
- [ ] Record metrics for both success and error

### recordRequest():
- [ ] Only for successful requests
- [ ] Include business context labels
- [ ] Called in try block or after success

### recordError():
- [ ] Only for failed requests
- [ ] Use exception class name (not message)
- [ ] Called in catch blocks
- [ ] Include error context

### recordWithOutcome():
- [ ] Initialize outcome variable with default
- [ ] Use "success" for success (case-insensitive)
- [ ] Set outcome in all code paths
- [ ] Used in finally block

### Manual Timing:
- [ ] Really need manual timing?
- [ ] Start timer at correct point
- [ ] Remember to stop timer
- [ ] Use correct timer in stop() call
- [ ] Handle exceptions (try-finally)

### recordThroughput():
- [ ] Calculate actual RPS (not total count)
- [ ] Reset counters after calculation
- [ ] Use actual elapsed time
- [ ] Update periodically (10-30s)
- [ ] Consider if counter rate() is better

---

## 📚 All Documentation Links

| Method | Guide | Additional |
|--------|-------|-----------|
| recordRequest() | [Full Guide](Method1_recordRequest_Guide.md) | [Sampling Explained](Histogram_Sampling_Explained.md) |
| recordError() | [Full Guide](Method2_recordError_Guide.md) | [Comparison](Method_Comparison.md) |
| recordWithOutcome() | [Full Guide](Method3_recordWithOutcome_Guide.md) | [Selection Guide](Method_Selection_Guide.md) |
| Manual Timing | [Full Guide](Method4_5_Manual_Timing_Guide.md) | [Complete Reference](Complete_Method_Reference.md) |
| recordThroughput() | [Full Guide](Method6_recordThroughput_Guide.md) | - |

---

## 🎉 You're Now a Metrics Expert!

### What You've Learned:

✅ **6 RequestMetrics methods** - When and how to use each  
✅ **RED pattern** - Rate, Errors, Duration tracking  
✅ **Cardinality management** - Preventing metric explosion  
✅ **Histogram sampling** - Performance optimization  
✅ **Manual timing** - Advanced patterns for async code  
✅ **Throughput tracking** - Real-time load monitoring  
✅ **Best practices** - Production-ready patterns  

---

## 🚀 Next Steps

1. **Practice** - Implement in your fraud-router-demo
2. **Test** - Verify metrics appear in Prometheus
3. **Query** - Create Prometheus queries and alerts
4. **Dashboard** - Build Grafana dashboards
5. **Scale** - Apply to all 8 microservices

---

## 📞 Quick Reference Card

```java
// Success
requestMetrics.recordRequest(duration, labels...);

// Error
requestMetrics.recordError(duration, errorType, labels...);

// Dynamic
requestMetrics.recordWithOutcome(duration, outcome, labels...);

// Manual timing
Timer.Sample s = requestMetrics.startTimer();
long d = s.stop(requestMetrics.getRequestTimer());

// Throughput
requestMetrics.recordThroughput(rps, labels...);
```

---

## 🎯 Golden Rules

1. **Start simple** - Use recordRequest/recordError for 90% of cases
2. **Be consistent** - Same pattern across your service
3. **Low cardinality** - Keep unique label values under 100
4. **Always record** - Both success AND error paths
5. **Test metrics** - Verify they appear in Prometheus
6. **Monitor performance** - Metrics overhead < 1% CPU

---

**Congratulations! You're ready to implement production-ready metrics! 🎉**
