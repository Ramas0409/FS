# 🎯 Fraud Switch Metrics Library - Complete Summary

**Based on Actual Code Implementation (v1.0.0)**

---

## 📚 Table of Contents

1. [Library Overview](#library-overview)
2. [RequestMetrics - Complete Guide](#requestmetrics)
3. [KafkaMetrics - Complete Guide](#kafkametrics)
4. [ComponentMetrics - Complete Guide](#componentmetrics)
5. [Metric Naming Conventions](#metric-naming-conventions)
6. [Common Tags & Labels](#common-tags--labels)
7. [Implementation Examples](#implementation-examples)
8. [Best Practices](#best-practices)

---

## Library Overview

### 📦 Maven Dependency
```xml
<dependency>
    <groupId>com.fraudswitch</groupId>
    <artifactId>fraud-switch-metrics-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 🎯 Core Components

| Component | Purpose | Methods |
|-----------|---------|---------|
| **RequestMetrics** | RED pattern (Rate, Errors, Duration) | 5 methods |
| **KafkaMetrics** | Kafka publish/consume tracking | 6 methods |
| **ComponentMetrics** | Infrastructure components | 15+ methods |
| **CardinalityEnforcer** | Prevents cardinality explosions | Automatic |

### 🏗️ Architecture

```
Your Service
    ↓
FraudSwitchMetricsAutoConfiguration (Spring Boot Auto-Config)
    ↓
    ├── RequestMetrics
    ├── KafkaMetrics
    └── ComponentMetrics
          ↓
    MeterRegistry (Micrometer)
          ↓
    Prometheus /actuator/prometheus
```

---

## RequestMetrics

### 📖 Purpose
Implements **RED metrics pattern** (Rate, Errors, Duration) for tracking HTTP API requests.

### 🎯 Methods

#### Method 1: `recordRequest(long durationMs, String... labels)`
Records a **successful** request with its duration.

**Usage:**
```java
@RestController
public class FraudController {
    
    @Autowired
    private RequestMetrics requestMetrics;
    
    @PostMapping("/check-fraud")
    public FraudResponse checkFraud(@RequestBody FraudRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            FraudResponse response = processFraud(request);
            
            // ✅ Record successful request
            long duration = System.currentTimeMillis() - startTime;
            requestMetrics.recordRequest(
                duration,
                MetricLabels.Request.EVENT_TYPE, request.getEventType(),
                MetricLabels.Request.GATEWAY, request.getGateway(),
                MetricLabels.Request.PRODUCT, request.getProduct()
            );
            
            return response;
        } catch (Exception e) {
            // Handle error
            throw e;
        }
    }
}
```

**Metrics Created:**
```prometheus
fraud_switch_fraud_router_requests_total{
  service="fraud-router",
  region="us-ohio-1",
  environment="production",
  event_type="beforeAuthenticationSync",
  gateway="RAFT",
  product="fraudsight"
} 1000

fraud_switch_fraud_router_request_duration_ms{
  event_type="beforeAuthenticationSync",
  gateway="RAFT",
  product="fraudsight",
  quantile="0.99"
} 87
```

---

#### Method 2: `recordError(long durationMs, String errorType, String... labels)`
Records a **failed** request with error type and duration.

**Usage:**
```java
@RestController
public class FraudController {
    
    @Autowired
    private RequestMetrics requestMetrics;
    
    @PostMapping("/check-fraud")
    public FraudResponse checkFraud(@RequestBody FraudRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            return processFraud(request);
        } catch (TimeoutException e) {
            // ❌ Record timeout error
            long duration = System.currentTimeMillis() - startTime;
            requestMetrics.recordError(
                duration,
                "TimeoutException",
                MetricLabels.Request.EVENT_TYPE, request.getEventType(),
                MetricLabels.Request.GATEWAY, request.getGateway()
            );
            throw e;
        } catch (ValidationException e) {
            // ❌ Record validation error
            long duration = System.currentTimeMillis() - startTime;
            requestMetrics.recordError(
                duration,
                "ValidationException",
                MetricLabels.Request.EVENT_TYPE, request.getEventType()
            );
            throw e;
        }
    }
}
```

**Metrics Created:**
```prometheus
fraud_switch_fraud_router_errors_total{
  service="fraud-router",
  region="us-ohio-1",
  error_type="TimeoutException",
  event_type="beforeAuthenticationSync",
  gateway="RAFT"
} 5
```

---

#### Method 3: `recordWithOutcome(long durationMs, String outcome, String... labels)`
Records request with explicit outcome (success/failure/timeout).

**Usage:**
```java
long startTime = System.currentTimeMillis();
String outcome = "success";

try {
    processFraud(request);
} catch (TimeoutException e) {
    outcome = "timeout";
} catch (Exception e) {
    outcome = "failure";
} finally {
    long duration = System.currentTimeMillis() - startTime;
    requestMetrics.recordWithOutcome(
        duration,
        outcome,
        MetricLabels.Request.EVENT_TYPE, request.getEventType()
    );
}
```

---

#### Method 4: `startTimer()` & `getRequestTimer()`
Manual timer management for complex scenarios.

**Usage:**
```java
Timer.Sample sample = requestMetrics.startTimer();

try {
    processFraud(request);
    long duration = sample.stop(requestMetrics.getRequestTimer());
    requestMetrics.recordRequest(duration, ...);
} catch (Exception e) {
    long duration = sample.stop(requestMetrics.getRequestTimer());
    requestMetrics.recordError(duration, e.getClass().getSimpleName(), ...);
}
```

---

#### Method 5: `recordThroughput(double rps, String... labels)`
Records current throughput (requests per second).

**Usage:**
```java
// In a scheduled task
@Scheduled(fixedRate = 5000)
public void reportThroughput() {
    double currentRps = calculateRequestsPerSecond();
    requestMetrics.recordThroughput(currentRps);
}
```

**Metrics Created:**
```prometheus
fraud_switch_fraud_router_throughput_rps{
  service="fraud-router",
  region="us-ohio-1"
} 287.5
```

---

### 📊 RequestMetrics - All Metrics

| Metric Name | Type | Description |
|-------------|------|-------------|
| `{prefix}.requests_total` | Counter | Total requests (success + errors) |
| `{prefix}.errors_total` | Counter | Total errors by error_type |
| `{prefix}.request_duration_ms` | Timer | Request latency with percentiles |
| `{prefix}.throughput_rps` | Gauge | Current throughput |

---

## KafkaMetrics

### 📖 Purpose
Tracks Kafka message publishing, consumption, lag, and batch processing.

### 🎯 Methods

#### Method 1: `recordPublish(String topic, int partition, long durationMs)`
Records a **successful** Kafka message publish.

**Usage:**
```java
@Service
public class FraudEventPublisher {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private KafkaTemplate<String, FraudEvent> kafkaTemplate;
    
    public void publishFraudEvent(FraudEvent event) {
        long startTime = System.currentTimeMillis();
        
        try {
            // Publish to Kafka
            SendResult<String, FraudEvent> result = kafkaTemplate.send(
                MetricLabels.Kafka.TOPIC_ASYNC_EVENTS,
                event.getTransactionId(),
                event
            ).get();
            
            // ✅ Record successful publish
            long duration = System.currentTimeMillis() - startTime;
            int partition = result.getRecordMetadata().partition();
            
            kafkaMetrics.recordPublish(
                MetricLabels.Kafka.TOPIC_ASYNC_EVENTS,
                partition,
                duration
            );
            
        } catch (Exception e) {
            // Handle error
            throw e;
        }
    }
}
```

**Metrics Created:**
```prometheus
fraud_switch_fraud_router_kafka_publish_total{
  service="fraud-router",
  topic="async.events",
  partition="0",
  operation="publish",
  status="success"
} 1000

fraud_switch_fraud_router_kafka_publish_duration_ms{
  topic="async.events",
  partition="0",
  quantile="0.99"
} 15
```

---

#### Method 2: `recordPublish(String topic, int partition, long durationMs, boolean success, String errorType)`
Records a Kafka publish with **explicit success/error status**.

**Usage:**
```java
long startTime = System.currentTimeMillis();

try {
    SendResult result = kafkaTemplate.send(topic, event).get();
    long duration = System.currentTimeMillis() - startTime;
    
    // ✅ Success
    kafkaMetrics.recordPublish(topic, result.getRecordMetadata().partition(), 
                               duration, true, null);
    
} catch (TimeoutException e) {
    long duration = System.currentTimeMillis() - startTime;
    
    // ❌ Error
    kafkaMetrics.recordPublish(topic, 0, duration, false, "TimeoutException");
    throw e;
}
```

**Metrics Created (on error):**
```prometheus
fraud_switch_fraud_router_kafka_publish_errors_total{
  service="fraud-router",
  topic="async.events",
  partition="0",
  error_type="TimeoutException"
} 5
```

---

#### Method 3: `recordConsume(String topic, int partition, String consumerGroup, long durationMs)`
Records a **successful** Kafka message consumption.

**Usage:**
```java
@Service
public class AsyncEventConsumer {
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @KafkaListener(
        topics = MetricLabels.Kafka.TOPIC_ASYNC_EVENTS,
        groupId = "async-processor"
    )
    public void consumeAsyncEvent(
            @Payload AsyncEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition) {
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Process message
            processEvent(event);
            
            // ✅ Record successful consumption
            long duration = System.currentTimeMillis() - startTime;
            kafkaMetrics.recordConsume(
                MetricLabels.Kafka.TOPIC_ASYNC_EVENTS,
                partition,
                "async-processor",
                duration
            );
            
        } catch (Exception e) {
            // Handle error
            throw e;
        }
    }
}
```

**Metrics Created:**
```prometheus
fraud_switch_async_processor_kafka_consumed_total{
  service="async-processor",
  topic="async.events",
  partition="0",
  consumer_group="async-processor",
  operation="consume",
  status="success"
} 950

fraud_switch_async_processor_kafka_consumption_duration_ms{
  topic="async.events",
  partition="0",
  consumer_group="async-processor",
  quantile="0.99"
} 125
```

---

#### Method 4: `recordConsume(String topic, int partition, String consumerGroup, long durationMs, boolean success, String errorType)`
Records Kafka consumption with **explicit success/error status**.

**Usage:**
```java
@KafkaListener(topics = "async.events", groupId = "async-processor")
public void consumeAsyncEvent(@Payload AsyncEvent event,
                              @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition) {
    long startTime = System.currentTimeMillis();
    
    try {
        processEvent(event);
        long duration = System.currentTimeMillis() - startTime;
        
        // ✅ Success
        kafkaMetrics.recordConsume(
            "async.events", partition, "async-processor", 
            duration, true, null
        );
        
    } catch (DatabaseException e) {
        long duration = System.currentTimeMillis() - startTime;
        
        // ❌ Error
        kafkaMetrics.recordConsume(
            "async.events", partition, "async-processor",
            duration, false, "DatabaseException"
        );
        throw e;
    }
}
```

**Metrics Created (on error):**
```prometheus
fraud_switch_async_processor_kafka_consumption_errors_total{
  service="async-processor",
  topic="async.events",
  partition="0",
  consumer_group="async-processor",
  error_type="DatabaseException"
} 3
```

---

#### Method 5: `recordConsumerLag(String topic, int partition, String consumerGroup, long lag)`
Records consumer lag (messages behind).

**Usage:**
```java
@Scheduled(fixedRate = 10000)  // Every 10 seconds
public void reportConsumerLag() {
    // Get lag from Kafka Admin API or metrics
    long lag = getConsumerLag("async.events", 0, "async-processor");
    
    kafkaMetrics.recordConsumerLag(
        "async.events",
        0,
        "async-processor",
        lag
    );
}
```

**Metrics Created:**
```prometheus
fraud_switch_async_processor_kafka_consumption_lag{
  service="async-processor",
  topic="async.events",
  partition="0",
  consumer_group="async-processor"
} 127  # 127 messages behind
```

---

#### Method 6: `recordBatchProcessing(String topic, int batchSize, long durationMs)`
Records batch processing metrics.

**Usage:**
```java
@KafkaListener(topics = "async.events", batch = "true")
public void consumeBatch(List<AsyncEvent> events) {
    long startTime = System.currentTimeMillis();
    
    try {
        // Process batch
        processBatch(events);
        
        long duration = System.currentTimeMillis() - startTime;
        kafkaMetrics.recordBatchProcessing(
            "async.events",
            events.size(),
            duration
        );
        
    } catch (Exception e) {
        // Handle error
        throw e;
    }
}
```

**Metrics Created:**
```prometheus
fraud_switch_async_processor_kafka_batch_processed_total{
  service="async-processor",
  topic="async.events"
} 100

fraud_switch_async_processor_kafka_batch_size{
  topic="async.events",
  quantile="0.99"
} 50

fraud_switch_async_processor_kafka_batch_duration_ms{
  topic="async.events",
  quantile="0.99"
} 500
```

---

#### Method 7: `recordMessageSize(String topic, long sizeBytes, String operation)`
Records message size in bytes.

**Usage:**
```java
// When publishing
byte[] messageBytes = serializeEvent(event);
kafkaTemplate.send(topic, event);

kafkaMetrics.recordMessageSize(
    topic,
    messageBytes.length,
    MetricLabels.Kafka.OP_PUBLISH
);
```

**Metrics Created:**
```prometheus
fraud_switch_fraud_router_kafka_message_size_bytes{
  service="fraud-router",
  topic="async.events",
  operation="publish",
  quantile="0.99"
} 2048  # 2KB average
```

---

### 📊 KafkaMetrics - All Metrics

| Metric Name | Type | Description |
|-------------|------|-------------|
| `{prefix}.kafka_publish_total` | Counter | Messages published |
| `{prefix}.kafka_publish_duration_ms` | Timer | Publish latency |
| `{prefix}.kafka_publish_errors_total` | Counter | Publish errors |
| `{prefix}.kafka_consumed_total` | Counter | Messages consumed |
| `{prefix}.kafka_consumption_duration_ms` | Timer | Processing latency |
| `{prefix}.kafka_consumption_errors_total` | Counter | Consumption errors |
| `{prefix}.kafka_consumption_lag` | Gauge | Consumer lag |
| `{prefix}.kafka_batch_processed_total` | Counter | Batches processed |
| `{prefix}.kafka_batch_size` | DistributionSummary | Batch sizes |
| `{prefix}.kafka_batch_duration_ms` | Timer | Batch processing time |
| `{prefix}.kafka_message_size_bytes` | DistributionSummary | Message sizes |

---

## ComponentMetrics

### 📖 Purpose
Tracks **infrastructure components**: database pools, Redis, circuit breakers, HTTP clients, thread pools.

### 🎯 Component Categories

ComponentMetrics provides metrics for **5 major infrastructure categories**:

1. **Database Connection Pools** (HikariCP, etc.)
2. **Redis/Cache Operations**
3. **Circuit Breakers** (Resilience4j)
4. **HTTP Client Calls** (RestTemplate, WebClient)
5. **Thread Pools**

---

### 1️⃣ Database Connection Pool Metrics

#### Method: `recordConnectionPoolMetrics()`
Records connection pool health metrics.

**Usage:**
```java
@Component
@Slf4j
public class DatabaseMetricsReporter {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private HikariDataSource dataSource;
    
    @Scheduled(fixedRate = 30000)  // Every 30 seconds
    public void reportPoolMetrics() {
        HikariPoolMXBean poolMXBean = dataSource.getHikariPoolMXBean();
        
        componentMetrics.recordConnectionPoolMetrics(
            "fraud-router-db",
            poolMXBean.getTotalConnections(),
            poolMXBean.getActiveConnections(),
            poolMXBean.getIdleConnections(),
            poolMXBean.getThreadsAwaitingConnection()
        );
    }
}
```

**Metrics Created:**
```prometheus
fraud_switch_fraud_router_db_connection_pool_size{
  pool_name="fraud-router-db"
} 20

fraud_switch_fraud_router_db_connection_pool_active{
  pool_name="fraud-router-db"
} 15

fraud_switch_fraud_router_db_connection_pool_idle{
  pool_name="fraud-router-db"
} 5

fraud_switch_fraud_router_db_connection_pool_waiting{
  pool_name="fraud-router-db"
} 0
```

---

#### Method: `recordConnectionWaitTime(String poolName, long waitTimeMs)`
Records time spent waiting for a connection.

**Usage:**
```java
long startTime = System.currentTimeMillis();
Connection conn = dataSource.getConnection();
long waitTime = System.currentTimeMillis() - startTime;

if (waitTime > 0) {
    componentMetrics.recordConnectionWaitTime("fraud-router-db", waitTime);
}
```

**Metrics Created:**
```prometheus
fraud_switch_fraud_router_db_connection_wait_time_ms{
  pool_name="fraud-router-db",
  quantile="0.99"
} 125
```

---

#### Method: `recordDatabaseQuery(String operation, String tableName, long durationMs, boolean success)`
Records database query execution.

**Usage:**
```java
@Repository
public class FraudRepository {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    public FraudRecord findById(String transactionId) {
        long startTime = System.currentTimeMillis();
        
        try {
            FraudRecord record = jdbcTemplate.queryForObject(
                "SELECT * FROM fraud_records WHERE transaction_id = ?",
                new Object[]{transactionId},
                new FraudRecordRowMapper()
            );
            
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordDatabaseQuery(
                MetricLabels.Database.OP_SELECT,
                "fraud_records",
                duration,
                true
            );
            
            return record;
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordDatabaseQuery(
                MetricLabels.Database.OP_SELECT,
                "fraud_records",
                duration,
                false
            );
            throw e;
        }
    }
}
```

**Metrics Created:**
```prometheus
fraud_switch_fraud_router_db_query_total{
  db_operation="SELECT",
  table_name="fraud_records",
  status="success"
} 1000

fraud_switch_fraud_router_db_query_duration_ms{
  db_operation="SELECT",
  table_name="fraud_records",
  quantile="0.99"
} 45
```

---

### 2️⃣ Redis/Cache Metrics

#### Method: `recordCacheOperation(String cacheName, String operation, boolean hit, long durationMs)`
Records cache operations (get, put, evict).

**Usage:**
```java
@Service
public class RulesService {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private RedisTemplate<String, RulesConfig> redisTemplate;
    
    public RulesConfig getRulesConfig(String merchantId) {
        long startTime = System.currentTimeMillis();
        String cacheKey = "rules:" + merchantId;
        
        // Try to get from cache
        RulesConfig config = redisTemplate.opsForValue().get(cacheKey);
        long duration = System.currentTimeMillis() - startTime;
        
        if (config != null) {
            // ✅ Cache HIT
            componentMetrics.recordCacheOperation(
                "rules-cache",
                MetricLabels.Cache.OP_GET,
                true,  // hit
                duration
            );
            return config;
        } else {
            // ❌ Cache MISS
            componentMetrics.recordCacheOperation(
                "rules-cache",
                MetricLabels.Cache.OP_GET,
                false,  // miss
                duration
            );
            
            // Load from database and cache
            config = loadFromDatabase(merchantId);
            redisTemplate.opsForValue().set(cacheKey, config);
            
            return config;
        }
    }
}
```

**Metrics Created:**
```prometheus
# Cache hits
fraud_switch_rules_service_cache_operations_total{
  cache_name="rules-cache",
  cache_operation="get",
  hit_type="hit"
} 850

# Cache misses
fraud_switch_rules_service_cache_operations_total{
  cache_name="rules-cache",
  cache_operation="get",
  hit_type="miss"
} 150

# Cache operation duration
fraud_switch_rules_service_cache_operation_duration_ms{
  cache_name="rules-cache",
  cache_operation="get",
  quantile="0.99"
} 5
```

---

#### Method: `recordCacheHit(String cacheName)` / `recordCacheMiss(String cacheName)`
Simplified cache hit/miss tracking.

**Usage:**
```java
RulesConfig config = cache.get(merchantId);

if (config != null) {
    componentMetrics.recordCacheHit("rules-cache");
} else {
    componentMetrics.recordCacheMiss("rules-cache");
}
```

---

#### Method: `recordRedisCommand(String command, long durationMs, boolean success)`
Records Redis command execution.

**Usage:**
```java
long startTime = System.currentTimeMillis();

try {
    redisTemplate.opsForValue().set(key, value);
    long duration = System.currentTimeMillis() - startTime;
    
    componentMetrics.recordRedisCommand("SET", duration, true);
    
} catch (Exception e) {
    long duration = System.currentTimeMillis() - startTime;
    componentMetrics.recordRedisCommand("SET", duration, false);
    throw e;
}
```

**Metrics Created:**
```prometheus
fraud_switch_fraud_router_redis_commands_total{
  redis_command="SET",
  status="success"
} 500

fraud_switch_fraud_router_redis_command_duration_ms{
  redis_command="SET",
  quantile="0.99"
} 3
```

---

### 3️⃣ Circuit Breaker Metrics

#### Method: `recordCircuitBreakerState(String circuitBreakerName, String state)`
Records circuit breaker state changes.

**Usage:**
```java
@Component
public class CircuitBreakerMonitor {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;
    
    @PostConstruct
    public void monitorCircuitBreakers() {
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb -> {
            cb.getEventPublisher().onStateTransition(event -> {
                componentMetrics.recordCircuitBreakerState(
                    event.getCircuitBreakerName(),
                    event.getStateTransition().getToState().toString()
                );
            });
        });
    }
}
```

**Metrics Created:**
```prometheus
fraud_switch_fraud_router_circuit_breaker_state{
  circuit_breaker_name="fraudsight_adapter",
  state="OPEN"
} 1  # 0=CLOSED, 1=OPEN, 2=HALF_OPEN
```

---

#### Method: `recordCircuitBreakerCall(String circuitBreakerName, String callType, long durationMs)`
Records circuit breaker call outcomes.

**Usage:**
```java
CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("fraudsight_adapter");
long startTime = System.currentTimeMillis();

try {
    circuitBreaker.executeSupplier(() -> callFraudSightAdapter(request));
    long duration = System.currentTimeMillis() - startTime;
    
    componentMetrics.recordCircuitBreakerCall(
        "fraudsight_adapter",
        MetricLabels.CircuitBreaker.CALL_SUCCESS,
        duration
    );
    
} catch (CallNotPermittedException e) {
    // Circuit breaker open
    long duration = System.currentTimeMillis() - startTime;
    componentMetrics.recordCircuitBreakerCall(
        "fraudsight_adapter",
        MetricLabels.CircuitBreaker.CALL_FAILURE,
        duration
    );
}
```

**Metrics Created:**
```prometheus
fraud_switch_fraud_router_circuit_breaker_calls_total{
  circuit_breaker_name="fraudsight_adapter",
  call_type="SUCCESS"
} 950

fraud_switch_fraud_router_circuit_breaker_calls_total{
  circuit_breaker_name="fraudsight_adapter",
  call_type="FAILURE"
} 50

fraud_switch_fraud_router_circuit_breaker_call_duration_ms{
  circuit_breaker_name="fraudsight_adapter",
  call_type="SUCCESS",
  quantile="0.99"
} 87
```

---

### 4️⃣ HTTP Client Metrics

#### Method: `recordHttpClientCall(String targetService, String httpMethod, int statusCode, long durationMs)`
Records HTTP client calls to internal services.

**Usage:**
```java
@Service
public class RulesServiceClient {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private RestTemplate restTemplate;
    
    public RulesResponse checkRules(String merchantId, String eventType) {
        long startTime = System.currentTimeMillis();
        
        try {
            ResponseEntity<RulesResponse> response = restTemplate.postForEntity(
                "http://rules-service/api/rules/check",
                new RulesRequest(merchantId, eventType),
                RulesResponse.class
            );
            
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordHttpClientCall(
                "rules-service",
                "POST",
                response.getStatusCodeValue(),
                duration
            );
            
            return response.getBody();
            
        } catch (HttpServerErrorException e) {
            long duration = System.currentTimeMillis() - startTime;
            componentMetrics.recordHttpClientCall(
                "rules-service",
                "POST",
                e.getRawStatusCode(),
                duration
            );
            throw e;
        }
    }
}
```

**Metrics Created:**
```prometheus
fraud_switch_fraud_router_http_client_calls_total{
  target_service="rules-service",
  http_method="POST",
  http_status="200"
} 1000

fraud_switch_fraud_router_http_client_calls_total{
  target_service="rules-service",
  http_method="POST",
  http_status="503"
} 5

fraud_switch_fraud_router_http_client_call_duration_ms{
  target_service="rules-service",
  http_method="POST",
  http_status="200",
  quantile="0.99"
} 45
```

---

### 5️⃣ Thread Pool Metrics

#### Method: `recordThreadPoolMetrics(String poolName, int activeThreads, int poolSize, int queueSize)`
Records thread pool health.

**Usage:**
```java
@Component
public class ThreadPoolMetricsReporter {
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private ThreadPoolTaskExecutor asyncExecutor;
    
    @Scheduled(fixedRate = 30000)
    public void reportThreadPoolMetrics() {
        componentMetrics.recordThreadPoolMetrics(
            "async-processor-pool",
            asyncExecutor.getActiveCount(),
            asyncExecutor.getPoolSize(),
            asyncExecutor.getThreadPoolExecutor().getQueue().size()
        );
    }
}
```

**Metrics Created:**
```prometheus
fraud_switch_async_processor_thread_pool_active{
  pool_name="async-processor-pool"
} 8

fraud_switch_async_processor_thread_pool_size{
  pool_name="async-processor-pool"
} 20

fraud_switch_async_processor_thread_pool_queue_size{
  pool_name="async-processor-pool"
} 50
```

---

#### Method: `recordRetryAttempt(String operation, int attemptNumber, boolean success)`
Records retry attempts.

**Usage:**
```java
int attempt = 0;
int maxRetries = 3;

while (attempt < maxRetries) {
    attempt++;
    
    try {
        callExternalService();
        
        componentMetrics.recordRetryAttempt(
            "call-fraud-provider",
            attempt,
            true
        );
        break;
        
    } catch (Exception e) {
        componentMetrics.recordRetryAttempt(
            "call-fraud-provider",
            attempt,
            false
        );
        
        if (attempt >= maxRetries) {
            throw e;
        }
        Thread.sleep(100 * attempt);  // Backoff
    }
}
```

**Metrics Created:**
```prometheus
fraud_switch_fraud_router_retry_attempts_total{
  operation="call-fraud-provider",
  attempt="1",
  status="failure"
} 50

fraud_switch_fraud_router_retry_attempts_total{
  operation="call-fraud-provider",
  attempt="2",
  status="success"
} 45
```

---

### 📊 ComponentMetrics - All Metrics

| Category | Metric Name | Type | Description |
|----------|-------------|------|-------------|
| **Database** | `{prefix}.db_connection_pool_size` | Gauge | Total connections |
| | `{prefix}.db_connection_pool_active` | Gauge | Active connections |
| | `{prefix}.db_connection_pool_idle` | Gauge | Idle connections |
| | `{prefix}.db_connection_pool_waiting` | Gauge | Waiting threads |
| | `{prefix}.db_connection_wait_time_ms` | Timer | Connection wait time |
| | `{prefix}.db_query_total` | Counter | Database queries |
| | `{prefix}.db_query_duration_ms` | Timer | Query execution time |
| **Cache** | `{prefix}.cache_operations_total` | Counter | Cache operations |
| | `{prefix}.cache_operation_duration_ms` | Timer | Operation duration |
| | `{prefix}.redis_commands_total` | Counter | Redis commands |
| | `{prefix}.redis_command_duration_ms` | Timer | Command duration |
| | `{prefix}.redis_connections_active` | Gauge | Active connections |
| **Circuit Breaker** | `{prefix}.circuit_breaker_state` | Gauge | CB state |
| | `{prefix}.circuit_breaker_calls_total` | Counter | CB calls |
| | `{prefix}.circuit_breaker_call_duration_ms` | Timer | Call duration |
| **HTTP Client** | `{prefix}.http_client_calls_total` | Counter | HTTP calls |
| | `{prefix}.http_client_call_duration_ms` | Timer | Call duration |
| **Thread Pool** | `{prefix}.thread_pool_active` | Gauge | Active threads |
| | `{prefix}.thread_pool_size` | Gauge | Pool size |
| | `{prefix}.thread_pool_queue_size` | Gauge | Queue size |
| **Retry** | `{prefix}.retry_attempts_total` | Counter | Retry attempts |

---

## Metric Naming Conventions

### ✅ Naming Pattern
```
fraud_switch_{service}_{metric_name}
```

Examples:
```
fraud_switch_fraud_router_requests_total
fraud_switch_fraud_router_kafka_publish_total
fraud_switch_fraud_router_db_connection_pool_size
```

### 📏 Metric Types

| Type | Purpose | Example |
|------|---------|---------|
| **Counter** | Ever-increasing value | `requests_total`, `errors_total` |
| **Gauge** | Point-in-time value | `connection_pool_size`, `consumer_lag` |
| **Timer** | Duration distribution | `request_duration_ms`, `query_duration_ms` |
| **DistributionSummary** | Size distribution | `batch_size`, `message_size_bytes` |

---

## Common Tags & Labels

### 🏷️ Standard Labels (All Metrics)

These labels are automatically added to **every** metric:

```java
service="fraud-router"
region="us-ohio-1"
environment="production"
```

### 📊 Request Labels

```java
MetricLabels.Request.EVENT_TYPE        // "beforeAuthenticationSync"
MetricLabels.Request.GATEWAY           // "RAFT"
MetricLabels.Request.PRODUCT           // "fraudsight"
MetricLabels.Request.PAYMENT_METHOD    // "card"
MetricLabels.Request.ENDPOINT          // "/check-fraud"
```

### 📨 Kafka Labels

```java
MetricLabels.Kafka.TOPIC              // "async.events"
MetricLabels.Kafka.PARTITION          // "0"
MetricLabels.Kafka.CONSUMER_GROUP     // "async-processor"
MetricLabels.Kafka.OPERATION          // "publish" or "consume"
```

### 🗄️ Database Labels

```java
MetricLabels.Database.POOL_NAME       // "fraud-router-db"
MetricLabels.Database.DB_OPERATION    // "SELECT", "INSERT", "UPDATE", "DELETE"
MetricLabels.Database.TABLE_NAME      // "fraud_records"
```

### 🔄 Circuit Breaker Labels

```java
MetricLabels.CircuitBreaker.CIRCUIT_BREAKER_NAME  // "fraudsight_adapter"
MetricLabels.CircuitBreaker.STATE                  // "CLOSED", "OPEN", "HALF_OPEN"
MetricLabels.CircuitBreaker.CALL_TYPE              // "SUCCESS", "FAILURE", "TIMEOUT"
```

### 💾 Cache Labels

```java
MetricLabels.Cache.CACHE_NAME         // "rules-cache"
MetricLabels.Cache.CACHE_OPERATION    // "get", "put", "evict"
MetricLabels.Cache.HIT_TYPE           // "hit" or "miss"
```

---

## Implementation Examples

### 🎯 Complete Service Example

```java
@RestController
@RequestMapping("/api/fraud")
@Slf4j
public class FraudController {
    
    @Autowired
    private RequestMetrics requestMetrics;
    
    @Autowired
    private KafkaMetrics kafkaMetrics;
    
    @Autowired
    private ComponentMetrics componentMetrics;
    
    @Autowired
    private FraudService fraudService;
    
    @Autowired
    private KafkaTemplate<String, FraudEvent> kafkaTemplate;
    
    /**
     * Complete example using all 3 metrics classes
     */
    @PostMapping("/check")
    public ResponseEntity<FraudResponse> checkFraud(@RequestBody FraudRequest request) {
        long requestStartTime = System.currentTimeMillis();
        
        try {
            // 1. Check cache (ComponentMetrics)
            FraudResponse cachedResponse = checkCache(request);
            if (cachedResponse != null) {
                // Cache hit - return immediately
                long requestDuration = System.currentTimeMillis() - requestStartTime;
                requestMetrics.recordRequest(
                    requestDuration,
                    MetricLabels.Request.EVENT_TYPE, request.getEventType(),
                    MetricLabels.Request.GATEWAY, request.getGateway(),
                    "cache", "hit"
                );
                return ResponseEntity.ok(cachedResponse);
            }
            
            // 2. Call fraud service (includes database queries - ComponentMetrics)
            FraudResponse response = fraudService.checkFraud(request);
            
            // 3. Publish to Kafka (KafkaMetrics)
            publishFraudEvent(request, response);
            
            // 4. Record successful request (RequestMetrics)
            long requestDuration = System.currentTimeMillis() - requestStartTime;
            requestMetrics.recordRequest(
                requestDuration,
                MetricLabels.Request.EVENT_TYPE, request.getEventType(),
                MetricLabels.Request.GATEWAY, request.getGateway(),
                MetricLabels.Request.PRODUCT, request.getProduct()
            );
            
            return ResponseEntity.ok(response);
            
        } catch (TimeoutException e) {
            // Timeout error
            long requestDuration = System.currentTimeMillis() - requestStartTime;
            requestMetrics.recordError(
                requestDuration,
                "TimeoutException",
                MetricLabels.Request.EVENT_TYPE, request.getEventType()
            );
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
            
        } catch (Exception e) {
            // Other errors
            long requestDuration = System.currentTimeMillis() - requestStartTime;
            requestMetrics.recordError(
                requestDuration,
                e.getClass().getSimpleName(),
                MetricLabels.Request.EVENT_TYPE, request.getEventType()
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    private FraudResponse checkCache(FraudRequest request) {
        long cacheStartTime = System.currentTimeMillis();
        String cacheKey = buildCacheKey(request);
        
        FraudResponse cached = cache.get(cacheKey);
        long cacheDuration = System.currentTimeMillis() - cacheStartTime;
        
        if (cached != null) {
            componentMetrics.recordCacheHit("fraud-cache");
        } else {
            componentMetrics.recordCacheMiss("fraud-cache");
        }
        
        return cached;
    }
    
    private void publishFraudEvent(FraudRequest request, FraudResponse response) {
        long publishStartTime = System.currentTimeMillis();
        
        try {
            FraudEvent event = buildFraudEvent(request, response);
            SendResult<String, FraudEvent> result = kafkaTemplate.send(
                "async.events",
                request.getTransactionId(),
                event
            ).get();
            
            long publishDuration = System.currentTimeMillis() - publishStartTime;
            kafkaMetrics.recordPublish(
                "async.events",
                result.getRecordMetadata().partition(),
                publishDuration
            );
            
        } catch (Exception e) {
            long publishDuration = System.currentTimeMillis() - publishStartTime;
            kafkaMetrics.recordPublish(
                "async.events",
                0,
                publishDuration,
                false,
                e.getClass().getSimpleName()
            );
        }
    }
}
```

---

## Best Practices

### ✅ DO:

1. **Use canonical names for all labels**
   ```java
   // ✅ CORRECT
   MetricLabels.Request.EVENT_TYPE, "beforeAuthenticationSync"
   MetricLabels.Request.GATEWAY, "RAFT"
   
   // ❌ WRONG
   "event_type", "beforeAuthenticationSync"  // Typo risk
   "eventType", "beforeAuthenticationSync"   // Wrong format
   ```

2. **Keep label cardinality low**
   ```java
   // ✅ CORRECT - Low cardinality
   "event_type", request.getEventType()      // ~10 unique values
   "gateway", request.getGateway()           // ~5 unique values
   
   // ❌ WRONG - High cardinality!
   "transaction_id", request.getTransactionId()  // Millions of unique values!
   "timestamp", String.valueOf(System.currentTimeMillis())  // Infinite!
   ```

3. **Record both success and error metrics**
   ```java
   try {
       processRequest();
       requestMetrics.recordRequest(duration, ...);
   } catch (Exception e) {
       requestMetrics.recordError(duration, e.getClass().getSimpleName(), ...);
   }
   ```

4. **Use exception class names for error types**
   ```java
   // ✅ CORRECT
   catch (TimeoutException e) {
       requestMetrics.recordError(duration, "TimeoutException", ...);
   }
   
   // ❌ WRONG
   catch (TimeoutException e) {
       requestMetrics.recordError(duration, e.getMessage(), ...);  // High cardinality!
   }
   ```

5. **Measure actual duration accurately**
   ```java
   long startTime = System.currentTimeMillis();
   // ... do work ...
   long duration = System.currentTimeMillis() - startTime;
   metrics.recordRequest(duration, ...);
   ```

---

### ❌ DON'T:

1. **Don't include PII in labels**
   ```java
   // ❌ NEVER do this
   "customer_email", request.getEmail()
   "card_number", request.getCardNumber()
   "customer_name", request.getName()
   ```

2. **Don't use dynamic label values**
   ```java
   // ❌ High cardinality explosion!
   "transaction_id", txnId
   "timestamp", timestamp
   "random_id", UUID.randomUUID().toString()
   ```

3. **Don't forget to record metrics**
   ```java
   // ❌ WRONG - No metrics!
   try {
       processRequest();
   } catch (Exception e) {
       log.error("Error", e);
       throw e;
   }
   
   // ✅ CORRECT - Record metrics
   try {
       processRequest();
       requestMetrics.recordRequest(duration, ...);
   } catch (Exception e) {
       requestMetrics.recordError(duration, e.getClass().getSimpleName(), ...);
       throw e;
   }
   ```

4. **Don't mix metric types**
   ```java
   // ❌ WRONG
   requestMetrics.recordRequest(duration, ...);           // Success
   requestMetrics.recordError(duration, "Success", ...);  // Also recording as error!
   
   // ✅ CORRECT - Only one
   requestMetrics.recordRequest(duration, ...);
   ```

---

## 📊 Prometheus Query Examples

### Request Metrics

```promql
# Request rate (per second)
rate(fraud_switch_fraud_router_requests_total[5m])

# Error rate
rate(fraud_switch_fraud_router_errors_total[5m])

# P99 latency
histogram_quantile(0.99, 
  rate(fraud_switch_fraud_router_request_duration_ms_bucket[5m])
)

# Error percentage
(rate(fraud_switch_fraud_router_errors_total[5m]) / 
 rate(fraud_switch_fraud_router_requests_total[5m])) * 100
```

### Kafka Metrics

```promql
# Publish rate
rate(fraud_switch_fraud_router_kafka_publish_total[5m])

# Consumer lag
fraud_switch_async_processor_kafka_consumption_lag

# Consumption rate by topic
sum by (topic) (rate(fraud_switch_async_processor_kafka_consumed_total[5m]))
```

### Component Metrics

```promql
# Active database connections
fraud_switch_fraud_router_db_connection_pool_active

# Cache hit rate
rate(fraud_switch_rules_service_cache_operations_total{hit_type="hit"}[5m]) /
rate(fraud_switch_rules_service_cache_operations_total[5m])

# Circuit breaker open count
sum(fraud_switch_fraud_router_circuit_breaker_state{state="OPEN"})
```

---

## 🎓 Summary

### RequestMetrics (5 methods)
- `recordRequest()` - Successful requests
- `recordError()` - Failed requests
- `recordWithOutcome()` - With explicit outcome
- `startTimer()` / `getRequestTimer()` - Manual timing
- `recordThroughput()` - RPS tracking

### KafkaMetrics (6 methods)
- `recordPublish()` - Message published (2 variants)
- `recordConsume()` - Message consumed (2 variants)
- `recordConsumerLag()` - Consumer lag
- `recordBatchProcessing()` - Batch operations
- `recordMessageSize()` - Message sizes

### ComponentMetrics (15+ methods)
- **Database:** Pool metrics, wait time, query execution
- **Cache:** Operations, hit/miss, Redis commands
- **Circuit Breaker:** State, calls
- **HTTP Client:** Service calls
- **Thread Pool:** Pool health, retry attempts

---

## 🚀 Quick Start

1. **Add dependency**
2. **Auto-configuration happens automatically**
3. **Inject metrics classes**
4. **Record metrics!**

```java
@Service
public class MyService {
    @Autowired private RequestMetrics requestMetrics;
    @Autowired private KafkaMetrics kafkaMetrics;
    @Autowired private ComponentMetrics componentMetrics;
    
    // Use them!
}
```

---

**Version:** 1.0.0  
**Last Updated:** October 23, 2025  
**Author:** Fraud Switch Platform Team
