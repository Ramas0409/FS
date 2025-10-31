package com.fraudswitch.metrics.core;

/**
 * Centralized metric label constants for Fraud Switch platform.
 * Uses canonical names from architecture documentation to ensure consistency.
 * 
 * <p>Label naming follows Prometheus conventions:
 * - snake_case for label names
 * - Descriptive and concise
 * - No spaces or special characters
 * 
 * @version 1.0.0
 * @since 2025-10-14
 */
public final class MetricLabels {

    // ==================== Common Labels ====================
    
    /**
     * Standard labels used across all services
     */
    public static final class Common {
        public static final String SERVICE = "service";
        public static final String REGION = "region";
        public static final String ENVIRONMENT = "environment";
        public static final String METHOD = "method";
        public static final String STATUS = "status";
        public static final String ERROR_TYPE = "error_type";
        public static final String OUTCOME = "outcome";
        
        private Common() {}
    }

    // ==================== Request Labels ====================
    
    public static final class Request {
        public static final String EVENT_TYPE = "event_type";
        public static final String GATEWAY = "gateway";
        public static final String PRODUCT = "product";
        public static final String PAYMENT_METHOD = "payment_method";
        public static final String MERCHANT_ID = "merchant_id";
        public static final String ENDPOINT = "endpoint";
        
        private Request() {}
    }

    // ==================== Provider Labels ====================
    
    /**
     * Canonical provider names (from canonical-architecture.md)
     */
    public static final class Provider {
        public static final String PROVIDER = "provider";
        
        // Provider values
        public static final String RAVELIN = "Ravelin";
        public static final String SIGNIFYD = "Signifyd";
        
        private Provider() {}
    }

    // ==================== Decision Labels ====================
    
    public static final class Decision {
        public static final String DECISION = "decision";
        public static final String CONFIDENCE_SCORE = "confidence_score";
        public static final String RULE_NAME = "rule_name";
        public static final String ROUTING_STRATEGY = "routing_strategy";
        
        // Decision values
        public static final String ACCEPT = "ACCEPT";
        public static final String DECLINE = "DECLINE";
        public static final String REVIEW = "REVIEW";
        public static final String ERROR = "ERROR";
        
        private Decision() {}
    }

    // ==================== Kafka Labels ====================
    
    public static final class Kafka {
        public static final String TOPIC = "topic";
        public static final String PARTITION = "partition";
        public static final String CONSUMER_GROUP = "consumer_group";
        public static final String OPERATION = "operation";
        
        // Canonical topic names
        public static final String TOPIC_PAN_QUEUE = "pan.queue";
        public static final String TOPIC_ASYNC_EVENTS = "async.events";
        public static final String TOPIC_FS_TRANSACTIONS = "fs.transactions";
        public static final String TOPIC_FS_DECLINES = "fs.declines";
        public static final String TOPIC_GP_TRANSACTIONS = "gp.transactions";
        
        // Operations
        public static final String OP_PUBLISH = "publish";
        public static final String OP_CONSUME = "consume";
        
        private Kafka() {}
    }

    // ==================== Circuit Breaker Labels ====================
    
    public static final class CircuitBreaker {
        public static final String CIRCUIT_BREAKER_NAME = "circuit_breaker_name";
        public static final String STATE = "state";
        public static final String CALL_TYPE = "call_type";
        
        // States
        public static final String STATE_CLOSED = "CLOSED";
        public static final String STATE_OPEN = "OPEN";
        public static final String STATE_HALF_OPEN = "HALF_OPEN";
        
        // Call types
        public static final String CALL_SUCCESS = "SUCCESS";
        public static final String CALL_FAILURE = "FAILURE";
        public static final String CALL_TIMEOUT = "TIMEOUT";
        
        private CircuitBreaker() {}
    }

    // ==================== Database Labels ====================
    
    public static final class Database {
        public static final String DB_NAME = "db_name";
        public static final String DB_OPERATION = "db_operation";
        public static final String TABLE_NAME = "table_name";
        public static final String POOL_NAME = "pool_name";
        
        // Operations
        public static final String OP_SELECT = "SELECT";
        public static final String OP_INSERT = "INSERT";
        public static final String OP_UPDATE = "UPDATE";
        public static final String OP_DELETE = "DELETE";
        
        private Database() {}
    }

    // ==================== Cache Labels ====================
    
    public static final class Cache {
        public static final String CACHE_NAME = "cache_name";
        public static final String CACHE_OPERATION = "cache_operation";
        public static final String HIT_TYPE = "hit_type";
        
        // Operations
        public static final String OP_GET = "get";
        public static final String OP_PUT = "put";
        public static final String OP_EVICT = "evict";
        
        // Hit types
        public static final String HIT = "hit";
        public static final String MISS = "miss";
        
        private Cache() {}
    }

    // ==================== HTTP Labels ====================
    
    public static final class Http {
        public static final String HTTP_METHOD = "http_method";
        public static final String HTTP_STATUS = "http_status";
        public static final String HTTP_PATH = "http_path";
        public static final String CLIENT_IP = "client_ip";
        
        private Http() {}
    }

    // ==================== Tokenization Labels ====================
    
    public static final class Tokenization {
        public static final String OPERATION = "operation";
        public static final String ENCRYPTION_ALGORITHM = "encryption_algorithm";
        public static final String KEY_VERSION = "key_version";
        
        // Operations
        public static final String OP_TOKENIZE = "tokenize";
        public static final String OP_DETOKENIZE = "detokenize";
        public static final String OP_DEK_ROTATION = "dek_rotation";
        
        // Algorithm
        public static final String ALGO_AES_256_GCM = "AES-256-GCM";
        
        private Tokenization() {}
    }

    // ==================== SFTP Labels ====================
    
    public static final class Sftp {
        public static final String ISSUER = "issuer";
        public static final String FILE_TYPE = "file_type";
        public static final String OPERATION = "operation";
        
        // Operations
        public static final String OP_UPLOAD = "upload";
        public static final String OP_DOWNLOAD = "download";
        public static final String OP_LIST = "list";
        
        private Sftp() {}
    }

    // ==================== Rules Engine Labels ====================
    
    public static final class Rules {
        public static final String RULE_SET = "rule_set";
        public static final String RULE_NAME = "rule_name";
        public static final String EVALUATION_TYPE = "evaluation_type";
        public static final String RULE_RESULT = "rule_result";
        
        // Evaluation types
        public static final String EVAL_PRE_AUTH = "pre_auth";
        public static final String EVAL_POST_AUTH = "post_auth";
        
        // Results
        public static final String RESULT_PASS = "pass";
        public static final String RESULT_FAIL = "fail";
        
        private Rules() {}
    }

    // ==================== BIN Lookup Labels ====================
    
    public static final class BinLookup {
        public static final String CARD_NETWORK = "card_network";
        public static final String CARD_TYPE = "card_type";
        public static final String ISSUER_COUNTRY = "issuer_country";
        public static final String LOOKUP_SOURCE = "lookup_source";
        
        // Networks (canonical names)
        public static final String NETWORK_VISA = "Visa";
        public static final String NETWORK_MASTERCARD = "Mastercard";
        public static final String NETWORK_AMEX = "Amex";
        public static final String NETWORK_DISCOVER = "Discover";
        
        // Card types
        public static final String TYPE_CREDIT = "credit";
        public static final String TYPE_DEBIT = "debit";
        public static final String TYPE_PREPAID = "prepaid";
        
        // Sources
        public static final String SOURCE_CACHE = "cache";
        public static final String SOURCE_DATABASE = "database";
        public static final String SOURCE_EXTERNAL = "external";
        
        private BinLookup() {}
    }

    private MetricLabels() {
        // Prevent instantiation
    }
}
