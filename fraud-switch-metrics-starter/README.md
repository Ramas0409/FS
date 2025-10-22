# Fraud Switch Metrics Spring Boot Starter

[![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)](https://github.com/fraudswitch/metrics-starter)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.1.5-green.svg)](https://spring.io/projects/spring-boot)
[![Micrometer](https://img.shields.io/badge/Micrometer-1.11.5-orange.svg)](https://micrometer.io/)

Centralized metrics library for Fraud Switch microservices providing standardized instrumentation, cardinality enforcement, and RED (Rate, Errors, Duration) pattern metrics.

## Features

✅ **Standardized Metrics** - Consistent naming across all 8 microservices  
✅ **Zero Boilerplate** - Spring Boot auto-configuration with single dependency  
✅ **Type Safety** - Compile-time validation with constant classes  
✅ **Cardinality Protection** - Runtime enforcement prevents metric explosions  
✅ **Performance Optimized** - <1% CPU overhead, <80MB memory  
✅ **Service-Specific** - Custom metrics for each service's business logic  
✅ **PCI Compliant** - No sensitive data in metric labels  
✅ **Production Ready** - Circuit breaker, sampling, backward compatibility  

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>com.fraudswitch</groupId>
    <artifactId>fraud-switch-metrics-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Configure Your Service

```yaml
# application.yml
fraud-switch:
  metrics:
    enabled: true
    service-name: fraud-router  # Use canonical name
    region: us-ohio-1           # or uk-london-1
    environment: prod
```

### 3. Inject and Use

```java
@RestController
@RequiredArgsConstructor
public class FraudController {
    
    private final FraudRouterMetrics metrics;
    
    @PostMapping("/screen")
    public FraudDecision screen(@RequestBody Request request) {
        Timer.Sample sample = metrics.getRequestMetrics().startTimer();
        
        try {
            // Business logic
            FraudDecision decision = processRequest(request);
            
            // Record success
            long duration = sample.stop(metrics.getRequestMetrics().getRequestTimer());
            metrics.recordRoutingDecision(
                request.getEventType(),
                request.getGateway(),
                request.getProduct(),
                decision.getProvider(),
                decision.getStrategy(),
                duration
            );
            
            return decision;
            
        } catch (Exception e) {
            // Record error
            long duration = sample.stop(metrics.getRequestMetrics().getRequestTimer());
            metrics.getRequestMetrics().recordError(
                duration, 
                e.getClass().getSimpleName()
            );
            throw e;
        }
    }
}
```

## Architecture

### Metric Naming Convention

```
fraud_switch.{service}.{metric}
```

**Examples:**
- `fraud_switch.fraud_router.requests_total`
- `fraud_switch.rules_service.evaluations_total`
- `fraud_switch.fraudsight_adapter.ravelin_calls_total`

### Label Standards

All labels use canonical names from `canonical-architecture.md`:

```java
// Common labels
MetricLabels.Common.SERVICE      // "fraud-router"
MetricLabels.Common.REGION       // "us-ohio-1"
MetricLabels.Common.ENVIRONMENT  // "prod"

// Provider labels (canonical names)
MetricLabels.Provider.RAVELIN    // "Ravelin"
MetricLabels.Provider.SIGNIFYD   // "Signifyd"

// Kafka topic labels (canonical names)
MetricLabels.Kafka.TOPIC_PAN_QUEUE     // "pan.queue"
MetricLabels.Kafka.TOPIC_ASYNC_EVENTS  // "async.events"
```

## Service-Specific Metrics

### Fraud Router

```java
@Autowired
private FraudRouterMetrics metrics;

// Routing decisions
metrics.recordRoutingDecision(
    "auth", "stripe", "fraud_sight", "Ravelin", "primary", 50L
);

// Parallel execution
metrics.recordParallelCall("boarding", true, 20L);
metrics.recordParallelCall("rules", true, 15L);
metrics.recordParallelCall("bin_lookup", true, 10L);

// PAN queue publishing
metrics.recordPanQueuePublish(true, 25L);

// Fallback decisions
metrics.recordFallbackDecision("Ravelin", "Signifyd", "circuit_open");
```

### Rules Service / BIN Lookup / Adapters

```java
@Autowired
private RequestMetrics requestMetrics;

// RED pattern metrics
requestMetrics.recordRequest(durationMs, 
    MetricLabels.Request.EVENT_TYPE, "auth",
    MetricLabels.Request.GATEWAY, "stripe"
);

requestMetrics.recordError(durationMs, "TimeoutException",
    MetricLabels.Request.EVENT_TYPE, "auth"
);
```

### Async Processor / Tokenization Service

```java
@Autowired
private KafkaMetrics kafkaMetrics;

// Kafka consumption
kafkaMetrics.recordConsume("async.events", 0, "processor-group", 150L);

// Consumer lag
kafkaMetrics.recordConsumerLag("async.events", 0, "processor-group", 42L);

// Message publishing
kafkaMetrics.recordPublish("fs.transactions", 0, 25L);
```

## Cardinality Enforcement

### Configuration

```yaml
fraud-switch:
  metrics:
    cardinality:
      enforcement-enabled: true
      max-labels-per-metric: 1000      # Max unique label combinations
      max-values-per-label: 100        # Max unique values per label
      check-interval: PT1M             # Check every 1 minute
      action: LOG                      # LOG, DROP, or CIRCUIT_BREAK
    
    circuit-breaker:
      enabled: true
      failure-threshold: 5             # Violations before opening
      open-duration: PT5M              # Stay open for 5 minutes
      half-open-duration: PT1M         # Test for 1 minute before closing
```

### Actions

- **LOG** - Log warning, continue recording (default)
- **DROP** - Drop metrics exceeding limits
- **CIRCUIT_BREAK** - Temporarily stop recording all metrics

### Monitoring

```java
@Autowired
private CardinalityEnforcer enforcer;

CardinalityEnforcer.CardinalityStats stats = enforcer.getStats();
System.out.println(stats); 
// CardinalityStats{totalMetrics=8, totalLabelCombinations=247, 
//                  maxLabelCombinations=89, circuitBreakerState=CLOSED, 
//                  violationsCount=0}
```

## Histogram Configuration

Service-specific buckets optimize for latency profiles:

```yaml
fraud-switch:
  metrics:
    histogram:
      buckets:
        fraud-router: [10, 25, 50, 75, 100, 150, 200]         # p99 < 100ms
        fraudsight-adapter: [25, 50, 75, 100, 150, 200]       # p99 < 100ms
        guaranteed-payment-adapter: [50, 100, 200, 300, 350, 500]  # p99 < 350ms
        async-processor: [100, 250, 500, 1000, 2000]          # Async
```

## Prometheus Metrics Exported

### RED Pattern (All Services)

```prometheus
# Rate
fraud_switch_fraud_router_requests_total{service="fraud-router",region="us-ohio-1"} 15000

# Errors
fraud_switch_fraud_router_errors_total{error_type="TimeoutException"} 23

# Duration
fraud_switch_fraud_router_request_duration_ms_bucket{le="100"} 14500
fraud_switch_fraud_router_request_duration_ms_sum 562000
fraud_switch_fraud_router_request_duration_ms_count 15000
```

### Business Metrics (Fraud Router)

```prometheus
fraud_switch_fraud_router_routing_decisions_total{provider="Ravelin",strategy="primary"} 12000
fraud_switch_fraud_router_fallback_decisions_total{primary_provider="Ravelin"} 5
fraud_switch_fraud_router_parallel_calls_total{call_type="boarding",status="success"} 15000
```

## Grafana Dashboards

Import pre-built dashboards from `src/main/resources/grafana/`:

- **fraud-switch-overview.json** - Platform-wide metrics
- **fraud-router-dashboard.json** - Service-specific metrics
- **cardinality-monitoring.json** - Cardinality enforcement stats

## Prometheus Alerts

Import alert rules from `src/main/resources/prometheus/`:

```yaml
groups:
  - name: fraud_switch_sla
    rules:
      - alert: HighLatency
        expr: histogram_quantile(0.99, fraud_switch_fraud_router_request_duration_ms_bucket) > 100
        for: 5m
        annotations:
          summary: "FraudRouter p99 latency > 100ms"
      
      - alert: HighErrorRate
        expr: rate(fraud_switch_fraud_router_errors_total[5m]) > 0.01
        for: 2m
        annotations:
          summary: "FraudRouter error rate > 1%"
```

## Performance

### Memory Overhead

| Configuration | Memory per Service |
|---------------|-------------------|
| Default (no sampling) | 60-80 MB |
| With 10% histogram sampling | 20-30 MB |

### CPU Overhead

< 1% CPU overhead for metric recording (negligible impact on p99 latency).

### Throughput

Handles 300 TPS sync + 1000 TPS async per region with no performance degradation.

## Advanced Configuration

### Custom Histogram Buckets

```yaml
fraud-switch:
  metrics:
    histogram:
      buckets:
        my-service: [5, 10, 25, 50, 100, 250, 500]
```

### Sampling (Optional)

Enable sampling to reduce cardinality:

```yaml
fraud-switch:
  metrics:
    sampling:
      enabled: true
      histogram-sample-rate: 0.1        # Sample 10% of histogram metrics
      high-cardinality-sample-rate: 0.05 # Sample 5% of high-cardinality metrics
```

### Common Labels

Add custom labels to all metrics:

```yaml
fraud-switch:
  metrics:
    common-labels:
      datacenter: "dc-east-1"
      cluster: "prod-cluster-a"
```

## Testing

### Unit Tests

```java
@Test
void shouldRecordMetrics() {
    MeterRegistry registry = new SimpleMeterRegistry();
    MetricsConfigurationProperties config = createTestConfig();
    CardinalityEnforcer enforcer = new CardinalityEnforcer(config);
    
    RequestMetrics metrics = new RequestMetrics(
        registry, enforcer, config, "test.service"
    );
    
    metrics.recordRequest(50L, "event_type", "auth");
    
    Counter counter = registry.find("test.service.requests_total").counter();
    assertThat(counter.count()).isEqualTo(1.0);
}
```

### Integration Tests

```java
@SpringBootTest
@TestPropertySource(properties = {
    "fraud-switch.metrics.service-name=fraud-router",
    "fraud-switch.metrics.region=us-ohio-1"
})
class MetricsIntegrationTest {
    
    @Autowired
    private FraudRouterMetrics metrics;
    
    @Test
    void shouldAutoConfigureMetrics() {
        assertThat(metrics).isNotNull();
    }
}
```

## Migration Guide

### From Existing Metrics

**Before:**
```java
Counter.builder("fraud_router_requests")  // ❌ No standard prefix
    .tag("type", eventType)               // ❌ Inconsistent label
    .register(registry)
    .increment();
```

**After:**
```java
requestMetrics.recordRequest(durationMs,
    MetricLabels.Request.EVENT_TYPE, eventType  // ✅ Type-safe, consistent
);
```

### Backward Compatibility

The library provides a `DeprecationHandler` for 6-month migration period:

```java
@Bean
public DeprecationHandler deprecationHandler() {
    return DeprecationHandler.builder()
        .mapOldToNew("fraud_router_requests", "fraud_switch.fraud_router.requests_total")
        .deprecationPeriod(Duration.ofDays(180))
        .build();
}
```

## Troubleshooting

### Metrics Not Appearing

1. Check configuration:
```yaml
fraud-switch:
  metrics:
    enabled: true  # Must be true
```

2. Verify service name:
```yaml
fraud-switch:
  metrics:
    service-name: fraud-router  # Must match one of: fraud-router, rules-service, etc.
```

3. Check actuator endpoints:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus
```

### High Cardinality Warnings

```
WARN CardinalityEnforcer - Cardinality violation detected for metric 
'fraud_switch.fraud_router.requests_total': 1200 combinations exceeds limit of 1000
```

**Solutions:**
1. Reduce label cardinality (avoid unbounded values like transaction IDs)
2. Increase limits in configuration
3. Enable sampling for high-cardinality metrics

### Circuit Breaker Opened

```
ERROR CardinalityEnforcer - Circuit breaker OPENED due to cardinality violations
```

**Recovery:**
1. Fix cardinality issues in application code
2. Wait for `open-duration` to elapse (default 5 minutes)
3. Circuit breaker will transition to HALF_OPEN for testing
4. If no violations during `half-open-duration`, circuit closes

## Support

- **Documentation:** [Internal Wiki](https://wiki.fraudswitch.com/metrics)
- **Issues:** [JIRA Project](https://jira.fraudswitch.com/projects/METRICS)
- **Team:** Platform Team (platform-team@fraudswitch.com)

## References

- [Solution Document v2.0](./docs/centralized_metrics_library_solution_v1.md)
- [Canonical Architecture v3.0](./docs/canonical-architecture.md)
- [Micrometer Documentation](https://micrometer.io/docs)
- [Prometheus Best Practices](https://prometheus.io/docs/practices/naming/)

## License

Copyright © 2025 Fraud Switch. All rights reserved.

---

**Version:** 1.0.0  
**Last Updated:** October 22, 2025  
**Status:** ✅ Production Ready
