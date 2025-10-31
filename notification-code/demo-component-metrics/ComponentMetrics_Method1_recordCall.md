# ComponentMetrics Method 1: recordCall()

## 📋 Method Signature
```java
public void recordCall(String targetComponent, long durationMs, String... labels)
```

## 🎯 Purpose
Records a **successful service-to-service call** (also called component-to-component call). Tracks when your service calls another microservice internally within the Fraud Switch platform.

---

## 📖 What is This For?

In Fraud Switch, microservices call each other frequently:
- **Fraud Router** → calls **Rules Service** (check merchant rules)
- **Fraud Router** → calls **BIN Lookup Service** (get card BIN data)
- **Fraud Router** → calls **FraudSight Adapter** (call Ravelin)
- **Fraud Router** → calls **Guaranteed Payment Adapter** (call Signifyd)
- **Async Processor** → calls **FraudSight Adapter** (async fraud checks)
- **Tokenization Service** → calls **Tokenization Service** (internal decrypt)

`recordCall()` tracks these **internal** service-to-service calls so you can monitor:
- How many calls between services?
- Which service dependencies are busiest?
- What's the latency of internal calls?
- Are internal calls succeeding?
- Which components are bottlenecks?

---

## 📖 Parameters Explained

### 1. `targetComponent` (String)
- **Which service** you're calling
- Examples: "rules_service", "bin_lookup_service", "fraudsight_adapter"
- Must use **canonical service names** from architecture doc

### 2. `durationMs` (long)
- How long the call took in milliseconds
- From start to completion of the HTTP call
- Includes network + processing time

### 3. `labels` (String... varargs)
- Optional additional labels (key-value pairs)
- Must be even number of strings
- Common labels: "operation", "endpoint", "method"

---

## 💻 Basic Usage Examples

### Example 1: Fraud Router Calling Rules Service

```java
@Service
public class FraudRouterService {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private RulesServiceClient rulesServiceClient;
    
    public FraudResponse processFraudCheck(FraudRequest request) {
        
        // Call Rules Service
        long startTime = System.currentTimeMillis();
        
        try {
            RulesResponse rulesResponse = rulesServiceClient.checkRules(
                request.getMerchantId(),
                request.getEventType()
            );
            
            // ✅ Success - Record the call
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCall(
                "rules_service",
                duration
            );
            
            log.info("Rules check completed: merchantId={}, duration={}ms", 
                    request.getMerchantId(), 
                    duration);
            
            return processFraudCheck(request, rulesResponse);
            
        } catch (Exception e) {
            log.error("Rules service call failed", e);
            // Would use recordCallError() here (Method 2)
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_fraud_router_component_calls_total{
  service="fraud-router",
  region="us-ohio-1",
  target_component="rules_service"
} 1

fraud_switch_fraud_router_component_duration_seconds{
  service="fraud-router",
  target_component="rules_service",
  quantile="0.99"
} 0.025  # 25ms
```

---

### Example 2: Fraud Router Calling BIN Lookup Service

```java
@Service
public class FraudRouterService {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private BinLookupServiceClient binLookupClient;
    
    public BinData lookupBin(String cardNumber) {
        String bin = cardNumber.substring(0, 6);
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Call BIN Lookup Service
            BinData binData = binLookupClient.lookup(bin);
            
            // ✅ Success - Record with operation label
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
            
        } catch (Exception e) {
            log.error("BIN lookup failed: bin={}", bin, e);
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_fraud_router_component_calls_total{
  service="fraud-router",
  target_component="bin_lookup_service",
  operation="lookup"
} 1

fraud_switch_fraud_router_component_duration_seconds{
  target_component="bin_lookup_service",
  operation="lookup",
  quantile="0.99"
} 0.015  # 15ms
```

---

### Example 3: Fraud Router Calling Adapters (Parallel Calls)

```java
@Service
@Slf4j
public class FraudRouterService {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private FraudSightAdapterClient fraudSightClient;
    
    @Autowired
    private GuaranteedPaymentAdapterClient guaranteedPaymentClient;
    
    public FraudResponse processFraudCheck(FraudRequest request) {
        
        // Determine which adapters to call
        String product = request.getProduct();
        
        if ("fraudsight".equals(product)) {
            return callFraudSightAdapter(request);
        } else if ("guaranteed_payment".equals(product)) {
            return callGuaranteedPaymentAdapter(request);
        } else {
            throw new IllegalArgumentException("Unknown product: " + product);
        }
    }
    
    private FraudResponse callFraudSightAdapter(FraudRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            // Call FraudSight Adapter
            FraudSightResponse response = fraudSightClient.checkFraud(request);
            
            // ✅ Success
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCall(
                "fraudsight_adapter",
                duration,
                "event_type", request.getEventType(),
                "gateway", request.getGateway()
            );
            
            log.info("FraudSight adapter call completed: txn={}, duration={}ms", 
                    request.getTransactionId(), 
                    duration);
            
            return convertToFraudResponse(response);
            
        } catch (Exception e) {
            log.error("FraudSight adapter call failed", e);
            throw e;
        }
    }
    
    private FraudResponse callGuaranteedPaymentAdapter(FraudRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            // Call Guaranteed Payment Adapter
            GuaranteedPaymentResponse response = guaranteedPaymentClient.checkFraud(request);
            
            // ✅ Success
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCall(
                "guaranteed_payment_adapter",
                duration,
                "event_type", request.getEventType(),
                "gateway", request.getGateway()
            );
            
            log.info("GP adapter call completed: txn={}, duration={}ms", 
                    request.getTransactionId(), 
                    duration);
            
            return convertToFraudResponse(response);
            
        } catch (Exception e) {
            log.error("GP adapter call failed", e);
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
# FraudSight calls
fraud_switch_fraud_router_component_calls_total{
  target_component="fraudsight_adapter",
  event_type="beforeAuthenticationSync",
  gateway="RAFT"
} 850

# Guaranteed Payment calls
fraud_switch_fraud_router_component_calls_total{
  target_component="guaranteed_payment_adapter",
  event_type="beforeAuthenticationSync",
  gateway="RAFT"
} 150
```

---

### Example 4: Async Processor Calling Adapters

```java
@Service
@Slf4j
public class AsyncProcessorService {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private FraudSightAdapterClient fraudSightClient;
    
    @KafkaListener(topics = "async.events", groupId = "async-processor")
    public void processAsyncEvent(AsyncEvent event) {
        
        // Call FraudSight Adapter asynchronously
        long startTime = System.currentTimeMillis();
        
        try {
            FraudSightResponse response = fraudSightClient.checkFraudAsync(event);
            
            // ✅ Success
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCall(
                "fraudsight_adapter",
                duration,
                "event_type", event.getEventType(),
                "processing_mode", "async"
            );
            
            log.info("Async fraud check completed: txn={}, duration={}ms", 
                    event.getTransactionId(), 
                    duration);
            
            // Process response
            processResponse(response);
            
        } catch (Exception e) {
            log.error("Async adapter call failed", e);
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_async_processor_component_calls_total{
  service="async-processor",
  target_component="fraudsight_adapter",
  event_type="beforeAuthenticationAsync",
  processing_mode="async"
} 1
```

---

### Example 5: Tokenization Service Internal Calls

```java
@Service
@Slf4j
public class TokenizationService {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private RestTemplate restTemplate;
    
    /**
     * Decrypt PAN - calls internal decrypt endpoint
     */
    public String decryptPan(String encryptedPan, String transactionId) {
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Call internal decrypt API
            DecryptRequest request = new DecryptRequest(encryptedPan, transactionId);
            
            DecryptResponse response = restTemplate.postForObject(
                "http://localhost:8080/internal/decrypt",
                request,
                DecryptResponse.class
            );
            
            // ✅ Success - Internal component call
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordCall(
                "tokenization_service",  // Calling itself
                duration,
                "operation", "decrypt",
                "internal", "true"
            );
            
            log.info("Decryption completed: txn={}, duration={}ms", 
                    transactionId, 
                    duration);
            
            return response.getPlainPan();
            
        } catch (Exception e) {
            log.error("Decryption failed: txn={}", transactionId, e);
            throw e;
        }
    }
}
```

---

## 🎯 When to Use recordCall()

### ✅ Use recordCall() for:

1. **Internal service-to-service HTTP calls**
   - Fraud Router → Rules Service
   - Fraud Router → BIN Lookup Service
   - Fraud Router → Adapters
   - Async Processor → Adapters

2. **Successful calls only**
   - After HTTP call completes successfully
   - Response received and processed
   - No exceptions thrown

3. **Monitoring service dependencies**
   - Track which services are called most
   - Measure inter-service latency
   - Identify slow dependencies
   - Monitor call patterns

---

### ❌ DON'T use recordCall() for:

1. **External API calls** (outside Fraud Switch)
   - Ravelin API → Use provider metrics
   - Signifyd API → Use provider metrics
   - Issuer APIs → Use issuer metrics

2. **Failed calls**
   ```java
   // DON'T do this:
   try {
       callService();
       componentMetrics.recordCall("service", duration);  // ❌ What if it fails?
   } catch (Exception e) {
       // No error metric!
   }
   
   // DO this:
   try {
       callService();
       componentMetrics.recordCall("service", duration);  // ✅ Only on success
   } catch (Exception e) {
       componentMetrics.recordCallError("service", duration, e.getClass().getSimpleName());
   }
   ```

3. **Kafka operations**
   - Use KafkaMetrics for Kafka publish/consume

4. **Database calls**
   - Use database-specific metrics (HikariCP, etc.)

---

## 📊 What Metrics Are Created

When you call:
```java
componentMetrics.recordCall("rules_service", 25, "operation", "check_rules")
```

**Metrics created:**
```prometheus
# Counter - Total calls
fraud_switch_fraud_router_component_calls_total{
  service="fraud-router",
  region="us-ohio-1",
  target_component="rules_service",
  operation="check_rules"
} 1

# Timer - Duration distribution
fraud_switch_fraud_router_component_duration_seconds_bucket{
  target_component="rules_service",
  operation="check_rules",
  le="0.1"  # 100ms bucket
} 1

# Percentiles
fraud_switch_fraud_router_component_duration_seconds{
  target_component="rules_service",
  operation="check_rules",
  quantile="0.99"
} 0.025  # 25ms
```

---

## 📈 Prometheus Queries

### Call Rate (Calls per second)
```promql
rate(fraud_switch_fraud_router_component_calls_total{target_component="rules_service"}[5m])
```

### Call Rate by Target Component
```promql
sum by (target_component) (
  rate(fraud_switch_fraud_router_component_calls_total[5m])
)
```

### P99 Latency by Component
```promql
histogram_quantile(0.99,
  sum by (target_component, le) (
    rate(fraud_switch_fraud_router_component_duration_seconds_bucket[5m])
  )
)
```

### Total Calls (Last hour)
```promql
increase(fraud_switch_fraud_router_component_calls_total[1h])
```

### Top 5 Most Called Services
```promql
topk(5,
  sum by (target_component) (
    fraud_switch_fraud_router_component_calls_total
  )
)
```

### Average Call Duration
```promql
rate(fraud_switch_fraud_router_component_duration_seconds_sum{target_component="rules_service"}[5m])
/
rate(fraud_switch_fraud_router_component_duration_seconds_count{target_component="rules_service"}[5m])
```

---

## ⚠️ Common Mistakes

### ❌ MISTAKE 1: Wrong component name
```java
// WRONG - Not using canonical name
componentMetrics.recordCall("RulesService", duration);  // ❌ Wrong casing
componentMetrics.recordCall("rules-service", duration);  // ❌ Wrong separator

// CORRECT - Use canonical name
componentMetrics.recordCall("rules_service", duration);  // ✅ From architecture doc
```

### ❌ MISTAKE 2: Recording before call completes
```java
// WRONG - Recording before call
componentMetrics.recordCall("rules_service", 0);  // ❌ Duration is 0!
callRulesService();

// CORRECT - Record after call
long startTime = System.currentTimeMillis();
callRulesService();
long duration = System.currentTimeMillis() - startTime;
componentMetrics.recordCall("rules_service", duration);  // ✅
```

### ❌ MISTAKE 3: Not catching exceptions
```java
// WRONG - No error handling
long startTime = System.currentTimeMillis();
callRulesService();  // Might fail!
long duration = System.currentTimeMillis() - startTime;
componentMetrics.recordCall("rules_service", duration);  // ❌ Never reached on error

// CORRECT - Proper error handling
long startTime = System.currentTimeMillis();
try {
    callRulesService();
    long duration = System.currentTimeMillis() - startTime;
    componentMetrics.recordCall("rules_service", duration);  // ✅
} catch (Exception e) {
    long duration = System.currentTimeMillis() - startTime;
    componentMetrics.recordCallError("rules_service", duration, e.getClass().getSimpleName());
    throw e;
}
```

### ❌ MISTAKE 4: Using for external APIs
```java
// WRONG - External provider call
callRavelin();
componentMetrics.recordCall("ravelin", duration);  // ❌ Not an internal component!

// CORRECT - Use provider metrics
callRavelin();
providerMetrics.recordProviderCall("ravelin", duration);  // ✅
```

---

## 📝 Best Practices

### ✅ DO:

1. **Use canonical component names**
   ```java
   componentMetrics.recordCall("rules_service", duration);  // ✅
   ```

2. **Calculate accurate duration**
   ```java
   long startTime = System.currentTimeMillis();
   callService();
   long duration = System.currentTimeMillis() - startTime;
   ```

3. **Add operation labels**
   ```java
   componentMetrics.recordCall(
       "rules_service",
       duration,
       "operation", "check_rules",
       "merchant_id", merchantId
   );
   ```

4. **Wrap in try-catch**
   ```java
   try {
       callService();
       componentMetrics.recordCall("service", duration);
   } catch (Exception e) {
       componentMetrics.recordCallError("service", duration, errorType);
   }
   ```

---

### ❌ DON'T:

1. Don't use for external APIs
2. Don't use wrong component names
3. Don't forget error handling
4. Don't record before call completes
5. Don't use high cardinality labels (transaction IDs)

---

## ✅ Quick Checklist

Before using recordCall():

- [ ] Call completed successfully
- [ ] Using canonical component name
- [ ] Calculated actual duration
- [ ] Called AFTER service call
- [ ] Wrapped in try-catch
- [ ] Added meaningful labels
- [ ] Labels are low cardinality
- [ ] Internal service-to-service call (not external)

---

## 🎯 Summary

**recordCall()** tracks successful internal service-to-service calls:

```java
componentMetrics.recordCall(targetComponent, durationMs, labels...);
```

**Use for:**
- Internal HTTP calls between Fraud Switch services
- After call completes successfully
- Monitoring service dependencies

**Creates:**
```prometheus
fraud_switch_{service}_component_calls_total{
  target_component="...",
  operation="..."
} 1

fraud_switch_{service}_component_duration_seconds{
  target_component="...",
  quantile="0.99"
} 0.025
```

**Next:** Type **"next"** for **Method 2: `recordCallError()`**!
