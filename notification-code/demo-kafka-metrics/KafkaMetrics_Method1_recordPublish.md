# KafkaMetrics Method 1: recordPublish()

## 📋 Method Signature
```java
public void recordPublish(String topic, String... labels)
```

## 🎯 Purpose
Records a **successful Kafka message publish** event. Tracks when your service publishes messages to Kafka topics.

---

## 📖 What is This For?

In Fraud Switch, services publish messages to Kafka topics:
- **Fraud Router** → publishes to `fraud.events` topic
- **Async Processor** → publishes to `async.events` topic  
- **Tokenization Service** → publishes to `pan.queue` topic
- **Issuer Data Service** → publishes to `issuer.datashare.events` topic

`recordPublish()` tracks these publishing operations so you can monitor:
- How many messages are being published?
- Which topics are busiest?
- Are messages being published successfully?
- What's the publish rate per topic?

---

## 📖 Parameters Explained

### 1. `topic` (String)
- **Which Kafka topic** you're publishing to
- Examples: "fraud.events", "async.events", "pan.queue"
- Must be the actual Kafka topic name

### 2. `labels` (String... varargs)
- Optional additional labels (key-value pairs)
- Must be even number of strings (key, value, key, value...)
- Common labels: "event_type", "gateway", "merchant_id"

---

## 💻 Basic Usage Examples

### Example 1: Simple Kafka Publish

```java
@Service
public class FraudEventPublisher {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private KafkaTemplate<String, FraudEvent> kafkaTemplate;
    
    public void publishFraudEvent(FraudEvent event) {
        try {
            // Publish to Kafka
            kafkaTemplate.send("fraud.events", event.getTransactionId(), event);
            
            // ✅ Record successful publish
            kafkaMetrics.recordPublish("fraud.events");
            
        } catch (Exception e) {
            log.error("Failed to publish fraud event", e);
            // Would use recordPublishError() here (Method 3)
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_fraud_router_kafka_publish_total{
  service="fraud-router",
  region="us-ohio-1",
  topic="fraud.events"
} 1
```

---

### Example 2: Publish with Event Type Label

```java
@Service
public class AsyncEventPublisher {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private KafkaTemplate<String, AsyncEvent> kafkaTemplate;
    
    public void publishAsync(AsyncEvent event) {
        try {
            // Publish to Kafka
            kafkaTemplate.send("async.events", event.getTransactionId(), event);
            
            // ✅ Record with event type label
            kafkaMetrics.recordPublish(
                "async.events",
                "event_type", event.getEventType()
            );
            
        } catch (Exception e) {
            log.error("Failed to publish async event", e);
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_async_processor_kafka_publish_total{
  service="async-processor",
  region="us-ohio-1",
  topic="async.events",
  event_type="beforeAuthenticationAsync"
} 1
```

**Now you can query:** "How many async events of each type per minute?"

---

### Example 3: Tokenization Service PAN Queue

```java
@Service
public class PanTokenizationPublisher {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private KafkaTemplate<String, PanTokenizationRequest> kafkaTemplate;
    
    public void publishPanForTokenization(PanTokenizationRequest request) {
        try {
            // Publish PAN to tokenization queue
            kafkaTemplate.send("pan.queue", request.getTransactionId(), request);
            
            // ✅ Record with gateway and product labels
            kafkaMetrics.recordPublish(
                "pan.queue",
                "gateway", request.getGateway(),
                "product", request.getProduct()
            );
            
            log.info("Published PAN for tokenization: txn={}", request.getTransactionId());
            
        } catch (Exception e) {
            log.error("Failed to publish PAN", e);
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_tokenization_service_kafka_publish_total{
  service="tokenization-service",
  region="us-ohio-1",
  topic="pan.queue",
  gateway="RAFT",
  product="fraudsight"
} 1
```

---

### Example 4: Issuer Data-Share Events

```java
@Service
public class IssuerDataSharePublisher {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private KafkaTemplate<String, IssuerDataShareEvent> kafkaTemplate;
    
    public void publishIssuerDataShare(IssuerDataShareEvent event) {
        try {
            // Publish to issuer data-share topic
            kafkaTemplate.send("issuer.datashare.events", 
                              event.getTransactionId(), 
                              event);
            
            // ✅ Record with issuer and event details
            kafkaMetrics.recordPublish(
                "issuer.datashare.events",
                "issuer_name", event.getIssuerName(),
                "event_type", event.getEventType()
            );
            
            log.info("Published issuer data-share: txn={}, issuer={}", 
                    event.getTransactionId(), 
                    event.getIssuerName());
            
        } catch (Exception e) {
            log.error("Failed to publish issuer data-share", e);
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_issuer_data_service_kafka_publish_total{
  service="issuer-data-service",
  region="us-ohio-1",
  topic="issuer.datashare.events",
  issuer_name="chase",
  event_type="transaction_data"
} 1
```

---

### Example 5: With Kafka Callback (Async Confirmation)

```java
@Service
public class FraudEventPublisherWithCallback {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private KafkaTemplate<String, FraudEvent> kafkaTemplate;
    
    public void publishFraudEventAsync(FraudEvent event) {
        
        kafkaTemplate.send("fraud.events", event.getTransactionId(), event)
            .addCallback(
                // SUCCESS callback
                success -> {
                    // ✅ Record successful publish
                    kafkaMetrics.recordPublish(
                        "fraud.events",
                        "event_type", event.getEventType(),
                        "gateway", event.getGateway()
                    );
                    
                    log.info("Successfully published fraud event: txn={}, partition={}, offset={}", 
                            event.getTransactionId(),
                            success.getRecordMetadata().partition(),
                            success.getRecordMetadata().offset());
                },
                
                // FAILURE callback
                failure -> {
                    // Would use recordPublishError() here
                    log.error("Failed to publish fraud event: txn={}", 
                             event.getTransactionId(), 
                             failure);
                }
            );
    }
}
```

---

## 🎯 When to Use recordPublish()

### ✅ Use recordPublish() for:

1. **Successful Kafka publishes**
   - After `kafkaTemplate.send()` succeeds
   - In success callback of async sends

2. **All topic types**
   - Event topics (`fraud.events`)
   - Queue topics (`pan.queue`)
   - Data-share topics (`issuer.datashare.events`)
   - Async processing topics (`async.events`)

3. **Monitoring publish patterns**
   - Track which topics are busiest
   - Monitor publish rates per event type
   - Identify high-volume sources

---

### ❌ DON'T use recordPublish() for:

1. **Failed publishes**
   ```java
   // DON'T do this:
   try {
       kafkaTemplate.send("topic", message);
       kafkaMetrics.recordPublish("topic");  // ❌ What if send fails?
   } catch (Exception e) {
       // No error metric!
   }
   
   // DO this:
   try {
       kafkaTemplate.send("topic", message);
       kafkaMetrics.recordPublish("topic");  // ✅ Only if successful
   } catch (Exception e) {
       kafkaMetrics.recordPublishError("topic", e.getClass().getSimpleName());  // ✅
   }
   ```

2. **Message consumption**
   - Use `recordConsume()` for consuming messages (Method 2)

3. **Before the publish**
   - Only record AFTER successful publish

---

## 📊 What Metrics Are Created

When you call:
```java
kafkaMetrics.recordPublish("fraud.events", "event_type", "beforeAuthenticationSync")
```

**Metric created:**
```prometheus
fraud_switch_fraud_router_kafka_publish_total{
  service="fraud-router",
  region="us-ohio-1",
  topic="fraud.events",
  event_type="beforeAuthenticationSync"
} 1
```

**This is a COUNTER** - it only goes up, never down.

---

## 📈 Prometheus Queries

### Publish Rate (Messages per second)
```promql
rate(fraud_switch_fraud_router_kafka_publish_total{topic="fraud.events"}[5m])
```

### Publish Rate by Topic
```promql
sum by (topic) (
  rate(fraud_switch_fraud_router_kafka_publish_total[5m])
)
```

### Publish Rate by Event Type
```promql
sum by (event_type) (
  rate(fraud_switch_fraud_router_kafka_publish_total{topic="fraud.events"}[5m])
)
```

### Total Messages Published (Last hour)
```promql
increase(fraud_switch_fraud_router_kafka_publish_total[1h])
```

### Top 5 Busiest Topics
```promql
topk(5,
  sum by (topic) (
    rate(fraud_switch_fraud_router_kafka_publish_total[5m])
  )
)
```

---

## ⚠️ Common Mistakes

### ❌ MISTAKE 1: Recording before publish
```java
// WRONG - Recording before publish
kafkaMetrics.recordPublish("topic");  // ❌ What if publish fails?
kafkaTemplate.send("topic", message);

// CORRECT - Recording after publish
kafkaTemplate.send("topic", message);
kafkaMetrics.recordPublish("topic");  // ✅ Only if successful
```

### ❌ MISTAKE 2: Not catching exceptions
```java
// WRONG - No error handling
kafkaTemplate.send("topic", message);
kafkaMetrics.recordPublish("topic");  // ❌ Always called even if send fails

// CORRECT - Proper error handling
try {
    kafkaTemplate.send("topic", message);
    kafkaMetrics.recordPublish("topic");  // ✅ Only on success
} catch (Exception e) {
    kafkaMetrics.recordPublishError("topic", e.getClass().getSimpleName());
    throw e;
}
```

### ❌ MISTAKE 3: High cardinality labels
```java
// WRONG - Transaction ID is high cardinality!
kafkaMetrics.recordPublish(
    "fraud.events",
    "transaction_id", transactionId  // ❌ Millions of unique values!
);

// CORRECT - Use low cardinality labels
kafkaMetrics.recordPublish(
    "fraud.events",
    "event_type", eventType,  // ✅ ~6 event types
    "gateway", gateway         // ✅ ~5 gateways
);
```

### ❌ MISTAKE 4: Wrong topic name
```java
// WRONG - Topic name doesn't match actual topic
kafkaMetrics.recordPublish("fraud-events");  // ❌ Using hyphen
kafkaTemplate.send("fraud.events", message); // Actual topic uses dot

// CORRECT - Use exact topic name
kafkaMetrics.recordPublish("fraud.events");  // ✅ Matches actual topic
kafkaTemplate.send("fraud.events", message);
```

---

## 📝 Best Practices

### ✅ DO:

1. **Record only on success**
   ```java
   try {
       kafkaTemplate.send("topic", message);
       kafkaMetrics.recordPublish("topic");  // ✅ After success
   } catch (Exception e) {
       // Handle error
   }
   ```

2. **Use exact topic names**
   ```java
   kafkaMetrics.recordPublish("fraud.events");  // ✅ Match actual topic
   ```

3. **Add business context labels**
   ```java
   kafkaMetrics.recordPublish(
       "fraud.events",
       "event_type", event.getEventType(),
       "gateway", event.getGateway()
   );
   ```

4. **Keep label cardinality low**
   - event_type: ~6 values ✅
   - gateway: ~5 values ✅
   - product: ~2 values ✅
   - transaction_id: millions ❌

---

### ❌ DON'T:

1. Don't record before publish
2. Don't use high cardinality labels
3. Don't forget error handling
4. Don't use wrong topic names

---

## ✅ Quick Checklist

Before using recordPublish():

- [ ] Kafka publish succeeded
- [ ] Using exact topic name
- [ ] Called AFTER successful send
- [ ] Added meaningful labels
- [ ] Labels are low cardinality
- [ ] Wrapped in try-catch
- [ ] No PII/sensitive data in labels

---

## 🎯 Real-World Example: Complete Kafka Publisher

```java
@Service
@Slf4j
public class CompleteFraudEventPublisher {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private KafkaTemplate<String, FraudEvent> kafkaTemplate;
    
    private static final String TOPIC = "fraud.events";
    
    /**
     * Publish fraud event with proper metrics tracking
     */
    public void publishFraudEvent(FraudEvent event) {
        try {
            // Publish to Kafka
            SendResult<String, FraudEvent> result = kafkaTemplate.send(
                TOPIC,
                event.getTransactionId(),
                event
            ).get(5, TimeUnit.SECONDS);  // Wait up to 5 seconds
            
            // ✅ SUCCESS - Record publish metric
            kafkaMetrics.recordPublish(
                TOPIC,
                "event_type", event.getEventType(),
                "gateway", event.getGateway(),
                "product", event.getProduct()
            );
            
            log.info("Published fraud event: txn={}, partition={}, offset={}",
                    event.getTransactionId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            
        } catch (TimeoutException e) {
            // ❌ TIMEOUT - Record error (Method 3)
            kafkaMetrics.recordPublishError(TOPIC, "TimeoutException");
            log.error("Timeout publishing fraud event: txn={}", event.getTransactionId(), e);
            throw new PublishException("Kafka publish timeout", e);
            
        } catch (ExecutionException e) {
            // ❌ EXECUTION ERROR - Record error
            kafkaMetrics.recordPublishError(TOPIC, "ExecutionException");
            log.error("Execution error publishing fraud event: txn={}", event.getTransactionId(), e);
            throw new PublishException("Kafka publish failed", e);
            
        } catch (InterruptedException e) {
            // ❌ INTERRUPTED - Record error
            kafkaMetrics.recordPublishError(TOPIC, "InterruptedException");
            Thread.currentThread().interrupt();
            log.error("Interrupted publishing fraud event: txn={}", event.getTransactionId(), e);
            throw new PublishException("Kafka publish interrupted", e);
        }
    }
}
```

---

## 🎓 Summary

**recordPublish()** tracks successful Kafka message publishes:

```java
kafkaMetrics.recordPublish(topic, labels...);
```

**Use for:**
- Successful Kafka publishes
- After `kafkaTemplate.send()` succeeds
- Monitoring topic activity

**Creates:**
```prometheus
fraud_switch_{service}_kafka_publish_total{
  topic="...",
  event_type="...",
  gateway="..."
} 1
```

**Next:** Type **"next"** for **Method 2: `recordConsume()`**!
