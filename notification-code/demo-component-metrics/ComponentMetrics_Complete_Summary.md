# 🎉 ComponentMetrics - Complete Method Guide

## Congratulations! You've Mastered Both ComponentMetrics Methods!

---

## 📚 All Methods At A Glance

```java
public class ComponentMetrics {
    
    // ✅ Method 1: Record successful service call
    public void recordCall(String targetComponent, long durationMs, String... labels)
    
    // ❌ Method 2: Record failed service call
    public void recordCallError(String targetComponent, long durationMs, String errorType, String... labels)
}
```

---

## 🎯 Quick Selection Guide

```
Calling another internal service?
    ↓
    ├─ Success → recordCall(targetComponent, duration, labels...)
    └─ Failed  → recordCallError(targetComponent, duration, errorType, labels...)
```

---

## 📊 Method Comparison Matrix

| Method | When | Pattern | Creates |
|--------|------|---------|---------|
| `recordCall()` | After successful call | In try block | `component_calls_total` |
| `recordCallError()` | After failed call | In catch block | `component_call_errors_total` |

---

## 💻 Complete Example: Fraud Router Service Dependencies

Here's a complete example showing all ComponentMetrics methods tracking Fraud Router's dependencies:

```java
@Service
@Slf4j
public class FraudRouterService {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private RulesServiceClient rulesServiceClient;
    
    @Autowired
    private BinLookupServiceClient binLookupClient;
    
    @Autowired
    private FraudSightAdapterClient fraudSightClient;
    
    @Autowired
    private GuaranteedPaymentAdapterClient guaranteedPaymentClient;
    
    /**
     * Process fraud check - calls multiple internal services
     */
    public FraudResponse processFraudCheck(FraudRequest request) {
        
        // Step 1: Call Rules Service (boarding + rules)
        RulesResponse rulesResponse = callRulesService(request);
        
        // Step 2: Call BIN Lookup Service (in parallel with rules)
        BinData binData = callBinLookupService(request.getCardNumber());
        
        // Step 3: Call appropriate adapter based on product
        if ("fraudsight".equals(request.getProduct())) {
            return callFraudSightAdapter(request, rulesResponse, binData);
        } else {
            return callGuaranteedPaymentAdapter(request, rulesResponse, binData);
        }
    }
    
    // =========================================================================
    // METHOD 1 & 2: Rules Service Call
    // =========================================================================
    
    private RulesResponse callRulesService(FraudRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            // Call Rules Service
            RulesResponse response = rulesServiceClient.checkRules(
                request.getMerchantId(),
                request.getEventType()
            );
            
            // ✅ METHOD 1: Record successful call
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCall(
                "rules_service",
                duration,
                "operation", "check_rules",
                "event_type", request.getEventType()
            );
            
            log.info("Rules check completed: merchantId={}, duration={}ms", 
                    request.getMerchantId(), 
                    duration);
            
            return response;
            
        } catch (TimeoutException e) {
            // ❌ METHOD 2: Record timeout error
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "rules_service",
                duration,
                "TimeoutException",
                "operation", "check_rules"
            );
            
            log.error("Rules service timeout: merchantId={}, duration={}ms", 
                     request.getMerchantId(),
                     duration,
                     e);
            throw e;
            
        } catch (Exception e) {
            // ❌ METHOD 2: Record other errors
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "rules_service",
                duration,
                e.getClass().getSimpleName(),
                "operation", "check_rules"
            );
            
            log.error("Rules service error: merchantId={}", 
                     request.getMerchantId(),
                     e);
            throw e;
        }
    }
    
    // =========================================================================
    // METHOD 1 & 2: BIN Lookup Service Call
    // =========================================================================
    
    private BinData callBinLookupService(String cardNumber) {
        String bin = cardNumber.substring(0, 6);
        long startTime = System.currentTimeMillis();
        
        try {
            // Call BIN Lookup Service
            BinData binData = binLookupClient.lookup(bin);
            
            // ✅ METHOD 1: Record successful call
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCall(
                "bin_lookup_service",
                duration,
                "operation", "lookup"
            );
            
            log.info("BIN lookup completed: bin={}, duration={}ms", 
                    bin, 
                    duration);
            
            return binData;
            
        } catch (HttpServerErrorException e) {
            // ❌ METHOD 2: Record HTTP server error
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "bin_lookup_service",
                duration,
                "HttpServerError",
                "operation", "lookup",
                "http_status", String.valueOf(e.getStatusCode().value())
            );
            
            log.error("BIN lookup server error: bin={}, status={}", 
                     bin,
                     e.getStatusCode(),
                     e);
            throw e;
            
        } catch (Exception e) {
            // ❌ METHOD 2: Record other errors
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "bin_lookup_service",
                duration,
                e.getClass().getSimpleName(),
                "operation", "lookup"
            );
            
            log.error("BIN lookup error: bin={}", bin, e);
            throw e;
        }
    }
    
    // =========================================================================
    // METHOD 1 & 2: FraudSight Adapter Call
    // =========================================================================
    
    private FraudResponse callFraudSightAdapter(
            FraudRequest request,
            RulesResponse rules,
            BinData binData) {
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Call FraudSight Adapter
            FraudSightResponse response = fraudSightClient.checkFraud(
                request,
                rules,
                binData
            );
            
            // ✅ METHOD 1: Record successful call
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCall(
                "fraudsight_adapter",
                duration,
                "event_type", request.getEventType(),
                "gateway", request.getGateway(),
                "product", "fraudsight"
            );
            
            log.info("FraudSight call completed: txn={}, duration={}ms", 
                    request.getTransactionId(), 
                    duration);
            
            return convertToFraudResponse(response);
            
        } catch (TimeoutException e) {
            // ❌ METHOD 2: Record timeout
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "fraudsight_adapter",
                duration,
                "TimeoutException",
                "event_type", request.getEventType(),
                "product", "fraudsight"
            );
            
            log.error("FraudSight timeout: txn={}, duration={}ms", 
                     request.getTransactionId(),
                     duration,
                     e);
            throw e;
            
        } catch (Exception e) {
            // ❌ METHOD 2: Record other errors
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "fraudsight_adapter",
                duration,
                e.getClass().getSimpleName(),
                "event_type", request.getEventType(),
                "product", "fraudsight"
            );
            
            log.error("FraudSight error: txn={}", 
                     request.getTransactionId(),
                     e);
            throw e;
        }
    }
    
    // =========================================================================
    // METHOD 1 & 2: Guaranteed Payment Adapter Call
    // =========================================================================
    
    private FraudResponse callGuaranteedPaymentAdapter(
            FraudRequest request,
            RulesResponse rules,
            BinData binData) {
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Call Guaranteed Payment Adapter
            GuaranteedPaymentResponse response = guaranteedPaymentClient.checkFraud(
                request,
                rules,
                binData
            );
            
            // ✅ METHOD 1: Record successful call
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCall(
                "guaranteed_payment_adapter",
                duration,
                "event_type", request.getEventType(),
                "gateway", request.getGateway(),
                "product", "guaranteed_payment"
            );
            
            log.info("GP call completed: txn={}, duration={}ms", 
                    request.getTransactionId(), 
                    duration);
            
            return convertToFraudResponse(response);
            
        } catch (TimeoutException e) {
            // ❌ METHOD 2: Record timeout
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "guaranteed_payment_adapter",
                duration,
                "TimeoutException",
                "event_type", request.getEventType(),
                "product", "guaranteed_payment"
            );
            
            log.error("GP timeout: txn={}, duration={}ms", 
                     request.getTransactionId(),
                     duration,
                     e);
            throw e;
            
        } catch (Exception e) {
            // ❌ METHOD 2: Record other errors
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCallError(
                "guaranteed_payment_adapter",
                duration,
                e.getClass().getSimpleName(),
                "event_type", request.getEventType(),
                "product", "guaranteed_payment"
            );
            
            log.error("GP error: txn={}", 
                     request.getTransactionId(),
                     e);
            throw e;
        }
    }
}
```

---

## 📊 Metrics Created Summary

The above service creates these component metrics:

```prometheus
# Method 1: Successful calls
fraud_switch_fraud_router_component_calls_total{
  target_component="rules_service",
  operation="check_rules"
} 1000

fraud_switch_fraud_router_component_calls_total{
  target_component="bin_lookup_service",
  operation="lookup"
} 1000

fraud_switch_fraud_router_component_calls_total{
  target_component="fraudsight_adapter",
  event_type="beforeAuthenticationSync",
  product="fraudsight"
} 850

fraud_switch_fraud_router_component_calls_total{
  target_component="guaranteed_payment_adapter",
  event_type="beforeAuthenticationSync",
  product="guaranteed_payment"
} 150

# Method 2: Failed calls
fraud_switch_fraud_router_component_call_errors_total{
  target_component="rules_service",
  error_type="TimeoutException",
  operation="check_rules"
} 5

fraud_switch_fraud_router_component_call_errors_total{
  target_component="bin_lookup_service",
  error_type="HttpServerError",
  operation="lookup"
} 3

fraud_switch_fraud_router_component_call_errors_total{
  target_component="fraudsight_adapter",
  error_type="TimeoutException",
  product="fraudsight"
} 10
```

---

## 📈 Complete Prometheus Dashboard

### Panel 1: Service Call Rate (By Target)
```promql
sum by (target_component) (
  rate(fraud_switch_fraud_router_component_calls_total[5m])
)
```

### Panel 2: Service Call Errors (By Target)
```promql
sum by (target_component) (
  rate(fraud_switch_fraud_router_component_call_errors_total[5m])
)
```

### Panel 3: P99 Latency by Service
```promql
histogram_quantile(0.99,
  sum by (target_component, le) (
    rate(fraud_switch_fraud_router_component_duration_seconds_bucket[5m])
  )
)
```

### Panel 4: Error Rate by Error Type
```promql
sum by (error_type) (
  rate(fraud_switch_fraud_router_component_call_errors_total[5m])
)
```

### Panel 5: Success Rate % by Service
```promql
(
  rate(fraud_switch_fraud_router_component_calls_total[5m])
  /
  (
    rate(fraud_switch_fraud_router_component_calls_total[5m]) +
    rate(fraud_switch_fraud_router_component_call_errors_total[5m])
  )
) * 100
```

### Panel 6: Call Volume Distribution
```promql
sum by (target_component) (
  fraud_switch_fraud_router_component_calls_total
)
```

### Panel 7: Most Error-Prone Services
```promql
topk(5,
  sum by (target_component) (
    rate(fraud_switch_fraud_router_component_call_errors_total[5m])
  )
)
```

### Panel 8: Circuit Breaker Status
```promql
fraud_switch_fraud_router_component_call_errors_total{
  error_type="CircuitBreakerOpen"
}
```

---

## 🎯 Fraud Switch Service Dependencies Map

### Fraud Router Dependencies:
```
Fraud Router
    ├── Rules Service (check_rules)
    ├── BIN Lookup Service (lookup)
    ├── FraudSight Adapter (check_fraud)
    └── Guaranteed Payment Adapter (check_fraud)
```

### Async Processor Dependencies:
```
Async Processor
    └── FraudSight Adapter (check_fraud_async)
```

### Tokenization Service Dependencies:
```
Tokenization Service
    └── Tokenization Service (internal decrypt)
```

---

## 🎓 Canonical Component Names

All **internal** Fraud Switch services:

| Canonical Name | Service |
|----------------|---------|
| `rules_service` | Rules Service |
| `bin_lookup_service` | BIN Lookup Service |
| `fraudsight_adapter` | FraudSight Adapter (calls Ravelin) |
| `guaranteed_payment_adapter` | Guaranteed Payment Adapter (calls Signifyd) |
| `async_processor` | Async Processor |
| `tokenization_service` | Tokenization Service |
| `issuer_data_service` | Issuer Data Service |
| `fraud_router` | Fraud Router (ETS) |

---

## 🚫 What NOT to Use ComponentMetrics For

### ❌ External Provider Calls:
```java
// WRONG - External provider
callRavelin();
componentMetrics.recordCall("ravelin", duration);  // ❌

// CORRECT - Use provider metrics
callRavelin();
providerMetrics.recordProviderCall("ravelin", duration);  // ✅
```

### ❌ Kafka Operations:
```java
// WRONG - Kafka operation
kafkaTemplate.send("topic", message);
componentMetrics.recordCall("kafka", duration);  // ❌

// CORRECT - Use Kafka metrics
kafkaTemplate.send("topic", message);
kafkaMetrics.recordPublish("topic");  // ✅
```

### ❌ Database Calls:
```java
// WRONG - Database operation
jdbcTemplate.query("SELECT...");
componentMetrics.recordCall("database", duration);  // ❌

// CORRECT - Use database connection pool metrics (HikariCP)
// or custom database metrics
```

---

## ✅ Complete Best Practices Checklist

### For Both Methods:
- [ ] Use canonical component names
- [ ] Calculate actual duration
- [ ] Add meaningful labels (operation, event_type)
- [ ] Keep label cardinality low (< 100)
- [ ] No PII/sensitive data in labels
- [ ] Only for internal Fraud Switch services

### For recordCall():
- [ ] Call AFTER successful service call
- [ ] Called in try block
- [ ] Not used for external APIs

### For recordCallError():
- [ ] Use exception class name (not message)
- [ ] Called in catch blocks
- [ ] Track different error types
- [ ] Keep error types limited (< 20)
- [ ] Don't also call recordCall()

---

## 🎓 Common Error Types

### HTTP Errors:
- `HttpServerError` (5xx)
- `HttpClientError` (4xx)
- `HttpBadRequest` (400)
- `HttpNotFound` (404)
- `HttpServiceUnavailable` (503)

### Network Errors:
- `TimeoutException`
- `ConnectionError`
- `ResourceAccessException`
- `SocketTimeoutException`

### Circuit Breaker:
- `CircuitBreakerOpen`
- `CircuitBreakerHalfOpen`

### Other:
- `ValidationException`
- `SerializationException`
- `UnexpectedError`

---

## 📊 Service Dependency Health

### Healthy Service:
```prometheus
# Success rate > 99%
fraud_switch_fraud_router_component_calls_total{
  target_component="rules_service"
} 1000

fraud_switch_fraud_router_component_call_errors_total{
  target_component="rules_service"
} 1

# Error rate: 0.1%
```

### Unhealthy Service:
```prometheus
# Success rate < 90%
fraud_switch_fraud_router_component_calls_total{
  target_component="bin_lookup_service"
} 100

fraud_switch_fraud_router_component_call_errors_total{
  target_component="bin_lookup_service"
} 50

# Error rate: 33%
```

---

## 🚨 Alerting Examples

### Alert: High Component Error Rate
```yaml
- alert: HighComponentErrorRate
  expr: |
    (
      rate(fraud_switch_fraud_router_component_call_errors_total[5m])
      /
      (
        rate(fraud_switch_fraud_router_component_calls_total[5m]) +
        rate(fraud_switch_fraud_router_component_call_errors_total[5m])
      )
    ) * 100 > 10
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "High error rate calling {{ $labels.target_component }}"
    description: "Error rate is {{ $value }}% for {{ $labels.target_component }}"
```

### Alert: Service Dependency Down
```yaml
- alert: ServiceDependencyDown
  expr: |
    rate(fraud_switch_fraud_router_component_call_errors_total{
      error_type="ConnectionError"
    }[5m]) > 5
  for: 1m
  labels:
    severity: critical
  annotations:
    summary: "{{ $labels.target_component }} appears to be down"
    description: "Connection errors to {{ $labels.target_component }}"
```

### Alert: Circuit Breaker Open
```yaml
- alert: CircuitBreakerOpen
  expr: |
    fraud_switch_fraud_router_component_call_errors_total{
      error_type="CircuitBreakerOpen"
    } > 0
  labels:
    severity: warning
  annotations:
    summary: "Circuit breaker open for {{ $labels.target_component }}"
```

---

## 🎉 You're Now a ComponentMetrics Expert!

### What You've Mastered:

✅ **recordCall()** - Track successful service calls  
✅ **recordCallError()** - Track failed service calls  

### You Can Now:

- Monitor all service-to-service calls
- Track inter-service latency
- Identify unreliable dependencies
- Alert on service failures
- Debug cascade failures
- Measure retry effectiveness
- Visualize service dependency graphs

---

## 📚 All Documentation Files

| Method | Guide |
|--------|-------|
| recordCall() | [Full Guide](ComponentMetrics_Method1_recordCall.md) |
| recordCallError() | [Full Guide](ComponentMetrics_Method2_recordCallError.md) |

---

## 🚀 Ready to Implement!

You now have everything you need to implement comprehensive component metrics in:
- **Fraud Router** (calls Rules, BIN Lookup, Adapters)
- **Async Processor** (calls Adapters)
- **Tokenization Service** (internal calls)
- **Any service** calling other internal services

---

**Congratulations on completing ComponentMetrics!** 🎉

What would you like to explore next?
