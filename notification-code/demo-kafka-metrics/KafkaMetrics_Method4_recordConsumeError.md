# KafkaMetrics Method 4: recordConsumeError()

## 📋 Method Signature
```java
public void recordConsumeError(String topic, String errorType, String... labels)
```

## 🎯 Purpose
Records a **failed Kafka message consumption** event. Tracks when your service consumes a message from Kafka but processing fails.

---

## 📖 What is This For?

Message consumption can fail for many reasons:
- **Processing errors** - Business logic throws exception
- **Deserialization errors** - Message can't be deserialized
- **Database errors** - Failed to save processed data
- **External API failures** - Downstream service unavailable
- **Validation errors** - Invalid message format
- **Timeout errors** - Processing took too long

`recordConsumeError()` tracks these failures so you can:
- Monitor message processing failure rates
- Alert on consume errors
- Identify which topics have issues
- Track different error types
- Detect poison messages
- Measure retry success rates

---

## 📖 Parameters Explained

### 1. `topic` (String)
- **Which Kafka topic** you were consuming from
- Examples: "pan.queue", "async.events", "fraud.events"
- Must be the actual Kafka topic name

### 2. `errorType` (String)
- **What type of error occurred during processing**
- Examples: "DatabaseException", "ValidationException", "TimeoutException"
- Best practice: Use exception class name

### 3. `labels` (String... varargs)
- Optional additional labels (key-value pairs)
- Must be even number of strings
- Common labels: "event_type", "partition", "consumer_group"

---

## 💻 Basic Usage Examples

### Example 1: Simple Consume Error Handling

```java
@Service
public class FraudEventConsumer {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    private static final String TOPIC = "fraud.events";
    
    @KafkaListener(topics = TOPIC, groupId = "fraud-processor")
    public void consumeFraudEvent(FraudEvent event) {
        try {
            // Process the event
            processFraudEvent(event);
            
            // ✅ Success
            kafkaMetrics.recordConsume(TOPIC);
            
            log.info("Processed fraud event: txn={}", event.getTransactionId());
            
        } catch (Exception e) {
            // ❌ Error - Record consume failure
            kafkaMetrics.recordConsumeError(
                TOPIC,
                e.getClass().getSimpleName()
            );
            
            log.error("Failed to process fraud event: txn={}", 
                     event.getTransactionId(), 
                     e);
            throw e;
        }
    }
    
    private void processFraudEvent(FraudEvent event) {
        // Business logic that might throw
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_fraud_router_kafka_consume_errors_total{
  service="fraud-router",
  region="us-ohio-1",
  topic="fraud.events",
  error_type="DatabaseException"
} 1
```

---

### Example 2: Tokenization Service with Specific Errors

```java
@Service
@Slf4j
public class PanTokenizationConsumer {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private TokenizationService tokenizationService;
    
    private static final String TOPIC = "pan.queue";
    
    @KafkaListener(topics = TOPIC, groupId = "tokenization-service")
    public void consumePanTokenizationRequest(
            @Payload PanTokenizationRequest request,
            @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition) {
        
        try {
            // Tokenize the PAN
            String token = tokenizationService.tokenize(request.getPan());
            
            // Save to database
            tokenizationService.saveToken(request.getTransactionId(), token);
            
            // ✅ Success
            kafkaMetrics.recordConsume(
                TOPIC,
                "partition", String.valueOf(partition),
                "gateway", request.getGateway()
            );
            
            log.info("Tokenized PAN: txn={}, partition={}", 
                    request.getTransactionId(), 
                    partition);
            
        } catch (TokenizationException e) {
            // ❌ Tokenization failed
            kafkaMetrics.recordConsumeError(
                TOPIC,
                "TokenizationException",
                "partition", String.valueOf(partition),
                "gateway", request.getGateway()
            );
            
            log.error("Tokenization failed: txn={}, partition={}", 
                     request.getTransactionId(),
                     partition,
                     e);
            throw e;
            
        } catch (DatabaseException e) {
            // ❌ Database error
            kafkaMetrics.recordConsumeError(
                TOPIC,
                "DatabaseException",
                "partition", String.valueOf(partition)
            );
            
            log.error("Database error: txn={}, partition={}", 
                     request.getTransactionId(),
                     partition,
                     e);
            throw e;
            
        } catch (Exception e) {
            // ❌ Unexpected error
            kafkaMetrics.recordConsumeError(
                TOPIC,
                "UnexpectedError",
                "partition", String.valueOf(partition)
            );
            
            log.error("Unexpected error: txn={}, partition={}", 
                     request.getTransactionId(),
                     partition,
                     e);
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_tokenization_service_kafka_consume_errors_total{
  service="tokenization-service",
  topic="pan.queue",
  error_type="TokenizationException",
  partition="0",
  gateway="RAFT"
} 1
```

**Now you can query:** "How many tokenization errors per partition?"

---

### Example 3: Async Processor with Retry Logic

```java
@Service
@Slf4j
public class AsyncEventConsumer {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private AsyncProcessorService processorService;
    
    private static final String TOPIC = "async.events";
    private static final int MAX_RETRIES = 3;
    
    @KafkaListener(topics = TOPIC, groupId = "async-processor")
    public void consumeAsyncEvent(
            @Payload AsyncEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        
        int attempt = 0;
        Exception lastException = null;
        
        while (attempt < MAX_RETRIES) {
            attempt++;
            
            try {
                // Process async event
                processorService.processAsync(event);
                
                // ✅ Success
                kafkaMetrics.recordConsume(
                    TOPIC,
                    "event_type", event.getEventType(),
                    "partition", String.valueOf(partition),
                    "retry_attempt", String.valueOf(attempt)
                );
                
                // Acknowledge message
                acknowledgment.acknowledge();
                
                log.info("Processed async event: txn={}, type={}, attempt={}", 
                        event.getTransactionId(),
                        event.getEventType(),
                        attempt);
                return;
                
            } catch (TransientException e) {
                // ❌ Transient error - retry
                lastException = e;
                
                kafkaMetrics.recordConsumeError(
                    TOPIC,
                    "TransientException",
                    "event_type", event.getEventType(),
                    "partition", String.valueOf(partition),
                    "retry_attempt", String.valueOf(attempt)
                );
                
                log.warn("Transient error: txn={}, attempt={}/{}", 
                        event.getTransactionId(),
                        attempt,
                        MAX_RETRIES);
                
                if (attempt < MAX_RETRIES) {
                    sleep(100 * attempt);  // Exponential backoff
                }
                
            } catch (PermanentException e) {
                // ❌ Permanent error - don't retry
                kafkaMetrics.recordConsumeError(
                    TOPIC,
                    "PermanentException",
                    "event_type", event.getEventType(),
                    "partition", String.valueOf(partition),
                    "retry_attempt", String.valueOf(attempt)
                );
                
                log.error("Permanent error: txn={}, sending to DLQ", 
                         event.getTransactionId(),
                         e);
                
                // Send to DLQ and acknowledge
                sendToDLQ(event, e);
                acknowledgment.acknowledge();
                return;
            }
        }
        
        // All retries exhausted
        log.error("Max retries exhausted: txn={}, sending to DLQ", 
                 event.getTransactionId());
        sendToDLQ(event, lastException);
        acknowledgment.acknowledge();
    }
    
    private void sendToDLQ(AsyncEvent event, Exception error) {
        // Send to dead letter queue
    }
    
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

**Metrics created:**
```prometheus
# First attempt failed
fraud_switch_async_processor_kafka_consume_errors_total{
  topic="async.events",
  error_type="TransientException",
  event_type="beforeAuthenticationAsync",
  partition="1",
  retry_attempt="1"
} 1

# Second attempt failed
fraud_switch_async_processor_kafka_consume_errors_total{
  topic="async.events",
  error_type="TransientException",
  event_type="beforeAuthenticationAsync",
  partition="1",
  retry_attempt="2"
} 1

# Third attempt succeeded (tracked by recordConsume)
fraud_switch_async_processor_kafka_consume_total{
  topic="async.events",
  event_type="beforeAuthenticationAsync",
  partition="1",
  retry_attempt="3"
} 1
```

---

### Example 4: Issuer Data-Share with Circuit Breaker

```java
@Service
@Slf4j
public class IssuerDataShareConsumer {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private IssuerCallService issuerCallService;
    
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;
    
    private static final String TOPIC = "issuer.datashare.events";
    
    @KafkaListener(topics = TOPIC, groupId = "issuer-data-service")
    public void consumeIssuerDataShareEvent(IssuerDataShareEvent event) {
        
        try {
            // Call configured issuers
            List<String> issuers = event.getIssuers();
            List<IssuerCallResult> results = new ArrayList<>();
            
            for (String issuer : issuers) {
                CircuitBreaker circuitBreaker = circuitBreakerRegistry
                    .circuitBreaker(issuer);
                
                try {
                    // Call with circuit breaker
                    IssuerCallResult result = circuitBreaker.executeSupplier(
                        () -> issuerCallService.callIssuer(issuer, event)
                    );
                    results.add(result);
                    
                } catch (CallNotPermittedException e) {
                    // Circuit breaker open
                    kafkaMetrics.recordConsumeError(
                        TOPIC,
                        "CircuitBreakerOpen",
                        "issuer_name", issuer,
                        "event_type", event.getEventType()
                    );
                    
                    log.warn("Circuit breaker open: txn={}, issuer={}", 
                            event.getTransactionId(),
                            issuer);
                    
                    results.add(IssuerCallResult.circuitBreakerOpen(issuer));
                }
            }
            
            // Check if any issuer calls succeeded
            boolean anySuccess = results.stream()
                .anyMatch(IssuerCallResult::isSuccess);
            
            if (anySuccess) {
                // ✅ At least one issuer succeeded
                kafkaMetrics.recordConsume(
                    TOPIC,
                    "event_type", event.getEventType(),
                    "issuer_count", String.valueOf(issuers.size())
                );
            } else {
                // ❌ All issuers failed
                kafkaMetrics.recordConsumeError(
                    TOPIC,
                    "AllIssuersFailed",
                    "event_type", event.getEventType(),
                    "issuer_count", String.valueOf(issuers.size())
                );
            }
            
            log.info("Processed issuer data-share: txn={}, issuers={}, success={}", 
                    event.getTransactionId(),
                    issuers,
                    anySuccess);
            
        } catch (Exception e) {
            // ❌ Unexpected error
            kafkaMetrics.recordConsumeError(
                TOPIC,
                e.getClass().getSimpleName(),
                "event_type", event.getEventType()
            );
            
            log.error("Failed to process issuer data-share: txn={}", 
                     event.getTransactionId(),
                     e);
            throw e;
        }
    }
}
```

---

### Example 5: Deserialization Error Handling

```java
@Service
@Slf4j
public class FraudEventConsumerWithDeserializationHandling {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    private static final String TOPIC = "fraud.events";
    
    @KafkaListener(topics = TOPIC, groupId = "fraud-processor")
    public void consumeFraudEvent(ConsumerRecord<String, String> record) {
        
        try {
            // Manual deserialization to catch errors
            FraudEvent event = deserialize(record.value());
            
            // Process the event
            processFraudEvent(event);
            
            // ✅ Success
            kafkaMetrics.recordConsume(TOPIC);
            
        } catch (DeserializationException e) {
            // ❌ Deserialization error - poison message
            kafkaMetrics.recordConsumeError(
                TOPIC,
                "DeserializationException",
                "partition", String.valueOf(record.partition()),
                "offset", String.valueOf(record.offset())
            );
            
            log.error("Deserialization error: partition={}, offset={}, payload={}", 
                     record.partition(),
                     record.offset(),
                     record.value(),
                     e);
            
            // Send to poison message topic
            sendToPoisonMessageTopic(record, e);
            
            // Don't throw - acknowledge to skip poison message
            
        } catch (Exception e) {
            // ❌ Processing error
            kafkaMetrics.recordConsumeError(
                TOPIC,
                e.getClass().getSimpleName()
            );
            
            log.error("Processing error: partition={}, offset={}", 
                     record.partition(),
                     record.offset(),
                     e);
            throw e;
        }
    }
    
    private FraudEvent deserialize(String json) throws DeserializationException {
        // Custom deserialization logic
    }
    
    private void sendToPoisonMessageTopic(ConsumerRecord<String, String> record, Exception error) {
        // Send to poison message topic for manual review
    }
}
```

---

## 🎯 When to Use recordConsumeError()

### ✅ Use recordConsumeError() for:

1. **Any message processing failure**
   - Business logic errors
   - Validation failures
   - Database errors
   - External API failures
   - Timeout errors

2. **In catch blocks**
   - After processing fails
   - After deserialization fails
   - After retries exhausted

3. **Error monitoring**
   - Track error rates by type
   - Alert on consume failures
   - Identify poison messages
   - Monitor retry patterns

---

### ❌ DON'T use recordConsumeError() for:

1. **Successful processing**
   ```java
   // DON'T do this:
   processFraudEvent(event);
   kafkaMetrics.recordConsumeError(TOPIC, "Success");  // ❌ Wrong method!
   
   // DO this:
   processFraudEvent(event);
   kafkaMetrics.recordConsume(TOPIC);  // ✅ Use recordConsume()
   ```

2. **Publish errors**
   - Use `recordPublishError()` for publish failures (Method 3)

3. **Before processing attempt**
   - Only record AFTER the failure occurs

---

## 📊 What Metrics Are Created

When you call:
```java
kafkaMetrics.recordConsumeError("pan.queue", "TokenizationException", "partition", "0")
```

**Metric created:**
```prometheus
fraud_switch_tokenization_service_kafka_consume_errors_total{
  service="tokenization-service",
  region="us-ohio-1",
  topic="pan.queue",
  error_type="TokenizationException",
  partition="0"
} 1
```

**This is a COUNTER** - it only goes up, never down.

---

## 📈 Prometheus Queries

### Consume Error Rate (Errors per second)
```promql
rate(fraud_switch_tokenization_service_kafka_consume_errors_total{topic="pan.queue"}[5m])
```

### Consume Error Rate by Error Type
```promql
sum by (error_type) (
  rate(fraud_switch_tokenization_service_kafka_consume_errors_total{topic="pan.queue"}[5m])
)
```

### Consume Error Rate by Topic
```promql
sum by (topic) (
  rate(fraud_switch_async_processor_kafka_consume_errors_total[5m])
)
```

### Consume Error Percentage
```promql
(
  rate(fraud_switch_async_processor_kafka_consume_errors_total{topic="async.events"}[5m])
  /
  (
    rate(fraud_switch_async_processor_kafka_consume_total{topic="async.events"}[5m]) +
    rate(fraud_switch_async_processor_kafka_consume_errors_total{topic="async.events"}[5m])
  )
) * 100
```

### Top 5 Error Types
```promql
topk(5,
  sum by (error_type) (
    fraud_switch_tokenization_service_kafka_consume_errors_total
  )
)
```

### Error Rate by Partition (Detect hot partitions)
```promql
sum by (partition) (
  rate(fraud_switch_tokenization_service_kafka_consume_errors_total{topic="pan.queue"}[5m])
)
```

### Alert: High Consume Error Rate
```promql
# Alert if error rate > 5 per second
rate(fraud_switch_tokenization_service_kafka_consume_errors_total[5m]) > 5
```

### Alert: Consume Error Percentage > 10%
```promql
(
  rate(fraud_switch_async_processor_kafka_consume_errors_total[5m])
  /
  (
    rate(fraud_switch_async_processor_kafka_consume_total[5m]) +
    rate(fraud_switch_async_processor_kafka_consume_errors_total[5m])
  )
) * 100 > 10
```

### Poison Message Detection (Repeated errors on same offset)
```promql
# Same partition/offset erroring repeatedly
increase(fraud_switch_tokenization_service_kafka_consume_errors_total{
  partition="0",
  error_type="DeserializationException"
}[1h]) > 10
```

---

## ⚠️ Common Mistakes

### ❌ MISTAKE 1: Using generic error message
```java
// WRONG - Too generic
catch (Exception e) {
    kafkaMetrics.recordConsumeError(TOPIC, "Error");  // ❌ Not helpful
}

// CORRECT - Use exception class name
catch (Exception e) {
    kafkaMetrics.recordConsumeError(TOPIC, e.getClass().getSimpleName());  // ✅
}
```

### ❌ MISTAKE 2: Including error details in error type
```java
// WRONG - High cardinality!
catch (DatabaseException e) {
    kafkaMetrics.recordConsumeError(
        TOPIC,
        e.getMessage()  // ❌ "Connection refused to 10.0.1.5:5432"
    );
}

// CORRECT - Use exception class
catch (DatabaseException e) {
    kafkaMetrics.recordConsumeError(
        TOPIC,
        "DatabaseException"  // ✅ Limited set of error types
    );
}
```

### ❌ MISTAKE 3: Not recording error on failure
```java
// WRONG - No error metrics
@KafkaListener(topics = TOPIC)
public void consume(FraudEvent event) {
    try {
        processFraudEvent(event);
        kafkaMetrics.recordConsume(TOPIC);
    } catch (Exception e) {
        log.error("Failed", e);  // ❌ No metrics recorded!
        throw e;
    }
}

// CORRECT - Record error
@KafkaListener(topics = TOPIC)
public void consume(FraudEvent event) {
    try {
        processFraudEvent(event);
        kafkaMetrics.recordConsume(TOPIC);
    } catch (Exception e) {
        kafkaMetrics.recordConsumeError(TOPIC, e.getClass().getSimpleName());  // ✅
        throw e;
    }
}
```

### ❌ MISTAKE 4: Recording both success and error
```java
// WRONG - Recording both!
@KafkaListener(topics = TOPIC)
public void consume(FraudEvent event) {
    try {
        processFraudEvent(event);
        kafkaMetrics.recordConsume(TOPIC);  // ✅ Success recorded
    } catch (Exception e) {
        kafkaMetrics.recordConsume(TOPIC);  // ❌ Still recording success!
        kafkaMetrics.recordConsumeError(TOPIC, e.getClass().getSimpleName());
        throw e;
    }
}

// CORRECT - Only one or the other
@KafkaListener(topics = TOPIC)
public void consume(FraudEvent event) {
    try {
        processFraudEvent(event);
        kafkaMetrics.recordConsume(TOPIC);  // ✅ Only on success
    } catch (Exception e) {
        kafkaMetrics.recordConsumeError(TOPIC, e.getClass().getSimpleName());  // ✅ Only on error
        throw e;
    }
}
```

---

## 📝 Best Practices

### ✅ DO:

1. **Use exception class name**
   ```java
   kafkaMetrics.recordConsumeError(TOPIC, e.getClass().getSimpleName());
   ```

2. **Add context labels**
   ```java
   kafkaMetrics.recordConsumeError(
       TOPIC,
       "DatabaseException",
       "partition", String.valueOf(partition),
       "event_type", event.getEventType()
   );
   ```

3. **Track different error types**
   ```java
   catch (TokenizationException e) {
       kafkaMetrics.recordConsumeError(TOPIC, "TokenizationException");
   } catch (DatabaseException e) {
       kafkaMetrics.recordConsumeError(TOPIC, "DatabaseException");
   }
   ```

4. **Keep error types to limited set**
   - TokenizationException ✅
   - DatabaseException ✅
   - ValidationException ✅
   - TimeoutException ✅
   - "Failed to tokenize PAN for txn_123" ❌

5. **Track retry attempts**
   ```java
   kafkaMetrics.recordConsumeError(
       TOPIC,
       "TransientException",
       "retry_attempt", String.valueOf(attempt)
   );
   ```

---

### ❌ DON'T:

1. Don't use error messages (high cardinality)
2. Don't forget to record errors
3. Don't use generic error types
4. Don't record both success and error
5. Don't include sensitive data in labels
6. Don't include transaction IDs in error type

---

## ✅ Quick Checklist

Before using recordConsumeError():

- [ ] Message processing failed
- [ ] Using exception class name (not message)
- [ ] Called in catch block
- [ ] Added meaningful context labels
- [ ] Error types limited (< 20 types)
- [ ] Not recording success metric too
- [ ] No PII/sensitive data in labels
- [ ] Partition/offset info included if relevant

---

## 🎯 Real-World Example: Complete Consumer with Error Handling

```java
@Service
@Slf4j
public class CompletePanTokenizationConsumer {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private TokenizationService tokenizationService;
    
    @Autowired
    private KafkaTemplate<String, PoisonMessage> poisonMessageTemplate;
    
    private static final String TOPIC = "pan.queue";
    private static final String POISON_TOPIC = "pan.queue.poison";
    
    /**
     * Consume PAN tokenization requests with comprehensive error handling
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
            // Validate request
            validateRequest(request);
            
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
            
            // ✅ SUCCESS - Record consumption metric
            long duration = System.currentTimeMillis() - startTime;
            kafkaMetrics.recordConsume(
                TOPIC,
                "partition", String.valueOf(partition),
                "gateway", request.getGateway()
            );
            
            // Acknowledge message
            acknowledgment.acknowledge();
            
            log.info("Tokenized PAN: txn={}, partition={}, offset={}, duration={}ms",
                    request.getTransactionId(),
                    partition,
                    offset,
                    duration);
            
        } catch (ValidationException e) {
            // ❌ VALIDATION ERROR - Poison message, don't retry
            kafkaMetrics.recordConsumeError(
                TOPIC,
                "ValidationException",
                "partition", String.valueOf(partition)
            );
            
            log.error("Validation error: txn={}, partition={}, offset={}",
                     request.getTransactionId(),
                     partition,
                     offset,
                     e);
            
            // Send to poison message topic
            sendToPoisonMessageTopic(request, partition, offset, e);
            
            // Acknowledge to skip poison message
            acknowledgment.acknowledge();
            
        } catch (TokenizationException e) {
            // ❌ TOKENIZATION ERROR - Retryable
            kafkaMetrics.recordConsumeError(
                TOPIC,
                "TokenizationException",
                "partition", String.valueOf(partition),
                "gateway", request.getGateway()
            );
            
            log.error("Tokenization failed: txn={}, partition={}, offset={}",
                     request.getTransactionId(),
                     partition,
                     offset,
                     e);
            
            // Don't acknowledge - will be retried
            throw e;
            
        } catch (DatabaseException e) {
            // ❌ DATABASE ERROR - Retryable
            kafkaMetrics.recordConsumeError(
                TOPIC,
                "DatabaseException",
                "partition", String.valueOf(partition)
            );
            
            log.error("Database error: txn={}, partition={}, offset={}",
                     request.getTransactionId(),
                     partition,
                     offset,
                     e);
            
            // Don't acknowledge - will be retried
            throw e;
            
        } catch (Exception e) {
            // ❌ UNEXPECTED ERROR
            kafkaMetrics.recordConsumeError(
                TOPIC,
                "UnexpectedError",
                "partition", String.valueOf(partition)
            );
            
            log.error("Unexpected error: txn={}, partition={}, offset={}",
                     request.getTransactionId(),
                     partition,
                     offset,
                     e);
            
            // Don't acknowledge - will be retried
            throw e;
        }
    }
    
    private void validateRequest(PanTokenizationRequest request) throws ValidationException {
        if (request.getPan() == null || request.getPan().isEmpty()) {
            throw new ValidationException("PAN is required");
        }
        if (request.getTransactionId() == null) {
            throw new ValidationException("Transaction ID is required");
        }
    }
    
    private void sendToPoisonMessageTopic(
            PanTokenizationRequest request,
            int partition,
            long offset,
            Exception error) {
        
        try {
            PoisonMessage poisonMessage = PoisonMessage.builder()
                .originalRequest(request)
                .sourcePartition(partition)
                .sourceOffset(offset)
                .errorMessage(error.getMessage())
                .errorType(error.getClass().getSimpleName())
                .failedAt(Instant.now())
                .build();
            
            poisonMessageTemplate.send(
                POISON_TOPIC,
                request.getTransactionId(),
                poisonMessage
            );
            
            log.info("Sent to poison message topic: txn={}", 
                    request.getTransactionId());
            
        } catch (Exception e) {
            log.error("Failed to send to poison message topic: txn={}", 
                     request.getTransactionId(),
                     e);
        }
    }
}
```

---

## 🎓 Summary

**recordConsumeError()** tracks failed Kafka message consumption:

```java
kafkaMetrics.recordConsumeError(topic, errorType, labels...);
```

**Use for:**
- Message processing failures
- In catch blocks
- After retries exhausted
- Deserialization errors
- Validation failures

**Creates:**
```prometheus
fraud_switch_{service}_kafka_consume_errors_total{
  topic="...",
  error_type="DatabaseException",
  partition="..."
} 1
```

**Best Practices:**
- Use exception class name
- Add context labels (partition, event_type)
- Track different error types separately
- Keep error types limited
- Handle poison messages appropriately

---

## 🎉 CONGRATULATIONS!

You've completed all 4 KafkaMetrics methods!

**Next:** Type **"summary"** for a complete overview of all KafkaMetrics methods! 🚀
