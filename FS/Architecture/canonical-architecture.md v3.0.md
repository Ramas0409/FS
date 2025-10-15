# Fraud Switch - Canonical Architecture

**System Name:** Fraud Switch (Payment Fraud Detection Platform)  
**Version:** 3.0 (DEK Rotation + Centralized Metrics Library)  
**Last Updated:** October 14, 2025  
**Status:** ✅ All 13 Sections Confirmed + DEK Rotation + Metrics Library Deployed  

---

## Table of Contents

1. [System Purpose & NFRs](#1-system-purpose--nfrs)
2. [Ingress & Auth](#2-ingress--auth)
3. [Core Processing](#3-core-processing)
4. [Rules & Decisions](#4-rules--decisions)
5. [PAN/PCI Boundary](#5-panpci-boundary)
6. [Fraud Providers & Adapters](#6-fraud-providers--adapters)
7. [Sync vs Async Flows](#7-sync-vs-async-flows)
8. [Streaming & Data](#8-streaming--data)
9. [Data Stores](#9-data-stores)
10. [Observability](#10-observability)
11. [Resilience & Scaling](#11-resilience--scaling)
12. [Interfaces (APIs)](#12-interfaces-apis)
13. [Known Constraints / Not-in-Scope](#13-known-constraints--not-in-scope)

---

## 1. System Purpose & NFRs

### System Purpose

**Fraud Switch** is a centralized payment fraud detection platform that:
- Routes transaction events to external fraud providers (Ravelin, Signifyd)
- Executes merchant-configured rules for pre-call and post-call fraud decisions
- Provides PAN tokenization and secure storage
- Streams transaction/decline data to analytics platforms
- Shares fraud data with issuers (Issuer Data-Share)

### Non-Functional Requirements (NFRs)

**Performance:**
- **FraudSight (Ravelin) Sync SLA**: p99 < 100ms (end-to-end)
- **GuaranteedPayment (Signifyd) Sync SLA**: p99 < 350ms (end-to-end)
- **Throughput**: 300 TPS sync + 1000 TPS async per region = 1300 TPS total
- **Latency Breakdown**:
  - ETS ingress → Fraud Router: <10ms
  - Fraud Router (boarding + rules + BIN parallel): <20ms
  - Ravelin: p99 <80ms, avg ~20ms
  - Signifyd: p99 ~350ms, avg ~250ms

**Availability:**
- **Uptime Target**: 99.9% (excluding planned maintenance)
- **Multi-AZ**: All services deployed across 3 AZs per region
- **Regional Isolation**: US (Ohio) and UK (London) completely independent

**Security:**
- **PAN Encryption**: Clear PAN encrypted with AES-256-GCM using rotated DEKs
- **DEK Rotation**: Every 30 minutes (limits blast radius to 30-min window)
- **PCI Compliance**: Clear PAN restricted to Tokenization Service only
- **Audit Trail**: All decrypt operations logged with who/when/why

**Scalability:**
- **Horizontal Scaling**: HPA based on CPU (70%) and memory (80%)
- **Pod Limits**: Min 10, Max 60 per service per region
- **Data Retention**: 6 months for encrypted PAN, 7 days for orphaned DEKs

---

## 2. Ingress & Auth

### External Ingress (ETS - External Transaction Service)

**Endpoint:** `POST /v1/ext-transactions/events`

**Gateways:**
- **RAFT**: Internal gateway
- **VAP**, **EXPRESS**, **WPG**, **ACCESS**: External gateways

**Authentication:**
- **API Key**: `X-API-Key` header (per gateway)
- **IP Whitelist**: Per gateway

**Rate Limiting:**
- **Per Gateway**: Configurable (e.g., 1000 RPS per gateway)
- **Global**: 1300 TPS per region

**Payload:**
```json
{
  "eventType": "beforeAuthenticationSync",
  "gateway": "RAFT",
  "transactionId": "txn_123",
  "accountNumber": "4111111111111111",
  "amount": 100.00,
  "merchantId": "merchant_456"
}
```

**Event Types:**
- **Sync**: `beforeAuthenticationSync`, `beforeAuthorizationSync`, `afterAuthorizationSync`
- **Async**: `afterAuthenticationAsync`, `afterAuthorizationAsync`
- **Fraud/Chargeback**: `confirmedFraud`, `confirmedChargeback`

---

## 3. Core Processing

### Fraud Router (60 pods per region)

**Responsibilities:**
1. **Ingress validation**: Schema validation, gateway authentication
2. **Boarding lookup**: DynamoDB cached merchant subscriptions (TTL: 1 hour)
3. **Parallel processing**: Rules Service + BIN Lookup + PAN Tokenization
4. **Adapter routing**: Route to FraudSightAdapter or GuaranteedPaymentAdapter
5. **Response aggregation**: Combine provider response + rules + BIN data
6. **Event publishing**: Publish to Kafka (`fs.transactions`, `fs.declines`, `pan.queue`)
7. **DEK rotation**: Rotate Data Encryption Keys every 30 minutes

**Technology:** Spring Boot, Spring Kafka, Resilience4j

**Flow:**
```
ETS → Fraud Router → (Parallel: Rules + BIN + PAN Hash) → Adapter → Provider → Response
                   ↓
                 Kafka Topics
```

---

## 4. Rules & Decisions

### Rules Service (20 pods per region)

**Purpose:**
- Execute merchant-configured fraud rules
- Pre-call rules (before provider) and post-call rules (after provider)

**Rule Types:**
1. **Velocity rules**: Transaction count/amount per time window
2. **Allow/Block lists**: Card BIN, merchant ID, country
3. **Threshold rules**: Max transaction amount, daily limit
4. **Risk score rules**: Override provider decision based on score

**Data Sources:**
- **DynamoDB**: Cached rules configuration (TTL: 5 minutes)
- **Redis**: Velocity counters (key: `velocity:{hpan}:{window}`)

**Performance:**
- **p95**: ~8ms
- **p99**: ~10ms
- **avg**: ~6ms

**Rules Execution:**
- **Pre-call**: Execute before calling fraud provider
- **Post-call**: Execute after provider response received
- **Decision priority**: Rules can override provider decision (configurable)

**Examples:**
```yaml
rules:
  - id: "rule_001"
    name: "Block high-risk BINs"
    type: "blocklist"
    condition: "bin IN ['411111', '555555']"
    action: "DECLINE"
    
  - id: "rule_002"
    name: "Velocity check"
    type: "velocity"
    condition: "transaction_count > 5 in 1_hour"
    action: "DECLINE"
```

---

## 5. PAN/PCI Boundary

### Clear PAN Restriction

**Principle:** Clear PAN never leaves the PCI boundary

**Services with Clear PAN Access:**
- **Fraud Router**: Receives clear PAN from ETS, immediately hashes to `hpan` using HMAC-SHA256
- **Tokenization Service**: Receives clear PAN from Kafka `pan.queue`, encrypts and stores

**Services WITHOUT Clear PAN Access:**
- **All other services**: Only see hashed PAN (`hpan`)
- **Fraud providers**: Receive hashed PAN only
- **Analytics/CDP/Snowflake**: Receive hashed PAN only

### PAN Tokenization Flow

**Publish:**
1. Fraud Router receives clear PAN from ETS
2. Hash PAN → `hpan = HMAC-SHA256(pan, hmac_key)`
3. Publish `{hpan, pan}` to Kafka topic `pan.queue`
4. Replace clear PAN with `hpan` in all downstream processing

**Consume:**
1. Tokenization Service consumes from `pan.queue`
2. Get current DEK (in-memory, rotated every 30 minutes)
3. Encrypt PAN: `pan_ct = AES-256-GCM(pan, dek_plaintext)`
4. Store in Aurora: `{hpan, pan_ct, dek_id}`

### PAN Persistence

**Storage:** Aurora PostgreSQL `encrypted_pan` table

**Schema:**
```sql
CREATE TABLE encrypted_pan (
  hpan VARCHAR(64) PRIMARY KEY,
  pan_ct BYTEA NOT NULL,
  dek_id BIGINT NOT NULL,
  last_seen_date DATE NOT NULL DEFAULT CURRENT_DATE,
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW(),
  
  CONSTRAINT fk_encrypted_pan_dek 
    FOREIGN KEY (dek_id) 
    REFERENCES dek_table(dek_id)
    ON DELETE RESTRICT
);

CREATE INDEX idx_encrypted_pan_last_seen 
ON encrypted_pan (last_seen_date);
```

**Retention:** 6 months (based on `last_seen_date`)

**Cleanup Job:**
- Runs nightly
- Deletes PANs with `last_seen_date < NOW() - INTERVAL '6 months'`
- After deleting PANs, deletes orphaned DEKs (7-day grace period)

### Encrypt/Decrypt API

**Encrypt Endpoint:** `POST /v1/pan/encrypt`
- **Input**: `{pan: "4111111111111111"}`
- **Output**: `{hpan: "abc123...", pan_ct: "encrypted_bytes"}`
- **Used by**: Tokenization Service

**Decrypt Endpoint:** `POST /v1/pan/decrypt` (Restricted)
- **Input**: `{hpan: "abc123..."}`
- **Process**:
  1. Query `encrypted_pan` table by hashed PAN → get `pan_ct` and `dek_id`
  2. Query `dek_table` by `dek_id` → get encrypted DEK
  3. Decrypt DEK using regional KMS master key
  4. Decrypt PAN using plaintext DEK (AES-256-GCM)
- **Audit logging** for all decrypt operations (who, when, why)
- **Regional**: US endpoint decrypts US PANs only; UK endpoint decrypts UK PANs only
- **Global merchants**: UI/CDP tries US first, then UK if not found

### Security Controls

- **Clear PAN never shared externally** (not to providers, analytics, logs)
- **Logs PII-redacted** (needs validation)
- `pan.queue` topic encrypted at rest and in transit
- **DEK rotation** limits blast radius to 30-minute windows

---

### DEK Rotation Strategy (Implemented v2.0)

**Overview:**
- **DEK (Data Encryption Key) rotation** implemented with 30-minute rotation cycle
- **Envelope encryption pattern**: DEKs encrypted with regional KMS master keys
- **Pessimistic locking** ensures exactly 1 DEK generated per rotation (48 DEKs/day)
- **Circuit breaker** protects against fallback latency violations

**Architecture Components:**
- **AWS Secrets Manager**: Stores HMAC key with automatic US→UK replication
- **AWS KMS**: Generates and encrypts DEKs (separate master keys per region)
- **Aurora PostgreSQL**: Stores encrypted DEKs with rotation_lock coordination
- **Fraud Router**: Rotates DEKs every 30 minutes using Spring @Scheduled

**Key Design Decisions:**

| Aspect | Implementation | Rationale |
|--------|---------------|-----------|
| **Rotation Frequency** | Every 30 minutes per pod | Limits blast radius to 30-minute window |
| **Coordination** | Pessimistic locking with `rotation_lock` column | Ensures exactly 1 DEK per rotation |
| **HMAC Key** | AWS Secrets Manager with automatic replication | Eliminates manual sync; zero drift risk |
| **Fallback** | Circuit breaker (Resilience4j) | Prevents SLA violations during DB issues |
| **Data Retention** | 6 months (PAN), orphaned DEKs deleted after 7 days | Compliance-aligned cleanup |

**Database Schema:**

```sql
-- DEK Table
CREATE TABLE dek_table (
  dek_id BIGSERIAL PRIMARY KEY,
  dek_enc BYTEA NOT NULL,
  rotation_lock BOOLEAN NOT NULL DEFAULT FALSE,
  inserted_timestamp TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_dek_inserted_timestamp 
ON dek_table (inserted_timestamp DESC);

CREATE INDEX idx_dek_rotation_lock 
ON dek_table (rotation_lock, inserted_timestamp DESC)
WHERE rotation_lock = FALSE;

-- Encrypted PAN Table (FK added in v2.0)
CREATE TABLE encrypted_pan (
  hpan VARCHAR(64) PRIMARY KEY,
  pan_ct BYTEA NOT NULL,
  dek_id BIGINT NOT NULL,
  last_seen_date DATE NOT NULL DEFAULT CURRENT_DATE,
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW(),
  
  CONSTRAINT fk_encrypted_pan_dek 
    FOREIGN KEY (dek_id) 
    REFERENCES dek_table(dek_id)
    ON DELETE RESTRICT
);

CREATE INDEX idx_encrypted_pan_last_seen 
ON encrypted_pan (last_seen_date);
```

**Rotation Algorithm:**

```
Every 30 minutes (per pod):
  1. Check for recent locked DEK (inserted within last 5 minutes)
  2. If found: Decrypt and reuse (no new DEK generation)
  3. Else: Try to acquire lock on existing unlocked DEK using FOR UPDATE SKIP LOCKED
  4. If lock acquired: Decrypt and reuse
  5. Else (race condition): Generate new DEK via KMS, insert with rotation_lock=TRUE
```

**Expected Behavior:**
- **First pod to rotate**: Generates new DEK with `rotation_lock=TRUE`
- **Remaining 59 pods**: Find locked DEK → reuse → no new generation
- **Result**: Exactly 1 DEK per rotation cycle (48/day vs 480/day without locking)

**Operational Metrics:**
- DEK age: <30 minutes (rotation successful)
- Table growth: ~48 DEKs/day per region (90% reduction from initial design)
- Encryption latency: <5ms (uses in-memory DEK)
- Decryption latency: +20-70ms (1 DB query + KMS decrypt)

**Regional Isolation:**
- **US (Ohio)**: KMS key `arn:aws:kms:us-east-2:*:key/us-master-key-xxx`, Aurora `us-east-2`/`us-east-1` (failover)
- **UK (London)**: KMS key `arn:aws:kms:eu-west-2:*:key/uk-master-key-xxx`, Aurora `eu-west-2`/`eu-west-1` (failover)
- No cross-region DEK sharing (data locality requirement)
- HMAC key replicated automatically via AWS Secrets Manager (primary: US, replica: UK)

**Security Controls:**
- Plaintext DEK never stored in database (only encrypted DEK)
- Plaintext DEK only exists in pod memory
- DEK cleared from memory on pod shutdown (@PreDestroy)
- Circuit breaker prevents repeated fallback attempts during outages
- Complete audit trail for all decrypt operations

**Resilience Features:**
- **Readiness probe**: Prevents traffic to pods without initialized DEK
- **Circuit breaker**: Opens after 50% fallback failure rate (5 consecutive failures)
- **Graceful degradation**: Pods continue using current DEK if rotation fails
- **Self-healing**: Fallback to database query if in-memory DEK lost

**Implementation Status:**
- ✅ Approved by Architecture Review Board (October 12, 2025)
- ✅ All CRITICAL and HIGH priority concerns resolved
- ✅ Ready for implementation (10-week timeline)

**Reference Documents:**
- pan_dek_rotation_final_v2.md (complete design specification)
- arch_review_dek.md (architecture review feedback)

---

## 6. Fraud Providers & Adapters

### Supported Providers

**1. Ravelin (FraudSight Product)**
- **Use Case**: Real-time fraud scoring for authentication events
- **SLA**: p99 < 100ms (including Fraud Switch overhead)
- **Latency**: p99 ~80ms, avg ~20ms (provider only)
- **Gateway Timeout**: 800ms

**2. Signifyd (GuaranteedPayment Product)**
- **Use Case**: Chargeback protection for authorization events
- **SLA**: p99 < 350ms (including Fraud Switch overhead)
- **Latency**: p99 ~350ms, avg ~250ms (provider only)
- **Gateway Timeout**: 1.5 seconds

### FraudSightAdapter (30 pods)

**Responsibilities:**
1. Transform Fraud Switch event → Ravelin API format
2. Call Ravelin API with timeout/retry
3. Parse response and map to standard decision format
4. Publish to Kafka `fs.transactions` topic

**Event Types Supported:**
- `beforeAuthenticationSync`
- `afterAuthenticationAsync`

### GuaranteedPaymentAdapter (30 pods)

**Responsibilities:**
1. Transform Fraud Switch event → Signifyd API format
2. Call Signifyd API with timeout/retry
3. Parse response and map to standard decision format
4. Publish to Kafka `fs.transactions` topic

**Event Types Supported:**
- `beforeAuthorizationSync`
- `afterAuthorizationSync`
- `afterAuthorizationAsync`

### Decision Mapping

**Provider Response → Fraud Switch Decision:**
- **Ravelin**: `accept`, `review`, `decline` → `APPROVE`, `REVIEW`, `DECLINE`
- **Signifyd**: `ACCEPT`, `REJECT`, `HOLD` → `APPROVE`, `DECLINE`, `REVIEW`

---

## 7. Sync vs Async Flows

### Synchronous Flow (Latency-Critical)

**Event Types:** `beforeAuthenticationSync`, `beforeAuthorizationSync`, `afterAuthorizationSync`

**Flow:**
```
ETS → Fraud Router → (Boarding + Rules + BIN) → Adapter → Provider → Response → ETS
```

**Latency Budget:**
- **FraudSight**: 100ms total (80ms provider + 20ms Fraud Switch overhead)
- **GuaranteedPayment**: 350ms total (300ms provider + 50ms Fraud Switch overhead)

**Characteristics:**
- **Blocking**: Gateway waits for response
- **No retries**: Single attempt only (timeout = failure)
- **Circuit breaker**: Protects against provider outages

### Asynchronous Flow (Fire-and-Forget)

**Event Types:** `afterAuthenticationAsync`, `afterAuthorizationAsync`

**Flow:**
```
ETS → Fraud Router → Adapter → 204 Accepted (immediate)
                   ↓
                 async.events (Kafka) → Async Processor → Provider
```

**Characteristics:**
- **Non-blocking**: Gateway receives 204 immediately
- **Retries**: 3 attempts with exponential backoff (1s, 5s, 15s)
- **DLQ**: Failed events after 3 retries → `async.events.dlq`

**Retry Logic:**
1. Initial attempt
2. Retry 1: After 1 second
3. Retry 2: After 5 seconds
4. Retry 3: After 15 seconds
5. DLQ: After 3 failures

---

## 8. Streaming & Data

### Kafka Topics (MSK)

**1. `fs.transactions` (Transaction Events)**
- **Producers**: FraudSightAdapter, GuaranteedPaymentAdapter
- **Consumers**: Kafka Connect → S3 → CDP → Snowflake
- **Partitions**: 12
- **Retention**: 7 days
- **Data**: All transaction events with provider decisions

**2. `fs.declines` (Decline Events)**
- **Producers**: Fraud Router (for rule-based declines)
- **Consumers**: Kafka Connect → S3 → Analytics
- **Partitions**: 6
- **Retention**: 7 days
- **Data**: In-switch decline events (rules-based, not provider)

**3. `async.events` (Async Enrichment)**
- **Producers**: FraudSightAdapter, GuaranteedPaymentAdapter
- **Consumers**: Async Processor
- **Partitions**: 12
- **Retention**: 1 day
- **Data**: Async events awaiting provider call

**4. `pan.queue` (PAN Tokenization)**
- **Producers**: Fraud Router
- **Consumers**: Tokenization Service
- **Partitions**: 6
- **Retention**: 1 hour (short retention, clear PAN)
- **Encryption**: At-rest and in-transit

**5. `issuer.datashare.requests` (Issuer Data-Share)**
- **Producers**: FraudSightAdapter, GuaranteedPaymentAdapter
- **Consumers**: Issuer Data Service
- **Partitions**: 12
- **Retention**: 7 days
- **Data**: Transaction events for issuer consumption

**6. `issuer.datashare.dlq` (Failed Issuer Deliveries)**
- **Producers**: Issuer Data Service
- **Consumers**: Manual remediation
- **Partitions**: 6
- **Retention**: 30 days
- **Data**: Failed SFTP uploads to issuers

### Analytics Pipeline

**Flow:**
```
Adapters → fs.transactions/fs.declines → Kafka Connect → S3 (Parquet) → CDP → Snowflake
```

**Kafka Connect:**
- **Connector**: S3 Sink Connector
- **Format**: Parquet (columnar, compressed)
- **Partitioning**: By date (`year=2024/month=10/day=11/`)
- **Flush**: Every 10,000 records or 5 minutes

**S3 Buckets:**
- `fraud-switch-transactions-{region}`
- `fraud-switch-declines-{region}`

**CDP (Customer Data Platform):**
- Reads from S3, enriches, sends to Snowflake

---

## 9. Data Stores

### Aurora PostgreSQL

**Purpose:**
- Primary relational database for operational data

**Data:**
- **Merchant configuration**: Subscriptions, provider mappings, rules
- **Encrypted PAN**: Hashed PAN + encrypted PAN + DEK ID (via Tokenization Service)
- **DEK table**: Encrypted DEKs with rotation metadata (v2.0)
- **Audit logs**: Decrypt API access logs

**Configuration:**
- **Multi-AZ**: Primary (us-east-2), failover (us-east-1) for US; Primary (eu-west-2), failover (eu-west-1) for UK
- **Connection pooling**: HikariCP (max 20 connections per pod)
- **Indexes**: Optimized for hashed PAN lookups, DEK rotation queries

**Retention:**
- **Encrypted PAN**: 6 months (based on `last_seen_date`)
- **DEK**: Orphaned DEKs deleted after 7-day grace period
- **Audit logs**: 90 days

### DynamoDB

**Purpose:**
- High-performance key-value store for real-time lookups

**Data:**
- **Boarding cache**: Merchant + product subscription (TTL: 1 hour)
- **Rules configuration**: Per-merchant rules (TTL: 5 minutes)
- **BIN cache**: Card metadata (issuer, type, country) (TTL: 24 hours)

**Configuration:**
- **On-demand pricing**: Auto-scales with traffic
- **TTL enabled**: Automatic expiration for cached data
- **Global tables**: Not used (regional isolation requirement)

### ElastiCache (Redis)

**Purpose:**
- In-memory caching for velocity counters and session data

**Data:**
- **Velocity counters**: Transaction count/amount per time window (key: `velocity:{hpan}:{window}`)
- **Session cache**: Temporary fraud scoring context (TTL: 15 minutes)

**Configuration:**
- **Cluster mode**: 3 shards, 1 replica per shard (multi-AZ)
- **Eviction policy**: LRU (Least Recently Used)
- **Max memory**: 16 GB per node

### S3

**Purpose:**
- **Cold storage** for analytics, archival, backups

**Buckets:**
- `fraud-switch-transactions-{region}`: Parquet files from Kafka Connect
- `fraud-switch-declines-{region}`: Rule-based decline events
- `fraud-switch-backups-{region}`: Database backups, config snapshots

**Lifecycle policies:**
- **Standard → Glacier**: After 90 days
- **Glacier → Deep Archive**: After 1 year
- **Delete**: After 7 years (regulatory retention)

---

## 10. Observability

### Metrics (Centralized Library - Implemented v3.0)

**Implementation:**
- **Centralized Library**: `fraud-switch-metrics-spring-boot-starter` v1.0
- **Auto-configuration**: Zero boilerplate for services (Spring Boot Starter pattern)
- **Type Safety**: Canonical names enforced via constants
- **Cardinality Protection**: Runtime enforcement with circuit breaker (max 1000 combinations/metric)
- **Performance Overhead**: <1% CPU, 60-80 MB memory per pod (3-4% of 2GB allocation)

**Metric Taxonomy:**

#### 1. RED Metrics (Request-based SLIs)
**Format:** `fraud_switch.{service}.{metric}`

**Per Service:**
- `fraud_switch.{service}.requests.total` (counter)
  - Labels: event_type, gateway, product, status
- `fraud_switch.{service}.request.duration.seconds` (histogram)
  - Service-specific buckets optimized for SLA targets:
    - **FraudRouter**: [0.01, 0.025, 0.05, 0.075, 0.1, 0.15, 0.2] (100ms SLA)
    - **Adapters**: [0.05, 0.1, 0.2, 0.35, 0.5, 1.0] (350ms SLA)
    - **Rules Service**: [0.005, 0.01, 0.02, 0.05, 0.1] (fast evaluation)
- `fraud_switch.{service}.errors.total` (counter)
  - Labels: event_type, gateway, product, error_type

#### 2. Component Metrics
**Format:** `fraud_switch.{service}.component.{metric}`

- `fraud_switch.{service}.component.calls.total` (counter)
  - Labels: component, status
- `fraud_switch.{service}.component.duration.seconds` (histogram)
  - Labels: component

#### 3. Business Metrics
- `fraud_switch.transactions.total` (counter)
  - Labels: provider, event_type, decision
- `fraud_switch.transactions.approved.total` (counter by provider)
- `fraud_switch.transactions.declined.total` (counter by provider, decline_reason)
- `fraud_switch.decision.overrides.total` (counter by override_type)
- `fraud_switch.rules.inswitch_decline.total` (counter by rule_id)

#### 4. Provider Metrics
- `fraud_switch.provider.calls.total` (counter by provider, status)
- `fraud_switch.provider.duration.seconds` (histogram by provider)
- `fraud_switch.provider.errors.total` (counter by provider, error_code)
- `fraud_switch.provider.timeout.total` (counter by provider)

#### 5. Infrastructure Metrics

**Kafka:**
- `fraud_switch.kafka.publish.total` (counter by topic)
- `fraud_switch.kafka.publish.duration.seconds` (histogram by topic)
- `fraud_switch.kafka.publish.errors.total` (counter by topic, error_type)
- `fraud_switch.kafka.consumer.lag` (gauge by topic, consumer_group, partition)

**Data Stores:**
- `fraud_switch.aurora.connection_pool.active` (gauge by pool_name)
- `fraud_switch.aurora.connection_pool.idle` (gauge by pool_name)
- `fraud_switch.aurora.query.duration.seconds` (histogram by query_type)
- `fraud_switch.dynamodb.query.duration.seconds` (histogram by table, operation)
- `fraud_switch.redis.hit_rate` (gauge by cache_name)

#### 6. Cardinality Enforcement Metrics (Library Safety)
- `fraud_switch.metrics.cardinality.warning` (counter)
  - Emitted when metric approaches 80% of cardinality limit
- `fraud_switch.metrics.cardinality.circuit_breaker` (counter)
  - Emitted when circuit breaker trips (new combinations rejected)

#### 7. DEK Rotation Metrics (v2.0)
- `fraud_switch.dek.age_minutes`: Age of current DEK (alert if >60 minutes)
- `fraud_switch.dek.rotation.success`: Counter for successful rotations
- `fraud_switch.dek.rotation.failure`: Counter for failed rotations
- `fraud_switch.dek.fallback.attempts`: Circuit breaker fallback attempts
- `fraud_switch.dek.fallback.success`: Successful fallback to DB
- `fraud_switch.dek.table_size`: Number of DEKs in dek_table

#### 8. Issuer Data-Share Metrics
- `fraud_switch.issuer.datashare.requests.sent`: Total requests sent per issuer
- `fraud_switch.issuer.datashare.requests.success`: Successful deliveries per issuer
- `fraud_switch.issuer.datashare.requests.failed`: Failed deliveries per issuer
- `fraud_switch.issuer.datashare.latency`: p95, p99 per issuer
- `fraud_switch.issuer.datashare.dlq.size`: Number of messages in DLQ
- `fraud_switch.issuer.datashare.circuit_breaker.state`: CLOSED, OPEN, HALF_OPEN per issuer

**Label Standards:**
- **Low Cardinality (Safe):** service (8), event_type (7), gateway (6), product (2), status (3), provider (2)
- **Medium Cardinality (Monitor):** component (10-20), topic (6), error_code (20-50)
- **High Cardinality (AVOID):** merchant_id, transaction_id, account_number_hash

**Cardinality Enforcement:**
- Runtime validation: max 1,000 unique label combinations per metric
- Circuit breaker: blocks new combinations at limit
- Alert when approaching 80% threshold

---

### Centralized Metrics Library (Implemented v3.0)

**Library:** `fraud-switch-metrics-spring-boot-starter` v1.0  
**Repository:** Internal Artifactory  
**Language:** Java 17, Spring Boot 3.1.5, Micrometer 1.11.5

**Architecture:**

**Core Components:**
- **MetricNames:** Centralized metric name constants
- **MetricLabels:** Canonical names for all labels (services, events, gateways, providers)
- **CardinalityEnforcer:** Runtime cardinality validation with circuit breaker
- **RequestMetrics:** RED pattern metrics (requests, errors, duration)
- **ComponentMetrics:** Service-to-service call metrics
- **KafkaMetrics:** Kafka publish/consume metrics
- **CustomMetricsBuilder:** Builder for service-specific business metrics

**Service-Specific Classes:**
- FraudRouterMetrics
- RulesServiceMetrics
- BinLookupMetrics
- AdapterMetrics
- AsyncProcessorMetrics
- TokenizationServiceMetrics

**Auto-Configuration:**
- Spring Boot Starter pattern
- Zero boilerplate for services
- Automatic bean wiring

**Usage Example:**
```java
@Service
public class FraudRouterService {
    private final FraudRouterMetrics metrics;
    
    public FraudRouterService(FraudRouterMetrics metrics) {
        this.metrics = metrics;  // Auto-injected
    }
    
    public FraudResponse processRequest(FraudRequest request) {
        long start = System.currentTimeMillis();
        try {
            FraudResponse response = callAdapter(request);
            metrics.recordRequest(eventType, gateway, product, "success");
            metrics.recordDuration(eventType, gateway, product, 
                System.currentTimeMillis() - start);
            return response;
        } catch (Exception e) {
            metrics.recordError(eventType, gateway, product, "server_error");
            throw e;
        }
    }
}
```

**Cardinality Protection:**
- Maximum 1,000 unique label combinations per metric
- Circuit breaker blocks new combinations at limit
- Warning at 80% threshold (800 combinations)
- Automatic alerting via `fraud_switch.metrics.cardinality.circuit_breaker` metric

**Performance Impact:**
- CPU overhead: <1% per core
- Memory overhead: 60-80 MB per pod (3-4% of 2GB allocation)
- Latency impact: 3 microseconds per request (0.003% of SLA)
- Histogram series: 1,107 (45% reduction via service-specific bucket optimization)

**Rollout Status:**
- Phase 1: Library development ✅ COMPLETE
- Phase 2: Pilot (Fraud Router) ✅ COMPLETE
- Phase 3: All 8 services ✅ COMPLETE
- Phase 4: Production validation ✅ COMPLETE

**Adoption:**
- All 8 services using library v1.0
- Load tested at 300 TPS (passed all gates)
- Integration tests validated
- Alert templates deployed
- Grafana dashboards deployed

---

### Alerting (Implemented v3.0)

**Alert Manager:** Prometheus AlertManager  
**Routing:** Critical/High → PagerDuty | Warning → Slack #fraud-switch-alerts  
**On-Call:** 24/7 rotation

**Alert Rules (10 production alerts):**

#### CRITICAL (PagerDuty, 5min ACK)

**1. HighTransactionFailureRate**
```yaml
alert: HighTransactionFailureRate
expr: |
  (
    sum(rate(fraud_switch_*_errors_total[5m])) by (service)
    /
    sum(rate(fraud_switch_*_requests_total[5m])) by (service)
  ) > 0.05
for: 2m
labels:
  severity: critical
  team: fraud-switch
annotations:
  summary: "{{ $labels.service }} error rate above 5%"
  description: "Error rate: {{ $value | humanizePercentage }}"
  runbook: "https://runbook.fraudswitch.com/high-error-rate"
```

**2. FraudSightLatencyP99Breach**
```yaml
alert: FraudSightLatencyP99Breach
expr: |
  histogram_quantile(0.99,
    sum(rate(fraud_switch_fraud_router_request_duration_seconds_bucket{product="FRAUD_SIGHT"}[5m])) by (le)
  ) > 0.1
for: 5m
labels:
  severity: critical
  team: fraud-switch
  product: fraud-sight
annotations:
  summary: "FraudSight p99 latency above 100ms SLA"
  description: "p99 latency: {{ $value | humanizeDuration }} (SLA: 100ms)"
  runbook: "https://runbook.fraudswitch.com/fraudsight-latency-breach"
```

**3. GuaranteedPaymentLatencyP99Breach**
```yaml
alert: GuaranteedPaymentLatencyP99Breach
expr: |
  histogram_quantile(0.99,
    sum(rate(fraud_switch_fraud_router_request_duration_seconds_bucket{product="GUARANTEED_PAYMENT"}[5m])) by (le)
  ) > 0.35
for: 5m
labels:
  severity: critical
  team: fraud-switch
  product: guaranteed-payment
annotations:
  summary: "GuaranteedPayment p99 latency above 350ms SLA"
  description: "p99 latency: {{ $value | humanizeDuration }} (SLA: 350ms)"
  runbook: "https://runbook.fraudswitch.com/guaranteed-payment-latency-breach"
```

**4. ProviderCompletelyDown**
```yaml
alert: ProviderCompletelyDown
expr: |
  sum(rate(fraud_switch_provider_calls_total{status="success"}[5m])) by (provider) == 0
  AND
  sum(rate(fraud_switch_provider_calls_total[5m])) by (provider) > 0
for: 2m
labels:
  severity: critical
  team: fraud-switch
annotations:
  summary: "{{ $labels.provider }} provider returning zero successful responses"
  description: "All requests to {{ $labels.provider }} are failing"
  runbook: "https://runbook.fraudswitch.com/provider-down"
```

**5. KafkaConsumerLagCritical**
```yaml
alert: KafkaConsumerLagCritical
expr: |
  fraud_switch_kafka_consumer_lag > 1000
for: 10m
labels:
  severity: critical
  team: fraud-switch
annotations:
  summary: "Kafka consumer lag critical for {{ $labels.topic }}"
  description: "Consumer lag: {{ $value }} messages (threshold: 1000)"
  runbook: "https://runbook.fraudswitch.com/kafka-lag-critical"
```

#### HIGH (PagerDuty, 15min ACK)

**6. ElevatedErrorRate**
```yaml
alert: ElevatedErrorRate
expr: |
  (
    sum(rate(fraud_switch_*_errors_total[5m])) by (service)
    /
    sum(rate(fraud_switch_*_requests_total[5m])) by (service)
  ) > 0.01
for: 10m
labels:
  severity: high
  team: fraud-switch
annotations:
  summary: "{{ $labels.service }} error rate above 1%"
  description: "Error rate: {{ $value | humanizePercentage }}"
  runbook: "https://runbook.fraudswitch.com/elevated-error-rate"
```

**7. ProviderDegradedPerformance**
```yaml
alert: ProviderDegradedPerformance
expr: |
  histogram_quantile(0.95,
    sum(rate(fraud_switch_provider_duration_seconds_bucket[5m])) by (provider, le)
  ) > (baseline * 1.5)
for: 10m
labels:
  severity: high
  team: fraud-switch
annotations:
  summary: "{{ $labels.provider }} performance degraded"
  description: "p95 latency 50% above baseline"
  runbook: "https://runbook.fraudswitch.com/provider-degraded"
```

**8. ComponentTimeoutRateHigh**
```yaml
alert: ComponentTimeoutRateHigh
expr: |
  (
    sum(rate(fraud_switch_*_component_calls_total{status="timeout"}[5m])) by (component)
    /
    sum(rate(fraud_switch_*_component_calls_total[5m])) by (component)
  ) > 0.02
for: 5m
labels:
  severity: high
  team: fraud-switch
annotations:
  summary: "{{ $labels.component }} timeout rate above 2%"
  description: "Timeout rate: {{ $value | humanizePercentage }}"
  runbook: "https://runbook.fraudswitch.com/component-timeout"
```

#### WARNING (Slack, 1hr ACK)

**9. KafkaConsumerLagWarning**
```yaml
alert: KafkaConsumerLagWarning
expr: |
  fraud_switch_kafka_consumer_lag > 500
for: 15m
labels:
  severity: warning
  team: fraud-switch
annotations:
  summary: "Kafka consumer lag warning for {{ $labels.topic }}"
  description: "Consumer lag: {{ $value }} messages (threshold: 500)"
  runbook: "https://runbook.fraudswitch.com/kafka-lag-warning"
```

**10. MetricsCardinalityWarning**
```yaml
alert: MetricsCardinalityWarning
expr: |
  fraud_switch_metrics_cardinality_warning > 0
for: 5m
labels:
  severity: warning
  team: platform
annotations:
  summary: "Metric {{ $labels.metric }} approaching cardinality limit"
  description: "Current: {{ $labels.current }}, Max: {{ $labels.max }}"
  runbook: "https://runbook.fraudswitch.com/cardinality-warning"
```

**Additional DEK Rotation Alerts:**
- **DEKRotationFailed**: No successful rotation for 2 hours (critical)
- **DEKAgeHigh**: DEK age >60 minutes (high)
- **DEKFallbackHigh**: >10% fallback rate for 10 minutes (warning)
- **DEKTableGrowthHigh**: >100 DEKs/day (anomaly detection)

**Runbooks:** All alerts include runbook links in annotations

---

### Dashboards (Implemented v3.0)

**Platform:** Grafana  
**Refresh Rate:** 30 seconds  
**Access Control:** Team-based (fraud-switch team)

#### 1. Service-Level Dashboard: "Fraud Switch - System Health"

**Panels:**
1. **Transaction Flow Overview:** 
   - Throughput (RPS) by service
   - Success rate gauge (target: >99%)
   - Decision breakdown (approve/decline/review) pie chart

2. **Latency Monitoring:**
   - p50/p95/p99 latency time series with SLA threshold lines
   - FraudSight SLA: 100ms (red line at p99)
   - GuaranteedPayment SLA: 350ms (red line at p99)
   - Heatmap of latency distribution

3. **Provider Health:**
   - Response time comparison (Ravelin vs Signifyd)
   - Error rate by provider (stacked area)
   - Availability gauge per provider (target: >99.9%)
   - Timeout rate per provider

4. **Kafka & Data Flow:**
   - Consumer lag by topic (bar chart)
   - Publish rate time series
   - Partition distribution heatmap
   - DLQ size gauge

5. **Infrastructure:**
   - Pod health matrix (green/yellow/red)
   - Connection pool utilization (Aurora, Redis)
   - Error rates by service (stacked area)
   - Memory/CPU utilization

6. **Active Alerts:**
   - Current firing alerts panel
   - Alert history (last 24 hours)

**Variables:**
- Region: US-Ohio, UK-London
- Service: All, Fraud Router, Rules Service, BIN Lookup, Adapters, Async Processor
- Time Range: 5m, 15m, 1h, 6h, 24h, 7d

#### 2. RED Metrics Dashboard: "Fraud Switch - RED Metrics"

**Panels:**
1. **Request Rate (RPS):**
   - PromQL: `sum(rate(fraud_switch_$service_requests_total[5m])) by (service)`
   - Visualization: Time series, multi-line

2. **Error Rate (%):**
   - PromQL: `(sum(rate(fraud_switch_$service_errors_total[5m])) by (service) / sum(rate(fraud_switch_$service_requests_total[5m])) by (service)) * 100`
   - Visualization: Time series with threshold at 1%

3. **Duration (p50/p95/p99):**
   - PromQL: `histogram_quantile(0.99, sum(rate(fraud_switch_$service_request_duration_seconds_bucket[5m])) by (service, le))`
   - Visualization: Time series, percentile bands

4. **Request Volume Heatmap:**
   - Breakdown by service, event_type, gateway

#### 3. Service-Specific Dashboards

**Fraud Router Dashboard:**
- Requests by Event Type (stacked area chart)
- Requests by Gateway (pie chart)
- Component Latency (p95 by component: Rules, BIN, Adapter)
- Decision Overrides time series
- SLA Compliance Gauge (p99 latency vs threshold)
- DEK Rotation Status (age, last rotation time)

**Provider Dashboard:**
- Provider Response Time Comparison (Ravelin vs Signifyd)
- Provider Error Rate Comparison
- Provider Availability (uptime %)
- Provider Timeout Rate
- Circuit Breaker State per provider

**Issuer Data-Share Dashboard:**
- Requests Sent by Issuer
- Success Rate by Issuer
- DLQ Size
- Circuit Breaker State by Issuer

**Location:** Grafana dashboards available at `src/main/resources/grafana-dashboards/` in metrics library

---

### Logging (ELK Stack)

**Log aggregation:**
- **Elasticsearch**: Centralized log storage
- **Logstash**: Log ingestion and parsing
- **Kibana**: Query and visualization

**Structured logging:**
- **Format**: JSON (for easy parsing)
- **Fields**: timestamp, service, level, trace_id, message, metadata
- **PII redaction**: Clear PAN, full credit card never logged (hashed PAN only)

**Retention:**
- **Hot storage**: 30 days in Elasticsearch
- **Cold storage**: Not yet implemented (needs discussion)

---

### Tracing (Instana → OpenTelemetry)

**Distributed tracing:**
- **Trace ID**: Propagated across all services (via HTTP headers)
- **Span**: Each service operation (e.g., "Fraud Router: call Rules Service")
- **Sampling**: 10% of requests (reduces overhead)

**Critical paths:**
- **Sync flow**: ETS → Fraud Router → Rules → BIN → Adapter → Provider → Response
- **Async flow**: Fraud Router → async.events → Async Processor → Adapter → Provider

---

### Known Gaps

**Observability (Remaining):**
- ~~Comprehensive metrics taxonomy~~ ✅ COMPLETED (v3.0)
- ~~Alert configuration~~ ✅ COMPLETED (v3.0)
- ~~Grafana dashboards~~ ✅ COMPLETED (v3.0)
- Standardize structured logging across all applications (IN PROGRESS)
- Implement proper PII redaction in logs (IN PROGRESS)
- Define cold storage strategy for logs (PLANNED)
- Implement synthetic monitors for proactive health checks (PLANNED)
- SLO-based alerting refinement (PLANNED)
- Fully utilize OpenTelemetry infrastructure (PLANNED)

**Technical Debt:**
- Memory leakage issues
- Excessive logging
- Pod restart investigation

---

## 11. Resilience & Scaling

### Circuit Breakers

**Pattern:**
- **Resilience4j** library for circuit breaker implementation
- **States**: CLOSED (normal), OPEN (failing), HALF_OPEN (testing recovery)

**Applied to:**
- **Fraud provider calls**: Ravelin, Signifyd
- **Rules Service calls**: Pre-call and post-call
- **BIN Lookup calls**
- **Issuer Data Service calls** (per-issuer circuit breakers)
- **DEK fallback** (v2.0): Database query fallback during rotation failures

**Configuration (per provider):**
```yaml
resilience4j.circuitbreaker:
  instances:
    ravelin:
      failureRateThreshold: 50        # Open after 50% failures
      waitDurationInOpenState: 30s    # Test after 30 seconds
      slidingWindowSize: 10           # Last 10 requests
    signifyd:
      failureRateThreshold: 50
      waitDurationInOpenState: 30s
      slidingWindowSize: 10
    dekFallback:
      failureRateThreshold: 50
      slowCallDurationThreshold: 100ms
      waitDurationInOpenState: 30s
      slidingWindowSize: 10
```

**Behavior:**
- **CLOSED → OPEN**: After 50% failure rate (5 out of 10 requests)
- **OPEN → HALF_OPEN**: After 30 seconds in OPEN state
- **HALF_OPEN → CLOSED**: If test requests succeed
- **HALF_OPEN → OPEN**: If test requests fail

### Retry Logic

**Applied to:**
- **Async events**: 3 retries with exponential backoff (1s, 5s, 15s)
- **Database queries**: 2 retries with 100ms delay
- **KMS calls**: 3 retries with exponential backoff (AWS SDK default)

**Not applied to:**
- **Synchronous fraud scoring**: No retries (latency impact too high)
- **Issuer data-share**: No retries (fire-and-forget pattern)

### Timeouts

**Service timeouts:**
- **Rules Service**: 50ms
- **BIN Lookup**: 100ms
- **Ravelin**: 800ms (gateway timeout)
- **Signifyd**: 1.5 seconds (gateway timeout)
- **Database queries**: 5 seconds
- **KMS calls**: 10 seconds

### Horizontal Pod Autoscaling (HPA)

**Scaling policy:**
- **Target CPU**: 70% utilization
- **Target memory**: 80% utilization
- **Min replicas**: 10 per service per region
- **Max replicas**: 60 per service per region

**Services:**
- **Fraud Router**: 60 pods (high traffic)
- **FraudSightAdapter**: 30 pods
- **GuaranteedPaymentAdapter**: 30 pods
- **Rules Service**: 20 pods
- **BIN Lookup Service**: 10 pods
- **Async Processor**: 20 pods
- **Tokenization Service**: 20 pods

### Regional Failover

**No cross-region failover:**
- US transactions must stay in US region (data locality)
- UK transactions must stay in UK region (data locality)

**Multi-AZ failover (within region):**
- **Aurora**: Automatic failover to standby (us-east-1 for US, eu-west-1 for UK)
- **MSK**: Automatic partition rebalancing across AZs
- **Redis**: Replica promotion if primary fails

### Readiness & Liveness Probes

**Readiness probe:**
- **Endpoint**: `/health/ready`
- **Checks**: Database connectivity, Kafka connectivity, DEK initialized (v2.0)
- **Failure**: Pod removed from load balancer (no traffic)

**Liveness probe:**
- **Endpoint**: `/health/live`
- **Checks**: Application process running
- **Failure**: Pod restarted by Kubernetes

---

## 12. Interfaces (APIs)

### External API (ETS)

**Endpoint:** `POST /v1/ext-transactions/events`

**Authentication:** API Key (`X-API-Key` header)

**Request:**
```json
{
  "eventType": "beforeAuthenticationSync",
  "gateway": "RAFT",
  "transactionId": "txn_123",
  "accountNumber": "4111111111111111",
  "amount": 100.00,
  "currency": "USD",
  "merchantId": "merchant_456"
}
```

**Response (Sync):**
```json
{
  "decision": "APPROVE",
  "score": 0.85,
  "provider": "Ravelin",
  "reasons": ["Low risk", "Known device"],
  "transactionId": "txn_123"
}
```

**Response (Async):**
```json
{
  "status": "ACCEPTED",
  "transactionId": "txn_123"
}
```

### Internal APIs

**Rules Service:**
- `POST /v1/rules/evaluate`

**BIN Lookup Service:**
- `GET /v1/bin/{bin}`

**Tokenization Service:**
- `POST /v1/pan/encrypt`
- `POST /v1/pan/decrypt` (Restricted)

---

## 13. Known Constraints / Not-in-Scope

### High-Priority (Next Phase)

**Observability:**
1. ~~Metrics & Alerting Strategy~~ ✅ COMPLETED (v3.0 - Centralized Library)
2. ~~Grafana Dashboards~~ ✅ COMPLETED (v3.0)
3. Structured Logging Standardization (IN PROGRESS)
4. PII Redaction in Logs (IN PROGRESS)
5. Cold Storage Strategy for Logs (PLANNED)
6. Synthetic Monitors (PLANNED)

**Resilience:**
1. Circuit Breaker Implementation (PLANNED per provider)
2. Exponential Backoff with Jitter (PLANNED)
3. Connection Pooling Optimization (Aurora/Redis) (PLANNED)
4. Fail-Open/Fail-Closed Policies (PLANNED)
5. Rate Limiting per Gateway (PLANNED)

**Data Store Optimization:**
1. BIN Lookup PostgreSQL Migration (PLANNED)
2. Rules Service PostgreSQL Migration (PLANNED)
3. Aurora Read Replica Utilization (PLANNED)
4. Custom HPA Metrics (PLANNED)

**Not-in-Scope:**
- Multi-region active-active (regional isolation required)
- Real-time merchant self-service UI (future phase)
- Provider fallback strategy (single provider per transaction)
- Dynamic provider routing based on performance

### Technical Debt

- **Memory leakage** issues
- **Excessive logging** (too much logging)
- **Pod restarts** (need to investigate root causes)

---

## Canonical Names Reference

Always use these canonical names for consistency:

**Services:**
- ETS (External Transaction Service)
- Fraud Router
- Rules Service
- BIN Lookup Service
- FraudSightAdapter
- GuaranteedPaymentAdapter
- Async Processor
- Tokenization Service
- Issuer Data Service

**Providers:**
- Ravelin
- Signifyd

**MSK Topics:**
- `fs.transactions`
- `fs.declines`
- `async.events`
- `pan.queue`
- `issuer.datashare.requests`
- `issuer.datashare.dlq`

**Infrastructure:**
- Kafka Connect
- S3
- CDP/Snowflake
- SFTP
- Encrypt/Decrypt API

**Internal Gateways:**
- RAFT
- VAP
- EXPRESS
- WPG
- ACCESS

**AWS Services (v2.0):**
- AWS Secrets Manager
- AWS KMS (Key Management Service)
- Aurora PostgreSQL
- DynamoDB
- ElastiCache/Redis
- MSK (Managed Streaming for Kafka)

**Metrics Library Components (v3.0):**
- fraud-switch-metrics-spring-boot-starter
- MetricNames (core constants)
- MetricLabels (canonical name constants)
- CardinalityEnforcer (safety component)
- RequestMetrics, ComponentMetrics, KafkaMetrics (common patterns)
- CustomMetricsBuilder (business metrics)

---

## Change Log

| Date | Section | Change Description |
|------|---------|-------------------|
| 2025-10-11 | Section 1 | System Purpose & NFRs confirmed |
| 2025-10-11 | Section 2 | Ingress & Auth confirmed |
| 2025-10-11 | Section 3 | Core Processing confirmed |
| 2025-10-11 | Section 4 | Rules & Decisions confirmed |
| 2025-10-11 | Section 5 | PAN/PCI Boundary confirmed |
| 2025-10-11 | Section 6 | Fraud Providers & Adapters confirmed |
| 2025-10-11 | Section 7 | Sync vs Async Flows confirmed |
| 2025-10-11 | Section 8 | Streaming & Data confirmed |
| 2025-10-11 | Section 9 | Data Stores confirmed |
| 2025-10-11 | Section 10 | Observability confirmed |
| 2025-10-11 | Section 11 | Resilience & Scaling confirmed |
| 2025-10-11 | Section 12 | Interfaces (APIs) confirmed |
| 2025-10-11 | Section 13 | Known Constraints / Not-in-Scope confirmed |
| 2025-10-11 | Issuer Data-Share | Integrated into Sections 1, 8, 10, 11, 13 |
| 2025-10-12 | Section 5 | DEK rotation strategy implemented and approved (v2.0) |
| 2025-10-12 | Section 5 | Removed "Known Gaps" (DEK rotation now implemented) |
| 2025-10-12 | Section 5 | Added comprehensive DEK Rotation Strategy section |
| 2025-10-12 | Section 5 | Updated PAN Persistence to include DEK ID |
| 2025-10-12 | Section 5 | Updated Encrypt/Decrypt API with DEK decryption steps |
| 2025-10-12 | Section 9 | Added dek_table and updated encrypted_pan schema |
| 2025-10-12 | Section 10 | Added DEK rotation metrics and alerts |
| 2025-10-12 | Section 11 | Added dekFallback circuit breaker configuration |
| **2025-10-14** | **Section 10** | **Centralized Metrics Library v1.0 integrated and deployed to all services** |
| **2025-10-14** | **Section 10** | **Added comprehensive metrics taxonomy (RED, Business, Infrastructure, Cardinality)** |
| **2025-10-14** | **Section 10** | **Added 10 production alert rules with PagerDuty/Slack routing** |
| **2025-10-14** | **Section 10** | **Added Grafana dashboards (System Health, RED Metrics, Service-Specific)** |
| **2025-10-14** | **Section 10** | **Added Centralized Metrics Library subsection with architecture details** |
| **2025-10-14** | **Section 10** | **Marked Metrics & Alerting as COMPLETE in Known Gaps** |
| **2025-10-14** | **Section 13** | **Updated High-Priority items (Metrics/Alerting completed)** |

---

**Document Version:** 3.0 (DEK Rotation + Centralized Metrics Library)  
**Last Updated:** October 14, 2025  
**Status:** ✅ All 13 Sections Confirmed + DEK Rotation + Metrics Library Deployed  
**Maintained By:** Architecture Team

---

*End of Canonical Architecture Document v3.0*