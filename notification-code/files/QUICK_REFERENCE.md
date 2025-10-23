# Fraud Switch Metrics Library - Quick Reference

## 1-Minute Quick Start

### Add Dependency
```xml
<dependency>
    <groupId>com.fraudswitch</groupId>
    <artifactId>fraud-switch-metrics-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Configure
```yaml
fraud-switch:
  metrics:
    service-name: fraud-router  # REQUIRED
    region: us-ohio-1          # REQUIRED
```

### Use
```java
@Autowired
private FraudRouterMetrics metrics;

// Record request
metrics.getRequestMetrics().recordRequest(durationMs, "label", "value");

// Record error
metrics.getRequestMetrics().recordError(durationMs, "TimeoutException");
```

## Common Patterns

### Pattern 1: Basic Request Metrics
```java
Timer.Sample sample = requestMetrics.startTimer();
try {
    doWork();
    requestMetrics.recordRequest(
        sample.stop(requestMetrics.getRequestTimer()),
        "event_type", "auth"
    );
} catch (Exception e) {
    requestMetrics.recordError(
        sample.stop(requestMetrics.getRequestTimer()),
        e.getClass().getSimpleName()
    );
}
```

### Pattern 2: Fraud Router Routing Decision
```java
metrics.recordRoutingDecision(
    eventType,      // "auth", "capture", "refund"
    gateway,        // "stripe", "adyen"
    product,        // "fraud_sight", "guaranteed_payment"
    provider,       // "Ravelin", "Signifyd"
    strategy,       // "primary", "fallback"
    durationMs
);
```

### Pattern 3: Parallel Execution
```java
metrics.recordParallelCall("boarding", true, 20L);
metrics.recordParallelCall("rules", true, 15L);
metrics.recordParallelCall("bin_lookup", false, 10L, "TimeoutException");
```

### Pattern 4: Kafka Publishing
```java
kafkaMetrics.recordPublish("pan.queue", 0, durationMs);
// or
metrics.recordPanQueuePublish(success, durationMs, errorType);
```

### Pattern 5: Kafka Consumption
```java
kafkaMetrics.recordConsume(
    "async.events",           // topic
    0,                        // partition
    "processor-group",        // consumer group
    durationMs
);

kafkaMetrics.recordConsumerLag("async.events", 0, "processor-group", lag);
```

## Metric Names Cheat Sheet

### Common (All Services)
```
fraud_switch.{service}.requests_total
fraud_switch.{service}.request_duration_ms
fraud_switch.{service}.errors_total
```

### Fraud Router
```
fraud_switch.fraud_router.routing_decisions_total
fraud_switch.fraud_router.routing_duration_ms
fraud_switch.fraud_router.parallel_calls_total
fraud_switch.fraud_router.pan_queue_publish_total
```

### Adapters (FraudSight/GuaranteedPayment)
```
fraud_switch.{adapter}.ravelin_calls_total
fraud_switch.{adapter}.signifyd_calls_total
fraud_switch.{adapter}.kafka_publish_total
```

### Kafka Services
```
fraud_switch.{service}.kafka_consumed_total
fraud_switch.{service}.kafka_consumption_lag
fraud_switch.{service}.kafka_consumption_errors_total
```

## Label Names Cheat Sheet

### Common Labels (Always Available)
```java
MetricLabels.Common.SERVICE        // "fraud-router"
MetricLabels.Common.REGION         // "us-ohio-1"
MetricLabels.Common.ENVIRONMENT    // "prod"
MetricLabels.Common.STATUS         // "success", "error"
MetricLabels.Common.ERROR_TYPE     // Exception class name
```

### Request Labels
```java
MetricLabels.Request.EVENT_TYPE       // "auth", "capture", "refund"
MetricLabels.Request.GATEWAY          // "stripe", "adyen"
MetricLabels.Request.PRODUCT          // "fraud_sight", "guaranteed_payment"
MetricLabels.Request.PAYMENT_METHOD   // "credit_card", "debit_card"
```

### Provider Labels (Canonical Names)
```java
MetricLabels.Provider.RAVELIN     // "Ravelin"
MetricLabels.Provider.SIGNIFYD    // "Signifyd"
```

### Kafka Labels (Canonical Topics)
```java
MetricLabels.Kafka.TOPIC_PAN_QUEUE       // "pan.queue"
MetricLabels.Kafka.TOPIC_ASYNC_EVENTS    // "async.events"
MetricLabels.Kafka.TOPIC_FS_TRANSACTIONS // "fs.transactions"
MetricLabels.Kafka.TOPIC_FS_DECLINES     // "fs.declines"
```

## Configuration Reference

### Minimal Configuration
```yaml
fraud-switch:
  metrics:
    service-name: fraud-router
    region: us-ohio-1
```

### Full Configuration
```yaml
fraud-switch:
  metrics:
    enabled: true
    service-name: fraud-router
    region: us-ohio-1
    environment: prod
    
    cardinality:
      enforcement-enabled: true
      max-labels-per-metric: 1000
      max-values-per-label: 100
      action: LOG  # LOG, DROP, CIRCUIT_BREAK
    
    histogram:
      buckets:
        fraud-router: [10, 25, 50, 75, 100, 150, 200]
    
    sampling:
      enabled: false
      histogram-sample-rate: 1.0
    
    circuit-breaker:
      enabled: true
      failure-threshold: 5
      open-duration: PT5M
```

## Prometheus Queries

### Request Rate (RPS)
```promql
rate(fraud_switch_fraud_router_requests_total[5m])
```

### Error Rate
```promql
rate(fraud_switch_fraud_router_errors_total[5m]) 
/ 
rate(fraud_switch_fraud_router_requests_total[5m])
```

### P99 Latency
```promql
histogram_quantile(0.99, 
  rate(fraud_switch_fraud_router_request_duration_ms_bucket[5m])
)
```

### By Provider
```promql
sum by (provider) (
  rate(fraud_switch_fraud_router_routing_decisions_total[5m])
)
```

### Cardinality Check
```promql
fraud_switch_cardinality_total_label_combinations
fraud_switch_cardinality_circuit_breaker_state
```

## Common Issues & Solutions

### Issue: Metrics not appearing
**Check:**
1. `fraud-switch.metrics.enabled=true`
2. Service name matches: fraud-router, rules-service, etc.
3. Actuator endpoint exposed: `/actuator/prometheus`

### Issue: High cardinality warning
**Solution:**
```yaml
fraud-switch:
  metrics:
    cardinality:
      max-labels-per-metric: 2000  # Increase limit
      action: LOG                  # Or DROP/CIRCUIT_BREAK
```

### Issue: Circuit breaker opened
**Recovery:**
1. Fix cardinality issues in code
2. Wait for open-duration (default 5 min)
3. Circuit auto-transitions to HALF_OPEN → CLOSED

### Issue: Performance impact
**Solution:**
```yaml
fraud-switch:
  metrics:
    sampling:
      enabled: true
      histogram-sample-rate: 0.1  # Sample 10%
```

## Testing Locally

### 1. View Metrics
```bash
curl http://localhost:8080/actuator/prometheus | grep fraud_switch
```

### 2. Check Cardinality
```bash
curl http://localhost:8080/actuator/metrics/cardinality.stats
```

### 3. Load Test
```bash
# Generate traffic
for i in {1..1000}; do
  curl -X POST http://localhost:8080/api/fraud/screen \
    -H "Content-Type: application/json" \
    -d '{"eventType":"auth","gateway":"stripe"}'
done

# Monitor metrics
watch -n 1 'curl -s http://localhost:8080/actuator/prometheus | grep fraud_switch_fraud_router_requests_total'
```

## Service-Specific Beans

### Fraud Router
```java
@Autowired private FraudRouterMetrics metrics;
```

### All Other Services
```java
@Autowired private RequestMetrics requestMetrics;
@Autowired private KafkaMetrics kafkaMetrics;
```

## Performance Targets

- **CPU Overhead:** < 1%
- **Memory Overhead:** < 80 MB
- **Latency Impact:** Negligible (< 1%)
- **Throughput:** 300 TPS sync + 1000 TPS async per region

## Links

- **Full Documentation:** [README.md](./README.md)
- **Deployment Guide:** [DEPLOYMENT.md](./DEPLOYMENT.md)
- **API Examples:** [Example App](./src/test/java/com/fraudswitch/metrics/example/)
- **Support:** platform-team@fraudswitch.com
- **Slack:** #metrics-library

---

**Version:** 1.0.0  
**Quick Ref Updated:** October 22, 2025
