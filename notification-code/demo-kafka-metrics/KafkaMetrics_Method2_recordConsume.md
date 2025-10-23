# KafkaMetrics Method 2: recordConsume()

## 📋 Method Signature
```java
public void recordConsume(String topic, String... labels)
```

## 🎯 Purpose
Records a **successful Kafka message consumption** event. Tracks when your service consumes (reads/processes) messages from Kafka topics.

---

## 📖 What is This For?

In Fraud Switch, services consume messages from Kafka topics:
- **Tokenization Service** → consumes from `pan.queue` topic
- **Async Processor** → consumes from `async.events` topic
- **Issuer Data Service** → consumes from `issuer.datashare.events` topic
- **Analytics Pipeline** → consumes from `fs.transactions`, `fs.declines` topics

`recordConsume()` tracks these consumption operations so you can monitor:
- How many messages are being consumed?
- Which topics are being read from?
- Are messages being processed successfully?
- What's the consumption rate per topic?
- Is there consumer lag?

---

## 📖 Parameters Explained

### 1. `topic` (String)
- **Which Kafka topic** you're consuming from
- Examples: "pan.queue", "async.events", "fraud.events"
- Must be the actual Kafka topic name

### 2. `labels` (String... varargs)
- Optional additional labels (key-value pairs)
- Must be even number of strings (key, value, key, value...)
- Common labels: "consumer_group", "partition", "event_type"

---

## 💻 Basic Usage Examples

### Example 1: Simple Kafka Consumer

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
            
            // ✅ Record successful consumption
            kafkaMetrics.recordConsume("fraud.events");
            
            log.info("Successfully processed fraud event: txn={}", event.getTransactionId());
            
        } catch (Exception e) {
            log.error("Failed to process fraud event", e);
            // Would use recordConsumeError() here (Method 4)
            throw e;
        }
    }
    
    private void processFraudEvent(FraudEvent event) {
        // Business logic here
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_fraud_router_kafka_consume_total{
  service="fraud-router",
  region="us-ohio-1",
  topic="fraud.events"
} 1
```

---

### Example 2: Tokenization Service Consuming PAN Queue

```java
@Service
@Slf4j
public class PanTokenizationConsumer {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private TokenizationService tokenizationService;
    
    @KafkaListener(topics = "pan.queue", groupId = "tokenization-service")
    public void consumePanTokenizationRequest(
            @Payload PanTokenizationRequest request,
            @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition) {
        
        try {
            // Tokenize the PAN
            String token = tokenizationService.tokenize(request.getPan());
            
            // ✅ Record successful consumption with partition
            kafkaMetrics.recordConsume(
                "pan.queue",
                "partition", String.valueOf(partition),
                "gateway", request.getGateway()
            );
            
            log.info("Tokenized PAN: txn={}, partition={}", 
                    request.getTransactionId(), 
                    partition);
            
        } catch (Exception e) {
            log.error("Failed to tokenize PAN: txn={}", request.getTransactionId(), e);
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_tokenization_service_kafka_consume_total{
  service="tokenization-service",
  region="us-ohio-1",
  topic="pan.queue",
  partition="0",
  gateway="RAFT"
} 1
```

**Now you can query:** "How many PAN tokenization requests per partition?"

---

### Example 3: Async Processor Consuming Events

```java
@Service
@Slf4j
public class AsyncEventConsumer {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private AsyncProcessorService processorService;
    
    @KafkaListener(
        topics = "async.events",
        groupId = "async-processor",
        concurrency = "3"
    )
    public void consumeAsyncEvent(
            @Payload AsyncEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {
        
        try {
            // Process async event
            processorService.processAsync(event);
            
            // ✅ Record with event type and processing details
            kafkaMetrics.recordConsume(
                "async.events",
                "event_type", event.getEventType(),
                "partition", String.valueOf(partition)
            );
            
            log.info("Processed async event: txn={}, type={}, partition={}, offset={}", 
                    event.getTransactionId(),
                    event.getEventType(),
                    partition,
                    offset);
            
        } catch (Exception e) {
            log.error("Failed to process async event: txn={}", 
                     event.getTransactionId(), 
                     e);
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_async_processor_kafka_consume_total{
  service="async-processor",
  region="us-ohio-1",
  topic="async.events",
  event_type="beforeAuthenticationAsync",
  partition="1"
} 1
```

---

### Example 4: Issuer Data-Share Consumer

```java
@Service
@Slf4j
public class IssuerDataShareConsumer {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private IssuerCallService issuerCallService;
    
    @KafkaListener(
        topics = "issuer.datashare.events",
        groupId = "issuer-data-service"
    )
    public void consumeIssuerDataShareEvent(IssuerDataShareEvent event) {
        
        try {
            // Call configured issuers
            List<String> issuers = event.getIssuers();
            
            for (String issuer : issuers) {
                issuerCallService.callIssuer(issuer, event);
            }
            
            // ✅ Record consumption with issuer count
            kafkaMetrics.recordConsume(
                "issuer.datashare.events",
                "issuer_count", String.valueOf(issuers.size()),
                "event_type", event.getEventType()
            );
            
            log.info("Processed issuer data-share: txn={}, issuers={}", 
                    event.getTransactionId(),
                    issuers);
            
        } catch (Exception e) {
            log.error("Failed to process issuer data-share: txn={}", 
                     event.getTransactionId(), 
                     e);
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_issuer_data_service_kafka_consume_total{
  service="issuer-data-service",
  region="us-ohio-1",
  topic="issuer.datashare.events",
  issuer_count="2",
  event_type="transaction_data"
} 1
```

---

### Example 5: Batch Consumer with Manual Acknowledgment

```java
@Service
@Slf4j
public class BatchFraudEventConsumer {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @KafkaListener(
        topics = "fraud.events",
        groupId = "batch-processor",
        containerFactory = "batchKafkaListenerContainerFactory"
    )
    public void consumeBatch(
            List<ConsumerRecord<String, FraudEvent>> records,
            Acknowledgment acknowledgment) {
        
        int successCount = 0;
        int errorCount = 0;
        
        for (ConsumerRecord<String, FraudEvent> record : records) {
            try {
                // Process individual record
                processFraudEvent(record.value());
                successCount++;
                
            } catch (Exception e) {
                errorCount++;
                log.error("Failed to process record: partition={}, offset={}", 
                         record.partition(), 
                         record.offset(), 
                         e);
            }
        }
        
        // Record metrics for successful messages
        for (int i = 0; i < successCount; i++) {
            kafkaMetrics.recordConsume("fraud.events");
        }
        
        // Record errors for failed messages
        for (int i = 0; i < errorCount; i++) {
            kafkaMetrics.recordConsumeError("fraud.events", "ProcessingError");
        }
        
        // Manual acknowledgment
        acknowledgment.acknowledge();
        
        log.info("Processed batch: total={}, success={}, errors={}", 
                records.size(), 
                successCount, 
                errorCount);
    }
}
```

---

## 🎯 When to Use recordConsume()

### ✅ Use recordConsume() for:

1. **Successful message consumption**
   - After message is successfully processed
   - In `@KafkaListener` methods
   - After business logic completes

2. **All consumer types**
   - Single message consumers
   - Batch consumers
   - Manual acknowledgment consumers

3. **Monitoring consumption patterns**
   - Track consumption rates per topic
   - Monitor per-partition consumption
   - Identify slow consumers

---

### ❌ DON'T use recordConsume() for:

1. **Failed message processing**
   ```java
   // DON'T do this:
   @KafkaListener(topics = "fraud.events")
   public void consume(FraudEvent event) {
       processFraudEvent(event);  // Might fail!
       kafkaMetrics.recordConsume("fraud.events");  // ❌ Always called
   }
   
   // DO this:
   @KafkaListener(topics = "fraud.events")
   public void consume(FraudEvent event) {
       try {
           processFraudEvent(event);
           kafkaMetrics.recordConsume("fraud.events");  // ✅ Only on success
       } catch (Exception e) {
           kafkaMetrics.recordConsumeError("fraud.events", e.getClass().getSimpleName());
           throw e;
       }
   }
   ```

2. **Message publishing**
   - Use `recordPublish()` for publishing messages (Method 1)

3. **Before processing**
   - Only record AFTER successful processing

---

## 📊 What Metrics Are Created

When you call:
```java
kafkaMetrics.recordConsume("async.events", "event_type", "beforeAuthenticationAsync")
```

**Metric created:**
```prometheus
fraud_switch_async_processor_kafka_consume_total{
  service="async-processor",
  region="us-ohio-1",
  topic="async.events",
  event_type="beforeAuthenticationAsync"
} 1
```

**This is a COUNTER** - it only goes up, never down.

---

## 📈 Prometheus Queries

### Consumption Rate (Messages per second)
```promql
rate(fraud_switch_tokenization_service_kafka_consume_total{topic="pan.queue"}[5m])
```

### Consumption Rate by Topic
```promql
sum by (topic) (
  rate(fraud_switch_async_processor_kafka_consume_total[5m])
)
```

### Consumption Rate by Event Type
```promql
sum by (event_type) (
  rate(fraud_switch_async_processor_kafka_consume_total{topic="async.events"}[5m])
)
```

### Consumption Rate by Partition
```promql
sum by (partition) (
  rate(fraud_switch_tokenization_service_kafka_consume_total{topic="pan.queue"}[5m])
)
```

### Total Messages Consumed (Last hour)
```promql
increase(fraud_switch_async_processor_kafka_consume_total[1h])
```

### Consumer Lag Detection (Publish vs Consume)
```promql
# Messages published but not yet consumed
rate(fraud_switch_fraud_router_kafka_publish_total{topic="fraud.events"}[5m])
-
rate(fraud_switch_async_processor_kafka_consume_total{topic="fraud.events"}[5m])
```

---

## ⚠️ Common Mistakes

### ❌ MISTAKE 1: Recording before processing
```java
// WRONG - Recording before processing
@KafkaListener(topics = "fraud.events")
public void consume(FraudEvent event) {
    kafkaMetrics.recordConsume("fraud.events");  // ❌ What if processing fails?
    processFraudEvent(event);
}

// CORRECT - Recording after processing
@KafkaListener(topics = "fraud.events")
public void consume(FraudEvent event) {
    processFraudEvent(event);
    kafkaMetrics.recordConsume("fraud.events");  // ✅ Only after success
}
```

### ❌ MISTAKE 2: Not catching exceptions
```java
// WRONG - No error handling
@KafkaListener(topics = "fraud.events")
public void consume(FraudEvent event) {
    processFraudEvent(event);  // Might throw!
    kafkaMetrics.recordConsume("fraud.events");  // ❌ Never reached on error
}

// CORRECT - Proper error handling
@KafkaListener(topics = "fraud.events")
public void consume(FraudEvent event) {
    try {
        processFraudEvent(event);
        kafkaMetrics.recordConsume("fraud.events");  // ✅ Only on success
    } catch (Exception e) {
        kafkaMetrics.recordConsumeError("fraud.events", e.getClass().getSimpleName());
        throw e;
    }
}
```

### ❌ MISTAKE 3: High cardinality labels
```java
// WRONG - Transaction ID is high cardinality!
kafkaMetrics.recordConsume(
    "fraud.events",
    "transaction_id", transactionId  // ❌ Millions of unique values!
);

// CORRECT - Use low cardinality labels
kafkaMetrics.recordConsume(
    "fraud.events",
    "event_type", eventType,  // ✅ ~6 event types
    "partition", partition    // ✅ ~10 partitions
);
```

### ❌ MISTAKE 4: Wrong topic name
```java
// WRONG - Topic name doesn't match actual topic
@KafkaListener(topics = "fraud.events")
public void consume(FraudEvent event) {
    processFraudEvent(event);
    kafkaMetrics.recordConsume("fraud-events");  // ❌ Using hyphen
}

// CORRECT - Use exact topic name
@KafkaListener(topics = "fraud.events")
public void consume(FraudEvent event) {
    processFraudEvent(event);
    kafkaMetrics.recordConsume("fraud.events");  // ✅ Matches actual topic
}
```

---

## 📝 Best Practices

### ✅ DO:

1. **Record only on success**
   ```java
   try {
       processFraudEvent(event);
       kafkaMetrics.recordConsume("fraud.events");  // ✅ After success
   } catch (Exception e) {
       // Handle error
   }
   ```

2. **Use exact topic names**
   ```java
   kafkaMetrics.recordConsume("fraud.events");  // ✅ Match actual topic
   ```

3. **Add business context labels**
   ```java
   kafkaMetrics.recordConsume(
       "async.events",
       "event_type", event.getEventType(),
       "partition", String.valueOf(partition)
   );
   ```

4. **Keep label cardinality low**
   - event_type: ~6 values ✅
   - partition: ~10 values ✅
   - consumer_group: ~1 value ✅
   - transaction_id: millions ❌

5. **Use with manual acknowledgment**
   ```java
   try {
       processFraudEvent(event);
       kafkaMetrics.recordConsume("fraud.events");
       acknowledgment.acknowledge();  // Ack after success
   } catch (Exception e) {
       // Don't ack on error
   }
   ```

---

### ❌ DON'T:

1. Don't record before processing
2. Don't use high cardinality labels
3. Don't forget error handling
4. Don't use wrong topic names
5. Don't record failed processing

---

## 🔄 Publish vs Consume Comparison

### recordPublish() (Method 1)
```java
// PUBLISH side
kafkaTemplate.send("fraud.events", event);
kafkaMetrics.recordPublish("fraud.events");
```
**Tracks:** Messages sent TO Kafka

### recordConsume() (Method 2)
```java
// CONSUME side
@KafkaListener(topics = "fraud.events")
public void consume(FraudEvent event) {
    processFraudEvent(event);
    kafkaMetrics.recordConsume("fraud.events");
}
```
**Tracks:** Messages read FROM Kafka

### Together for End-to-End Monitoring:
```promql
# Publish rate
rate(fraud_switch_fraud_router_kafka_publish_total{topic="fraud.events"}[5m])

# Consume rate
rate(fraud_switch_async_processor_kafka_consume_total{topic="fraud.events"}[5m])

# Lag (difference)
rate(fraud_switch_fraud_router_kafka_publish_total{topic="fraud.events"}[5m])
-
rate(fraud_switch_async_processor_kafka_consume_total{topic="fraud.events"}[5m])
```

---

## ✅ Quick Checklist

Before using recordConsume():

- [ ] Message successfully processed
- [ ] Using exact topic name
- [ ] Called AFTER successful processing
- [ ] Added meaningful labels
- [ ] Labels are low cardinality
- [ ] Wrapped in try-catch
- [ ] No PII/sensitive data in labels
- [ ] Manual ack (if used) called after metrics

---

## 🎯 Real-World Example: Complete Kafka Consumer

```java
@Service
@Slf4j
public class CompletePanTokenizationConsumer {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private TokenizationService tokenizationService;
    
    @Autowired
    private AuditService auditService;
    
    private static final String TOPIC = "pan.queue";
    
    /**
     * Consume PAN tokenization requests with proper metrics tracking
     */
    @KafkaListener(
        topics = TOPIC,
        groupId = "tokenization-service",
        concurrency = "3"
    )
    public void consumePanTokenizationRequest(
            @Payload PanTokenizationRequest request,
            @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Tokenize the PAN
            String token = tokenizationService.tokenize(
                request.getPan(),
                request.getTransactionId()
            );
            
            // Save to database
            tokenizationService.saveToken(
                request.getTransactionId(),
                token
            );
            
            // Audit
            auditService.auditTokenization(
                request.getTransactionId(),
                partition,
                offset
            );
            
            // ✅ SUCCESS - Record consumption metric
            long duration = System.currentTimeMillis() - startTime;
            kafkaMetrics.recordConsume(
                TOPIC,
                "partition", String.valueOf(partition),
                "gateway", request.getGateway(),
                "product", request.getProduct()
            );
            
            // Also record processing duration
            kafkaMetrics.recordConsumeDuration(TOPIC, duration);
            
            // Manual acknowledgment
            acknowledgment.acknowledge();
            
            log.info("Tokenized PAN: txn={}, partition={}, offset={}, duration={}ms",
                    request.getTransactionId(),
                    partition,
                    offset,
                    duration);
            
        } catch (TokenizationException e) {
            // ❌ TOKENIZATION ERROR - Record error (Method 4)
            kafkaMetrics.recordConsumeError(TOPIC, "TokenizationError");
            log.error("Tokenization failed: txn={}, partition={}, offset={}",
                     request.getTransactionId(),
                     partition,
                     offset,
                     e);
            // Don't acknowledge - message will be retried
            throw e;
            
        } catch (DatabaseException e) {
            // ❌ DATABASE ERROR - Record error
            kafkaMetrics.recordConsumeError(TOPIC, "DatabaseError");
            log.error("Database error: txn={}, partition={}, offset={}",
                     request.getTransactionId(),
                     partition,
                     offset,
                     e);
            throw e;
            
        } catch (Exception e) {
            // ❌ UNEXPECTED ERROR - Record error
            kafkaMetrics.recordConsumeError(TOPIC, "UnexpectedError");
            log.error("Unexpected error: txn={}, partition={}, offset={}",
                     request.getTransactionId(),
                     partition,
                     offset,
                     e);
            throw e;
        }
    }
}
```

---

## 🎓 Summary

**recordConsume()** tracks successful Kafka message consumption:

```java
kafkaMetrics.recordConsume(topic, labels...);
```

**Use for:**
- Successful message processing
- After business logic completes
- In `@KafkaListener` methods

**Creates:**
```prometheus
fraud_switch_{service}_kafka_consume_total{
  topic="...",
  event_type="...",
  partition="..."
} 1
```

**Combine with recordPublish() to monitor:**
- End-to-end message flow
- Consumer lag
- Processing rates

---

**Do you understand recordConsume()?** 

Type **"next"** for **Method 3: `recordPublishError()`** which tracks Kafka publish failures! 🚀
