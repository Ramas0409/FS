# KafkaMetrics Method 3: recordPublishError()

## 📋 Method Signature
```java
public void recordPublishError(String topic, String errorType, String... labels)
```

## 🎯 Purpose
Records a **failed Kafka message publish** event. Tracks when your service tries to publish to Kafka but the operation fails.

---

## 📖 What is This For?

Publishing to Kafka can fail for many reasons:
- **Timeout** - Broker didn't respond in time
- **Network issues** - Connection lost
- **Serialization errors** - Message can't be serialized
- **Broker unavailable** - Kafka cluster down
- **Topic not found** - Topic doesn't exist
- **Authorization failure** - No permission to write

`recordPublishError()` tracks these failures so you can:
- Monitor publish failure rates
- Alert on publish errors
- Identify which topics have issues
- Track different error types
- Detect Kafka cluster problems

---

## 📖 Parameters Explained

### 1. `topic` (String)
- **Which Kafka topic** you were trying to publish to
- Examples: "fraud.events", "async.events", "pan.queue"
- Must be the actual Kafka topic name (even if publish failed)

### 2. `errorType` (String)
- **What type of error occurred**
- Examples: "TimeoutException", "SerializationException", "NetworkException"
- Best practice: Use exception class name

### 3. `labels` (String... varargs)
- Optional additional labels (key-value pairs)
- Must be even number of strings
- Common labels: "event_type", "gateway", "retry_attempt"

---

## 💻 Basic Usage Examples

### Example 1: Simple Publish Error Handling

```java
@Service
public class FraudEventPublisher {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private KafkaTemplate<String, FraudEvent> kafkaTemplate;
    
    private static final String TOPIC = "fraud.events";
    
    public void publishFraudEvent(FraudEvent event) {
        try {
            // Attempt to publish
            kafkaTemplate.send(TOPIC, event.getTransactionId(), event);
            
            // ✅ Success
            kafkaMetrics.recordPublish(TOPIC);
            
        } catch (Exception e) {
            // ❌ Error - Record publish failure
            kafkaMetrics.recordPublishError(
                TOPIC,
                e.getClass().getSimpleName()
            );
            
            log.error("Failed to publish fraud event: txn={}", 
                     event.getTransactionId(), 
                     e);
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_fraud_router_kafka_publish_errors_total{
  service="fraud-router",
  region="us-ohio-1",
  topic="fraud.events",
  error_type="TimeoutException"
} 1
```

---

### Example 2: Publish Error with Event Context

```java
@Service
public class AsyncEventPublisher {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private KafkaTemplate<String, AsyncEvent> kafkaTemplate;
    
    private static final String TOPIC = "async.events";
    
    public void publishAsyncEvent(AsyncEvent event) {
        try {
            kafkaTemplate.send(TOPIC, event.getTransactionId(), event);
            
            // ✅ Success
            kafkaMetrics.recordPublish(
                TOPIC,
                "event_type", event.getEventType()
            );
            
        } catch (TimeoutException e) {
            // ❌ Timeout - Record with context
            kafkaMetrics.recordPublishError(
                TOPIC,
                "TimeoutException",
                "event_type", event.getEventType(),
                "gateway", event.getGateway()
            );
            
            log.error("Timeout publishing async event: txn={}, type={}", 
                     event.getTransactionId(),
                     event.getEventType(),
                     e);
            throw e;
            
        } catch (SerializationException e) {
            // ❌ Serialization error
            kafkaMetrics.recordPublishError(
                TOPIC,
                "SerializationException",
                "event_type", event.getEventType()
            );
            
            log.error("Serialization error: txn={}", 
                     event.getTransactionId(), 
                     e);
            throw e;
            
        } catch (Exception e) {
            // ❌ Other errors
            kafkaMetrics.recordPublishError(
                TOPIC,
                "UnexpectedError",
                "event_type", event.getEventType()
            );
            
            log.error("Unexpected publish error: txn={}", 
                     event.getTransactionId(), 
                     e);
            throw e;
        }
    }
}
```

**Metrics created:**
```prometheus
fraud_switch_async_processor_kafka_publish_errors_total{
  service="async-processor",
  topic="async.events",
  error_type="TimeoutException",
  event_type="beforeAuthenticationAsync",
  gateway="RAFT"
} 1
```

**Now you can query:** "How many timeout errors per event type?"

---

### Example 3: Publish with Retry and Error Tracking

```java
@Service
@Slf4j
public class FraudEventPublisherWithRetry {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private KafkaTemplate<String, FraudEvent> kafkaTemplate;
    
    private static final String TOPIC = "fraud.events";
    private static final int MAX_RETRIES = 3;
    
    public void publishFraudEventWithRetry(FraudEvent event) {
        int attempt = 0;
        Exception lastException = null;
        
        while (attempt < MAX_RETRIES) {
            attempt++;
            
            try {
                // Attempt to publish
                kafkaTemplate.send(TOPIC, event.getTransactionId(), event)
                    .get(5, TimeUnit.SECONDS);  // Wait for confirmation
                
                // ✅ Success
                kafkaMetrics.recordPublish(
                    TOPIC,
                    "event_type", event.getEventType(),
                    "retry_attempt", String.valueOf(attempt)
                );
                
                log.info("Published fraud event: txn={}, attempt={}", 
                        event.getTransactionId(), 
                        attempt);
                return;
                
            } catch (TimeoutException e) {
                lastException = e;
                
                // ❌ Timeout - Record with retry attempt
                kafkaMetrics.recordPublishError(
                    TOPIC,
                    "TimeoutException",
                    "event_type", event.getEventType(),
                    "retry_attempt", String.valueOf(attempt)
                );
                
                log.warn("Publish timeout: txn={}, attempt={}/{}", 
                        event.getTransactionId(), 
                        attempt, 
                        MAX_RETRIES);
                
                if (attempt < MAX_RETRIES) {
                    // Wait before retry
                    sleep(100 * attempt);  // Exponential backoff
                }
                
            } catch (Exception e) {
                lastException = e;
                
                // ❌ Non-retryable error
                kafkaMetrics.recordPublishError(
                    TOPIC,
                    e.getClass().getSimpleName(),
                    "event_type", event.getEventType(),
                    "retry_attempt", String.valueOf(attempt)
                );
                
                log.error("Non-retryable publish error: txn={}, attempt={}", 
                         event.getTransactionId(), 
                         attempt, 
                         e);
                throw e;
            }
        }
        
        // All retries exhausted
        log.error("Max retries exhausted: txn={}, attempts={}", 
                 event.getTransactionId(), 
                 MAX_RETRIES);
        throw new PublishException("Failed after " + MAX_RETRIES + " attempts", lastException);
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
fraud_switch_fraud_router_kafka_publish_errors_total{
  topic="fraud.events",
  error_type="TimeoutException",
  event_type="beforeAuthenticationSync",
  retry_attempt="1"
} 1

# Second attempt failed
fraud_switch_fraud_router_kafka_publish_errors_total{
  topic="fraud.events",
  error_type="TimeoutException",
  event_type="beforeAuthenticationSync",
  retry_attempt="2"
} 1

# Third attempt succeeded (tracked by recordPublish)
fraud_switch_fraud_router_kafka_publish_total{
  topic="fraud.events",
  event_type="beforeAuthenticationSync",
  retry_attempt="3"
} 1
```

---

### Example 4: Async Publish with Callback Error Handling

```java
@Service
@Slf4j
public class AsyncPublisherWithCallbacks {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private KafkaTemplate<String, FraudEvent> kafkaTemplate;
    
    private static final String TOPIC = "fraud.events";
    
    public void publishAsync(FraudEvent event) {
        
        kafkaTemplate.send(TOPIC, event.getTransactionId(), event)
            .addCallback(
                // SUCCESS callback
                success -> {
                    // ✅ Record success
                    kafkaMetrics.recordPublish(
                        TOPIC,
                        "event_type", event.getEventType()
                    );
                    
                    log.info("Published: txn={}, partition={}, offset={}",
                            event.getTransactionId(),
                            success.getRecordMetadata().partition(),
                            success.getRecordMetadata().offset());
                },
                
                // FAILURE callback
                failure -> {
                    // ❌ Record error
                    kafkaMetrics.recordPublishError(
                        TOPIC,
                        failure.getClass().getSimpleName(),
                        "event_type", event.getEventType()
                    );
                    
                    log.error("Publish failed: txn={}", 
                             event.getTransactionId(), 
                             failure);
                }
            );
    }
}
```

---

### Example 5: Dead Letter Queue (DLQ) Pattern

```java
@Service
@Slf4j
public class PublisherWithDLQ {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private KafkaTemplate<String, FraudEvent> kafkaTemplate;
    
    @Autowired
    private KafkaTemplate<String, FailedEvent> dlqKafkaTemplate;
    
    private static final String TOPIC = "fraud.events";
    private static final String DLQ_TOPIC = "fraud.events.dlq";
    
    public void publishWithDLQ(FraudEvent event) {
        try {
            // Try to publish to main topic
            kafkaTemplate.send(TOPIC, event.getTransactionId(), event)
                .get(5, TimeUnit.SECONDS);
            
            // ✅ Success
            kafkaMetrics.recordPublish(TOPIC);
            
        } catch (Exception e) {
            // ❌ Failed - Record error
            kafkaMetrics.recordPublishError(
                TOPIC,
                e.getClass().getSimpleName(),
                "event_type", event.getEventType()
            );
            
            log.error("Failed to publish to main topic: txn={}", 
                     event.getTransactionId(), 
                     e);
            
            // Send to DLQ
            try {
                FailedEvent failedEvent = FailedEvent.builder()
                    .originalEvent(event)
                    .error(e.getMessage())
                    .failedAt(Instant.now())
                    .build();
                
                dlqKafkaTemplate.send(DLQ_TOPIC, event.getTransactionId(), failedEvent)
                    .get(5, TimeUnit.SECONDS);
                
                // ✅ DLQ publish succeeded
                kafkaMetrics.recordPublish(
                    DLQ_TOPIC,
                    "source_topic", TOPIC,
                    "error_type", e.getClass().getSimpleName()
                );
                
                log.info("Sent to DLQ: txn={}", event.getTransactionId());
                
            } catch (Exception dlqError) {
                // ❌ DLQ also failed!
                kafkaMetrics.recordPublishError(
                    DLQ_TOPIC,
                    dlqError.getClass().getSimpleName(),
                    "source_topic", TOPIC
                );
                
                log.error("Failed to publish to DLQ: txn={}", 
                         event.getTransactionId(), 
                         dlqError);
                
                // Critical - both main and DLQ failed!
                throw new CriticalPublishException("Both main and DLQ publish failed", dlqError);
            }
        }
    }
}
```

**Metrics created:**
```prometheus
# Main topic failed
fraud_switch_fraud_router_kafka_publish_errors_total{
  topic="fraud.events",
  error_type="TimeoutException",
  event_type="beforeAuthenticationSync"
} 1

# DLQ succeeded
fraud_switch_fraud_router_kafka_publish_total{
  topic="fraud.events.dlq",
  source_topic="fraud.events",
  error_type="TimeoutException"
} 1
```

---

## 🎯 When to Use recordPublishError()

### ✅ Use recordPublishError() for:

1. **Any Kafka publish failure**
   - Timeout exceptions
   - Serialization errors
   - Network failures
   - Broker unavailable
   - Authorization failures

2. **In catch blocks**
   - After `kafkaTemplate.send()` fails
   - In failure callbacks
   - After retry exhaustion

3. **Error monitoring**
   - Track error rates by type
   - Alert on publish failures
   - Identify problematic topics

---

### ❌ DON'T use recordPublishError() for:

1. **Successful publishes**
   ```java
   // DON'T do this:
   kafkaTemplate.send(TOPIC, event);
   kafkaMetrics.recordPublishError(TOPIC, "Success");  // ❌ Wrong method!
   
   // DO this:
   kafkaTemplate.send(TOPIC, event);
   kafkaMetrics.recordPublish(TOPIC);  // ✅ Use recordPublish()
   ```

2. **Consumption errors**
   - Use `recordConsumeError()` for consumption failures (Method 4)

3. **Before the publish attempt**
   - Only record AFTER the failure occurs

---

## 📊 What Metrics Are Created

When you call:
```java
kafkaMetrics.recordPublishError("fraud.events", "TimeoutException", "event_type", "beforeAuthenticationSync")
```

**Metric created:**
```prometheus
fraud_switch_fraud_router_kafka_publish_errors_total{
  service="fraud-router",
  region="us-ohio-1",
  topic="fraud.events",
  error_type="TimeoutException",
  event_type="beforeAuthenticationSync"
} 1
```

**This is a COUNTER** - it only goes up, never down.

---

## 📈 Prometheus Queries

### Publish Error Rate (Errors per second)
```promql
rate(fraud_switch_fraud_router_kafka_publish_errors_total{topic="fraud.events"}[5m])
```

### Publish Error Rate by Error Type
```promql
sum by (error_type) (
  rate(fraud_switch_fraud_router_kafka_publish_errors_total{topic="fraud.events"}[5m])
)
```

### Publish Error Rate by Topic
```promql
sum by (topic) (
  rate(fraud_switch_fraud_router_kafka_publish_errors_total[5m])
)
```

### Publish Error Percentage
```promql
(
  rate(fraud_switch_fraud_router_kafka_publish_errors_total{topic="fraud.events"}[5m])
  /
  (
    rate(fraud_switch_fraud_router_kafka_publish_total{topic="fraud.events"}[5m]) +
    rate(fraud_switch_fraud_router_kafka_publish_errors_total{topic="fraud.events"}[5m])
  )
) * 100
```

### Top 5 Error Types
```promql
topk(5,
  sum by (error_type) (
    fraud_switch_fraud_router_kafka_publish_errors_total
  )
)
```

### Alert: High Publish Error Rate
```promql
# Alert if error rate > 10 per second
rate(fraud_switch_fraud_router_kafka_publish_errors_total[5m]) > 10
```

### Alert: Publish Error Percentage > 5%
```promql
(
  rate(fraud_switch_fraud_router_kafka_publish_errors_total[5m])
  /
  (
    rate(fraud_switch_fraud_router_kafka_publish_total[5m]) +
    rate(fraud_switch_fraud_router_kafka_publish_errors_total[5m])
  )
) * 100 > 5
```

---

## ⚠️ Common Mistakes

### ❌ MISTAKE 1: Using generic error message
```java
// WRONG - Too generic
catch (Exception e) {
    kafkaMetrics.recordPublishError(TOPIC, "Error");  // ❌ Not helpful
}

// CORRECT - Use exception class name
catch (Exception e) {
    kafkaMetrics.recordPublishError(TOPIC, e.getClass().getSimpleName());  // ✅
}
```

### ❌ MISTAKE 2: Including error details in error type
```java
// WRONG - High cardinality!
catch (TimeoutException e) {
    kafkaMetrics.recordPublishError(
        TOPIC,
        e.getMessage()  // ❌ "Timeout after 5000ms for txn_123"
    );
}

// CORRECT - Use exception class
catch (TimeoutException e) {
    kafkaMetrics.recordPublishError(
        TOPIC,
        "TimeoutException"  // ✅ Limited set of error types
    );
}
```

### ❌ MISTAKE 3: Not recording error on failure
```java
// WRONG - No error metrics
try {
    kafkaTemplate.send(TOPIC, event);
    kafkaMetrics.recordPublish(TOPIC);
} catch (Exception e) {
    log.error("Failed", e);  // ❌ No metrics recorded!
    throw e;
}

// CORRECT - Record error
try {
    kafkaTemplate.send(TOPIC, event);
    kafkaMetrics.recordPublish(TOPIC);
} catch (Exception e) {
    kafkaMetrics.recordPublishError(TOPIC, e.getClass().getSimpleName());  // ✅
    throw e;
}
```

### ❌ MISTAKE 4: Recording both success and error
```java
// WRONG - Recording both!
try {
    kafkaTemplate.send(TOPIC, event);
    kafkaMetrics.recordPublish(TOPIC);  // ✅ Success recorded
} catch (Exception e) {
    kafkaMetrics.recordPublish(TOPIC);  // ❌ Still recording success!
    kafkaMetrics.recordPublishError(TOPIC, e.getClass().getSimpleName());
    throw e;
}

// CORRECT - Only one or the other
try {
    kafkaTemplate.send(TOPIC, event);
    kafkaMetrics.recordPublish(TOPIC);  // ✅ Only on success
} catch (Exception e) {
    kafkaMetrics.recordPublishError(TOPIC, e.getClass().getSimpleName());  // ✅ Only on error
    throw e;
}
```

---

## 📝 Best Practices

### ✅ DO:

1. **Use exception class name**
   ```java
   kafkaMetrics.recordPublishError(TOPIC, e.getClass().getSimpleName());
   ```

2. **Add context labels**
   ```java
   kafkaMetrics.recordPublishError(
       TOPIC,
       "TimeoutException",
       "event_type", event.getEventType(),
       "retry_attempt", String.valueOf(attempt)
   );
   ```

3. **Track different error types**
   ```java
   catch (TimeoutException e) {
       kafkaMetrics.recordPublishError(TOPIC, "TimeoutException");
   } catch (SerializationException e) {
       kafkaMetrics.recordPublishError(TOPIC, "SerializationException");
   }
   ```

4. **Keep error types to limited set**
   - TimeoutException ✅
   - SerializationException ✅
   - NetworkException ✅
   - BrokerUnavailableException ✅
   - "Timeout after 5000ms" ❌

---

### ❌ DON'T:

1. Don't use error messages (high cardinality)
2. Don't forget to record errors
3. Don't use generic error types
4. Don't record both success and error
5. Don't include sensitive data in labels

---

## ✅ Quick Checklist

Before using recordPublishError():

- [ ] Kafka publish failed
- [ ] Using exception class name (not message)
- [ ] Called in catch block
- [ ] Added meaningful context labels
- [ ] Error types limited (< 20 types)
- [ ] Not recording success metric too
- [ ] No PII/sensitive data in labels

---

## 🎯 Real-World Example: Complete Publish with Error Handling

```java
@Service
@Slf4j
public class CompleteFraudEventPublisher {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private KafkaTemplate<String, FraudEvent> kafkaTemplate;
    
    private static final String TOPIC = "fraud.events";
    private static final int TIMEOUT_MS = 5000;
    
    /**
     * Publish fraud event with comprehensive error handling
     */
    public void publishFraudEvent(FraudEvent event) {
        try {
            // Attempt to publish
            SendResult<String, FraudEvent> result = kafkaTemplate.send(
                TOPIC,
                event.getTransactionId(),
                event
            ).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            
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
            // ❌ TIMEOUT
            kafkaMetrics.recordPublishError(
                TOPIC,
                "TimeoutException",
                "event_type", event.getEventType(),
                "gateway", event.getGateway()
            );
            
            log.error("Publish timeout: txn={}, timeout={}ms",
                     event.getTransactionId(),
                     TIMEOUT_MS,
                     e);
            throw new PublishException("Kafka publish timeout", e);
            
        } catch (SerializationException e) {
            // ❌ SERIALIZATION ERROR
            kafkaMetrics.recordPublishError(
                TOPIC,
                "SerializationException",
                "event_type", event.getEventType()
            );
            
            log.error("Serialization error: txn={}, event_type={}",
                     event.getTransactionId(),
                     event.getEventType(),
                     e);
            throw new PublishException("Failed to serialize event", e);
            
        } catch (ExecutionException e) {
            // ❌ EXECUTION ERROR (unwrap cause)
            Throwable cause = e.getCause();
            String errorType = cause != null ? 
                cause.getClass().getSimpleName() : 
                "ExecutionException";
            
            kafkaMetrics.recordPublishError(
                TOPIC,
                errorType,
                "event_type", event.getEventType()
            );
            
            log.error("Execution error: txn={}, cause={}",
                     event.getTransactionId(),
                     errorType,
                     e);
            throw new PublishException("Kafka publish failed", e);
            
        } catch (InterruptedException e) {
            // ❌ INTERRUPTED
            kafkaMetrics.recordPublishError(
                TOPIC,
                "InterruptedException",
                "event_type", event.getEventType()
            );
            
            Thread.currentThread().interrupt();
            log.error("Publish interrupted: txn={}",
                     event.getTransactionId(),
                     e);
            throw new PublishException("Kafka publish interrupted", e);
            
        } catch (Exception e) {
            // ❌ UNEXPECTED ERROR
            kafkaMetrics.recordPublishError(
                TOPIC,
                "UnexpectedError",
                "event_type", event.getEventType()
            );
            
            log.error("Unexpected publish error: txn={}",
                     event.getTransactionId(),
                     e);
            throw new PublishException("Unexpected Kafka error", e);
        }
    }
}
```

---

## 🎓 Summary

**recordPublishError()** tracks failed Kafka message publishes:

```java
kafkaMetrics.recordPublishError(topic, errorType, labels...);
```

**Use for:**
- Kafka publish failures
- In catch blocks
- After retries exhausted
- In failure callbacks

**Creates:**
```prometheus
fraud_switch_{service}_kafka_publish_errors_total{
  topic="...",
  error_type="TimeoutException",
  event_type="..."
} 1
```

**Best Practices:**
- Use exception class name
- Add context labels
- Track different error types
- Keep error types limited

---

**Do you understand recordPublishError()?** 

Type **"next"** for **Method 4: `recordConsumeError()`** which tracks Kafka consume failures! 🚀
