# PAN Tokenization, Detokenization & Hashing - Complete Solution

**Project:** Fraud Switch - Payment Fraud Detection Platform  
**Version:** 2.0  
**Last Updated:** October 16, 2025  
**Status:** âœ… Ready for Implementation  
**Audience:** Development Team

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Architecture Overview](#2-architecture-overview)
3. [HMAC Key Management (Secrets Manager)](#3-hmac-key-management-secrets-manager)
4. [DEK Management (KMS)](#4-dek-management-kms)
5. [PAN Hashing Implementation](#5-pan-hashing-implementation)
6. [PAN Encryption Implementation](#6-pan-encryption-implementation)
7. [PAN Decryption Implementation](#7-pan-decryption-implementation)
8. [Database Schema](#8-database-schema)
9. [Service Implementation Guide](#9-service-implementation-guide)
10. [Multi-Region Configuration](#10-multi-region-configuration)
11. [IAM Policies & Security](#11-iam-policies--security)
12. [Error Handling & Resilience](#12-error-handling--resilience)
13. [Testing Strategy](#13-testing-strategy)
14. [Operational Procedures](#14-operational-procedures)

---

## 1. Executive Summary

### Purpose

This document provides complete implementation details for PAN (Primary Account Number) tokenization, detokenization, and hashing in the Fraud Switch platform. It covers:

- **PAN Hashing**: HMAC-SHA256 hashing for generating consistent identifiers (`hpan`)
- **PAN Encryption**: AES-256-GCM encryption with rotated Data Encryption Keys (DEKs)
- **PAN Decryption**: Secure retrieval of clear PAN for authorized use cases
- **Key Management**: AWS Secrets Manager (HMAC) and AWS KMS (DEK) integration

### Key Components

| Component | Technology | Purpose |
|-----------|-----------|---------|
| **HMAC Key Storage** | AWS Secrets Manager | Stores 256-bit HMAC key with automatic US→UK replication |
| **DEK Encryption** | AWS KMS | Generates and encrypts DEKs using regional master keys |
| **DEK Storage** | Aurora PostgreSQL | Stores encrypted DEKs with 30-minute rotation |
| **PAN Storage** | Aurora PostgreSQL | Stores encrypted PANs indexed by hashed PAN |
| **Fraud Router** | Spring Boot | Hashes PANs, rotates DEKs, publishes to Kafka |
| **Tokenization Service** | Spring Boot | Encrypts PANs, stores in database |

### Security Properties

✅ **Clear PAN never stored in database** - Only encrypted ciphertext  
✅ **Plaintext DEK never stored** - Only exists in pod memory  
✅ **30-minute DEK rotation** - Limits blast radius to 30-minute window  
✅ **Regional isolation** - US and UK use separate KMS master keys  
✅ **HMAC key never rotates** - Ensures consistent hashing across regions  
✅ **Complete audit trail** - All decrypt operations logged  

### Performance Targets

- **Hashing latency**: <1ms (in-memory operation)
- **Encryption latency**: <5ms (in-memory DEK + AES-256-GCM)
- **Decryption latency**: +20-70ms (1 DB query + KMS decrypt)
- **DEK rotation**: 48 DEKs/day per region (1 every 30 minutes)

---

## 2. Architecture Overview

### Component Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                         Fraud Router (60 pods)                  │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────────┐  │
│  │ HMAC Manager │  │  DEK Manager │  │ PAN Hash/Encrypt     │  │
│  │ (Load once)  │  │ (Rotate 30m) │  │ (Per transaction)    │  │
│  └──────┬───────┘  └──────┬───────┘  └──────────┬──────────┘  │
└─────────┼──────────────────┼─────────────────────┼──────────────┘
          │                  │                     │
          │                  │                     └──────────┐
          │                  │                                │
┌─────────▼────────┐ ┌───────▼────────┐          ┌───────────▼─────────┐
│ AWS Secrets      │ │   AWS KMS      │          │   Kafka MSK         │
│ Manager          │ │                │          │   Topic: pan.queue  │
│                  │ │ GenerateDataKey│          │   {hpan, pan}       │
│ HMAC Key (256b)  │ │ Decrypt        │          └──────────┬──────────┘
│ US → UK replicate│ │                │                     │
└──────────────────┘ └────────────────┘          ┌──────────▼──────────┐
                                                  │ Tokenization Service│
                                                  │ (20 pods)           │
                                                  │                     │
                                                  │ Encrypt & Store PAN │
                                                  └──────────┬──────────┘
                                                             │
                                         ┌───────────────────▼───────────────────┐
                                         │      Aurora PostgreSQL (Multi-AZ)     │
                                         │                                       │
                                         │  ┌─────────────┐  ┌──────────────┐  │
                                         │  │  dek_table  │  │encrypted_pan │  │
                                         │  │             │  │              │  │
                                         │  │ dek_id (PK) │  │ hpan (PK)    │  │
                                         │  │ dek_enc     │  │ pan_ct       │  │
                                         │  │ rotation_lock│ │ dek_id (FK)  │  │
                                         │  └─────────────┘  └──────────────┘  │
                                         └───────────────────────────────────────┘
```

### Data Flow

**1. PAN Hashing (Fraud Router)**
```
Clear PAN → HMAC-SHA256(pan, hmac_key) → hpan (64 hex chars)
```

**2. PAN Publishing (Fraud Router → Kafka)**
```
{hpan: "abc123...", pan: "4111111111111111"} → pan.queue topic
```

**3. PAN Encryption (Tokenization Service)**
```
Clear PAN → AES-256-GCM(pan, dek_plaintext) → pan_ct (encrypted bytes)
Store: {hpan, pan_ct, dek_id} → encrypted_pan table
```

**4. PAN Decryption (Restricted API)**
```
hpan → Query encrypted_pan → {pan_ct, dek_id}
dek_id → Query dek_table → dek_enc
dek_enc → KMS Decrypt → dek_plaintext
{pan_ct, dek_plaintext} → AES-256-GCM Decrypt → Clear PAN
```

---

## 3. HMAC Key Management (Secrets Manager)

### Overview

The HMAC key is a 256-bit symmetric key used to hash PANs. It **never rotates** to ensure consistent hashing across all regions and time periods.

### Key Characteristics

- **Size**: 256 bits (32 bytes)
- **Algorithm**: HMAC-SHA256
- **Rotation**: Never (fraud provider and analytics requirement)
- **Replication**: Automatic US → UK via AWS Secrets Manager
- **Access**: Fraud Router only (via IAM policy)

### Setup Instructions

#### Step 1: Generate HMAC Key

```bash
# Generate 256-bit random key
openssl rand -out hmac-key.bin 32

# Verify size (should output: 32)
wc -c < hmac-key.bin
```

#### Step 2: Create Secret in AWS (US Primary)

```bash
# Create secret with automatic replication to UK
aws secretsmanager create-secret \
  --name fraud-switch/hmac-key-global \
  --description "HMAC key for PAN hashing (never rotates)" \
  --secret-binary fileb://hmac-key.bin \
  --replica-regions Region=eu-west-2 \
  --region us-east-2 \
  --tags Key=Service,Value=FraudSwitch \
         Key=Component,Value=FraudRouter \
         Key=Environment,Value=Production
```

#### Step 3: Verify Replication

```bash
# Check replication status
aws secretsmanager describe-secret \
  --secret-id fraud-switch/hmac-key-global \
  --region us-east-2 \
  --query 'ReplicationStatus'

# Expected output:
# [
#   {
#     "Region": "eu-west-2",
#     "Status": "InSync",
#     "LastAccessedDate": "2025-10-16T..."
#   }
# ]
```

#### Step 4: Test Read from UK Region

```bash
# Read from UK replica
aws secretsmanager get-secret-value \
  --secret-id fraud-switch/hmac-key-global \
  --region eu-west-2 \
  --query 'SecretBinary' \
  --output text | base64 -d | wc -c

# Should output: 32
```

### Application Configuration

**application.yml (US Region)**
```yaml
aws:
  secretsManager:
    hmacKeyName: "fraud-switch/hmac-key-global"
    region: us-east-2
```

**application.yml (UK Region)**
```yaml
aws:
  secretsManager:
    hmacKeyName: "fraud-switch/hmac-key-global"
    region: eu-west-2
```

### Java Implementation (Fraud Router)

```java
package com.fraudswitch.router.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Slf4j
@Component
public class HMACManager {
    
    private final SecretsManagerClient secretsManagerClient;
    private final String hmacKeyName;
    
    private byte[] hmacKey;
    
    public HMACManager(
            SecretsManagerClient secretsManagerClient,
            @Value("${aws.secretsManager.hmacKeyName}") String hmacKeyName) {
        this.secretsManagerClient = secretsManagerClient;
        this.hmacKeyName = hmacKeyName;
    }
    
    @PostConstruct
    public void loadHMACKey() {
        try {
            log.info("Loading HMAC key from Secrets Manager: {}", hmacKeyName);
            
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                GetSecretValueRequest.builder()
                    .secretId(hmacKeyName)
                    .build()
            );
            
            this.hmacKey = response.secretBinary().asByteArray();
            
            if (hmacKey.length != 32) {
                throw new IllegalStateException(
                    "Invalid HMAC key size: " + hmacKey.length + " (expected 32 bytes)"
                );
            }
            
            log.info("Successfully loaded HMAC key (256 bits)");
            
        } catch (Exception e) {
            log.error("Failed to load HMAC key from Secrets Manager", e);
            throw new RuntimeException("HMAC key initialization failed", e);
        }
    }
    
    /**
     * Hash PAN using HMAC-SHA256
     * @param clearPAN Clear PAN (e.g., "4111111111111111")
     * @return Hashed PAN as hex string (64 characters)
     */
    public String hashPAN(String clearPAN) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(hmacKey, "HmacSHA256");
            mac.init(keySpec);
            
            byte[] hash = mac.doFinal(clearPAN.getBytes(StandardCharsets.UTF_8));
            
            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
            
        } catch (Exception e) {
            log.error("Failed to hash PAN", e);
            throw new RuntimeException("PAN hashing failed", e);
        }
    }
    
    /**
     * Get HMAC key size for validation
     * @return Key size in bits
     */
    public int getKeySize() {
        return hmacKey != null ? hmacKey.length * 8 : 0;
    }
}
```

### Testing HMAC

```java
@SpringBootTest
class HMACManagerTest {
    
    @Autowired
    private HMACManager hmacManager;
    
    @Test
    void testPANHashing() {
        String testPAN = "4111111111111111";
        
        String hpan1 = hmacManager.hashPAN(testPAN);
        String hpan2 = hmacManager.hashPAN(testPAN);
        
        // Same PAN should produce same hash
        assertEquals(hpan1, hpan2);
        
        // Hash should be 64 hex characters
        assertEquals(64, hpan1.length());
        
        // Different PANs should produce different hashes
        String differentPAN = "5111111111111111";
        String differentHpan = hmacManager.hashPAN(differentPAN);
        assertNotEquals(hpan1, differentHpan);
    }
    
    @Test
    void testHMACKeySize() {
        assertEquals(256, hmacManager.getKeySize());
    }
}
```

---

## 4. DEK Management (KMS)

### Overview

Data Encryption Keys (DEKs) are 256-bit AES keys used to encrypt PANs. DEKs are rotated every 30 minutes and encrypted with AWS KMS master keys.

### Key Characteristics

- **Size**: 256 bits (32 bytes)
- **Algorithm**: AES-256-GCM
- **Rotation**: Every 30 minutes (48 DEKs/day per region)
- **Storage**: Encrypted DEKs stored in Aurora PostgreSQL
- **Regional Isolation**: Separate KMS master keys per region

### KMS Master Key Setup

#### Step 1: Create KMS Master Key (US Region)

```bash
# Create KMS key for US region
aws kms create-key \
  --description "Fraud Switch DEK encryption master key - US" \
  --key-usage ENCRYPT_DECRYPT \
  --key-spec SYMMETRIC_DEFAULT \
  --region us-east-2

# Create alias
aws kms create-alias \
  --alias-name alias/fraud-switch-dek-master-us \
  --target-key-id <KEY_ID_FROM_ABOVE> \
  --region us-east-2
```

#### Step 2: Create KMS Master Key (UK Region)

```bash
# Create KMS key for UK region
aws kms create-key \
  --description "Fraud Switch DEK encryption master key - UK" \
  --key-usage ENCRYPT_DECRYPT \
  --key-spec SYMMETRIC_DEFAULT \
  --region eu-west-2

# Create alias
aws kms create-alias \
  --alias-name alias/fraud-switch-dek-master-uk \
  --target-key-id <KEY_ID_FROM_ABOVE> \
  --region eu-west-2
```

### Application Configuration

**application.yml (US Region)**
```yaml
aws:
  kms:
    masterKeyArn: "arn:aws:kms:us-east-2:123456789012:key/us-master-key-xxx"
    region: us-east-2

dek:
  rotation:
    intervalMinutes: 30
    retryIntervalSeconds: 10
```

**application.yml (UK Region)**
```yaml
aws:
  kms:
    masterKeyArn: "arn:aws:kms:eu-west-2:123456789012:key/uk-master-key-xxx"
    region: eu-west-2

dek:
  rotation:
    intervalMinutes: 30
    retryIntervalSeconds: 10
```

### Java Implementation - DEK Manager

```java
package com.fraudswitch.router.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class DEKManager {
    
    private final KmsClient kmsClient;
    private final DekRepository dekRepository;
    private final String kmsMasterKeyArn;
    private final int rotationRetryInterval;
    
    private volatile byte[] currentDEK;
    private volatile Long currentDekId;
    private volatile boolean initialized = false;
    
    public DEKManager(
            KmsClient kmsClient,
            DekRepository dekRepository,
            @Value("${aws.kms.masterKeyArn}") String kmsMasterKeyArn,
            @Value("${dek.rotation.retryIntervalSeconds}") int rotationRetryInterval) {
        this.kmsClient = kmsClient;
        this.dekRepository = dekRepository;
        this.kmsMasterKeyArn = kmsMasterKeyArn;
        this.rotationRetryInterval = rotationRetryInterval;
    }
    
    @PostConstruct
    public void initializeDEK() {
        try {
            log.info("Initializing DEK from database");
            
            // Try to load latest DEK from database
            DekRecord latestDek = dekRepository.findLatestDek();
            
            if (latestDek != null) {
                // Decrypt DEK using KMS
                DecryptResponse response = kmsClient.decrypt(
                    DecryptRequest.builder()
                        .ciphertextBlob(SdkBytes.fromByteArray(latestDek.getDekEnc()))
                        .keyId(kmsMasterKeyArn)
                        .build()
                );
                
                this.currentDEK = response.plaintext().asByteArray();
                this.currentDekId = latestDek.getDekId();
                this.initialized = true;
                
                log.info("DEK initialized successfully: dek_id={}", currentDekId);
                
            } else {
                log.warn("No DEK found in database, generating initial DEK");
                generateAndStoreDEK();
            }
            
        } catch (Exception e) {
            log.error("Failed to initialize DEK, will retry", e);
            scheduleRetryInitialization();
        }
    }
    
    private void scheduleRetryInitialization() {
        ScheduledExecutorService retryExecutor = 
            Executors.newSingleThreadScheduledExecutor();
        
        retryExecutor.scheduleWithFixedDelay(() -> {
            if (!initialized) {
                try {
                    log.info("Retrying DEK initialization");
                    initializeDEK();
                    if (initialized) {
                        retryExecutor.shutdown();
                    }
                } catch (Exception e) {
                    log.warn("DEK initialization retry failed", e);
                }
            }
        }, rotationRetryInterval, rotationRetryInterval, TimeUnit.SECONDS);
    }
    
    /**
     * Rotate DEK every 30 minutes (pessimistic locking)
     */
    @Scheduled(fixedRateString = "${dek.rotation.intervalMinutes}", 
               timeUnit = TimeUnit.MINUTES)
    public void rotateDEK() {
        try {
            log.info("Starting DEK rotation");
            
            // Step 1: Check for recently locked DEK (within last 5 minutes)
            DekRecord recentLockedDek = dekRepository.findRecentLockedDek(
                Instant.now().minusSeconds(300)
            );
            
            if (recentLockedDek != null) {
                log.info("Found recent locked DEK, reusing: dek_id={}", 
                    recentLockedDek.getDekId());
                decryptAndStore(recentLockedDek, "Reused locked");
                return;
            }
            
            // Step 2: Try to acquire lock on existing unlocked DEK
            DekRecord unlockedDek = dekRepository.tryAcquireLock();
            
            if (unlockedDek != null) {
                log.info("Acquired lock on existing DEK: dek_id={}", 
                    unlockedDek.getDekId());
                decryptAndStore(unlockedDek, "Reused unlocked");
                return;
            }
            
            // Step 3: Generate new DEK (race condition - multiple pods may reach here)
            log.info("No DEK available, generating new DEK");
            generateAndStoreDEK();
            
        } catch (Exception e) {
            log.error("DEK rotation failed, keeping current DEK", e);
        }
    }
    
    private void generateAndStoreDEK() {
        try {
            // Generate new DEK via KMS
            GenerateDataKeyResponse response = kmsClient.generateDataKey(
                GenerateDataKeyRequest.builder()
                    .keyId(kmsMasterKeyArn)
                    .keySpec(DataKeySpec.AES_256)
                    .build()
            );
            
            byte[] plaintextDEK = response.plaintext().asByteArray();
            byte[] encryptedDEK = response.ciphertextBlob().asByteArray();
            
            // Store encrypted DEK with lock
            Long newDekId = dekRepository.insertLockedDek(encryptedDEK);
            
            this.currentDEK = plaintextDEK;
            this.currentDekId = newDekId;
            this.initialized = true;
            
            log.info("Generated and stored new DEK: dek_id={}", newDekId);
            
        } catch (Exception e) {
            log.error("Failed to generate DEK", e);
            throw new RuntimeException("DEK generation failed", e);
        }
    }
    
    private void decryptAndStore(DekRecord dekRecord, String source) {
        try {
            DecryptResponse response = kmsClient.decrypt(
                DecryptRequest.builder()
                    .ciphertextBlob(SdkBytes.fromByteArray(dekRecord.getDekEnc()))
                    .keyId(kmsMasterKeyArn)
                    .build()
            );
            
            this.currentDEK = response.plaintext().asByteArray();
            this.currentDekId = dekRecord.getDekId();
            this.initialized = true;
            
            log.info("{} DEK: dek_id={}", source, currentDekId);
            
        } catch (Exception e) {
            log.error("Failed to decrypt DEK", e);
            throw new RuntimeException("DEK decryption failed", e);
        }
    }
    
    /**
     * Get current DEK for encryption
     * @return Current DEK (plaintext)
     */
    public byte[] getCurrentDEK() {
        return currentDEK;
    }
    
    /**
     * Get current DEK ID
     * @return Current DEK ID
     */
    public Long getCurrentDekId() {
        return currentDekId;
    }
    
    /**
     * Check if DEK is initialized
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Update DEK (for fallback scenarios)
     * @param dek DEK plaintext
     * @param dekId DEK ID
     */
    public void updateDEK(byte[] dek, Long dekId) {
        this.currentDEK = dek;
        this.currentDekId = dekId;
        this.initialized = true;
    }
    
    @PreDestroy
    public void cleanup() {
        // Clear DEK from memory on shutdown
        if (currentDEK != null) {
            Arrays.fill(currentDEK, (byte) 0);
            currentDEK = null;
            log.info("Cleared DEK from memory");
        }
    }
}
```

### Database Repository

```java
package com.fraudswitch.router.repository;

import lombok.Data;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@Repository
public class DekRepository {
    
    private final JdbcTemplate jdbcTemplate;
    
    public DekRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    /**
     * Find latest DEK (most recent by insertion timestamp)
     */
    public DekRecord findLatestDek() {
        String sql = """
            SELECT dek_id, dek_enc, rotation_lock, inserted_timestamp
            FROM dek_table
            ORDER BY inserted_timestamp DESC
            LIMIT 1
            """;
        
        List<DekRecord> results = jdbcTemplate.query(sql, new DekRowMapper());
        return results.isEmpty() ? null : results.get(0);
    }
    
    /**
     * Find recently locked DEK (within time threshold)
     */
    public DekRecord findRecentLockedDek(Instant threshold) {
        String sql = """
            SELECT dek_id, dek_enc, rotation_lock, inserted_timestamp
            FROM dek_table
            WHERE rotation_lock = TRUE
              AND inserted_timestamp >= ?
            ORDER BY inserted_timestamp DESC
            LIMIT 1
            """;
        
        List<DekRecord> results = jdbcTemplate.query(
            sql, 
            new DekRowMapper(), 
            threshold
        );
        return results.isEmpty() ? null : results.get(0);
    }
    
    /**
     * Try to acquire lock on existing unlocked DEK (pessimistic locking)
     * Uses FOR UPDATE SKIP LOCKED to avoid contention
     */
    @Transactional
    public DekRecord tryAcquireLock() {
        String sql = """
            SELECT dek_id, dek_enc, rotation_lock, inserted_timestamp
            FROM dek_table
            WHERE rotation_lock = FALSE
            ORDER BY inserted_timestamp DESC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """;
        
        List<DekRecord> results = jdbcTemplate.query(sql, new DekRowMapper());
        return results.isEmpty() ? null : results.get(0);
    }
    
    /**
     * Insert new DEK with rotation_lock=TRUE
     */
    @Transactional
    public Long insertLockedDek(byte[] encryptedDek) {
        String sql = """
            INSERT INTO dek_table (dek_enc, rotation_lock)
            VALUES (?, TRUE)
            RETURNING dek_id
            """;
        
        return jdbcTemplate.queryForObject(sql, Long.class, encryptedDek);
    }
    
    /**
     * Find DEK by ID
     */
    public DekRecord findById(Long dekId) {
        String sql = """
            SELECT dek_id, dek_enc, rotation_lock, inserted_timestamp
            FROM dek_table
            WHERE dek_id = ?
            """;
        
        List<DekRecord> results = jdbcTemplate.query(sql, new DekRowMapper(), dekId);
        return results.isEmpty() ? null : results.get(0);
    }
    
    private static class DekRowMapper implements RowMapper<DekRecord> {
        @Override
        public DekRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            DekRecord record = new DekRecord();
            record.setDekId(rs.getLong("dek_id"));
            record.setDekEnc(rs.getBytes("dek_enc"));
            record.setRotationLock(rs.getBoolean("rotation_lock"));
            record.setInsertedTimestamp(rs.getTimestamp("inserted_timestamp").toInstant());
            return record;
        }
    }
    
    @Data
    public static class DekRecord {
        private Long dekId;
        private byte[] dekEnc;
        private boolean rotationLock;
        private Instant insertedTimestamp;
    }
}
```

---

## 5. PAN Hashing Implementation

### Overview

PAN hashing generates a consistent, deterministic identifier (`hpan`) for each PAN using HMAC-SHA256.

### Use Cases

1. **Fraud provider integration**: Send `hpan` instead of clear PAN
2. **Analytics/CDP**: Track transactions by `hpan` without exposing PAN
3. **Database indexing**: Use `hpan` as primary key for encrypted PAN lookup
4. **Velocity checks**: Track transaction counts by `hpan`

### Implementation (Fraud Router)

```java
package com.fraudswitch.router.service;

import com.fraudswitch.router.crypto.HMACManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PANHashingService {
    
    private final HMACManager hmacManager;
    
    public PANHashingService(HMACManager hmacManager) {
        this.hmacManager = hmacManager;
    }
    
    /**
     * Hash PAN to generate hpan
     * @param clearPAN Clear PAN (16-19 digits)
     * @return Hashed PAN (64 hex characters)
     */
    public String hashPAN(String clearPAN) {
        // Validate PAN format
        if (clearPAN == null || clearPAN.isEmpty()) {
            throw new IllegalArgumentException("PAN cannot be null or empty");
        }
        
        if (!clearPAN.matches("\\d{13,19}")) {
            throw new IllegalArgumentException("Invalid PAN format");
        }
        
        // Hash PAN
        String hpan = hmacManager.hashPAN(clearPAN);
        
        log.debug("Generated hpan for PAN (last 4: {})", 
            clearPAN.substring(clearPAN.length() - 4));
        
        return hpan;
    }
}
```

### Testing

```java
@SpringBootTest
class PANHashingServiceTest {
    
    @Autowired
    private PANHashingService panHashingService;
    
    @Test
    void testConsistentHashing() {
        String pan = "4111111111111111";
        
        String hpan1 = panHashingService.hashPAN(pan);
        String hpan2 = panHashingService.hashPAN(pan);
        
        assertEquals(hpan1, hpan2);
    }
    
    @Test
    void testDifferentPANsDifferentHashes() {
        String pan1 = "4111111111111111";
        String pan2 = "5111111111111111";
        
        String hpan1 = panHashingService.hashPAN(pan1);
        String hpan2 = panHashingService.hashPAN(pan2);
        
        assertNotEquals(hpan1, hpan2);
    }
    
    @Test
    void testInvalidPAN() {
        assertThrows(
            IllegalArgumentException.class,
            () -> panHashingService.hashPAN("invalid")
        );
    }
}
```

---

## 6. PAN Encryption Implementation

### Overview

PAN encryption uses AES-256-GCM with in-memory DEKs to encrypt clear PANs before storage.

### Encryption Algorithm

- **Mode**: AES-256-GCM (Galois/Counter Mode)
- **Key Size**: 256 bits (from DEK)
- **IV Size**: 12 bytes (96 bits, randomly generated per encryption)
- **Tag Size**: 128 bits (authentication tag)

### Implementation (Tokenization Service)

```java
package com.fraudswitch.tokenization.crypto;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

@Slf4j
@Service
public class PANEncryptionService {
    
    private final DEKManager dekManager;
    private final SecureRandom secureRandom;
    
    public PANEncryptionService(DEKManager dekManager) {
        this.dekManager = dekManager;
        this.secureRandom = new SecureRandom();
    }
    
    /**
     * Encrypt PAN using current DEK
     * @param clearPAN Clear PAN
     * @return Encrypted PAN data (ciphertext + DEK ID)
     */
    public EncryptedPanData encryptPAN(String clearPAN) {
        byte[] dek = dekManager.getCurrentDEK();
        Long dekId = dekManager.getCurrentDekId();
        
        if (dek == null || dekId == null) {
            throw new IllegalStateException("DEK not initialized");
        }
        
        return encryptWithDEK(clearPAN, dek, dekId);
    }
    
    private EncryptedPanData encryptWithDEK(String clearPAN, byte[] dek, Long dekId) {
        try {
            // Generate random IV (12 bytes for GCM)
            byte[] iv = new byte[12];
            secureRandom.nextBytes(iv);
            
            // Initialize cipher
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv); // 128-bit tag
            SecretKeySpec keySpec = new SecretKeySpec(dek, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            
            // Encrypt PAN
            byte[] ciphertext = cipher.doFinal(
                clearPAN.getBytes(StandardCharsets.UTF_8)
            );
            
            // Prepend IV to ciphertext for storage
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            
            log.debug("Encrypted PAN using DEK ID: {}", dekId);
            
            return EncryptedPanData.builder()
                .ciphertext(combined)
                .dekId(dekId)
                .build();
                
        } catch (Exception e) {
            log.error("Failed to encrypt PAN", e);
            throw new RuntimeException("PAN encryption failed", e);
        }
    }
    
    @Data
    @Builder
    public static class EncryptedPanData {
        private byte[] ciphertext;  // IV (12 bytes) + encrypted PAN + tag (16 bytes)
        private Long dekId;
    }
}
```

### Kafka Consumer (Tokenization Service)

```java
package com.fraudswitch.tokenization.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudswitch.tokenization.crypto.PANEncryptionService;
import com.fraudswitch.tokenization.repository.EncryptedPanRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PANQueueConsumer {
    
    private final PANEncryptionService encryptionService;
    private final EncryptedPanRepository encryptedPanRepository;
    private final ObjectMapper objectMapper;
    
    public PANQueueConsumer(
            PANEncryptionService encryptionService,
            EncryptedPanRepository encryptedPanRepository,
            ObjectMapper objectMapper) {
        this.encryptionService = encryptionService;
        this.encryptedPanRepository = encryptedPanRepository;
        this.objectMapper = objectMapper;
    }
    
    @KafkaListener(topics = "pan.queue", groupId = "tokenization-service")
    public void consumePANEvent(String message) {
        try {
            // Parse message
            PANEvent event = objectMapper.readValue(message, PANEvent.class);
            
            log.debug("Received PAN event: hpan={}", event.getHpan());
            
            // Check if already exists
            if (encryptedPanRepository.existsByHpan(event.getHpan())) {
                // Update last_seen_date
                encryptedPanRepository.updateLastSeenDate(event.getHpan());
                log.debug("Updated last_seen_date for existing hpan: {}", event.getHpan());
                return;
            }
            
            // Encrypt PAN
            PANEncryptionService.EncryptedPanData encrypted = 
                encryptionService.encryptPAN(event.getPan());
            
            // Store in database
            encryptedPanRepository.insert(
                event.getHpan(),
                encrypted.getCiphertext(),
                encrypted.getDekId()
            );
            
            log.info("Successfully encrypted and stored PAN: hpan={}, dek_id={}", 
                event.getHpan(), encrypted.getDekId());
            
        } catch (Exception e) {
            log.error("Failed to process PAN event", e);
            // Let Kafka retry or move to DLQ
            throw new RuntimeException("PAN processing failed", e);
        }
    }
    
    @Data
    private static class PANEvent {
        private String hpan;
        private String pan;
    }
}
```

---

## 7. PAN Decryption Implementation

### Overview

PAN decryption is a **restricted operation** used only for authorized use cases (customer support, issuer data-share).

### Security Controls

1. **Audit logging**: All decrypt operations logged with who/when/why
2. **IAM restrictions**: Only specific services can call decrypt API
3. **Regional isolation**: US endpoint decrypts US PANs only
4. **Rate limiting**: Prevent abuse

### Implementation (Decrypt API)

```java
package com.fraudswitch.tokenization.service;

import com.fraudswitch.tokenization.crypto.DEKManager;
import com.fraudswitch.tokenization.repository.DekRepository;
import com.fraudswitch.tokenization.repository.EncryptedPanRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class PANDecryptionService {
    
    private final EncryptedPanRepository encryptedPanRepository;
    private final DekRepository dekRepository;
    private final KmsClient kmsClient;
    private final String kmsMasterKeyArn;
    
    public PANDecryptionService(
            EncryptedPanRepository encryptedPanRepository,
            DekRepository dekRepository,
            KmsClient kmsClient,
            String kmsMasterKeyArn) {
        this.encryptedPanRepository = encryptedPanRepository;
        this.dekRepository = dekRepository;
        this.kmsClient = kmsClient;
        this.kmsMasterKeyArn = kmsMasterKeyArn;
    }
    
    /**
     * Decrypt PAN by hashed PAN
     * @param hpan Hashed PAN
     * @param requestContext Audit context (who/why)
     * @return Clear PAN
     */
    public String decryptPAN(String hpan, DecryptRequestContext requestContext) {
        try {
            log.info("Decrypting PAN: hpan={}, requestedBy={}, reason={}", 
                hpan, requestContext.getRequestedBy(), requestContext.getReason());
            
            // Step 1: Query encrypted_pan table
            EncryptedPanRepository.EncryptedPanRecord panRecord = 
                encryptedPanRepository.findByHpan(hpan);
            
            if (panRecord == null) {
                throw new PANNotFoundException("PAN not found: " + hpan);
            }
            
            // Step 2: Query dek_table
            DekRepository.DekRecord dekRecord = 
                dekRepository.findById(panRecord.getDekId());
            
            if (dekRecord == null) {
                throw new DEKNotFoundException("DEK not found: " + panRecord.getDekId());
            }
            
            // Step 3: Decrypt DEK using KMS
            DecryptResponse kmsResponse = kmsClient.decrypt(
                DecryptRequest.builder()
                    .ciphertextBlob(SdkBytes.fromByteArray(dekRecord.getDekEnc()))
                    .keyId(kmsMasterKeyArn)
                    .build()
            );
            
            byte[] dekPlaintext = kmsResponse.plaintext().asByteArray();
            
            // Step 4: Decrypt PAN using DEK
            String clearPAN = decryptPANWithDEK(panRecord.getPanCt(), dekPlaintext);
            
            // Audit log
            logDecryptOperation(hpan, requestContext);
            
            log.info("Successfully decrypted PAN: hpan={}", hpan);
            
            return clearPAN;
            
        } catch (Exception e) {
            log.error("Failed to decrypt PAN: hpan={}", hpan, e);
            throw new RuntimeException("PAN decryption failed", e);
        }
    }
    
    private String decryptPANWithDEK(byte[] ciphertext, byte[] dek) {
        try {
            // Extract IV (first 12 bytes)
            byte[] iv = new byte[12];
            System.arraycopy(ciphertext, 0, iv, 0, 12);
            
            // Extract encrypted data (remaining bytes)
            byte[] encryptedData = new byte[ciphertext.length - 12];
            System.arraycopy(ciphertext, 12, encryptedData, 0, encryptedData.length);
            
            // Initialize cipher
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
            SecretKeySpec keySpec = new SecretKeySpec(dek, "AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
            
            // Decrypt
            byte[] plaintext = cipher.doFinal(encryptedData);
            
            return new String(plaintext, StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
    
    private void logDecryptOperation(String hpan, DecryptRequestContext context) {
        // TODO: Send audit log to centralized logging system
        log.info("[AUDIT] PAN_DECRYPT: hpan={}, requestedBy={}, reason={}, timestamp={}", 
            hpan, 
            context.getRequestedBy(), 
            context.getReason(), 
            System.currentTimeMillis()
        );
    }
    
    @Data
    public static class DecryptRequestContext {
        private String requestedBy;  // Service/user requesting decryption
        private String reason;        // Business justification
    }
    
    public static class PANNotFoundException extends RuntimeException {
        public PANNotFoundException(String message) {
            super(message);
        }
    }
    
    public static class DEKNotFoundException extends RuntimeException {
        public DEKNotFoundException(String message) {
            super(message);
        }
    }
}
```

### REST Controller

```java
package com.fraudswitch.tokenization.controller;

import com.fraudswitch.tokenization.service.PANDecryptionService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/v1/pan")
public class PANDecryptionController {
    
    private final PANDecryptionService decryptionService;
    
    public PANDecryptionController(PANDecryptionService decryptionService) {
        this.decryptionService = decryptionService;
    }
    
    @PostMapping("/decrypt")
    public ResponseEntity<DecryptResponse> decryptPAN(
            @RequestBody DecryptRequest request,
            @RequestHeader("X-Requested-By") String requestedBy) {
        
        try {
            PANDecryptionService.DecryptRequestContext context = 
                new PANDecryptionService.DecryptRequestContext();
            context.setRequestedBy(requestedBy);
            context.setReason(request.getReason());
            
            String clearPAN = decryptionService.decryptPAN(request.getHpan(), context);
            
            return ResponseEntity.ok(
                DecryptResponse.builder()
                    .pan(clearPAN)
                    .build()
            );
            
        } catch (PANDecryptionService.PANNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Decrypt API error", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    @Data
    private static class DecryptRequest {
        private String hpan;
        private String reason;
    }
    
    @Data
    @lombok.Builder
    private static class DecryptResponse {
        private String pan;
    }
}
```

---

## 8. Database Schema

### Complete DDL

```sql
-- ============================================================================
-- DEK Table: Stores encrypted Data Encryption Keys
-- ============================================================================
CREATE TABLE dek_table (
  -- Primary Key (auto-increment)
  dek_id BIGSERIAL PRIMARY KEY,
  
  -- Encrypted DEK (encrypted with KMS master key)
  dek_enc BYTEA NOT NULL,
  
  -- Rotation Coordination
  rotation_lock BOOLEAN NOT NULL DEFAULT FALSE,
  
  -- Timestamp
  inserted_timestamp TIMESTAMP DEFAULT NOW()
);

-- Index for rotation queries (find latest DEK)
CREATE INDEX idx_dek_inserted_timestamp 
ON dek_table (inserted_timestamp DESC);

-- Index for pessimistic locking (find unlocked DEKs)
CREATE INDEX idx_dek_rotation_lock 
ON dek_table (rotation_lock, inserted_timestamp DESC)
WHERE rotation_lock = FALSE;

COMMENT ON TABLE dek_table IS 
  'Stores encrypted Data Encryption Keys (DEKs). ' ||
  'Each DEK is encrypted with regional KMS master key. ' ||
  'Plaintext DEK never stored in database. ' ||
  'DEKs rotated every 30 minutes.';

COMMENT ON COLUMN dek_table.dek_enc IS 
  'Encrypted DEK (256-bit AES key encrypted with KMS master key)';

COMMENT ON COLUMN dek_table.rotation_lock IS 
  'Pessimistic lock to ensure exactly 1 DEK generated per rotation cycle';

-- ============================================================================
-- Encrypted PAN Table: Stores encrypted PANs indexed by hashed PAN
-- ============================================================================
CREATE TABLE encrypted_pan (
  -- Hashed PAN (HMAC-SHA256) - Primary Key
  hpan VARCHAR(64) PRIMARY KEY,
  
  -- Encrypted PAN (AES-256-GCM)
  pan_ct BYTEA NOT NULL,
  
  -- DEK ID (foreign key to dek_table)
  dek_id BIGINT NOT NULL,
  
  -- Last seen date (for retention/cleanup)
  last_seen_date DATE NOT NULL DEFAULT CURRENT_DATE,
  
  -- Timestamps
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW(),
  
  -- Foreign Key Constraint
  CONSTRAINT fk_encrypted_pan_dek 
    FOREIGN KEY (dek_id) 
    REFERENCES dek_table(dek_id)
    ON DELETE RESTRICT
);

-- Index for cleanup job (delete old PANs)
CREATE INDEX idx_encrypted_pan_last_seen 
ON encrypted_pan (last_seen_date);

-- Index for DEK lookup (for cleanup of orphaned DEKs)
CREATE INDEX idx_encrypted_pan_dek_id
ON encrypted_pan (dek_id);

COMMENT ON TABLE encrypted_pan IS 
  'Stores encrypted PANs indexed by hashed PAN (hpan). ' ||
  'Clear PAN never stored. ' ||
  'Retention: 6 months based on last_seen_date.';

COMMENT ON COLUMN encrypted_pan.hpan IS 
  'Hashed PAN using HMAC-SHA256 (64 hex characters)';

COMMENT ON COLUMN encrypted_pan.pan_ct IS 
  'Encrypted PAN using AES-256-GCM (IV + ciphertext + tag)';

COMMENT ON COLUMN encrypted_pan.dek_id IS 
  'Reference to DEK used for encryption';

COMMENT ON COLUMN encrypted_pan.last_seen_date IS 
  'Last date this PAN was seen in a transaction (used for retention)';
```

### Trigger for updated_at

```sql
CREATE OR REPLACE FUNCTION update_modified_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_encrypted_pan_updated_at
BEFORE UPDATE ON encrypted_pan
FOR EACH ROW
EXECUTE FUNCTION update_modified_column();
```

---

## 9. Service Implementation Guide

### Fraud Router Implementation

**Dependencies (pom.xml)**
```xml
<dependencies>
    <!-- AWS SDK -->
    <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>secretsmanager</artifactId>
    </dependency>
    <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>kms</artifactId>
    </dependency>
    
    <!-- Spring Kafka -->
    <dependency>
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka</artifactId>
    </dependency>
    
    <!-- Resilience4j (Circuit Breaker) -->
    <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-spring-boot2</artifactId>
    </dependency>
</dependencies>
```

**Configuration (application.yml)**
```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      retries: 3

aws:
  region: ${AWS_REGION:us-east-2}
  secretsManager:
    hmacKeyName: "fraud-switch/hmac-key-global"
    region: ${AWS_REGION:us-east-2}
  kms:
    masterKeyArn: ${KMS_MASTER_KEY_ARN}
    region: ${AWS_REGION:us-east-2}

dek:
  rotation:
    intervalMinutes: 30
    retryIntervalSeconds: 10

resilience4j:
  circuitbreaker:
    instances:
      dekFallback:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 60s
        permittedNumberOfCallsInHalfOpenState: 5
```

### Tokenization Service Implementation

**Configuration (application.yml)**
```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
    consumer:
      group-id: tokenization-service
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      auto-offset-reset: earliest
      enable-auto-commit: false
    listener:
      ack-mode: manual

  datasource:
    url: jdbc:postgresql://${DB_HOST}:5432/fraudswitch
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000

aws:
  region: ${AWS_REGION:us-east-2}
  kms:
    masterKeyArn: ${KMS_MASTER_KEY_ARN}
    region: ${AWS_REGION:us-east-2}
```

---

## 10. Multi-Region Configuration

### US Region (Ohio)

**Environment Variables**
```bash
# AWS Configuration
AWS_REGION=us-east-2
KMS_MASTER_KEY_ARN=arn:aws:kms:us-east-2:123456789012:key/us-master-key-xxx

# Database
DB_HOST=fraud-switch-us.cluster-xxx.us-east-2.rds.amazonaws.com
DB_USERNAME=fraud_switch_app
DB_PASSWORD=<from-secrets-manager>

# Kafka
KAFKA_BOOTSTRAP_SERVERS=b-1.fraud-switch-us.xxx.kafka.us-east-2.amazonaws.com:9092
```

### UK Region (London)

**Environment Variables**
```bash
# AWS Configuration
AWS_REGION=eu-west-2
KMS_MASTER_KEY_ARN=arn:aws:kms:eu-west-2:123456789012:key/uk-master-key-xxx

# Database
DB_HOST=fraud-switch-uk.cluster-xxx.eu-west-2.rds.amazonaws.com
DB_USERNAME=fraud_switch_app
DB_PASSWORD=<from-secrets-manager>

# Kafka
KAFKA_BOOTSTRAP_SERVERS=b-1.fraud-switch-uk.xxx.kafka.eu-west-2.amazonaws.com:9092
```

---

## 11. IAM Policies & Security

### Fraud Router IAM Role

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SecretsManagerReadHMAC",
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue"
      ],
      "Resource": [
        "arn:aws:secretsmanager:us-east-2:123456789012:secret:fraud-switch/hmac-key-global-*",
        "arn:aws:secretsmanager:eu-west-2:123456789012:secret:fraud-switch/hmac-key-global-*"
      ]
    },
    {
      "Sid": "KMSGenerateAndDecrypt",
      "Effect": "Allow",
      "Action": [
        "kms:GenerateDataKey",
        "kms:Decrypt"
      ],
      "Resource": [
        "arn:aws:kms:us-east-2:123456789012:key/us-master-key-xxx",
        "arn:aws:kms:eu-west-2:123456789012:key/uk-master-key-xxx"
      ]
    },
    {
      "Sid": "KafkaProduceAccess",
      "Effect": "Allow",
      "Action": [
        "kafka:DescribeCluster",
        "kafka:GetBootstrapBrokers"
      ],
      "Resource": [
        "arn:aws:kafka:us-east-2:123456789012:cluster/fraud-switch-us/*",
        "arn:aws:kafka:eu-west-2:123456789012:cluster/fraud-switch-uk/*"
      ]
    }
  ]
}
```

### Tokenization Service IAM Role

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "KMSDecryptOnly",
      "Effect": "Allow",
      "Action": [
        "kms:Decrypt"
      ],
      "Resource": [
        "arn:aws:kms:us-east-2:123456789012:key/us-master-key-xxx",
        "arn:aws:kms:eu-west-2:123456789012:key/uk-master-key-xxx"
      ]
    },
    {
      "Sid": "KafkaConsumeAccess",
      "Effect": "Allow",
      "Action": [
        "kafka:DescribeCluster",
        "kafka:GetBootstrapBrokers"
      ],
      "Resource": [
        "arn:aws:kafka:us-east-2:123456789012:cluster/fraud-switch-us/*",
        "arn:aws:kafka:eu-west-2:123456789012:cluster/fraud-switch-uk/*"
      ]
    },
    {
      "Sid": "RDSConnect",
      "Effect": "Allow",
      "Action": [
        "rds-db:connect"
      ],
      "Resource": [
        "arn:aws:rds-db:us-east-2:123456789012:dbuser:*/fraud_switch_app",
        "arn:aws:rds-db:eu-west-2:123456789012:dbuser:*/fraud_switch_app"
      ]
    }
  ]
}
```

### KMS Key Policy

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "Enable IAM User Permissions",
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::123456789012:root"
      },
      "Action": "kms:*",
      "Resource": "*"
    },
    {
      "Sid": "Allow Fraud Router to Generate and Decrypt",
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::123456789012:role/FraudRouterRole"
      },
      "Action": [
        "kms:GenerateDataKey",
        "kms:Decrypt"
      ],
      "Resource": "*"
    },
    {
      "Sid": "Allow Tokenization Service to Decrypt",
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::123456789012:role/TokenizationServiceRole"
      },
      "Action": [
        "kms:Decrypt"
      ],
      "Resource": "*"
    }
  ]
}
```

---

## 12. Error Handling & Resilience

### Circuit Breaker Configuration (Fraud Router)

**Purpose**: Prevent cascade failures when database or KMS is unavailable

```java
@Configuration
public class CircuitBreakerConfig {
    
    @Bean
    public CircuitBreaker dekFallbackCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .permittedNumberOfCallsInHalfOpenState(5)
            .recordExceptions(Exception.class)
            .build();
        
        return CircuitBreaker.of("dekFallback", config);
    }
}
```

### Readiness Probe (Kubernetes)

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: fraud-router
spec:
  containers:
  - name: fraud-router
    image: fraud-router:latest
    readinessProbe:
      httpGet:
        path: /actuator/health/readiness
        port: 8080
      initialDelaySeconds: 30
      periodSeconds: 10
      failureThreshold: 3
```

### Health Check Implementation

```java
@Component
public class DEKHealthIndicator implements HealthIndicator {
    
    private final DEKManager dekManager;
    
    public DEKHealthIndicator(DEKManager dekManager) {
        this.dekManager = dekManager;
    }
    
    @Override
    public Health health() {
        if (dekManager.isInitialized() && dekManager.getCurrentDEK() != null) {
            return Health.up()
                .withDetail("dekInitialized", true)
                .withDetail("dekId", dekManager.getCurrentDekId())
                .build();
        } else {
            return Health.down()
                .withDetail("dekInitialized", false)
                .withDetail("reason", "DEK not initialized")
                .build();
        }
    }
}
```

### Retry Logic (Database Connection)

```java
@Configuration
public class RetryConfig {
    
    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();
        
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(1000);
        backOffPolicy.setMaxInterval(16000);
        backOffPolicy.setMultiplier(2.0);
        
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(5);
        
        retryTemplate.setBackOffPolicy(backOffPolicy);
        retryTemplate.setRetryPolicy(retryPolicy);
        
        return retryTemplate;
    }
}
```

### Kafka Consumer Error Handling

```java
@Component
public class PANQueueErrorHandler implements ConsumerRecordRecoverer {
    
    private static final Logger log = LoggerFactory.getLogger(PANQueueErrorHandler.class);
    
    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        log.error("Failed to process PAN event after retries: topic={}, partition={}, offset={}", 
            record.topic(), record.partition(), record.offset(), exception);
        
        // Send to DLQ
        // TODO: Implement DLQ publishing
    }
}
```

---

## 13. Testing Strategy

### Unit Tests

**HMAC Manager Test**
```java
@SpringBootTest
class HMACManagerTest {
    
    @Autowired
    private HMACManager hmacManager;
    
    @Test
    void testConsistentHashing() {
        String pan = "4111111111111111";
        String hpan1 = hmacManager.hashPAN(pan);
        String hpan2 = hmacManager.hashPAN(pan);
        
        assertEquals(hpan1, hpan2, "Same PAN should produce same hash");
        assertEquals(64, hpan1.length(), "Hash should be 64 hex characters");
    }
    
    @Test
    void testDifferentPANsProduceDifferentHashes() {
        String pan1 = "4111111111111111";
        String pan2 = "5111111111111111";
        
        assertNotEquals(
            hmacManager.hashPAN(pan1), 
            hmacManager.hashPAN(pan2),
            "Different PANs should produce different hashes"
        );
    }
    
    @Test
    void testKeySize() {
        assertEquals(256, hmacManager.getKeySize(), "HMAC key should be 256 bits");
    }
}
```

**DEK Manager Test**
```java
@SpringBootTest
class DEKManagerTest {
    
    @Autowired
    private DEKManager dekManager;
    
    @Test
    void testDEKInitialization() {
        assertTrue(dekManager.isInitialized(), "DEK should be initialized");
        assertNotNull(dekManager.getCurrentDEK(), "Current DEK should not be null");
        assertNotNull(dekManager.getCurrentDekId(), "Current DEK ID should not be null");
    }
    
    @Test
    void testDEKSize() {
        byte[] dek = dekManager.getCurrentDEK();
        assertEquals(32, dek.length, "DEK should be 32 bytes (256 bits)");
    }
}
```

**Encryption/Decryption Round-Trip Test**
```java
@SpringBootTest
class PANEncryptionDecryptionTest {
    
    @Autowired
    private PANEncryptionService encryptionService;
    
    @Autowired
    private PANDecryptionService decryptionService;
    
    @Autowired
    private HMACManager hmacManager;
    
    @Autowired
    private EncryptedPanRepository encryptedPanRepository;
    
    @Test
    @Transactional
    void testEncryptDecryptRoundTrip() {
        String originalPAN = "4111111111111111";
        String hpan = hmacManager.hashPAN(originalPAN);
        
        // Encrypt
        PANEncryptionService.EncryptedPanData encrypted = 
            encryptionService.encryptPAN(originalPAN);
        
        // Store in database
        encryptedPanRepository.insert(
            hpan, 
            encrypted.getCiphertext(), 
            encrypted.getDekId()
        );
        
        // Decrypt
        PANDecryptionService.DecryptRequestContext context = 
            new PANDecryptionService.DecryptRequestContext();
        context.setRequestedBy("test");
        context.setReason("unit test");
        
        String decryptedPAN = decryptionService.decryptPAN(hpan, context);
        
        assertEquals(originalPAN, decryptedPAN, "Decrypted PAN should match original");
    }
}
```

### Integration Tests

**DEK Rotation Test**
```java
@SpringBootTest
class DEKRotationIntegrationTest {
    
    @Autowired
    private DEKManager dekManager;
    
    @Autowired
    private DekRepository dekRepository;
    
    @Test
    void testDEKRotation() throws InterruptedException {
        Long initialDekId = dekManager.getCurrentDekId();
        
        // Trigger rotation
        dekManager.rotateDEK();
        
        // Wait for rotation to complete
        Thread.sleep(2000);
        
        Long newDekId = dekManager.getCurrentDekId();
        
        // Verify new DEK was generated or reused
        assertNotNull(newDekId);
        
        // Verify DEK exists in database
        DekRepository.DekRecord dekRecord = dekRepository.findById(newDekId);
        assertNotNull(dekRecord);
    }
}
```

**Multi-Pod DEK Coordination Test**
```java
@SpringBootTest
class MultiPodDEKCoordinationTest {
    
    @Autowired
    private DEKManager dekManager;
    
    @Autowired
    private DekRepository dekRepository;
    
    @Test
    void testPessimisticLocking() throws InterruptedException {
        // Simulate multiple pods trying to rotate at the same time
        int numThreads = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
        
        CountDownLatch latch = new CountDownLatch(numThreads);
        List<Long> dekIds = Collections.synchronizedList(new ArrayList<>());
        
        for (int i = 0; i < numThreads; i++) {
            executorService.submit(() -> {
                try {
                    dekManager.rotateDEK();
                    dekIds.add(dekManager.getCurrentDekId());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(30, TimeUnit.SECONDS);
        executorService.shutdown();
        
        // All pods should converge to the same DEK
        Set<Long> uniqueDekIds = new HashSet<>(dekIds);
        assertEquals(1, uniqueDekIds.size(), 
            "All pods should use the same DEK after rotation");
    }
}
```

### Load Tests

**Encryption Performance Test**
```java
@SpringBootTest
class PANEncryptionPerformanceTest {
    
    @Autowired
    private PANEncryptionService encryptionService;
    
    @Test
    void testEncryptionLatency() {
        String testPAN = "4111111111111111";
        int iterations = 10000;
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            encryptionService.encryptPAN(testPAN);
        }
        
        long endTime = System.nanoTime();
        long avgLatencyNanos = (endTime - startTime) / iterations;
        long avgLatencyMs = avgLatencyNanos / 1_000_000;
        
        assertTrue(avgLatencyMs < 5, 
            "Average encryption latency should be < 5ms, was: " + avgLatencyMs + "ms");
    }
}
```

---

## 14. Operational Procedures

### Initial Setup Checklist

#### Step 1: Generate and Store HMAC Key

```bash
# Generate HMAC key
openssl rand -out hmac-key.bin 32

# Create secret in US region with UK replication
aws secretsmanager create-secret \
  --name fraud-switch/hmac-key-global \
  --description "HMAC key for PAN hashing" \
  --secret-binary fileb://hmac-key.bin \
  --replica-regions Region=eu-west-2 \
  --region us-east-2

# Verify replication
aws secretsmanager describe-secret \
  --secret-id fraud-switch/hmac-key-global \
  --region us-east-2 \
  --query 'ReplicationStatus'

# Test UK read
aws secretsmanager get-secret-value \
  --secret-id fraud-switch/hmac-key-global \
  --region eu-west-2
```

#### Step 2: Create KMS Master Keys

```bash
# US Region
aws kms create-key \
  --description "Fraud Switch DEK encryption - US" \
  --key-usage ENCRYPT_DECRYPT \
  --region us-east-2

aws kms create-alias \
  --alias-name alias/fraud-switch-dek-master-us \
  --target-key-id <KEY_ID> \
  --region us-east-2

# UK Region
aws kms create-key \
  --description "Fraud Switch DEK encryption - UK" \
  --key-usage ENCRYPT_DECRYPT \
  --region eu-west-2

aws kms create-alias \
  --alias-name alias/fraud-switch-dek-master-uk \
  --target-key-id <KEY_ID> \
  --region eu-west-2
```

#### Step 3: Create Database Schema

```bash
# Connect to US Aurora cluster
psql -h fraud-switch-us.cluster-xxx.us-east-2.rds.amazonaws.com \
     -U admin -d fraudswitch

# Execute DDL
\i schema.sql

# Verify tables
\dt
\d dek_table
\d encrypted_pan

# Repeat for UK region
```

#### Step 4: Configure IAM Roles

```bash
# Create Fraud Router role
aws iam create-role \
  --role-name FraudRouterRole \
  --assume-role-policy-document file://trust-policy.json

aws iam put-role-policy \
  --role-name FraudRouterRole \
  --policy-name FraudRouterPolicy \
  --policy-document file://fraud-router-policy.json

# Create Tokenization Service role
aws iam create-role \
  --role-name TokenizationServiceRole \
  --assume-role-policy-document file://trust-policy.json

aws iam put-role-policy \
  --role-name TokenizationServiceRole \
  --policy-name TokenizationServicePolicy \
  --policy-document file://tokenization-service-policy.json
```

#### Step 5: Deploy Services

```bash
# Build Docker images
docker build -t fraud-router:latest ./fraud-router
docker build -t tokenization-service:latest ./tokenization-service

# Push to ECR
aws ecr get-login-password --region us-east-2 | \
  docker login --username AWS --password-stdin 123456789012.dkr.ecr.us-east-2.amazonaws.com

docker tag fraud-router:latest \
  123456789012.dkr.ecr.us-east-2.amazonaws.com/fraud-router:latest
docker push 123456789012.dkr.ecr.us-east-2.amazonaws.com/fraud-router:latest

# Deploy to Kubernetes
kubectl apply -f k8s/fraud-router-deployment.yaml
kubectl apply -f k8s/tokenization-service-deployment.yaml

# Verify pods are healthy
kubectl get pods -l app=fraud-router
kubectl get pods -l app=tokenization-service
```

### Monitoring & Alerting

#### Key Metrics to Monitor

**DEK Rotation Metrics:**
- `dek_rotation_success_total` - Counter of successful rotations
- `dek_rotation_failure_total` - Counter of failed rotations
- `dek_age_seconds` - Gauge of current DEK age
- `dek_fallback_attempts_total` - Counter of fallback attempts
- `dek_generation_total` - Counter of new DEK generations

**Encryption Metrics:**
- `pan_encryption_duration_seconds` - Histogram of encryption latency
- `pan_encryption_success_total` - Counter of successful encryptions
- `pan_encryption_failure_total` - Counter of failed encryptions

**Decryption Metrics:**
- `pan_decryption_duration_seconds` - Histogram of decryption latency
- `pan_decryption_success_total` - Counter of successful decryptions
- `pan_decryption_failure_total` - Counter of failed decryptions
- `pan_decryption_audit_total` - Counter of all decrypt operations (for audit)

**Database Metrics:**
- `dek_table_size` - Gauge of total DEKs in database
- `encrypted_pan_table_size` - Gauge of total PANs in database
- `orphaned_dek_count` - Gauge of DEKs with no references

#### Alert Rules

**Critical Alerts:**
```yaml
# DEK Not Initialized
- alert: DEKNotInitialized
  expr: dek_initialized == 0
  for: 5m
  severity: critical
  annotations:
    summary: "DEK not initialized in {{ $labels.pod }}"
    description: "Fraud Router pod cannot encrypt PANs"

# DEK Rotation Failures
- alert: DEKRotationFailureRate
  expr: rate(dek_rotation_failure_total[5m]) > 0.1
  for: 10m
  severity: critical
  annotations:
    summary: "High DEK rotation failure rate"
    description: "DEK rotation failing at {{ $value }}/sec"

# DEK Age Exceeds Threshold
- alert: DEKAgeTooOld
  expr: dek_age_seconds > 3600
  for: 5m
  severity: warning
  annotations:
    summary: "DEK age exceeds 1 hour"
    description: "DEK not rotated for {{ $value }} seconds"
```

**Warning Alerts:**
```yaml
# High Encryption Latency
- alert: HighEncryptionLatency
  expr: histogram_quantile(0.99, pan_encryption_duration_seconds) > 0.010
  for: 5m
  severity: warning
  annotations:
    summary: "P99 encryption latency > 10ms"
    description: "Current p99: {{ $value }}s"

# High Decryption Latency
- alert: HighDecryptionLatency
  expr: histogram_quantile(0.99, pan_decryption_duration_seconds) > 0.100
  for: 5m
  severity: warning
  annotations:
    summary: "P99 decryption latency > 100ms"
    description: "Current p99: {{ $value }}s"
```

### Data Cleanup Jobs

#### PAN Retention Cleanup (Nightly)

**Kubernetes CronJob**
```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: pan-cleanup
spec:
  schedule: "0 2 * * *"  # 2 AM daily
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: cleanup
            image: tokenization-service:latest
            command:
            - /bin/sh
            - -c
            - |
              java -jar tokenization-service.jar \
                --spring.profiles.active=cleanup \
                --cleanup.mode=pan
          restartPolicy: OnFailure
```

**Cleanup Script**
```sql
-- Delete PANs older than 6 months
DELETE FROM encrypted_pan
WHERE last_seen_date < CURRENT_DATE - INTERVAL '6 months';

-- Log deleted count
SELECT 'Deleted ' || ROW_COUNT() || ' expired PANs';
```

#### Orphaned DEK Cleanup (Weekly)

**Cleanup Script**
```sql
-- Delete DEKs with no PAN references and older than 7 days
DELETE FROM dek_table
WHERE dek_id NOT IN (
    SELECT DISTINCT dek_id FROM encrypted_pan
)
AND inserted_timestamp < NOW() - INTERVAL '7 days';

-- Log deleted count
SELECT 'Deleted ' || ROW_COUNT() || ' orphaned DEKs';
```

### Disaster Recovery

#### Backup Procedures

**HMAC Key Backup**
```bash
# Export HMAC key (US region)
aws secretsmanager get-secret-value \
  --secret-id fraud-switch/hmac-key-global \
  --region us-east-2 \
  --query 'SecretBinary' \
  --output text | base64 -d > hmac-key-backup.bin

# Store in secure offline location (HSM, safe, etc.)
# DO NOT commit to source control
```

**Database Backup**
```bash
# Aurora automated backups (enabled by default)
# Retention: 7 days
# Manual snapshot before major changes
aws rds create-db-cluster-snapshot \
  --db-cluster-snapshot-identifier fraud-switch-manual-snapshot-$(date +%Y%m%d) \
  --db-cluster-identifier fraud-switch-us
```

#### Recovery Procedures

**HMAC Key Recovery**
```bash
# Restore from backup file
aws secretsmanager create-secret \
  --name fraud-switch/hmac-key-global-recovery \
  --secret-binary fileb://hmac-key-backup.bin \
  --replica-regions Region=eu-west-2 \
  --region us-east-2

# Update application configuration to use new secret name
# Or rename secret back to original name
```

**Database Recovery**
```bash
# Restore from snapshot
aws rds restore-db-cluster-from-snapshot \
  --db-cluster-identifier fraud-switch-us-restored \
  --snapshot-identifier fraud-switch-manual-snapshot-20251016 \
  --engine aurora-postgresql

# Update application configuration with new endpoint
```

### Troubleshooting Guide

#### Issue: DEK Not Initializing

**Symptoms:**
- Pods stuck in NotReady state
- Logs show "Failed to initialize DEK"
- Health check returns DOWN

**Diagnosis:**
```bash
# Check pod logs
kubectl logs -l app=fraud-router --tail=100

# Check database connectivity
kubectl exec -it fraud-router-xxx -- psql -h $DB_HOST -U $DB_USERNAME -d fraudswitch -c "SELECT 1"

# Check KMS permissions
kubectl exec -it fraud-router-xxx -- \
  aws kms describe-key --key-id $KMS_MASTER_KEY_ARN
```

**Resolution:**
1. Verify database connectivity
2. Check IAM role permissions (KMS decrypt)
3. Check if `dek_table` exists
4. Manually insert initial DEK if table is empty

#### Issue: High Encryption Latency

**Symptoms:**
- P99 encryption latency > 10ms
- Slow transaction processing

**Diagnosis:**
```bash
# Check DEK fallback rate
kubectl exec -it fraud-router-xxx -- curl http://localhost:8080/actuator/metrics/dek_fallback_attempts_total

# Check circuit breaker state
kubectl exec -it fraud-router-xxx -- curl http://localhost:8080/actuator/circuitbreakers
```

**Resolution:**
1. Check if DEK is in memory (not falling back to DB)
2. Review circuit breaker state
3. Check database query performance
4. Review KMS API latency

#### Issue: PAN Decryption Failure

**Symptoms:**
- 500 errors on decrypt API
- "DEK not found" errors

**Diagnosis:**
```bash
# Check if DEK exists in database
psql -h $DB_HOST -U $DB_USERNAME -d fraudswitch \
  -c "SELECT dek_id FROM dek_table WHERE dek_id = <DEK_ID>"

# Check KMS permissions
aws kms decrypt --ciphertext-blob fileb://test-encrypted-dek.bin --key-id $KMS_MASTER_KEY_ARN
```

**Resolution:**
1. Verify DEK exists in database
2. Check KMS permissions
3. Verify correct KMS master key ARN
4. Check for expired/deleted KMS keys

### Security Audit Procedures

#### Quarterly Security Review

**Access Review:**
```bash
# List all IAM roles with KMS access
aws iam list-policies --scope Local | \
  jq '.Policies[] | select(.PolicyName | contains("KMS"))'

# Review Secrets Manager access logs
aws cloudtrail lookup-events \
  --lookup-attributes AttributeKey=ResourceName,AttributeValue=fraud-switch/hmac-key-global \
  --start-time $(date -u -d '90 days ago' +%Y-%m-%dT%H:%M:%S) \
  --max-results 100
```

**Decrypt Audit Review:**
```bash
# Query decrypt operations from logs
# Review who/when/why for all decrypt operations
SELECT 
  timestamp,
  hpan,
  requested_by,
  reason
FROM pan_decrypt_audit
WHERE timestamp > NOW() - INTERVAL '90 days'
ORDER BY timestamp DESC;
```

**DEK Rotation Verification:**
```bash
# Verify DEK rotation is occurring every 30 minutes
SELECT 
  dek_id,
  inserted_timestamp,
  LAG(inserted_timestamp) OVER (ORDER BY inserted_timestamp) as prev_timestamp,
  EXTRACT(EPOCH FROM (inserted_timestamp - LAG(inserted_timestamp) OVER (ORDER BY inserted_timestamp))) / 60 as minutes_diff
FROM dek_table
ORDER BY inserted_timestamp DESC
LIMIT 50;

# Expected: ~30 minute intervals between DEKs
```

---

## Appendix A: Quick Reference

### Key Concepts

| Term | Description |
|------|-------------|
| **PAN** | Primary Account Number (credit card number) |
| **hpan** | Hashed PAN using HMAC-SHA256 (64 hex chars) |
| **DEK** | Data Encryption Key (256-bit AES key) |
| **KMS** | AWS Key Management Service |
| **Master Key** | KMS key used to encrypt DEKs |
| **Envelope Encryption** | DEKs encrypted with KMS master key |

### Algorithm Summary

| Operation | Algorithm | Key Size | IV Size |
|-----------|-----------|----------|---------|
| PAN Hashing | HMAC-SHA256 | 256 bits | N/A |
| PAN Encryption | AES-256-GCM | 256 bits | 96 bits |
| DEK Encryption | AWS KMS | 256 bits | Managed by KMS |

### Service Endpoints

| Service | Port | Endpoint | Purpose |
|---------|------|----------|---------|
| Fraud Router | 8080 | `/actuator/health` | Health check |
| Tokenization Service | 8080 | `/actuator/health` | Health check |
| Decrypt API | 8080 | `/v1/pan/decrypt` | PAN decryption |

### Database Tables

| Table | Purpose | Retention |
|-------|---------|-----------|
| `dek_table` | Store encrypted DEKs | 7 days after orphaned |
| `encrypted_pan` | Store encrypted PANs | 6 months |

### AWS Resources

| Resource | Region | ARN Pattern |
|----------|--------|-------------|
| HMAC Secret | US/UK | `arn:aws:secretsmanager:{region}:*:secret:fraud-switch/hmac-key-global-*` |
| KMS Master Key | US | `arn:aws:kms:us-east-2:*:key/us-master-key-xxx` |
| KMS Master Key | UK | `arn:aws:kms:eu-west-2:*:key/uk-master-key-xxx` |

---

## Appendix B: Code Snippets

### Generate Test HMAC Key (Development Only)

```bash
# Generate 256-bit key
openssl rand -hex 32 > hmac-key-dev.txt

# Use in application-dev.yml
# DO NOT use in production
```

### Manual DEK Generation (Emergency)

```java
// Generate DEK manually via KMS
GenerateDataKeyResponse response = kmsClient.generateDataKey(
    GenerateDataKeyRequest.builder()
        .keyId("arn:aws:kms:us-east-2:123456789012:key/us-master-key-xxx")
        .keySpec(DataKeySpec.AES_256)
        .build()
);

byte[] plaintextDEK = response.plaintext().asByteArray();
byte[] encryptedDEK = response.ciphertextBlob().asByteArray();

// Insert into database
jdbcTemplate.update(
    "INSERT INTO dek_table (dek_enc, rotation_lock) VALUES (?, TRUE)",
    encryptedDEK
);
```

### Query Encrypted PAN by Last 4 Digits

```sql
-- First, hash the full PAN to get hpan
-- Then query by hpan (cannot query by last 4 directly)

SELECT 
  hpan,
  dek_id,
  last_seen_date,
  created_at
FROM encrypted_pan
WHERE hpan = '<hpan_from_hmac_hash>';
```

---

## Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2025-10-12 | Architecture Team | Initial draft |
| 2.0 | 2025-10-16 | Architecture Team | Added circuit breaker, pessimistic locking, Secrets Manager replication |

---

## Contact & Support

**Architecture Team:**
- Email: architecture@fraudswitch.com
- Slack: #fraud-switch-architecture

**On-Call:**
- PagerDuty: Fraud Switch On-Call

**Documentation:**
- Confluence: https://wiki.company.com/fraud-switch
- GitHub: https://github.com/company/fraud-switch

---

**Document Status:** ✅ Ready for Implementation  
**Last Updated:** October 16, 2025  
**Next Review:** January 16, 2026