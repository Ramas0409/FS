#!/bin/bash

# Fraud Router Demo - Test Script
# Tests various fraud check scenarios and displays metrics

BASE_URL="http://localhost:8080"
API_URL="$BASE_URL/api/fraud"

echo "=================================="
echo "Fraud Router Demo - Test Script"
echo "=================================="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test 1: Health Check
echo -e "${YELLOW}Test 1: Health Check${NC}"
curl -s "$API_URL/health" | jq '.'
echo ""
sleep 1

# Test 2: Low-Risk Transaction (Ravelin)
echo -e "${YELLOW}Test 2: Low-Risk Transaction via Ravelin${NC}"
curl -s -X POST "$API_URL/check" \
  -H "Content-Type: application/json" \
  -d '{
    "transaction_id": "txn_low_risk_001",
    "merchant_id": "merch_001",
    "amount": 49.99,
    "currency": "USD",
    "transaction_type": "auth",
    "card_bin": "424242",
    "card_last_four": "4242",
    "payment_method": "stripe",
    "customer_email": "good.customer@example.com",
    "provider_preference": "Ravelin"
  }' | jq '.'
echo ""
sleep 1

# Test 3: High-Risk Transaction (Signifyd)
echo -e "${YELLOW}Test 3: High-Risk Transaction via Signifyd${NC}"
curl -s -X POST "$API_URL/check" \
  -H "Content-Type: application/json" \
  -d '{
    "transaction_id": "txn_high_risk_001",
    "merchant_id": "merch_002",
    "amount": 9999.99,
    "currency": "USD",
    "transaction_type": "capture",
    "card_bin": "666666",
    "card_last_four": "6666",
    "payment_method": "adyen",
    "customer_email": "suspicious@example.com",
    "provider_preference": "Signifyd"
  }' | jq '.'
echo ""
sleep 1

# Test 4: Async Processing
echo -e "${YELLOW}Test 4: Async Fraud Check${NC}"
curl -s -X POST "$API_URL/check/async" \
  -H "Content-Type: application/json" \
  -d '{
    "transaction_id": "txn_async_001",
    "merchant_id": "merch_003",
    "amount": 199.99,
    "currency": "EUR",
    "transaction_type": "auth",
    "async_mode": true
  }' | jq '.'
echo ""
sleep 1

# Test 5: Multiple Rapid Requests
echo -e "${YELLOW}Test 5: Load Test (10 rapid requests)${NC}"
for i in {1..10}; do
  curl -s -X POST "$API_URL/check" \
    -H "Content-Type: application/json" \
    -d "{
      \"transaction_id\": \"txn_load_$i\",
      \"merchant_id\": \"merch_load_test\",
      \"amount\": $((RANDOM % 500 + 50)),
      \"currency\": \"USD\",
      \"transaction_type\": \"auth\",
      \"payment_method\": \"stripe\"
    }" > /dev/null &
done
wait
echo -e "${GREEN}✓ Load test completed${NC}"
echo ""
sleep 2

# Display Metrics
echo -e "${YELLOW}================================${NC}"
echo -e "${YELLOW}Metrics Summary${NC}"
echo -e "${YELLOW}================================${NC}"
echo ""

echo -e "${GREEN}1. Request Metrics:${NC}"
curl -s "$BASE_URL/actuator/prometheus" | grep 'fraud_switch_fraud_router_requests_total{' | head -5
echo ""

echo -e "${GREEN}2. Fraud Decisions:${NC}"
curl -s "$BASE_URL/actuator/prometheus" | grep 'fraud_switch_fraud_router_fraud_decisions_total{' | head -5
echo ""

echo -e "${GREEN}3. Provider Calls:${NC}"
curl -s "$BASE_URL/actuator/prometheus" | grep 'fraud_switch_fraud_router_provider_calls_total{' | head -5
echo ""

echo -e "${GREEN}4. Routing Decisions:${NC}"
curl -s "$BASE_URL/actuator/prometheus" | grep 'fraud_switch_fraud_router_routing_decisions_total{' | head -5
echo ""

echo -e "${GREEN}5. Kafka Publishes:${NC}"
curl -s "$BASE_URL/actuator/prometheus" | grep 'fraud_switch_fraud_router_kafka_publishes_total{' | head -5
echo ""

echo -e "${GREEN}6. External Calls:${NC}"
curl -s "$BASE_URL/actuator/prometheus" | grep 'fraud_switch_fraud_router_external_calls_total{' | head -5
echo ""

echo -e "${GREEN}7. Latency Percentiles (p99):${NC}"
curl -s "$BASE_URL/actuator/prometheus" | grep 'fraud_router_request_duration_seconds.*quantile="0.99"' | head -3
echo ""

echo "=================================="
echo -e "${GREEN}Test Complete!${NC}"
echo "=================================="
echo ""
echo "View full metrics: $BASE_URL/actuator/prometheus"
echo "View health: $BASE_URL/actuator/health"
echo ""
