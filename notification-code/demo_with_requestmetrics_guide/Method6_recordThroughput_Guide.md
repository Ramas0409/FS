# RequestMetrics Method 6: recordThroughput()

## 📋 Method Signature
```java
public void recordThroughput(double rps, String... labels)
```

## 🎯 Purpose
Records **current throughput** (requests per second) as a **Gauge metric**. Unlike counters that always increment, gauges can go up and down to show the current value.

---

## 📖 Parameters Explained

### 1. `rps` (double)
- **Requests Per Second** value
- Current throughput rate
- Can be any positive double value
- Examples: 150.5, 1000.0, 23.7

### 2. `labels` (String... varargs)
- Optional additional labels
- Same as other methods (key-value pairs)

---

## 🔍 Understanding Gauges vs Counters

### Counter (requests_total)
```prometheus
# Always goes up
fraud_switch_fraud_router_requests_total 1000
fraud_switch_fraud_router_requests_total 1001
fraud_switch_fraud_router_requests_total 1002
fraud_switch_fraud_router_requests_total 1003
```
**Use:** Total count over time

---

### Gauge (throughput_rps)
```prometheus
# Can go up or down
fraud_switch_fraud_router_throughput_rps 150.5
fraud_switch_fraud_router_throughput_rps 200.3  # Increased
fraud_switch_fraud_router_throughput_rps 175.8  # Decreased
fraud_switch_fraud_router_throughput_rps 180.0  # Increased again
```
**Use:** Current instantaneous value

---

## 💻 Basic Usage Examples

### Example 1: Periodic Throughput Calculation

```java
@Component
public class ThroughputTracker {
    
    @Autowired
    private RequestMetrics requestMetrics;
    
    private final AtomicLong requestCount = new AtomicLong(0);
    private long lastResetTime = System.currentTimeMillis();
    
    /**
     * Called on every request
     */
    public void recordRequest() {
        requestCount.incrementAndGet();
    }
    
    /**
     * Calculate and record throughput every 10 seconds
     */
    @Scheduled(fixedRate = 10000)  // Every 10 seconds
    public void calculateThroughput() {
        long now = System.currentTimeMillis();
        long count = requestCount.getAndSet(0);  // Reset counter
        long elapsed = now - lastResetTime;
        lastResetTime = now;
        
        // Calculate RPS
        double rps = (count * 1000.0) / elapsed;
        
        // Record throughput
        requestMetrics.recordThroughput(
            rps,
            "service", "fraud-router"
        );
        
        log.info("Current throughput: {} RPS", String.format("%.2f", rps));
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_fraud_router_throughput_rps{
  service="fraud-router",
  region="us-ohio-1"
} 150.5
```

---

### Example 2: Real-Time Throughput with Sliding Window

```java
@Component
public class SlidingWindowThroughput {
    
    @Autowired
    private RequestMetrics requestMetrics;
    
    // Store timestamps of last 100 requests
    private final Queue<Long> requestTimestamps = new ConcurrentLinkedQueue<>();
    private final int WINDOW_SIZE = 100;
    
    /**
     * Called on every request
     */
    public void recordRequest() {
        long now = System.currentTimeMillis();
        requestTimestamps.offer(now);
        
        // Keep only last 100 requests
        while (requestTimestamps.size() > WINDOW_SIZE) {
            requestTimestamps.poll();
        }
        
        // Calculate throughput from sliding window
        if (requestTimestamps.size() >= 10) {  // Need at least 10 samples
            calculateAndRecordThroughput();
        }
    }
    
    private void calculateAndRecordThroughput() {
        Long first = requestTimestamps.peek();
        Long last = ((ConcurrentLinkedQueue<Long>) requestTimestamps).stream()
                .reduce((a, b) -> b)
                .orElse(null);
        
        if (first != null && last != null && last > first) {
            long windowMs = last - first;
            int count = requestTimestamps.size();
            
            // Calculate RPS
            double rps = (count * 1000.0) / windowMs;
            
            // Record throughput
            requestMetrics.recordThroughput(rps);
        }
    }
}
```

---

### Example 3: Per-Endpoint Throughput

```java
@Component
public class EndpointThroughputTracker {
    
    @Autowired
    private RequestMetrics requestMetrics;
    
    // Track requests per endpoint
    private final Map<String, AtomicLong> endpointCounts = new ConcurrentHashMap<>();
    private long lastCalculationTime = System.currentTimeMillis();
    
    /**
     * Record request for specific endpoint
     */
    public void recordRequest(String endpoint) {
        endpointCounts.computeIfAbsent(endpoint, k -> new AtomicLong(0))
                     .incrementAndGet();
    }
    
    /**
     * Calculate throughput per endpoint every 30 seconds
     */
    @Scheduled(fixedRate = 30000)
    public void calculateEndpointThroughput() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastCalculationTime;
        
        endpointCounts.forEach((endpoint, counter) -> {
            long count = counter.getAndSet(0);
            double rps = (count * 1000.0) / elapsed;
            
            // Record per-endpoint throughput
            requestMetrics.recordThroughput(
                rps,
                "endpoint", endpoint
            );
            
            log.debug("Endpoint {} throughput: {} RPS", endpoint, rps);
        });
        
        lastCalculationTime = now;
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_fraud_router_throughput_rps{
  endpoint="/api/fraud/check"
} 87.3

fraud_switch_fraud_router_throughput_rps{
  endpoint="/api/users"
} 45.6

fraud_switch_fraud_router_throughput_rps{
  endpoint="/api/payments"
} 23.1
```

---

### Example 4: Kafka Consumer Throughput

```java
@Component
public class KafkaConsumerThroughput {
    
    @Autowired
    private RequestMetrics requestMetrics;
    
    private final Map<String, AtomicLong> topicMessageCounts = new ConcurrentHashMap<>();
    private long lastResetTime = System.currentTimeMillis();
    
    @KafkaListener(topics = "fraud-events")
    public void consumeFraudEvents(FraudEvent event) {
        // Track message
        topicMessageCounts.computeIfAbsent("fraud-events", k -> new AtomicLong(0))
                         .incrementAndGet();
        
        // Process event
        processFraudEvent(event);
    }
    
    @KafkaListener(topics = "transactions")
    public void consumeTransactions(Transaction txn) {
        // Track message
        topicMessageCounts.computeIfAbsent("transactions", k -> new AtomicLong(0))
                         .incrementAndGet();
        
        // Process transaction
        processTransaction(txn);
    }
    
    /**
     * Calculate consumption rate every 15 seconds
     */
    @Scheduled(fixedRate = 15000)
    public void calculateConsumptionRate() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastResetTime;
        
        topicMessageCounts.forEach((topic, counter) -> {
            long count = counter.getAndSet(0);
            double messagesPerSecond = (count * 1000.0) / elapsed;
            
            requestMetrics.recordThroughput(
                messagesPerSecond,
                "operation", "kafka_consume",
                "topic", topic
            );
            
            log.info("Kafka topic {} consumption rate: {} msg/sec", 
                    topic, String.format("%.2f", messagesPerSecond));
        });
        
        lastResetTime = now;
    }
}
```

---

## 🎯 When to Use recordThroughput()

### ✅ Good Use Cases:

1. **Monitoring current load**
   - "What's my current RPS?"
   - "Is traffic increasing or decreasing?"

2. **Capacity planning**
   - "Am I approaching my throughput limit?"
   - "Do I need to scale?"

3. **Per-component throughput**
   - "Which endpoints are busiest?"
   - "Which Kafka topics have highest volume?"

4. **Real-time dashboards**
   - Live RPS gauges
   - Traffic visualization

5. **Alerting on throughput**
   - "Alert if RPS > 1000"
   - "Alert if RPS drops to 0"

---

### ❌ When NOT to Use:

1. **Total request counting**
   ```java
   // DON'T do this:
   recordThroughput(totalRequests);  // ❌ Use counters instead
   
   // DO this:
   recordRequest(duration);  // ✅ Counter tracks totals
   ```

2. **Historical analysis**
   - Gauges show current value, not history
   - Use counters for historical trends
   - Prometheus can calculate rate from counters

3. **When Prometheus can calculate it**
   ```promql
   # Prometheus can calculate RPS from counters:
   rate(fraud_switch_fraud_router_requests_total[5m])
   
   # So you don't always need to record throughput manually
   ```

---

## 🔄 Throughput vs Rate (Counter-based)

### Manual Throughput Gauge
```java
recordThroughput(150.5);
```
**Prometheus:**
```prometheus
fraud_switch_fraud_router_throughput_rps 150.5
```
**Query:**
```promql
fraud_switch_fraud_router_throughput_rps
```

**Pros:**
- ✅ Instant current value
- ✅ Can see real-time RPS
- ✅ Good for live dashboards

**Cons:**
- ⚠️ Only shows current snapshot
- ⚠️ No historical trend
- ⚠️ Manual calculation required

---

### Counter-based Rate
```java
recordRequest(duration);  // Just count requests
```
**Prometheus:**
```prometheus
fraud_switch_fraud_router_requests_total 10000
```
**Query:**
```promql
rate(fraud_switch_fraud_router_requests_total[5m])
```

**Pros:**
- ✅ Automatic rate calculation
- ✅ Historical trends
- ✅ No manual calculation
- ✅ More accurate over time

**Cons:**
- ⚠️ Requires scrape interval
- ⚠️ Slight delay (based on window)

---

## 📊 Complete Example: Load Monitoring Service

```java
@Component
@Slf4j
public class LoadMonitoringService {
    
    @Autowired
    private RequestMetrics requestMetrics;
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    // Track requests in memory
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successRequests = new AtomicLong(0);
    private final AtomicLong errorRequests = new AtomicLong(0);
    
    // Track per endpoint
    private final Map<String, AtomicLong> endpointRequests = new ConcurrentHashMap<>();
    
    // Timing
    private long lastCalculationTime = System.currentTimeMillis();
    
    /**
     * Called by interceptor on every request
     */
    public void recordRequestReceived(String endpoint) {
        totalRequests.incrementAndGet();
        endpointRequests.computeIfAbsent(endpoint, k -> new AtomicLong(0))
                       .incrementAndGet();
    }
    
    /**
     * Called when request completes successfully
     */
    public void recordRequestSuccess(String endpoint) {
        successRequests.incrementAndGet();
    }
    
    /**
     * Called when request fails
     */
    public void recordRequestError(String endpoint) {
        errorRequests.incrementAndGet();
    }
    
    /**
     * Calculate and record throughput metrics every 10 seconds
     */
    @Scheduled(fixedRate = 10000)
    public void calculateAndRecordMetrics() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastCalculationTime;
        
        // Calculate overall throughput
        long total = totalRequests.getAndSet(0);
        double totalRps = (total * 1000.0) / elapsed;
        
        long success = successRequests.getAndSet(0);
        double successRps = (success * 1000.0) / elapsed;
        
        long errors = errorRequests.getAndSet(0);
        double errorRps = (errors * 1000.0) / elapsed;
        
        // Record overall throughput
        requestMetrics.recordThroughput(
            totalRps,
            "type", "total"
        );
        
        requestMetrics.recordThroughput(
            successRps,
            "type", "success"
        );
        
        requestMetrics.recordThroughput(
            errorRps,
            "type", "error"
        );
        
        // Calculate error rate percentage
        double errorRate = total > 0 ? (errors * 100.0) / total : 0.0;
        
        // Log summary
        log.info("Throughput - Total: {}, Success: {}, Error: {} RPS | Error Rate: {}%",
                String.format("%.2f", totalRps),
                String.format("%.2f", successRps),
                String.format("%.2f", errorRps),
                String.format("%.2f", errorRate));
        
        // Calculate per-endpoint throughput
        endpointRequests.forEach((endpoint, counter) -> {
            long count = counter.getAndSet(0);
            double rps = (count * 1000.0) / elapsed;
            
            if (rps > 0) {  // Only record if there was traffic
                requestMetrics.recordThroughput(
                    rps,
                    "endpoint", endpoint
                );
            }
        });
        
        // Check if we're approaching limits
        if (totalRps > 800) {
            log.warn("High throughput detected: {} RPS (threshold: 800)", totalRps);
        }
        
        if (errorRate > 5.0) {
            log.error("High error rate detected: {}% (threshold: 5%)", errorRate);
        }
        
        lastCalculationTime = now;
    }
    
    /**
     * Expose current throughput for health checks
     */
    public double getCurrentThroughput() {
        // Get current value from Prometheus registry
        Gauge gauge = meterRegistry.find("fraud_switch_fraud_router_throughput_rps")
                .tag("type", "total")
                .gauge();
        
        return gauge != null ? gauge.value() : 0.0;
    }
}
```

**Dashboard queries:**
```promql
# Current total throughput
fraud_switch_fraud_router_throughput_rps{type="total"}

# Current success throughput
fraud_switch_fraud_router_throughput_rps{type="success"}

# Current error throughput
fraud_switch_fraud_router_throughput_rps{type="error"}

# Per-endpoint throughput
fraud_switch_fraud_router_throughput_rps{endpoint!=""}

# Alert if throughput > 1000 RPS
fraud_switch_fraud_router_throughput_rps{type="total"} > 1000

# Alert if throughput drops to 0 (service down)
fraud_switch_fraud_router_throughput_rps{type="total"} == 0
```

---

## ⚠️ Common Mistakes

### ❌ MISTAKE 1: Recording total count instead of rate
```java
// WRONG - This is a count, not a rate!
long totalRequests = 10000;
recordThroughput(totalRequests);  // ❌

// CORRECT - Calculate actual RPS
long requestsInWindow = 150;
long windowMs = 1000;
double rps = (requestsInWindow * 1000.0) / windowMs;
recordThroughput(rps);  // ✅ 150 RPS
```

### ❌ MISTAKE 2: Not resetting counters
```java
// WRONG - Counter keeps growing!
AtomicLong counter = new AtomicLong(0);

@Scheduled(fixedRate = 10000)
public void calculate() {
    double rps = counter.get() / 10.0;  // ❌ Includes old requests!
    recordThroughput(rps);
}

// CORRECT - Reset after calculation
@Scheduled(fixedRate = 10000)
public void calculate() {
    long count = counter.getAndSet(0);  // ✅ Reset
    double rps = count / 10.0;
    recordThroughput(rps);
}
```

### ❌ MISTAKE 3: Wrong time window
```java
// WRONG - Time window doesn't match schedule
long count = counter.getAndSet(0);
double rps = count / 10.0;  // Assumes 10 seconds

@Scheduled(fixedRate = 5000)  // ❌ Actually runs every 5 seconds!
public void calculate() {
    recordThroughput(rps);
}

// CORRECT - Calculate elapsed time
long lastTime = System.currentTimeMillis();

@Scheduled(fixedRate = 5000)
public void calculate() {
    long now = System.currentTimeMillis();
    long elapsed = now - lastTime;
    long count = counter.getAndSet(0);
    double rps = (count * 1000.0) / elapsed;  // ✅ Accurate
    recordThroughput(rps);
    lastTime = now;
}
```

### ❌ MISTAKE 4: Recording on every request
```java
// WRONG - Updates gauge on EVERY request
@GetMapping("/api/test")
public Result test() {
    recordThroughput(getCurrentRPS());  // ❌ Too frequent!
    return doWork();
}

// CORRECT - Calculate periodically
@Scheduled(fixedRate = 10000)
public void calculate() {
    recordThroughput(getCurrentRPS());  // ✅ Every 10 seconds
}
```

---

## 📝 Best Practices

### ✅ DO:

1. **Calculate periodically** (every 10-30 seconds)
   ```java
   @Scheduled(fixedRate = 10000)
   public void calculateThroughput() {
       // Calculate and record
   }
   ```

2. **Reset counters after calculation**
   ```java
   long count = counter.getAndSet(0);
   ```

3. **Use actual elapsed time**
   ```java
   long elapsed = now - lastTime;
   double rps = (count * 1000.0) / elapsed;
   ```

4. **Add meaningful labels**
   ```java
   recordThroughput(rps, "endpoint", "/api/fraud", "method", "POST");
   ```

5. **Consider using Prometheus rate() instead**
   ```promql
   rate(fraud_switch_fraud_router_requests_total[5m])
   ```

---

### ❌ DON'T:

1. Don't record total counts
2. Don't forget to reset counters
3. Don't hardcode time windows
4. Don't update too frequently (waste)
5. Don't update too rarely (inaccurate)

---

## 🎯 When to Use Gauges vs Counter Rates

### Use recordThroughput() (Gauge) when:
- ✅ Need instant current value
- ✅ Building real-time dashboards
- ✅ Alerting on current load
- ✅ Capacity monitoring
- ✅ Show "right now" metrics

### Use Counter + rate() when:
- ✅ Need historical trends
- ✅ Calculating averages over time
- ✅ Don't need instant values
- ✅ Want Prometheus to calculate
- ✅ More accurate long-term

---

## 📊 Prometheus Queries

### Current throughput
```promql
fraud_switch_fraud_router_throughput_rps
```

### Throughput by endpoint
```promql
fraud_switch_fraud_router_throughput_rps{endpoint!=""}
```

### Sum of all endpoint throughput
```promql
sum(fraud_switch_fraud_router_throughput_rps)
```

### Alert if throughput exceeds limit
```promql
fraud_switch_fraud_router_throughput_rps > 1000
```

### Alert if throughput drops (service issue)
```promql
fraud_switch_fraud_router_throughput_rps < 1
```

---

## ✅ Quick Checklist

Before using recordThroughput():

- [ ] Really need a gauge (vs counter rate)?
- [ ] Calculating actual RPS (not total count)
- [ ] Resetting counters after calculation
- [ ] Using actual elapsed time
- [ ] Updating periodically (not too often/rare)
- [ ] Adding meaningful labels
- [ ] Considered using Prometheus rate() instead

---

## 🎯 Summary

**recordThroughput()** records current throughput as a gauge:

```java
double rps = (requestCount * 1000.0) / elapsedMs;
requestMetrics.recordThroughput(rps, labels...);
```

**Use for:**
- Real-time load monitoring
- Current capacity tracking
- Live dashboards
- Instant throughput values

**Consider instead:**
- Counter + `rate()` for historical analysis
- Simpler and often more accurate

---

## 🎉 CONGRATULATIONS!

You've completed all 6 RequestMetrics methods! 

**[View Complete Summary](computer:///mnt/user-data/outputs/All_Methods_Summary.md)** ← Next, I'll create this!

Type **"summary"** to see a final overview of all methods!
