# Deployment Guide - Fraud Switch Metrics Spring Boot Starter

## Prerequisites

- JDK 17+
- Maven 3.8+
- Access to internal Artifactory
- Proper Maven settings.xml configuration

## Building the Library

### 1. Clone Repository

```bash
git clone https://github.com/fraudswitch/metrics-starter.git
cd metrics-starter
```

### 2. Build with Maven

```bash
# Clean build with tests
mvn clean install

# Skip tests (not recommended)
mvn clean install -DskipTests

# Build without integration tests
mvn clean install -DskipITs
```

### 3. Verify Build

```bash
# Check build artifacts
ls -lh target/fraud-switch-metrics-spring-boot-starter-1.0.0.jar

# Verify test results
cat target/surefire-reports/*.txt
```

## Publishing to Artifactory

### 1. Configure Maven Settings

Edit `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>fraudswitch-releases</id>
      <username>${env.ARTIFACTORY_USERNAME}</username>
      <password>${env.ARTIFACTORY_PASSWORD}</password>
    </server>
    <server>
      <id>fraudswitch-snapshots</id>
      <username>${env.ARTIFACTORY_USERNAME}</username>
      <password>${env.ARTIFACTORY_PASSWORD}</password>
    </server>
  </servers>
</settings>
```

### 2. Deploy to Artifactory

```bash
# Deploy release version
mvn clean deploy

# Deploy snapshot version (for testing)
mvn versions:set -DnewVersion=1.0.1-SNAPSHOT
mvn clean deploy
```

### 3. Verify Deployment

```bash
# Check Artifactory
curl -u ${ARTIFACTORY_USERNAME}:${ARTIFACTORY_PASSWORD} \
  https://artifactory.fraudswitch.com/artifactory/libs-release-local/com/fraudswitch/fraud-switch-metrics-spring-boot-starter/1.0.0/
```

## Integration into Services

### Phase 1: Pilot Service (Fraud Router)

#### Week 1: Integration

1. **Add Dependency**

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.fraudswitch</groupId>
    <artifactId>fraud-switch-metrics-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

2. **Configure Application**

```yaml
# application.yml
fraud-switch:
  metrics:
    enabled: true
    service-name: fraud-router
    region: ${AWS_REGION}  # us-ohio-1 or uk-london-1
    environment: ${ENV}     # dev, staging, prod
```

3. **Update Code**

```java
// Before
@Component
public class FraudRouterMetrics {
    private final MeterRegistry registry;
    
    public void recordRequest(...) {
        Counter.builder("fraud_router_requests")...
    }
}

// After
@Component
@RequiredArgsConstructor
public class FraudController {
    private final FraudRouterMetrics metrics;
    
    public void handleRequest(...) {
        metrics.recordRoutingDecision(...);
    }
}
```

4. **Test Locally**

```bash
# Start application
mvn spring-boot:run

# Verify metrics endpoint
curl http://localhost:8080/actuator/prometheus | grep fraud_switch

# Expected output:
# fraud_switch_fraud_router_requests_total{...} 0.0
# fraud_switch_fraud_router_request_duration_ms_bucket{...} 0.0
```

5. **Deploy to Dev Environment**

```bash
# Build application
mvn clean package

# Deploy via CI/CD pipeline
./deploy.sh dev us-ohio-1
```

6. **Validate in Dev**

```bash
# Check Prometheus
curl http://prometheus.dev.fraudswitch.com/api/v1/query?query=fraud_switch_fraud_router_requests_total

# Check Grafana dashboards
# Navigate to: https://grafana.dev.fraudswitch.com/d/fraud-router
```

#### Week 2: Load Testing & Validation

1. **Run Load Tests**

```bash
# Start load test
cd load-tests
./run-load-test.sh fraud-router 300 300  # 300 TPS for 5 minutes

# Monitor metrics
watch -n 1 'curl -s http://localhost:8080/actuator/prometheus | grep fraud_switch'
```

2. **Validate Performance**

- p99 latency: Should remain < 100ms
- CPU overhead: Should be < 1%
- Memory overhead: 60-80 MB additional
- No dropped metrics
- Cardinality within limits

3. **Check Cardinality**

```bash
# Query cardinality stats
curl http://localhost:8080/actuator/metrics/fraud.switch.cardinality.stats

# Expected:
# {
#   "totalMetrics": 15,
#   "totalLabelCombinations": 247,
#   "maxLabelCombinations": 89,
#   "circuitBreakerState": "CLOSED"
# }
```

4. **Production Deployment**

```bash
# Deploy to staging
./deploy.sh staging us-ohio-1

# Validate in staging (24-48 hours)
# Monitor dashboards, alerts, error rates

# Deploy to production (blue-green)
./deploy.sh prod us-ohio-1 --blue-green

# Monitor for 2 hours
# Cut over to new version
./deploy.sh prod us-ohio-1 --cutover
```

### Phase 2: Remaining Services (Weeks 3-4)

Services to integrate:
1. Rules Service
2. BIN Lookup Service
3. FraudSight Adapter
4. GuaranteedPayment Adapter
5. Async Processor
6. Tokenization Service
7. Issuer Data Service

**Parallel Integration Strategy:**

```bash
# Week 3: Day 1-2
- Rules Service (dev)
- BIN Lookup Service (dev)

# Week 3: Day 3-4
- FraudSight Adapter (dev)
- GuaranteedPayment Adapter (dev)

# Week 3: Day 5
- Async Processor (dev)

# Week 4: Day 1
- Tokenization Service (dev)
- Issuer Data Service (dev)

# Week 4: Day 2-5
- All services to staging & prod
```

## Rollback Procedure

### If Issues Detected

1. **Immediate Rollback**

```bash
# Revert to previous version
./deploy.sh prod us-ohio-1 --rollback

# Verify old metrics still working
curl http://prometheus.prod.fraudswitch.com/api/v1/query?query=fraud_router_requests_total
```

2. **Remove Dependency**

```xml
<!-- Comment out dependency -->
<!--
<dependency>
    <groupId>com.fraudswitch</groupId>
    <artifactId>fraud-switch-metrics-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
-->
```

3. **Disable via Configuration**

```yaml
# Quick disable without code changes
fraud-switch:
  metrics:
    enabled: false
```

## Monitoring During Rollout

### Key Metrics to Watch

```promql
# Request rate - should remain stable
rate(fraud_switch_fraud_router_requests_total[5m])

# Error rate - should not increase
rate(fraud_switch_fraud_router_errors_total[5m]) / rate(fraud_switch_fraud_router_requests_total[5m])

# Latency - should not degrade
histogram_quantile(0.99, rate(fraud_switch_fraud_router_request_duration_ms_bucket[5m]))

# Cardinality - should stay within limits
fraud_switch_cardinality_total_label_combinations < 1000
```

### Alerts to Configure

```yaml
# prometheus/alerts.yml
groups:
  - name: metrics_library_rollout
    rules:
      - alert: HighCardinality
        expr: fraud_switch_cardinality_total_label_combinations > 800
        for: 5m
        annotations:
          summary: "Approaching cardinality limit"
      
      - alert: MetricsCircuitBreakerOpen
        expr: fraud_switch_cardinality_circuit_breaker_state == 1  # OPEN
        for: 1m
        annotations:
          summary: "Metrics circuit breaker opened"
      
      - alert: LatencyRegression
        expr: |
          histogram_quantile(0.99, rate(fraud_switch_fraud_router_request_duration_ms_bucket[5m]))
          / on() histogram_quantile(0.99, rate(fraud_router_requests_duration_bucket[5m])) > 1.1
        for: 10m
        annotations:
          summary: "10% latency increase after metrics library deployment"
```

## Success Criteria

### Before Declaring Success

- [ ] All 8 services integrated and deployed to production
- [ ] No increase in p99 latency (< 1%)
- [ ] No increase in error rate
- [ ] CPU overhead < 1%
- [ ] Memory overhead < 80 MB per service
- [ ] All Prometheus metrics accessible
- [ ] Grafana dashboards functional
- [ ] No cardinality violations (30 days)
- [ ] Circuit breaker remained CLOSED
- [ ] Load tests passed (300 TPS sync + 1000 TPS async)

### Final Validation Checklist

```bash
# 1. Check all services reporting metrics
for service in fraud-router rules-service bin-lookup-service \
               fraudsight-adapter guaranteed-payment-adapter \
               async-processor tokenization-service issuer-data-service; do
  echo "Checking $service..."
  curl -s http://prometheus.prod.fraudswitch.com/api/v1/query?query=fraud_switch_${service//-/_}_requests_total | jq '.data.result | length'
done

# 2. Validate metric naming consistency
curl -s http://prometheus.prod.fraudswitch.com/api/v1/label/__name__/values | \
  grep fraud_switch | sort | uniq -c

# 3. Check cardinality across all services
for service in fraud-router rules-service bin-lookup-service \
               fraudsight-adapter guaranteed-payment-adapter \
               async-processor tokenization-service issuer-data-service; do
  echo "Cardinality for $service:"
  curl -s http://${service}.prod.fraudswitch.com/actuator/metrics/cardinality.stats
done

# 4. Validate dashboards
echo "Check Grafana dashboards:"
echo "- Platform Overview: https://grafana.prod.fraudswitch.com/d/fraud-switch-overview"
echo "- Per-service dashboards available"
```

## Post-Deployment

### 1. Documentation Updates

- Update runbooks with new metric names
- Update alert definitions
- Update dashboard links
- Update incident response procedures

### 2. Team Training

- Conduct training session for all engineers
- Share best practices guide
- Create Slack channel for questions: #metrics-library
- Office hours: Weekly for first month

### 3. Monitoring Schedule

- **Week 1-2:** Daily cardinality checks
- **Week 3-4:** Every 3 days
- **Month 2-6:** Weekly
- **After 6 months:** Monthly

## Troubleshooting

See [README.md](./README.md#troubleshooting) for detailed troubleshooting guide.

## Support

- **Platform Team:** platform-team@fraudswitch.com
- **Slack:** #metrics-library
- **JIRA:** https://jira.fraudswitch.com/projects/METRICS
- **On-Call:** PagerDuty - Metrics Library

---

**Version:** 1.0.0  
**Last Updated:** October 22, 2025  
**Maintained By:** Platform Team
