# Fraud Switch - Canonical Architecture

**System Name:** Fraud Switch (Payment Fraud Detection Platform)  
**Version:** 2.0 (DEK Rotation Implemented)  
**Last Updated:** October 12, 2025  
**Status:** ✅ All 13 Sections Confirmed + DEK Rotation Approved  

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

Low-latency orchestration platform evaluating payment transactions for fraud risk:
- Routes per-merchant to **one provider**: **Ravelin** (via **FraudSightAdapter**) or **Signifyd** (via **GuaranteedPaymentAdapter**)
- No A/B testing, dual scoring, or automatic provider fallback
- Performs **rules evaluation** (can override provider decisions), **BIN enrichment**, and **issuer data-share** (fire-and-forget, merchant-specific contracts only)

### Event Capture (MSK Topics)

- **`fs.transactions`**: Request+response from fraud providers → **CDP/Snowflake** for fraud operations
- **`fs.declines`**: In-switch rule-based declines → **CDP/Snowflake** for fraud operations
- **`async.events`**: All non-sync transaction events (async, fraud, chargeback) → sent to fraud providers asynchronously
- **`pan.queue`**: Hashed PAN + encrypted PAN + DEK ID → persisted to DB for **Encrypt/Decrypt API** functionality
- **`issuer.datashare.requests`**: Transaction data shared with issuer banks (Capital One, Discover, TSYS) for fraud intelligence programs
- **`issuer.datashare.dlq`**: Failed issuer data-share events for manual review and reprocessing

### Non-Functional Requirements

**Latency (end-to-end, per product):**
- **FraudSight subscription**: p99 < 100 ms, p95 < 75 ms, avg < 60 ms
- **GuaranteedPayment subscription**: p99 < 350 ms, p95 < 300 ms, avg < 200 ms
- Measured at **ETS boundary** for external traffic (external merchants/gateways)
- Measured at **Fraud Router boundary** for internal traffic (RAFT, VAP, EXPRESS, WPG, ACCESS)

**Availability:**
- **99.999%** (includes scheduled maintenance windows)
- Applies to entire synchronous path (ETS/Fraud Router → provider → response)

**Throughput (per region):**
- **~300 TPS** synchronous transactions
- **~1000 TPS** asynchronous events

### Regional Architecture

- Two **independent** regional stacks: **US (Ohio)** and **UK (London)**
- Each region: **multi-AZ** deployment with complete service copies (ETS, Fraud Router, Rules Service, BIN Lookup, Adapters, MSK, data stores)
- **No cross-region failover or replication** (data locality requirements)

---

## 2. Ingress & Auth

### ETS (External Transaction Service)

**Purpose:**
- Single **HTTP entry point** for external merchants/gateways (not internal gateways)
- **TLS termination** with ALB (AWS Application Load Balancer)

**Responsibilities:**
- **Authentication**: API key + HMAC signature validation
- **Basic validation**: Schema validation, required fields
- **PAN handling**: Hashing and encryption (before forwarding to Fraud Router)
- **Routing**: Forwards to **Fraud Router** (all external traffic)

**Technology:** Spring Boot, ALB for TLS termination

### Internal Traffic (Gateways)

**Direct to Fraud Router:**
- Internal gateways (**RAFT**, **VAP**, **EXPRESS**, **WPG**, **ACCESS**) call **Fraud Router** directly
- **No ETS** involved (no external network hops)
- **Authentication**: mTLS (mutual TLS) + service mesh identity

**Latency advantage:**
- Skip ETS layer → lower latency for internal traffic

### Authentication Modes

**External merchants (via ETS):**
- **API key** (identifies merchant/integration)
- **HMAC signature** validation (ensures request integrity)

**Internal gateways (direct to Fraud Router):**
- **mTLS** (certificate-based authentication)
- **Service mesh identity** (Istio/Linkerd)

---

## 3. Core Processing

### Fraud Router

**Purpose:**
- Central orchestration layer receiving all traffic (external via ETS, internal direct)
- Coordinates fraud scoring, rules evaluation, BIN enrichment, issuer data-share

**Core Flow (Synchronous):**

1. **Receive request** from ETS or internal gateway
2. **Parallel execution:**
   - **Boarding check**: Validate merchant + product subscription (DynamoDB)
   - **Rules Service (pre-call)**: Apply merchant-specific rules before provider call
   - **BIN Lookup**: Enrich with card metadata (issuer, card type, country)
3. **Route to provider adapter**: FraudSightAdapter (Ravelin) or GuaranteedPaymentAdapter (Signifyd)
4. **Rules Service (post-call)**: Can override provider decision
5. **Publish events**: `fs.transactions`, `pan.queue`, `issuer.datashare.requests`
6. **Return response** (approve/decline/review + fraud score)

**Technology:** Spring Boot, reactive programming (WebFlux), Redis caching

---

## 4. Rules & Decisions

### Rules Service

**Purpose:**
- Merchant-specific rule evaluation (velocity checks, allow/block lists, risk thresholds)
- Can **override provider decisions** (e.g., force decline even if provider approves)

**Execution Points:**
- **Pre-call**: Before calling fraud provider (can block early)
- **Post-call**: After receiving provider response (can override decision)

**Rule Types:**
- **Velocity rules**: Transaction count/amount per time window (1 hour, 24 hours, 7 days)
- **Allow/block lists**: Email domains, IP addresses, BINs, countries
- **Risk thresholds**: Auto-decline if fraud score > threshold

**Storage:**
- **Rules definitions**: DynamoDB (merchant-specific configuration)
- **Velocity counters**: Redis (high-performance reads/writes)

**Performance:**
- **p95**: ~8ms, **p99**: ~10ms, **avg**: ~6ms
- **Timeout**: 50ms (circuit breaker if exceeded)

**Technology:** Spring Boot, Redis, DynamoDB

---

## 5. PAN/PCI Boundary

### PCI Boundary

- **Clear PAN** (unencrypted Primary Account Number) exists **only inside Fraud Router** (PCI segment)
- Only **Fraud Router** (and **ETS** for external traffic) have access to clear PAN in memory
- All other services only see **hashed PAN**

### PAN Handling (Entry Level)

- **External traffic**: hashing/encryption occurs at **ETS**
- **Internal traffic**: hashing/encryption occurs at **Fraud Router**
- **Hashing**: HMAC algorithm creates **hashed PAN** (deterministic) for external sharing
- **Encryption**: **KMS** (AWS Key Management Service) encrypts PAN using **DEK (Data Encryption Key)** for internal operations
- **Same hash algorithm** used for all purposes (fraud provider sharing, internal deduplication, velocity checks)

### Request Flow

- After hashing/encryption, **account number swapped** with hashed value in request before forwarding
- **Fraud providers** (**Ravelin**, **Signifyd**) receive **hashed PAN only**, never clear or encrypted PAN

### PAN Persistence

- **Hashed PAN + encrypted PAN + DEK ID** published to `pan.queue` topic (encrypted at rest and in transit)
- **Tokenization Service** consumes from `pan.queue`:
  - Checks if hashed PAN already exists in **Aurora PostgreSQL**
  - If **absent**: insert new record (hashed PAN, encrypted PAN, DEK ID, `last_seen_date`)
  - If **present**: update only `last_seen_date` = current date (no duplicate insert)
- **DEK Rotation**: Encryption uses current in-memory DEK (rotated every 30 minutes)
- **Data retention**: Delete hashed PANs not seen for **6 months** (based on `last_seen_date`)
- **DEK cleanup**: Delete orphaned DEKs (no associated PANs) after 7-day grace period

### Encrypt/Decrypt API

- Provides **controlled access** to retrieve encrypted PAN when authorized
- **Decryption process**:
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

### Overview

- Two fraud providers: **Ravelin** (FraudSight product) and **Signifyd** (GuaranteedPayment product)
- **One provider per merchant** (no A/B testing or dual scoring)
- Provider selection based on merchant's product subscription (stored in DynamoDB)

### FraudSightAdapter (Ravelin)

**Characteristics:**
- **Lower latency**: p99 <100ms, p95 <80ms, avg ~20ms
- **Higher throughput**: Handles more TPS
- **Synchronous-first**: Most scoring happens synchronously

**API:**
- **HTTP/REST** (synchronous)
- **Webhook** (asynchronous event notifications from Ravelin)

**Timeout:**
- **Gateway timeout**: 800ms
- **Circuit breaker**: Opens after 5 consecutive timeouts

### GuaranteedPaymentAdapter (Signifyd)

**Characteristics:**
- **Higher latency**: p99 ~350ms, p95 ~300ms, avg ~250ms
- **More comprehensive**: Includes chargeback guarantee (financial liability shift)
- **Asynchronous-first**: Initial score, then enrichment

**API:**
- **HTTP/REST** (synchronous)
- **Webhook** (asynchronous case updates from Signifyd)

**Timeout:**
- **Gateway timeout**: 1.5 seconds
- **Circuit breaker**: Opens after 5 consecutive timeouts

### Adapter Pattern

**Common Interface:**
```java
interface FraudProviderAdapter {
    FraudResponse scoreSynchronous(Transaction tx);
    void sendAsynchronous(TransactionEvent event);
}
```

**Responsibilities:**
- **Protocol translation**: Convert internal format ↔ provider-specific format
- **Error handling**: Retry logic, circuit breaker, fallback responses
- **Event publishing**: Write to `fs.transactions`, `async.events` topics
- **Webhook handling**: Process provider notifications

**Technology:** Spring Boot, Resilience4j (circuit breaker, retry), WebClient (reactive HTTP)

---

## 7. Sync vs Async Flows

### Synchronous Flow (Blocking)

**Use Cases:**
- **beforeAuthenticationSync**: Pre-auth fraud check (cardholder not yet charged)
- **beforeAuthorizationSync**: Pre-authorization fraud check (payment authorization pending)
- **afterAuthorizationSync**: Post-authorization fraud check (payment authorized)

**Characteristics:**
- **Blocking**: Fraud Router waits for provider response
- **Low latency required**: Must meet p99 SLAs (100ms or 350ms)
- **Critical path**: Impacts customer checkout experience

**Flow:**
```
Request → Fraud Router → (Rules + BIN parallel) → Adapter → Provider → Response → Fraud Router → ETS/Gateway → Response
```

### Asynchronous Flow (Non-blocking)

**Use Cases:**
- **afterAuthenticationAsync**: Post-auth enrichment (non-critical)
- **afterAuthorizationAsync**: Post-authorization enrichment (non-critical)
- **confirmedFraud**: Merchant confirms fraud (e.g., from internal fraud team)
- **confirmedChargeback**: Chargeback notification from payment processor

**Characteristics:**
- **Non-blocking**: Fraud Router responds immediately (HTTP 204)
- **No latency SLA**: Enrichment happens in background
- **Best-effort delivery**: Published to `async.events` topic

**Flow:**
```
Request → Fraud Router → 204 No Content → (publish to async.events) → Async Processor → Adapter → Provider
```

### Event Handling

**Async Processor:**
- Consumes from `async.events` topic (Kafka)
- Routes to appropriate adapter based on merchant's provider
- **Retry logic**: 3 attempts with exponential backoff (1s, 5s, 15s)
- **DLQ**: Failed events after 3 retries → manual review

---

## 8. Streaming & Data

### MSK (Managed Streaming for Apache Kafka)

**Purpose:**
- Event streaming backbone for async flows, analytics, PAN persistence, issuer data-share

**Topics:**

| Topic | Purpose | Consumers | Retention |
|-------|---------|-----------|-----------|
| `fs.transactions` | Provider request+response | Kafka Connect → S3 → CDP → Snowflake | 7 days |
| `fs.declines` | In-switch rule-based declines | Kafka Connect → S3 → CDP → Snowflake | 7 days |
| `async.events` | Async transaction events | Async Processor → Adapters → Providers | 7 days |
| `pan.queue` | Hashed+encrypted PAN | Tokenization Service → Aurora | 7 days |
| `issuer.datashare.requests` | Issuer fraud intelligence | Issuer Data Service → External APIs | 7 days |
| `issuer.datashare.dlq` | Failed issuer events | Manual review/reprocessing | 30 days |

**Configuration:**
- **Partitions**: 25 per topic (supports high throughput)
- **Replication factor**: 3 (multi-AZ durability)
- **Retention**: 7 days (except DLQ: 30 days)
- **Compression**: LZ4 (balance speed/compression ratio)

### Kafka Connect

**Purpose:**
- Stream `fs.transactions` and `fs.declines` to **S3** for analytics pipeline

**Configuration:**
- **S3 Sink Connector**: Batches events into Parquet files
- **Flush interval**: 60 seconds or 10,000 records (whichever first)
- **Partitioning**: By date (`year=YYYY/month=MM/day=DD/hour=HH`)

**Downstream:**
- **CDP** (Customer Data Platform) ingests from S3
- **Snowflake** queries via external tables (for fraud operations analytics)

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

### Metrics (Instana → OpenTelemetry migration in progress)

**Service-level metrics:**
- **Latency**: p50, p95, p99, p999 per service, per endpoint
- **Throughput**: Requests per second (RPS)
- **Error rate**: 4xx, 5xx percentages
- **Availability**: Uptime percentage

**Business metrics:**
- **Approval rate**: % of transactions approved by provider
- **Decline rate**: % of transactions declined (by provider or rules)
- **Fraud score distribution**: Histogram of scores by provider
- **Provider latency**: Ravelin vs Signifyd latency comparison

**DEK Rotation metrics (v2.0):**
- `fraud_switch.dek.age_minutes`: Age of current DEK (alert if >60 minutes)
- `fraud_switch.dek.rotation.success`: Counter for successful rotations
- `fraud_switch.dek.rotation.failure`: Counter for failed rotations
- `fraud_switch.dek.fallback.attempts`: Circuit breaker fallback attempts
- `fraud_switch.dek.fallback.success`: Successful fallback to DB
- `fraud_switch.dek.table_size`: Number of DEKs in dek_table

**Issuer Data-Share metrics:**
- `issuer.datashare.requests.sent`: Total requests sent per issuer
- `issuer.datashare.requests.success`: Successful deliveries per issuer
- `issuer.datashare.requests.failed`: Failed deliveries per issuer
- `issuer.datashare.latency`: p95, p99 per issuer
- `issuer.datashare.dlq.size`: Number of messages in DLQ
- `issuer.datashare.circuit_breaker.state`: CLOSED, OPEN, HALF_OPEN per issuer

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

### Tracing (Instana → OpenTelemetry)

**Distributed tracing:**
- **Trace ID**: Propagated across all services (via HTTP headers)
- **Span**: Each service operation (e.g., "Fraud Router: call Rules Service")
- **Sampling**: 10% of requests (reduces overhead)

**Critical paths:**
- **Sync flow**: ETS → Fraud Router → Rules → BIN → Adapter → Provider → Response
- **Async flow**: Fraud Router → async.events → Async Processor → Adapter → Provider

### Known Gaps

**Metrics:**
- **Business metrics finalization**: Need comprehensive definitions for fraud operations
- **SLO-based alerting**: Not yet implemented; requires understanding and proper enforcement

**Dashboards & Alerting:**
- **Dashboards**: Need service-level, merchant-level, and issuer-level views
- **SLO-based alerting**: Need more discussion and suggestions
- **No alert configuration yet** (no thresholds, routing, severity levels defined)
- **DEK Rotation alerts** (v2.0):
  - DEKRotationFailed: No successful rotation for 2 hours (critical)
  - DEKAgeHigh: DEK age >60 minutes (high)
  - DEKFallbackHigh: >10% fallback rate for 10 minutes (warning)
  - DEKTableGrowthHigh: >100 DEKs/day (anomaly detection)

**Synthetic Monitoring:**
- **No synthetic monitors or health checks** for end-to-end path testing

**Known Gaps (High Priority):**
- Finalize comprehensive **business metrics** for monitoring
- Configure **alerting** with thresholds and routing (PagerDuty, Slack, etc.)
- Standardize **structured logging** across all applications
- Implement proper **PII redaction** in logs
- Define **cold storage strategy** for logs (retention, migration, search)
- Implement **synthetic monitors** for proactive health checks
- Fully utilize **OpenTelemetry** infrastructure already in place

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

### External APIs (for merchants/gateways)

**ETS API:**
- **POST /v1/fraud/score**: Synchronous fraud scoring
- **POST /v1/fraud/async**: Asynchronous event submission
- **Authentication**: API key + HMAC signature

**Encrypt/Decrypt API:**
- **POST /v1/pan/decrypt**: Decrypt PAN by hashed PAN (requires authorization)
- **Authentication**: OAuth 2.0 (internal services only)
- **Audit**: All calls logged (who, when, why)

### Internal APIs (service-to-service)

**Fraud Router:**
- Called by: ETS, internal gateways (RAFT, VAP, EXPRESS, WPG, ACCESS)
- Calls: Rules Service, BIN Lookup, Adapters

**Rules Service:**
- **POST /v1/rules/evaluate**: Evaluate pre-call or post-call rules
- **Authentication**: mTLS

**BIN Lookup Service:**
- **GET /v1/bin/{bin}**: Retrieve card metadata by BIN
- **Authentication**: mTLS

**Adapters:**
- **POST /v1/fraud/score**: Score transaction with provider
- **POST /v1/fraud/async**: Send async event to provider
- **Authentication**: mTLS

### Webhook Endpoints (provider callbacks)

**FraudSightAdapter webhook:**
- **POST /webhooks/ravelin**: Receive async notifications from Ravelin
- **Authentication**: Signature validation (HMAC)

**GuaranteedPaymentAdapter webhook:**
- **POST /webhooks/signifyd**: Receive case updates from Signifyd
- **Authentication**: Signature validation (HMAC)

---

## 13. Known Constraints / Not-in-Scope

### Regional Constraints

- **No cross-region failover**: US data cannot go to UK (and vice versa) due to data locality requirements
- **No cross-region replication**: Each region operates independently
- **HMAC key replication** (v2.0): AWS Secrets Manager automatically replicates HMAC key from US to UK

### Provider Constraints

- **One provider per merchant**: No A/B testing, dual scoring, or automatic fallback
- **No provider orchestration**: Fraud Router routes to exactly one provider based on merchant configuration
- **Provider-specific timeouts**: Ravelin (800ms), Signifyd (1.5s)

### Issuer Data-Share Constraints

- **Fire-and-forget**: No acknowledgment or retry guarantees
- **Best-effort delivery**: Failed events go to DLQ for manual review
- **Merchant-specific contracts**: Not all merchants participate in issuer programs
- **Per-issuer circuit breakers**: Prevent cascading failures if issuer API is down

### Operational Gaps

- **Metrics and alerts** configuration (highest priority)
- **Grafana dashboards** development
- **Cold storage for logs**: 30-day hot storage only

### Highest Priority for Next Phase

- **Metrics and alerts** configuration
- **Grafana dashboards** development (including DEK rotation and issuer data-share dashboards)

### Explicitly Out of Scope

- Payment processing
- Settlement
- Dispute resolution
- **Fraud Switch scope**: fraud scoring/routing to appropriate provider only

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
| **2025-10-12** | **Section 5** | **DEK rotation strategy implemented and approved (v2.0)** |
| **2025-10-12** | **Section 5** | **Removed "Known Gaps" (DEK rotation now implemented)** |
| **2025-10-12** | **Section 5** | **Added comprehensive DEK Rotation Strategy section** |
| **2025-10-12** | **Section 5** | **Updated PAN Persistence to include DEK ID** |
| **2025-10-12** | **Section 5** | **Updated Encrypt/Decrypt API with DEK decryption steps** |
| **2025-10-12** | **Section 9** | **Added dek_table and updated encrypted_pan schema** |
| **2025-10-12** | **Section 10** | **Added DEK rotation metrics and alerts** |
| **2025-10-12** | **Section 11** | **Added dekFallback circuit breaker configuration** |

---

**Document Version:** 2.0 (DEK Rotation Implemented)  
**Last Updated:** October 12, 2025  
**Status:** ✅ All 13 Sections Confirmed + DEK Rotation Approved  
**Maintained By:** Architecture Team

---

*End of Canonical Architecture Document v2.0*