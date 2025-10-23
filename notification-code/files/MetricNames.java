package com.fraudswitch.metrics.core;

/**
 * Centralized metric name constants for Fraud Switch platform.
 * All metrics follow the naming convention: fraud_switch.{service}.{metric}
 * 
 * <p>This class provides compile-time validation and prevents typos in metric names.
 * Names are aligned with canonical architecture (canonical-architecture.md v3.0).
 * 
 * @version 1.0.0
 * @since 2025-10-14
 */
public final class MetricNames {

    private static final String PREFIX = "fraud_switch";

    // ==================== Common Metrics ====================
    
    public static final class Common {
        public static final String REQUESTS_TOTAL = PREFIX + ".requests_total";
        public static final String REQUEST_DURATION_MS = PREFIX + ".request_duration_ms";
        public static final String ERRORS_TOTAL = PREFIX + ".errors_total";
        public static final String CIRCUIT_BREAKER_STATE = PREFIX + ".circuit_breaker_state";
        public static final String CIRCUIT_BREAKER_CALLS_TOTAL = PREFIX + ".circuit_breaker_calls_total";
        public static final String RETRY_ATTEMPTS_TOTAL = PREFIX + ".retry_attempts_total";
        
        private Common() {}
    }

    // ==================== Fraud Router ====================
    
    public static final class FraudRouter {
        private static final String SERVICE = PREFIX + ".fraud_router";
        
        // Request metrics
        public static final String REQUESTS_TOTAL = SERVICE + ".requests_total";
        public static final String REQUEST_DURATION_MS = SERVICE + ".request_duration_ms";
        public static final String ERRORS_TOTAL = SERVICE + ".errors_total";
        
        // Routing metrics
        public static final String ROUTING_DECISIONS_TOTAL = SERVICE + ".routing_decisions_total";
        public static final String ROUTING_DURATION_MS = SERVICE + ".routing_duration_ms";
        public static final String FALLBACK_DECISIONS_TOTAL = SERVICE + ".fallback_decisions_total";
        
        // Queue metrics
        public static final String PAN_QUEUE_PUBLISH_TOTAL = SERVICE + ".pan_queue_publish_total";
        public static final String PAN_QUEUE_PUBLISH_ERRORS_TOTAL = SERVICE + ".pan_queue_publish_errors_total";
        
        // Parallel execution metrics
        public static final String PARALLEL_CALLS_TOTAL = SERVICE + ".parallel_calls_total";
        public static final String PARALLEL_CALL_DURATION_MS = SERVICE + ".parallel_call_duration_ms";
        public static final String PARALLEL_CALL_ERRORS_TOTAL = SERVICE + ".parallel_call_errors_total";
        
        private FraudRouter() {}
    }

    // ==================== Rules Service ====================
    
    public static final class RulesService {
        private static final String SERVICE = PREFIX + ".rules_service";
        
        public static final String REQUESTS_TOTAL = SERVICE + ".requests_total";
        public static final String REQUEST_DURATION_MS = SERVICE + ".request_duration_ms";
        public static final String ERRORS_TOTAL = SERVICE + ".errors_total";
        
        public static final String EVALUATIONS_TOTAL = SERVICE + ".evaluations_total";
        public static final String EVALUATION_DURATION_MS = SERVICE + ".evaluation_duration_ms";
        public static final String RULES_FIRED_TOTAL = SERVICE + ".rules_fired_total";
        public static final String CACHE_HITS_TOTAL = SERVICE + ".cache_hits_total";
        public static final String CACHE_MISSES_TOTAL = SERVICE + ".cache_misses_total";
        
        private RulesService() {}
    }

    // ==================== BIN Lookup Service ====================
    
    public static final class BinLookupService {
        private static final String SERVICE = PREFIX + ".bin_lookup_service";
        
        public static final String REQUESTS_TOTAL = SERVICE + ".requests_total";
        public static final String REQUEST_DURATION_MS = SERVICE + ".request_duration_ms";
        public static final String ERRORS_TOTAL = SERVICE + ".errors_total";
        
        public static final String LOOKUPS_TOTAL = SERVICE + ".lookups_total";
        public static final String LOOKUP_DURATION_MS = SERVICE + ".lookup_duration_ms";
        public static final String CACHE_HITS_TOTAL = SERVICE + ".cache_hits_total";
        public static final String CACHE_MISSES_TOTAL = SERVICE + ".cache_misses_total";
        public static final String CACHE_SIZE = SERVICE + ".cache_size";
        
        private BinLookupService() {}
    }

    // ==================== FraudSight Adapter ====================
    
    public static final class FraudSightAdapter {
        private static final String SERVICE = PREFIX + ".fraudsight_adapter";
        
        public static final String REQUESTS_TOTAL = SERVICE + ".requests_total";
        public static final String REQUEST_DURATION_MS = SERVICE + ".request_duration_ms";
        public static final String ERRORS_TOTAL = SERVICE + ".errors_total";
        
        public static final String RAVELIN_CALLS_TOTAL = SERVICE + ".ravelin_calls_total";
        public static final String RAVELIN_CALL_DURATION_MS = SERVICE + ".ravelin_call_duration_ms";
        public static final String RAVELIN_ERRORS_TOTAL = SERVICE + ".ravelin_errors_total";
        public static final String RAVELIN_DECISIONS_TOTAL = SERVICE + ".ravelin_decisions_total";
        
        public static final String KAFKA_PUBLISH_TOTAL = SERVICE + ".kafka_publish_total";
        public static final String KAFKA_PUBLISH_ERRORS_TOTAL = SERVICE + ".kafka_publish_errors_total";
        
        private FraudSightAdapter() {}
    }

    // ==================== GuaranteedPayment Adapter ====================
    
    public static final class GuaranteedPaymentAdapter {
        private static final String SERVICE = PREFIX + ".guaranteed_payment_adapter";
        
        public static final String REQUESTS_TOTAL = SERVICE + ".requests_total";
        public static final String REQUEST_DURATION_MS = SERVICE + ".request_duration_ms";
        public static final String ERRORS_TOTAL = SERVICE + ".errors_total";
        
        public static final String SIGNIFYD_CALLS_TOTAL = SERVICE + ".signifyd_calls_total";
        public static final String SIGNIFYD_CALL_DURATION_MS = SERVICE + ".signifyd_call_duration_ms";
        public static final String SIGNIFYD_ERRORS_TOTAL = SERVICE + ".signifyd_errors_total";
        public static final String SIGNIFYD_DECISIONS_TOTAL = SERVICE + ".signifyd_decisions_total";
        
        public static final String KAFKA_PUBLISH_TOTAL = SERVICE + ".kafka_publish_total";
        public static final String KAFKA_PUBLISH_ERRORS_TOTAL = SERVICE + ".kafka_publish_errors_total";
        
        private GuaranteedPaymentAdapter() {}
    }

    // ==================== Async Processor ====================
    
    public static final class AsyncProcessor {
        private static final String SERVICE = PREFIX + ".async_processor";
        
        public static final String REQUESTS_TOTAL = SERVICE + ".requests_total";
        public static final String REQUEST_DURATION_MS = SERVICE + ".request_duration_ms";
        public static final String ERRORS_TOTAL = SERVICE + ".errors_total";
        
        public static final String KAFKA_CONSUMED_TOTAL = SERVICE + ".kafka_consumed_total";
        public static final String KAFKA_CONSUMPTION_LAG = SERVICE + ".kafka_consumption_lag";
        public static final String KAFKA_CONSUMPTION_ERRORS_TOTAL = SERVICE + ".kafka_consumption_errors_total";
        
        public static final String ENRICHMENT_CALLS_TOTAL = SERVICE + ".enrichment_calls_total";
        public static final String ENRICHMENT_DURATION_MS = SERVICE + ".enrichment_duration_ms";
        
        private AsyncProcessor() {}
    }

    // ==================== Tokenization Service ====================
    
    public static final class TokenizationService {
        private static final String SERVICE = PREFIX + ".tokenization_service";
        
        public static final String REQUESTS_TOTAL = SERVICE + ".requests_total";
        public static final String REQUEST_DURATION_MS = SERVICE + ".request_duration_ms";
        public static final String ERRORS_TOTAL = SERVICE + ".errors_total";
        
        public static final String TOKENIZATION_CALLS_TOTAL = SERVICE + ".tokenization_calls_total";
        public static final String TOKENIZATION_DURATION_MS = SERVICE + ".tokenization_duration_ms";
        public static final String DETOKENIZATION_CALLS_TOTAL = SERVICE + ".detokenization_calls_total";
        public static final String DETOKENIZATION_DURATION_MS = SERVICE + ".detokenization_duration_ms";
        
        public static final String DEK_ROTATIONS_TOTAL = SERVICE + ".dek_rotations_total";
        public static final String ENCRYPTION_ERRORS_TOTAL = SERVICE + ".encryption_errors_total";
        
        public static final String KAFKA_CONSUMED_TOTAL = SERVICE + ".kafka_consumed_total";
        public static final String KAFKA_CONSUMPTION_ERRORS_TOTAL = SERVICE + ".kafka_consumption_errors_total";
        
        private TokenizationService() {}
    }

    // ==================== Issuer Data Service ====================
    
    public static final class IssuerDataService {
        private static final String SERVICE = PREFIX + ".issuer_data_service";
        
        public static final String REQUESTS_TOTAL = SERVICE + ".requests_total";
        public static final String REQUEST_DURATION_MS = SERVICE + ".request_duration_ms";
        public static final String ERRORS_TOTAL = SERVICE + ".errors_total";
        
        public static final String SFTP_UPLOADS_TOTAL = SERVICE + ".sftp_uploads_total";
        public static final String SFTP_UPLOAD_DURATION_MS = SERVICE + ".sftp_upload_duration_ms";
        public static final String SFTP_ERRORS_TOTAL = SERVICE + ".sftp_errors_total";
        
        public static final String FILE_GENERATION_DURATION_MS = SERVICE + ".file_generation_duration_ms";
        public static final String RECORDS_PROCESSED_TOTAL = SERVICE + ".records_processed_total";
        
        private IssuerDataService() {}
    }

    // ==================== Infrastructure Metrics ====================
    
    public static final class Infrastructure {
        private static final String INFRA = PREFIX + ".infra";
        
        public static final String DB_CONNECTION_POOL_SIZE = INFRA + ".db_connection_pool_size";
        public static final String DB_CONNECTION_POOL_ACTIVE = INFRA + ".db_connection_pool_active";
        public static final String DB_CONNECTION_POOL_IDLE = INFRA + ".db_connection_pool_idle";
        public static final String DB_CONNECTION_WAIT_TIME_MS = INFRA + ".db_connection_wait_time_ms";
        
        public static final String REDIS_CONNECTIONS_ACTIVE = INFRA + ".redis_connections_active";
        public static final String REDIS_COMMAND_DURATION_MS = INFRA + ".redis_command_duration_ms";
        
        public static final String KAFKA_PRODUCER_CONNECTIONS = INFRA + ".kafka_producer_connections";
        public static final String KAFKA_CONSUMER_LAG = INFRA + ".kafka_consumer_lag";
        
        private Infrastructure() {}
    }

    private MetricNames() {
        // Prevent instantiation
    }
}
