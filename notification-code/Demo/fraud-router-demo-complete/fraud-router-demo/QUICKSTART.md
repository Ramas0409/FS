# Quick Start Guide - Fraud Router Demo

Get up and running in 5 minutes!

## Prerequisites

- Java 17+
- Maven 3.8+
- curl (for testing)
- jq (optional, for pretty JSON)

## Step 1: Install Metrics Library

```bash
# Navigate to metrics starter and install
cd fraud-switch-metrics-starter
mvn clean install -DskipTests

# Verify installation
ls ~/.m2/repository/com/fraudswitch/fraud-switch-metrics-spring-boot-starter/1.0.0/
```

Expected output:
```
fraud-switch-metrics-spring-boot-starter-1.0.0.jar
fraud-switch-metrics-spring-boot-starter-1.0.0.pom
```

## Step 2: Build Demo Application

```bash
# Navigate to demo app
cd ../fraud-router-demo

# Build
mvn clean package -DskipTests

# Verify build
ls target/*.jar
```

Expected output:
```
fraud-router-demo-1.0.0.jar
```

## Step 3: Run Application

```bash
# Start the application
java -jar target/fraud-router-demo-1.0.0.jar

# Or use Maven
mvn spring-boot:run
```

Wait for startup message:
```
================================================================================
  Fraud Router Demo Application Started Successfully!
================================================================================
  REST API:        http://localhost:8080/api/fraud
  Prometheus:      http://localhost:8080/actuator/prometheus
  Health Check:    http://localhost:8080/actuator/health
  All Metrics:     http://localhost:8080/actuator/metrics
================================================================================
```

## Step 4: Test the Application

### Option A: Use Test Script (Recommended)

```bash
# Run comprehensive test suite
./test-demo.sh
```

This will:
1. ✅ Check application health
2. ✅ Submit low-risk transaction
3. ✅ Submit high-risk transaction
4. ✅ Test async processing
5. ✅ Run load test (10 requests)
6. ✅ Display metrics summary

### Option B: Manual Testing

```bash
# Test 1: Health Check
curl http://localhost:8080/api/fraud/health

# Test 2: Submit Fraud Check
curl -X POST http://localhost:8080/api/fraud/check \
  -H "Content-Type: application/json" \
  -d @test-payload.json | jq

# Test 3: View Metrics
curl http://localhost:8080/actuator/prometheus | grep fraud_switch
```

## Step 5: Explore Metrics

### View All Metrics

```bash
curl http://localhost:8080/actuator/prometheus | grep fraud_switch
```

### Key Metrics to Check

```bash
# Request count
curl -s http://localhost:8080/actuator/prometheus | grep 'fraud_switch_fraud_router_requests_total'

# Fraud decisions
curl -s http://localhost:8080/actuator/prometheus | grep 'fraud_decisions_total'

# Provider calls
curl -s http://localhost:8080/actuator/prometheus | grep 'provider_calls_total'

# Kafka publishes
curl -s http://localhost:8080/actuator/prometheus | grep 'kafka_publishes_total'

# Latency (p99)
curl -s http://localhost:8080/actuator/prometheus | grep 'quantile="0.99"'
```

## Common API Calls

### 1. Simple Fraud Check

```bash
curl -X POST http://localhost:8080/api/fraud/check \
  -H "Content-Type: application/json" \
  -d '{
    "transaction_id": "txn_001",
    "merchant_id": "merch_001",
    "amount": 99.99,
    "currency": "USD",
    "transaction_type": "auth",
    "payment_method": "stripe"
  }'
```

### 2. Async Fraud Check

```bash
curl -X POST http://localhost:8080/api/fraud/check/async \
  -H "Content-Type: application/json" \
  -d '{
    "transaction_id": "txn_async",
    "merchant_id": "merch_001",
    "amount": 199.99,
    "currency": "USD",
    "transaction_type": "auth",
    "async_mode": true
  }'
```

### 3. High-Risk Transaction

```bash
curl -X POST http://localhost:8080/api/fraud/check \
  -H "Content-Type: application/json" \
  -d '{
    "transaction_id": "txn_high_risk",
    "merchant_id": "merch_001",
    "amount": 9999.99,
    "currency": "USD",
    "transaction_type": "capture",
    "card_bin": "666666",
    "provider_preference": "Signifyd"
  }'
```

## Load Testing

### Generate 100 Requests

```bash
for i in {1..100}; do
  curl -s -X POST http://localhost:8080/api/fraud/check \
    -H "Content-Type: application/json" \
    -d "{
      \"transaction_id\": \"txn_$i\",
      \"merchant_id\": \"merch_load\",
      \"amount\": $((RANDOM % 1000 + 1)),
      \"currency\": \"USD\",
      \"transaction_type\": \"auth\",
      \"payment_method\": \"stripe\"
    }" > /dev/null &
done
wait

# Check metrics
curl -s http://localhost:8080/actuator/prometheus | grep 'requests_total'
```

### Watch Metrics in Real-Time

```bash
# Linux/Mac
watch -n 1 'curl -s http://localhost:8080/actuator/prometheus | grep -E "(requests_total|fraud_decisions_total)"'

# Windows (PowerShell)
while($true) {
  curl -s http://localhost:8080/actuator/prometheus | Select-String "requests_total|fraud_decisions_total"
  Start-Sleep -Seconds 1
  Clear-Host
}
```

## Troubleshooting

### Application Won't Start

```bash
# Check Java version
java -version  # Must be 17+

# Check port 8080 is free
netstat -an | grep 8080  # Linux/Mac
netstat -an | findstr 8080  # Windows

# Check logs
cat logs/application.log
```

### Metrics Not Showing

```bash
# 1. Verify metrics library is loaded
curl http://localhost:8080/actuator/health

# 2. Check available metrics
curl http://localhost:8080/actuator/metrics

# 3. Look for errors in logs
grep ERROR logs/application.log
```

### Connection Refused

```bash
# Ensure application is running
ps aux | grep fraud-router  # Linux/Mac
tasklist | findstr java  # Windows

# Check application logs
tail -f logs/application.log
```

## Next Steps

1. **Read Full README**: `README.md` - Complete documentation
2. **Review Code**: Explore `src/main/java` for implementation details
3. **Customize**: Modify `application.yml` for your needs
4. **Deploy**: Package as Docker container or deploy to K8s

## Useful Commands

```bash
# Build without tests
mvn clean package -DskipTests

# Run with different port
java -jar target/fraud-router-demo-1.0.0.jar --server.port=9090

# Run with debug logging
java -jar target/fraud-router-demo-1.0.0.jar --logging.level.com.fraudswitch=DEBUG

# Generate load with Apache Bench
ab -n 1000 -c 10 -T 'application/json' -p test-payload.json http://localhost:8080/api/fraud/check
```

## Key Files

- `pom.xml` - Maven dependencies
- `src/main/resources/application.yml` - Configuration
- `src/main/java/.../FraudRoutingService.java` - Core business logic
- `src/main/java/.../FraudRouterController.java` - REST endpoints
- `test-demo.sh` - Automated test script
- `README.md` - Full documentation

---

**You're ready to go! 🚀**

Run `./test-demo.sh` to see everything in action!
