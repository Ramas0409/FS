# 🎉 KafkaMetrics - Complete Method Guide

## Congratulations! You've Mastered All 4 KafkaMetrics Methods!

---

## 📚 All Methods At A Glance

```java
public class KafkaMetrics {
    
    // ✅ Method 1: Record successful publish
    public void recordPublish(String topic, String... labels)
    
    // ✅ Method 2: Record successful consume
    public void recordConsume(String topic, String... labels)
    
    // ❌ Method 3: Record failed publish
    public void recordPublishError(String topic, String errorType, String... labels)
    
    // ❌ Method 4: Record failed consume
    public void recordConsumeError(String topic, String errorType, String... labels)
}
```

---

## 🎯 Quick Selection Guide

```
Publishing a message?
    ↓
    ├─ Success → recordPublish(topic, labels...)
    └─ Failed  → recordPublishError(topic, errorType, labels...)

Consuming a message?
    ↓
    ├─ Success → recordConsume(topic, labels...)
    └─ Failed  → recordConsumeError(topic, errorType, labels...)
```

---

## 📊 Method Comparison Matrix

| Method | Operation | When | Pattern | Creates |
|--------|-----------|------|---------|---------|
| `recordPublish()` | Publish | After send succeeds | After `send()` | `kafka_publish_total` |
| `recordConsume()` | Consume | After processing succeeds | In `@KafkaListener` | `kafka_consume_total` |
| `recordPublishError()` | Publish | In catch block | When send fails | `kafka_publish_errors_total` |
| `recordConsumeError()` | Consume | In catch block | When processing fails | `kafka_consume_errors_total` |

---

## 💻 All Methods - Side by Side Examples

### Method 1: recordPublish() - ✅ Successful Publish
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
            
            // ✅ SUCCESS - Method 1
            kafkaMetrics.recordPublish(
                "fraud.events",
                "event_type", event.getEventType()
            );
            
        } catch (Exception e) {
            // Handle error
            throw e;
        }
    }
}
```

**Creates:**
```prometheus
fraud_switch_fraud_router_kafka_publish_total{
  topic="fraud.events",
  event_type="beforeAuthenticationSync"
} 1
```

---

### Method 2: recordConsume() - ✅ Successful Consume
```java
@Service
public class FraudEventConsumer {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @KafkaListener(topics = "fraud.events", groupId = "fraud-processor")
    public void consumeFraudEvent(FraudEvent event) {
        try {
            // Process the event
            processFraudEvent(event);
            
            // ✅ SUCCESS - Method 2
            kafkaMetrics.recordConsume(
                "fraud.events",
                "event_type", event.getEventType()
            );
            
        } catch (Exception e) {
            // Handle error
            throw e;
        }
    }
}
```

**Creates:**
```prometheus
fraud_switch_fraud_router_kafka_consume_total{
  topic="fraud.events",
  event_type="beforeAuthenticationSync"
} 1
```

---

### Method 3: recordPublishError() - ❌ Failed Publish
```java
@Service
public class FraudEventPublisher {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private KafkaTemplate<String, FraudEvent> kafkaTemplate;
    
    public void publishFraudEvent(FraudEvent event) {
        try {
            kafkaTemplate.send("fraud.events", event.getTransactionId(), event);
            kafkaMetrics.recordPublish("fraud.events");
            
        } catch (TimeoutException e) {
            // ❌ ERROR - Method 3
            kafkaMetrics.recordPublishError(
                "fraud.events",
                "TimeoutException",
                "event_type", event.getEventType()
            );
            throw e;
        }
    }
}
```

**Creates:**
```prometheus
fraud_switch_fraud_router_kafka_publish_errors_total{
  topic="fraud.events",
  error_type="TimeoutException",
  event_type="beforeAuthenticationSync"
} 1
```

---

### Method 4: recordConsumeError() - ❌ Failed Consume
```java
@Service
public class FraudEventConsumer {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @KafkaListener(topics = "fraud.events", groupId = "fraud-processor")
    public void consumeFraudEvent(FraudEvent event) {
        try {
            processFraudEvent(event);
            kafkaMetrics.recordConsume("fraud.events");
            
        } catch (DatabaseException e) {
            // ❌ ERROR - Method 4
            kafkaMetrics.recordConsumeError(
                "fraud.events",
                "DatabaseException",
                "event_type", event.getEventType()
            );
            throw e;
        }
    }
}
```

**Creates:**
```prometheus
fraud_switch_fraud_router_kafka_consume_errors_total{
  topic="fraud.events",
  error_type="DatabaseException",
  event_type="beforeAuthenticationSync"
} 1
```

---

## 🎓 Complete Kafka Flow Example

Here's a complete example showing all 4 methods in a real Kafka flow:

```java
// ==================== PRODUCER SERVICE ====================
@Service
@Slf4j
public class FraudEventPublisher {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private KafkaTemplate<String, FraudEvent> kafkaTemplate;
    
    private static final String TOPIC = "fraud.events";
    
    /**
     * Publish fraud event - Uses Methods 1 & 3
     */
    public void publishFraudEvent(FraudEvent event) {
        try {
            // Publish to Kafka
            kafkaTemplate.send(TOPIC, event.getTransactionId(), event)
                .get(5, TimeUnit.SECONDS);
            
            // ✅ METHOD 1: Record successful publish
            kafkaMetrics.recordPublish(
                TOPIC,
                "event_type", event.getEventType(),
                "gateway", event.getGateway()
            );
            
            log.info("Published fraud event: txn={}", event.getTransactionId());
            
        } catch (TimeoutException e) {
            // ❌ METHOD 3: Record publish error
            kafkaMetrics.recordPublishError(
                TOPIC,
                "TimeoutException",
                "event_type", event.getEventType()
            );
            
            log.error("Publish timeout: txn={}", event.getTransactionId(), e);
            throw new PublishException("Kafka timeout", e);
            
        } catch (Exception e) {
            // ❌ METHOD 3: Record publish error
            kafkaMetrics.recordPublishError(
                TOPIC,
                e.getClass().getSimpleName(),
                "event_type", event.getEventType()
            );
            
            log.error("Publish failed: txn={}", event.getTransactionId(), e);
            throw new PublishException("Kafka error", e);
        }
    }
}

// ==================== CONSUMER SERVICE ====================
@Service
@Slf4j
public class FraudEventConsumer {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private FraudProcessorService processorService;
    
    private static final String TOPIC = "fraud.events";
    
    /**
     * Consume fraud event - Uses Methods 2 & 4
     */
    @KafkaListener(topics = TOPIC, groupId = "fraud-processor")
    public void consumeFraudEvent(
            @Payload FraudEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition) {
        
        try {
            // Process the event
            processorService.processFraudEvent(event);
            
            // ✅ METHOD 2: Record successful consumption
            kafkaMetrics.recordConsume(
                TOPIC,
                "event_type", event.getEventType(),
                "partition", String.valueOf(partition)
            );
            
            log.info("Processed fraud event: txn={}, partition={}", 
                    event.getTransactionId(),
                    partition);
            
        } catch (DatabaseException e) {
            // ❌ METHOD 4: Record consume error
            kafkaMetrics.recordConsumeError(
                TOPIC,
                "DatabaseException",
                "event_type", event.getEventType(),
                "partition", String.valueOf(partition)
            );
            
            log.error("Database error: txn={}, partition={}", 
                     event.getTransactionId(),
                     partition,
                     e);
            throw e;
            
        } catch (ValidationException e) {
            // ❌ METHOD 4: Record consume error
            kafkaMetrics.recordConsumeError(
                TOPIC,
                "ValidationException",
                "event_type", event.getEventType()
            );
            
            log.error("Validation error: txn={}", event.getTransactionId(), e);
            throw e;
            
        } catch (Exception e) {
            // ❌ METHOD 4: Record consume error
            kafkaMetrics.recordConsumeError(
                TOPIC,
                "UnexpectedError",
                "event_type", event.getEventType()
            );
            
            log.error("Unexpected error: txn={}", event.getTransactionId(), e);
            throw e;
        }
    }
}
```

---

## 📊 Metrics Created Summary

All methods create these Kafka metrics:

```prometheus
# Method 1: Successful publishes
fraud_switch_{service}_kafka_publish_total{
  topic="fraud.events",
  event_type="beforeAuthenticationSync"
} 1000

# Method 2: Successful consumes
fraud_switch_{service}_kafka_consume_total{
  topic="fraud.events",
  event_type="beforeAuthenticationSync",
  partition="0"
} 950

# Method 3: Failed publishes
fraud_switch_{service}_kafka_publish_errors_total{
  topic="fraud.events",
  error_type="TimeoutException",
  event_type="beforeAuthenticationSync"
} 10

# Method 4: Failed consumes
fraud_switch_{service}_kafka_consume_errors_total{
  topic="fraud.events",
  error_type="DatabaseException",
  event_type="beforeAuthenticationSync"
} 5
```

---

## 📈 Complete Prometheus Dashboard

### Panel 1: Publish Rate
```promql
sum by (topic) (
  rate(fraud_switch_fraud_router_kafka_publish_total[5m])
)
```

### Panel 2: Consume Rate
```promql
sum by (topic) (
  rate(fraud_switch_async_processor_kafka_consume_total[5m])
)
```

### Panel 3: Publish Error Rate
```promql
sum by (topic, error_type) (
  rate(fraud_switch_fraud_router_kafka_publish_errors_total[5m])
)
```

### Panel 4: Consume Error Rate
```promql
sum by (topic, error_type) (
  rate(fraud_switch_async_processor_kafka_consume_errors_total[5m])
)
```

### Panel 5: Publish Success Rate %
```promql
(
  rate(fraud_switch_fraud_router_kafka_publish_total[5m])
  /
  (
    rate(fraud_switch_fraud_router_kafka_publish_total[5m]) +
    rate(fraud_switch_fraud_router_kafka_publish_errors_total[5m])
  )
) * 100
```

### Panel 6: Consume Success Rate %
```promql
(
  rate(fraud_switch_async_processor_kafka_consume_total[5m])
  /
  (
    rate(fraud_switch_async_processor_kafka_consume_total[5m]) +
    rate(fraud_switch_async_processor_kafka_consume_errors_total[5m])
  )
) * 100
```

### Panel 7: Consumer Lag (Publish vs Consume)
```promql
# Messages published but not yet consumed
rate(fraud_switch_fraud_router_kafka_publish_total{topic="fraud.events"}[5m])
-
rate(fraud_switch_async_processor_kafka_consume_total{topic="fraud.events"}[5m])
```

### Panel 8: Top Error Types (All Kafka Operations)
```promql
topk(10,
  sum by (error_type) (
    rate(fraud_switch_fraud_router_kafka_publish_errors_total[5m]) +
    rate(fraud_switch_async_processor_kafka_consume_errors_total[5m])
  )
)
```

---

## 🎯 Method Usage Breakdown

### Method 1: recordPublish() - 90% of publishes
```java
// Simple successful publish
kafkaTemplate.send("fraud.events", event);
kafkaMetrics.recordPublish("fraud.events", "event_type", eventType);
```

**When:** After `kafkaTemplate.send()` succeeds  
**Creates:** `kafka_publish_total` counter

---

### Method 2: recordConsume() - 85% of consumes
```java
// Simple successful consume
@KafkaListener(topics = "fraud.events")
public void consume(FraudEvent event) {
    processFraudEvent(event);
    kafkaMetrics.recordConsume("fraud.events");
}
```

**When:** After message processing succeeds  
**Creates:** `kafka_consume_total` counter

---

### Method 3: recordPublishError() - 5-10% of publishes
```java
// Handle publish failures
catch (Exception e) {
    kafkaMetrics.recordPublishError("fraud.events", e.getClass().getSimpleName());
}
```

**When:** In catch block after publish fails  
**Creates:** `kafka_publish_errors_total` counter

---

### Method 4: recordConsumeError() - 10-15% of consumes
```java
// Handle processing failures
catch (Exception e) {
    kafkaMetrics.recordConsumeError("fraud.events", e.getClass().getSimpleName());
}
```

**When:** In catch block after processing fails  
**Creates:** `kafka_consume_errors_total` counter

---

## 🚨 Common Error Types

### Publish Errors (Method 3):
- `TimeoutException` - Broker didn't respond
- `SerializationException` - Can't serialize message
- `NetworkException` - Network failure
- `BrokerUnavailableException` - Kafka down
- `AuthorizationException` - No write permission

### Consume Errors (Method 4):
- `DeserializationException` - Can't deserialize message
- `DatabaseException` - DB save failed
- `ValidationException` - Invalid message
- `TimeoutException` - Processing took too long
- `ApiException` - External API failed
- `TokenizationException` - Tokenization failed

---

## ✅ Complete Best Practices Checklist

### For All Methods:
- [ ] Use exact topic name
- [ ] Add meaningful labels (event_type, gateway, partition)
- [ ] Keep label cardinality low (< 100 unique values)
- [ ] No PII/sensitive data in labels
- [ ] No transaction IDs in labels

### For recordPublish():
- [ ] Call AFTER successful send
- [ ] Wrap in try-catch
- [ ] Use with recordPublishError() for errors

### For recordConsume():
- [ ] Call AFTER successful processing
- [ ] In `@KafkaListener` methods
- [ ] Before manual acknowledgment
- [ ] Use with recordConsumeError() for errors

### For recordPublishError():
- [ ] Use exception class name (not message)
- [ ] Call in catch blocks
- [ ] Track different error types
- [ ] Don't also call recordPublish()

### For recordConsumeError():
- [ ] Use exception class name (not message)
- [ ] Call in catch blocks
- [ ] Include partition info if available
- [ ] Don't also call recordConsume()

---

## 📋 Decision Matrix

| Situation | Method | Example |
|-----------|--------|---------|
| Published successfully | `recordPublish()` | `kafkaTemplate.send()` succeeded |
| Publish failed | `recordPublishError()` | `kafkaTemplate.send()` threw exception |
| Consumed successfully | `recordConsume()` | Processing completed without error |
| Consume failed | `recordConsumeError()` | Processing threw exception |

---

## 🎓 Real-World Kafka Topics in Fraud Switch

| Topic | Publisher | Consumer | Methods Used |
|-------|-----------|----------|--------------|
| `fraud.events` | Fraud Router | Async Processor | 1, 2, 3, 4 |
| `async.events` | Fraud Router | Async Processor | 1, 2, 3, 4 |
| `pan.queue` | Fraud Router | Tokenization Service | 1, 2, 3, 4 |
| `issuer.datashare.events` | Issuer Data Service | Issuer Caller | 1, 2, 3, 4 |
| `fs.transactions` | Adapters | Analytics | 1, 2, 3, 4 |
| `fs.declines` | Adapters | Analytics | 1, 2, 3, 4 |

---

## 🎉 You're Now a KafkaMetrics Expert!

### What You've Mastered:

✅ **recordPublish()** - Track successful Kafka publishes  
✅ **recordConsume()** - Track successful message processing  
✅ **recordPublishError()** - Track publish failures  
✅ **recordConsumeError()** - Track consumption failures  

### You Can Now:

- Monitor Kafka operations end-to-end
- Track publish/consume rates by topic
- Identify error patterns
- Detect consumer lag
- Alert on Kafka failures
- Debug poison messages
- Optimize retry strategies

---

## 📚 All Documentation Files

| Method | Guide | Size |
|--------|-------|------|
| recordPublish() | [Full Guide](KafkaMetrics_Method1_recordPublish.md) | Comprehensive |
| recordConsume() | [Full Guide](KafkaMetrics_Method2_recordConsume.md) | Comprehensive |
| recordPublishError() | [Full Guide](KafkaMetrics_Method3_recordPublishError.md) | Comprehensive |
| recordConsumeError() | [Full Guide](KafkaMetrics_Method4_recordConsumeError.md) | Comprehensive |

---

## 🚀 Next Steps

1. **Implement** in your Kafka producers
2. **Implement** in your Kafka consumers
3. **Create** Prometheus queries
4. **Build** Grafana dashboards
5. **Set up** alerts for errors
6. **Monitor** consumer lag

---

**You've completed KafkaMetrics! Ready to implement it in your services!** 🎉

Need help with anything else about the Fraud Switch metrics library?
