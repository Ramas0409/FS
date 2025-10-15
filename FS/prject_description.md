**Fraud Switch - Payment Fraud Detection Platform**

Ground truth architecture for payment fraud detection system routing transactions to external fraud providers (Ravelin/Signifyd) with real-time decisioning, PCI-compliant PAN encryption, and multi-region deployment.

**Primary Reference:** `canonical-architecture.md v3.0.md` - Always consult before proposing changes

**System Overview:**
- 8 microservices: ETS, Fraud Router, Rules Service, BIN Lookup Service, FraudSightAdapter, GuaranteedPaymentAdapter, Async Processor, Tokenization Service, Issuer Data Service
- Data stores: Aurora PostgreSQL, DynamoDB, ElastiCache/Redis, MSK (Kafka), S3
- Regions: US (Ohio), UK (London) - isolated, no cross-region failover
- PCI compliance: Clear PAN only in Tokenization Service; AES-256-GCM encryption with 30-min DEK rotation

**NFRs:**
- Latency: FraudSight p99 <100ms, GuaranteedPayment p99 <350ms
- Throughput: 300 TPS sync + 1000 TPS async per region
- Availability: 99.9% (multi-AZ within region)

**Key Features:**
- Centralized metrics library (v3.0) - deployed across all services
- RED metrics + custom business metrics with cardinality enforcement
- Issuer data-share with SFTP delivery and circuit breakers
- Rules-based decisioning with merchant-specific configuration
- Async enrichment pipeline for non-blocking fraud scoring

**Architecture Patterns:**
- Sync flow: ETS → Router → (Boarding + Rules + BIN parallel) → Adapter → Provider
- Async flow: Router → Adapter → async.events → Async Processor → Provider
- PAN tokenization: Router → pan.queue → Tokenization Service → Aurora
- Analytics: Adapters → fs.transactions/fs.declines → Kafka Connect → S3 → CDP → Snowflake

**Communication Guidelines:**
- Always use canonical names (ETS, Fraud Router, Ravelin, Signifyd, etc.)
- Reference specific sections from canonical-architecture.md when discussing current state
- Assume senior engineer/architect audience - be concise but thorough
- Include code examples, metrics, and observability strategy in proposals
- Call out NFR impacts, PCI considerations, and failure scenarios

**Active Work:**
See `technical_topics_tracker.md` for prioritized roadmap of improvements (circuit breakers, connection pooling, data store optimization, etc.)