# Architecture Review: Centralized Metrics Library Solution

**Project:** Fraud Switch - Centralized Metrics Library  
**Document Reviewed:** centralized_metrics_library_solution.md v1.0  
**Review Date:** October 14, 2025  
**Reviewer:** Architecture Review Board  
**Status:** 🟡 APPROVED WITH CONDITIONS

---

## Executive Summary

The proposed centralized metrics library is **architecturally sound** and addresses a real pain point (code duplication, inconsistent naming). The Spring Boot starter approach is appropriate, and the design aligns well with canonical-architecture.md.

**Recommendation:** ✅ **APPROVE WITH CONDITIONS**

**Conditions for Approval:**
1. Address all CRITICAL issues before Phase 1 implementation
2. Address HIGH priority issues before Phase 3 rollout
3. Performance validation gates in Phase 2 pilot

**Key Strengths:**
- Excellent alignment with canonical-architecture.md
- Comprehensive use of canonical names
- Strong type safety approach
- Realistic performance analysis
- Well-thought-out rollout plan

**Key Concerns:**
- Histogram bucket configuration may impact memory
- Cardinality management needs runtime enforcement
- Missing guidance on custom business metrics
- Test coverage for service-specific classes needs validation

---

## CRITICAL Issues (Must Fix Before Approval)

### CRITICAL #1: Histogram Memory Overhead Underestimated

**Section:** 6.2 Memory Overhead

**Issue:**  
Memory calculation assumes simple counters/timers but doesn't account for histogram buckets properly.

**Current Calculation:**
```
Total metric series: ~1640
In-memory storage: ~380 KB
```

**Problem:**
- Each histogram with 11 buckets (as defined in Section 13.2) creates 11+ time series
- Duration metrics are histograms: `request.duration.seconds`, `component.duration.seconds`, `kafka.publish.duration.seconds`
- Estimated histogram metrics: ~200
- Actual time series: 1640 + (200 × 11) = **3,840 time series**
- Prometheus memory: ~1KB per series = **3.8 MB** (not 380 KB)

**Impact:**
- Memory overhead closer to **60-80 MB per pod** (not 40-50 MB)
- Still acceptable (4% of 2GB) but calculation is misleading

**Recommendation:**
```markdown
## 6.2 Memory Overhead (CORRECTED)

Metric series breakdown:
- Counters: 1,200 series (~1.2 MB)
- Gauges: 240 series (~240 KB)
- Histograms: 200 metrics × 11 buckets = 2,200 series (~2.2 MB)
- Summary overhead: ~200 series (~200 KB)

**Total in-memory:** ~3.8 MB
**Micrometer overhead:** ~40 MB
**Histogram buffers:** ~20 MB

**Total per pod: 60-80 MB**
Current pod memory: 2 GB
**Impact: 3-4% of allocation** ✅ Acceptable
```

**Action Required:** Update Section 6.2 with corrected calculation before approval.

---

### CRITICAL #2: Cardinality Explosion Risk - No Runtime Enforcement

**Section:** Appendix B: Label Cardinality Guidelines

**Issue:**  
Document defines cardinality guidelines but provides **no runtime enforcement mechanism**. This is a production risk.

**Problem Scenario:**
```java
// Developer accidentally uses high-cardinality label
metrics.recordRequest(
    MetricLabels.EVENT_TYPE, eventType,
    MetricLabels.GATEWAY, gateway,
    "merchant_id", request.getMerchantId()  // ❌ High cardinality!
);
```

Result:
- 1000s of merchants × 7 event types × 6 gateways = **42,000 time series**
- Prometheus OOM
- No compile-time or runtime protection

**Current Mitigation:** Code review only (insufficient)

**Recommendation:**

Add runtime cardinality validator to `RequestMetrics`:

```java
public class RequestMetrics {
    
    private static final Set<String> ALLOWED_LABEL_KEYS = Set.of(
        MetricLabels.EVENT_TYPE,
        MetricLabels.GATEWAY,
        MetricLabels.PRODUCT,
        MetricLabels.STATUS
    );
    
    private final CardinalityMonitor cardinalityMonitor;
    
    public void recordRequest(String... labelKeysAndValues) {
        // Validate label keys
        for (int i = 0; i < labelKeysAndValues.length; i += 2) {
            String key = labelKeysAndValues[i];
            if (!ALLOWED_LABEL_KEYS.contains(key)) {
                log.error("Invalid label key for requests.total: {}", key);
                throw new IllegalArgumentException(
                    "Label key not allowed: " + key
                );
            }
        }
        
        // Track cardinality
        String labelCombination = buildLabelCombination(labelKeysAndValues);
        cardinalityMonitor.track(
            MetricNames.REQUESTS_TOTAL, 
            labelCombination
        );
        
        // Record metric
        requestCounter
            .tags(labelKeysAndValues)
            .register(meterRegistry)
            .increment();
    }
}
```

Add `CardinalityMonitor` bean:

```java
@Component
public class CardinalityMonitor {
    
    private final Map<String, Set<String>> labelCombinations = 
        new ConcurrentHashMap<>();
    
    private final int maxCardinalityPerMetric = 10000;
    
    public void track(String metricName, String labelCombination) {
        labelCombinations
            .computeIfAbsent(metricName, k -> ConcurrentHashMap.newKeySet())
            .add(labelCombination);
        
        int currentCardinality = labelCombinations.get(metricName).size();
        
        if (currentCardinality > maxCardinalityPerMetric) {
            log.error("Cardinality limit exceeded for {}: {} combinations",
                     metricName, currentCardinality);
            // Alert ops team
            alertService.sendAlert(
                "CRITICAL",
                "Metric cardinality exceeded: " + metricName
            );
        }
    }
    
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void reportCardinality() {
        labelCombinations.forEach((metric, combinations) -> {
            log.info("Metric {} has {} unique label combinations",
                    metric, combinations.size());
        });
    }
}
```

**Action Required:** Implement runtime cardinality validation before Phase 1.

---

### CRITICAL #3: Missing Integration Test for Auto-Configuration

**Section:** 8. Testing Strategy

**Issue:**  
Only unit tests defined. Missing **integration tests** to validate Spring Boot auto-configuration actually works in real applications.

**Problem:**
- Unit tests use `SimpleMeterRegistry` (mocks)
- Doesn't test `@ConditionalOnClass`, `@ConditionalOnMissingBean`
- Doesn't validate `spring.factories` configuration
- Real-world issues (classpath conflicts, bean ordering) won't be caught

**Recommendation:**

Add integration test module:

```
fraud-switch-metrics-spring-boot-starter-integration-tests/
├── pom.xml
└── src/test/java/
    └── FraudSwitchMetricsAutoConfigurationIT.java
```

**Integration Test:**

```java
@SpringBootTest(classes = TestApplication.class)
class FraudSwitchMetricsAutoConfigurationIT {
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    @Autowired(required = false)
    private FraudRouterMetrics fraudRouterMetrics;
    
    @Autowired(required = false)
    private RulesServiceMetrics rulesServiceMetrics;
    
    @Test
    void shouldAutoConfigureAllMetricBeans() {
        assertThat(fraudRouterMetrics).isNotNull();
        assertThat(rulesServiceMetrics).isNotNull();
    }
    
    @Test
    void shouldRegisterMetricsWithPrometheus() {
        fraudRouterMetrics.recordRequest(
            MetricLabels.EVENT_BEFORE_AUTH_SYNC,
            MetricLabels.GATEWAY_RAFT,
            MetricLabels.PRODUCT_FRAUD_SIGHT,
            MetricLabels.STATUS_SUCCESS
        );
        
        // Verify metric exists in Prometheus registry
        Meter meter = meterRegistry.find(
            "fraud_switch.fraud_router.requests.total"
        ).meter();
        
        assertThat(meter).isNotNull();
    }
    
    @Test
    void shouldRespectDisabledConfiguration() {
        // Test with fraud-switch.metrics.enabled=false
    }
}
```

**Action Required:** Add integration test module before Phase 2 pilot.

---

## HIGH Priority Issues (Address Before Phase 3)

### HIGH #1: No Guidance on Custom Business Metrics

**Section:** 4.3 Service-Specific Metrics

**Issue:**  
Library provides `FraudRouterMetrics.recordDecisionOverride()` as example, but **no clear pattern** for services to add their own business metrics.

**Problem:**
- What if Fraud Router needs a new metric: `pan_encryption_failures.total`?
- Should they add to library or implement locally?
- If local, how to ensure consistency?

**Current Guidance:** None

**Recommendation:**

Add to Section 4.3:

```markdown
### Adding Custom Business Metrics

**Pattern 1: Add to Service-Specific Class (Preferred)**

For metrics specific to one service, add to that service's metrics class:

```java
// In FraudRouterMetrics.java
public class FraudRouterMetrics {
    
    private final Counter.Builder panEncryptionFailureCounter;
    
    public FraudRouterMetrics(MeterRegistry meterRegistry) {
        // ... existing code ...
        
        this.panEncryptionFailureCounter = Counter.builder(
            MetricNames.buildName(
                MetricLabels.SERVICE_FRAUD_ROUTER, 
                "pan.encryption.failures.total"
            )
        ).description("Total PAN encryption failures");
    }
    
    public void recordPanEncryptionFailure(String errorCode) {
        panEncryptionFailureCounter
            .tag("error_code", errorCode)
            .register(meterRegistry)
            .increment();
    }
}
```

**Pattern 2: Extend Common Metrics (For Reusable Patterns)**

If multiple services need similar metrics:

```java
// In common/SecurityMetrics.java (NEW)
public class SecurityMetrics {
    public void recordEncryptionFailure(String operation, String errorCode);
    public void recordDecryptionFailure(String operation, String errorCode);
}
```

**Pattern 3: Local Implementation (Last Resort)**

Only if metric is experimental or temporary:

```java
@Component
public class ExperimentalMetrics {
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    public void recordExperiment(String experimentName) {
        Counter.builder("fraud_switch.fraud_router.experiment.total")
            .tag("experiment", experimentName)
            .register(meterRegistry)
            .increment();
    }
}
```

**Decision Tree:**
1. Metric used by 2+ services? → Add to Common
2. Metric specific to 1 service? → Add to Service-Specific
3. Metric experimental/temporary? → Local implementation
4. When in doubt? → Ask in #metrics-library Slack channel
```

**Action Required:** Add custom metrics guidance before Phase 3.

---

### HIGH #2: Histogram Buckets Not Optimized for Latency Targets

**Section:** 13.2 Implementation Questions - Histogram Buckets

**Issue:**  
Default histogram buckets don't align with **actual SLA targets** from canonical-architecture.md.

**Current Buckets:**
```
[0.01, 0.025, 0.05, 0.075, 0.1, 0.15, 0.2, 0.3, 0.5, 1.0, 2.0]
```

**SLA Targets (from canonical-architecture.md Section 1):**
- FraudSight: p99 < 100ms = 0.1s
- GuaranteedPayment: p99 < 350ms = 0.35s
- Rules Service: p99 ~10ms = 0.01s
- BIN Lookup: p99 ~100ms = 0.1s

**Problem:**
- No bucket at 0.35s (GuaranteedPayment p99)
- Buckets above 1.0s unnecessary (all timeouts < 1.5s)
- Missing granularity around critical thresholds

**Recommendation:**

**Per-Service Bucket Optimization:**

```yaml
# application.yml (in each service)
fraud-switch:
  metrics:
    histogram:
      buckets:
        # Fraud Router (0-500ms range)
        request.duration.seconds: [0.01, 0.025, 0.05, 0.075, 0.1, 0.15, 0.2, 0.3, 0.5]
        
        # GuaranteedPayment Adapter (0-1500ms range)
        provider.duration.seconds: [0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.5, 0.8, 1.0, 1.5]
        
        # Rules Service (0-50ms range)
        request.duration.seconds: [0.001, 0.005, 0.01, 0.015, 0.02, 0.03, 0.05]
```

**Library Support:**

```java
@ConfigurationProperties(prefix = "fraud-switch.metrics")
public class FraudSwitchMetricsProperties {
    
    private Map<String, double[]> histogramBuckets = new HashMap<>();
    
    public double[] getBucketsForMetric(String metricName) {
        return histogramBuckets.getOrDefault(
            metricName,
            DEFAULT_BUCKETS
        );
    }
}
```

Update `RequestMetrics`:

```java
this.requestTimer = Timer.builder(metricName)
    .description("Request duration")
    .serviceLevelObjectives(
        Duration.ofMillis(10),   // p50 target
        Duration.ofMillis(75),   // p95 target
        Duration.ofMillis(100)   // p99 target
    )
    .publishPercentiles(0.5, 0.95, 0.99)
    .publishPercentileHistogram();
```

**Action Required:** Optimize histogram buckets per service before Phase 3.

---

### HIGH #3: Missing Backward Compatibility Strategy

**Section:** 10.2 Disadvantages - Breaking Changes

**Issue:**  
Document mentions semantic versioning but provides **no concrete examples** of how to handle breaking changes.

**Scenario:**
- v1.0.0: `recordRequest(eventType, gateway, product, status)`
- v2.0.0: Need to add `region` label

How do services upgrade without breaking?

**Recommendation:**

**Backward Compatibility Guidelines:**

```markdown
## Breaking Change Strategy

### Scenario 1: Adding Required Parameter

❌ **Bad (Breaking Change):**
```java
// v1.0.0
public void recordRequest(String eventType, String gateway, String product);

// v2.0.0 (BREAKS EXISTING CODE)
public void recordRequest(String eventType, String gateway, String product, String region);
```

✅ **Good (Backward Compatible):**
```java
// v1.0.0
public void recordRequest(String eventType, String gateway, String product) {
    recordRequest(eventType, gateway, product, "unknown");
}

// v2.0.0 (NEW METHOD, OLD METHOD DEPRECATED)
public void recordRequest(String eventType, String gateway, String product, String region) {
    // New implementation
}

@Deprecated(since = "2.0.0", forRemoval = true)
public void recordRequest(String eventType, String gateway, String product) {
    recordRequest(eventType, gateway, product, detectRegion());
}
```

### Scenario 2: Renaming Metric

❌ **Bad:**
```java
// v1.0.0: fraud_switch.fraud_router.requests_total
// v2.0.0: fraud_switch.fraud_router.requests.total (BREAKING)
```

✅ **Good:**
```java
// v2.0.0: Support both names during transition
public class MetricNames {
    @Deprecated
    public static final String REQUESTS_TOTAL_OLD = "requests_total";
    public static final String REQUESTS_TOTAL = "requests.total";
}

// Emit both metrics for 3 months
requestCounter
    .tag("metric_version", "v1")
    .register(registry, REQUESTS_TOTAL_OLD);
    
requestCounter
    .tag("metric_version", "v2")
    .register(registry, REQUESTS_TOTAL);
```

### Scenario 3: Removing Deprecated Feature

**Timeline:**
- v2.0.0: Deprecate old method, add `@Deprecated(forRemoval = true)`
- v2.1.0, v2.2.0: Keep deprecated method (6 months)
- v3.0.0: Remove deprecated method (major version bump)

**Migration Period:** Minimum 6 months between deprecation and removal
```

**Action Required:** Add backward compatibility guidelines before Phase 2.

---

### HIGH #4: Load Test Plan Missing Actual Implementation

**Section:** 9.2 Phase 2 - Load Test (300 TPS)

**Issue:**  
"Load test (300 TPS)" listed as task but **no details** on how to execute or validate.

**Missing:**
- Load test tool (JMeter? Gatling? K6?)
- Test scenarios
- Success criteria
- How to measure <1% CPU overhead
- How to measure <50MB memory increase

**Recommendation:**

```markdown
## Load Test Plan (Phase 2)

**Tool:** Gatling (already used by Fraud Switch)

**Test Scenario:**
- Duration: 10 minutes
- Ramp-up: 0 → 300 TPS over 2 minutes
- Sustained: 300 TPS for 6 minutes
- Ramp-down: 300 → 0 TPS over 2 minutes

**Endpoints:**
- POST /v1/fraud/score (beforeAuthenticationSync)
- POST /v1/fraud/score (afterAuthorizationSync)
- Mix: 70% beforeAuth, 30% afterAuth

**Baseline Metrics (Before Library):**
- Collect from Staging environment without library
- Capture: CPU%, memory, p99 latency

**Comparison Metrics (After Library):**
- Same test, with library enabled
- Compare: CPU% (+X%), memory (+Y MB), p99 latency (+Z ms)

**Success Criteria:**
| Metric | Baseline | With Library | Max Increase | Status |
|--------|----------|--------------|--------------|--------|
| CPU % | X% | Y% | +1% | ✅ |
| Memory (MB) | A MB | B MB | +50 MB | ✅ |
| p99 Latency (ms) | C ms | D ms | +5 ms | ✅ |

**Gatling Script:**
```scala
class MetricsLibraryLoadTest extends Simulation {
  
  val httpProtocol = http.baseUrl("http://fraud-router-staging:8080")
  
  val scn = scenario("Fraud Scoring")
    .exec(
      http("beforeAuth")
        .post("/v1/fraud/score")
        .body(StringBody("""{"eventType":"beforeAuthenticationSync",...}"""))
    )
  
  setUp(
    scn.inject(
      rampUsersPerSec(0) to 300 during (2 minutes),
      constantUsersPerSec(300) during (6 minutes),
      rampUsersPerSec(300) to 0 during (2 minutes)
    )
  ).protocols(httpProtocol)
}
```

**Validation:**
```bash
# Compare Prometheus metrics
kubectl exec -it prometheus-0 -- promtool query range \
  --start=2025-10-14T10:00:00Z \
  --end=2025-10-14T10:10:00Z \
  'rate(process_cpu_seconds_total{service="fraud-router"}[1m])'
```
```

**Action Required:** Add detailed load test plan before Phase 2.

---

## MEDIUM Priority Issues (Nice-to-Have)

### MEDIUM #1: No Alerting Integration

**Section:** 10 (Observability)

**Issue:**  
Metrics library produces data but doesn't help with **alerting setup**. Teams still need to configure alerts manually for each service.

**Opportunity:**  
Library could provide **recommended alert templates** in Prometheus alert format.

**Recommendation:**

Add `src/main/resources/prometheus-alerts/` to library:

```yaml
# fraud_router_alerts.yaml
groups:
  - name: fraud_router
    interval: 30s
    rules:
      - alert: FraudRouterHighErrorRate
        expr: |
          (
            sum(rate(fraud_switch_fraud_router_errors_total[5m]))
            /
            sum(rate(fraud_switch_fraud_router_requests_total[5m]))
          ) > 0.01
        for: 5m
        labels:
          severity: high
          service: fraud_router
        annotations:
          summary: "Fraud Router error rate above 1%"
          description: "Current error rate: {{ $value | humanizePercentage }}"
```

Services can import:
```bash
kubectl apply -f fraud-switch-metrics-library/alerts/fraud_router_alerts.yaml
```

**Priority:** Medium (helpful but not blocking)

---

### MEDIUM #2: Missing Grafana Dashboard JSON

**Section:** 12 (Success Metrics)

**Issue:**  
Document mentions "Grafana dashboards" multiple times but provides **no dashboard templates**.

**Opportunity:**  
Library could include **dashboard JSON** for each service, automatically using correct metric names.

**Recommendation:**

Add `src/main/resources/grafana-dashboards/`:

```json
{
  "dashboard": {
    "title": "Fraud Router - RED Metrics",
    "panels": [
      {
        "title": "Request Rate",
        "targets": [
          {
            "expr": "sum(rate(fraud_switch_fraud_router_requests_total[5m])) by (event_type)"
          }
        ]
      },
      {
        "title": "Error Rate",
        "targets": [
          {
            "expr": "sum(rate(fraud_switch_fraud_router_errors_total[5m])) by (error_type)"
          }
        ]
      },
      {
        "title": "P99 Latency",
        "targets": [
          {
            "expr": "histogram_quantile(0.99, sum(rate(fraud_switch_fraud_router_request_duration_seconds_bucket[5m])) by (le))"
          }
        ]
      }
    ]
  }
}
```

**Priority:** Medium (saves time but not critical)

---

### MEDIUM #3: No Metric Sampling Strategy

**Section:** 13.2 Implementation Questions - Sampling

**Issue:**  
Document defers sampling decision ("No, unless performance issues observed") but provides no plan **if performance issues occur**.

**Scenario:**  
- Fraud Router scales to 100 pods
- 100 pods × 1640 series × 12 samples/min = **1.97M samples/min** to Prometheus
- Prometheus struggles

What's the fallback plan?

**Recommendation:**

```markdown
## Sampling Strategy (If Needed)

**Trigger:** Prometheus scrape duration >30s for 3 consecutive scrapes

**Phase 1: Reduce Histogram Percentiles**
```yaml
fraud-switch:
  metrics:
    percentiles: [0.95, 0.99]  # Remove 0.50
```
Impact: 33% reduction in histogram series

**Phase 2: Increase Scrape Interval**
```yaml
# prometheus.yml
scrape_configs:
  - job_name: fraud-switch
    scrape_interval: 30s  # From 15s
```

**Phase 3: Exemplar Sampling**
```java
requestTimer
    .publishPercentileHistogram()
    .distributionStatisticExpiry(Duration.ofMinutes(2))  # Expire old buckets
```

**Phase 4: Request Sampling (Last Resort)**
```java
if (ThreadLocalRandom.current().nextDouble() < 0.1) {
    // Only record 10% of requests
    metrics.recordRequest(...);
}
```
```

**Priority:** Medium (document the plan, don't implement yet)

---

## Strengths (What's Done Well)

### ✅ Strength #1: Excellent Canonical Name Integration

**Section:** 4.1 Core Constants - MetricLabels

**What's Done Well:**
- **Perfect alignment** with canonical-architecture.md
- All 8 services named correctly: `SERVICE_FRAUD_ROUTER`, `SERVICE_RULES`, etc.
- All providers: `PROVIDER_RAVELIN`, `PROVIDER_SIGNIFYD`
- All topics: `TOPIC_TRANSACTIONS`, `TOPIC_DECLINES`, `TOPIC_PAN_QUEUE`
- All gateways: `GATEWAY_RAFT`, `GATEWAY_VAP`, etc.

**Evidence:**
```java
// Perfect consistency with canonical-architecture.md
public static final String SERVICE_FRAUD_ROUTER = "fraud_router";
public static final String PROVIDER_RAVELIN = "Ravelin";
public static final String TOPIC_TRANSACTIONS = "fs.transactions";
```

**Impact:** Zero drift between architecture doc and implementation.

---

### ✅ Strength #2: Comprehensive Type Safety

**Section:** 4.1, 4.2

**What's Done Well:**
- Constants prevent typos at compile-time
- IDE auto-completion for all canonical names
- Refactoring-safe (rename in one place, propagates everywhere)

**Example:**
```java
// ✅ Type-safe
metrics.recordRequest(
    MetricLabels.EVENT_BEFORE_AUTH_SYNC,  // Auto-complete
    MetricLabels.GATEWAY_RAFT,
    MetricLabels.PRODUCT_FRAUD_SIGHT,
    MetricLabels.STATUS_SUCCESS
);

// ❌ Old way (error-prone)
metrics.recordRequest("beforeAuthSync", "RAFT", "FRAUD_SIGHT", "success");
```

---

### ✅ Strength #3: Realistic Performance Analysis

**Section:** 6. Performance Impact Analysis

**What's Done Well:**
- Actual numbers: 60-120ns per operation (Micrometer baseline)
- Realistic overhead: <1% CPU (validated with calculations)
- Memory: 40-50 MB per pod (2.5% of allocation)
- Latency: 3 microseconds per request (0.003% of SLA)

**Honesty:** Admits memory calculation may be off (see CRITICAL #1), but baseline methodology is sound.

---

### ✅ Strength #4: Well-Structured Rollout Plan

**Section:** 9. Deployment & Rollout Plan

**What's Done Well:**
- Phased approach: Library → Pilot → Rollout → Validation
- Pilot service (Fraud Router) is highest-risk (good for early validation)
- 1 service per day in Phase 3 (reasonable pace)
- Clear success criteria per phase
- Rollback options documented

**Risk Mitigation:**
- Canary deployment (5% pods first)
- 48-hour monitoring before full rollout
- Multiple rollback options (disable, revert, remove)

---

### ✅ Strength #5: Spring Boot Starter Pattern

**Section:** 4.4 Auto-Configuration

**What's Done Well:**
- Follows Spring Boot conventions (`@ConditionalOnClass`, `@ConditionalOnMissingBean`)
- Zero boilerplate for services (just add dependency)
- `spring.factories` for auto-discovery
- Configuration properties for customization

**Integration:**
```xml
<dependency>
    <groupId>com.fraudswitch</groupId>
    <artifactId>fraud-switch-metrics-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Service gets all metrics beans automatically. Excellent developer experience.

---

## Recommendations

### Recommendation #1: Add Metrics Validation Tool

**Purpose:** Catch cardinality issues during local development before production.

**Implementation:**
```java
@Component
public class MetricsValidator {
    
    @EventListener(ApplicationReadyEvent.class)
    public void validateMetrics() {
        List<String> issues = new ArrayList<>();
        
        // Check 1: No metrics with >10 labels
        meterRegistry.getMeters().forEach(meter -> {
            if (meter.getId().getTags().size() > 10) {
                issues.add("Metric has too many labels: " + meter.getId());
            }
        });
        
        // Check 2: No high-cardinality labels
        meterRegistry.getMeters().forEach(meter -> {
            meter.getId().getTags().forEach(tag -> {
                if (isHighCardinalityLabel(tag.getKey())) {
                    issues.add("High-cardinality label: " + tag.getKey());
                }
            });
        });
        
        if (!issues.isEmpty()) {
            log.error("Metrics validation failed:\n{}", 
                     String.join("\n", issues));
            // In dev: throw exception
            // In prod: alert ops team
        }
    }
}
```

---

### Recommendation #2: Add Metrics Cleanup Job

**Purpose:** Remove unused metric series after pods scale down.

**Problem:** If Fraud Router scales 60 → 20 pods, 40 pods' worth of stale metrics remain in Prometheus.

**Solution:**
```java
@Scheduled(fixedRate = 3600000) // Every hour
public void cleanupStaleMetrics() {
    // Get active pod IPs from Kubernetes
    Set<String> activePods = kubernetesClient.getActivePodIPs();
    
    // Remove metrics from inactive pods
    meterRegistry.getMeters().forEach(meter -> {
        String podIp = meter.getId().getTag("pod_ip");
        if (podIp != null && !activePods.contains(podIp)) {
            meterRegistry.remove(meter.getId());
        }
    });
}
```

---

### Recommendation #3: Add Developer Quick Start Guide

**Purpose:** Help developers get started in <5 minutes.

**Content:**
```markdown
# Quick Start: Add Metrics to Your Service

## Step 1: Add Dependency (30 seconds)
```xml
<dependency>
    <groupId>com.fraudswitch</groupId>
    <artifactId>fraud-switch-metrics-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Step 2: Inject Metrics Bean (30 seconds)
```java
@Service
public class MyService {
    
    private final FraudRouterMetrics metrics;  // Auto-wired
    
    public MyService(FraudRouterMetrics metrics) {
        this.metrics = metrics;
    }
}
```

## Step 3: Record Metrics (1 minute)
```java
public void handleRequest(Request req) {
    long start = System.currentTimeMillis();
    
    try {
        // ... business logic ...
        
        metrics.recordRequest(
            MetricLabels.EVENT_BEFORE_AUTH_SYNC,
            MetricLabels.GATEWAY_RAFT,
            MetricLabels.PRODUCT_FRAUD_SIGHT,
            MetricLabels.STATUS_SUCCESS
        );
        metrics.recordDuration(..., System.currentTimeMillis() - start);
        
    } catch (Exception e) {
        metrics.recordError(..., MetricLabels.ERROR_TYPE_SERVER);
        throw e;
    }
}
```

## Step 4: View Metrics (1 minute)
- Local: http://localhost:8080/actuator/prometheus
- Staging: https://prometheus-staging.fraudswitch.com
- Production: https://grafana.fraudswitch.com

Done! 🎉
```

---

## Overall Assessment

### Summary Table

| Category | Rating | Comment |
|----------|--------|---------|
| **Architectural Soundness** | ⭐⭐⭐⭐⭐ | Excellent design, follows best practices |
| **Integration with Existing System** | ⭐⭐⭐⭐⭐ | Perfect alignment with canonical-architecture.md |
| **Performance Impact** | ⭐⭐⭐⭐ | Realistic but memory calculation needs correction |
| **Implementation Feasibility** | ⭐⭐⭐⭐ | Achievable with 6 engineer-weeks |
| **Risk Assessment** | ⭐⭐⭐⭐ | Good risk identification; needs runtime cardinality enforcement |
| **Completeness** | ⭐⭐⭐⭐ | Missing integration tests, custom metrics guidance |
| **Trade-offs Analysis** | ⭐⭐⭐⭐⭐ | Honest, thorough, well-balanced |

**Overall Rating:** ⭐⭐⭐⭐ (4.4/5)

---

## Final Decision

**Status:** ✅ **APPROVED WITH CONDITIONS**

**Conditions:**

**BEFORE PHASE 1 (Week 1):**
- [ ] Fix memory calculation (CRITICAL #1)
- [ ] Implement runtime cardinality enforcement (CRITICAL #2)
- [ ] Add integration tests (CRITICAL #3)

**BEFORE PHASE 2 (Week 2):**
- [ ] Add detailed load test plan (HIGH #4)
- [ ] Add backward compatibility guidelines (HIGH #3)

**BEFORE PHASE 3 (Week 3):**
- [ ] Add custom metrics guidance (HIGH #1)
- [ ] Optimize histogram buckets per service (HIGH #2)

**Nice-to-Have (Future):**
- Add alert templates (MEDIUM #1)
- Add Grafana dashboard JSON (MEDIUM #2)
- Document sampling strategy (MEDIUM #3)

---

## Sign-Off

**Architecture Review Board:**
- [x] Design approved with conditions
- [x] Ready for implementation after addressing CRITICAL issues
- [x] Re-review required after Phase 2 pilot

**Next Review:** After Phase 2 pilot completion (Week 2)

---

**Document Version:** 1.0  
**Review Date:** October 14, 2025  
**Maintained By:** Architecture Review Board
