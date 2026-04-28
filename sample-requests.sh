#!/usr/bin/env bash
# =============================================================
# Document Search Service — Sample API Requests
# =============================================================
# Prerequisites:
#   - Service running on localhost:8080 (docker-compose up)
#   - jq installed for pretty-printing (brew install jq)
# =============================================================

BASE_URL="http://localhost:8080/api/v1"

# Generate a JWT for testing (in prod this comes from your auth service)
# For dev, set any Bearer token — security is permit-all in dev profile
TOKEN="Bearer dev-token"
TENANT_A="tenant-alpha"
TENANT_B="tenant-beta"

echo "=============================="
echo "1. Health Check"
echo "=============================="
curl -s http://localhost:8080/actuator/health | jq .

echo ""
echo "=============================="
echo "2. Index Document (Tenant A)"
echo "=============================="
RESPONSE=$(curl -s -X POST "$BASE_URL/documents" \
  -H "Authorization: $TOKEN" \
  -H "X-Tenant-ID: $TENANT_A" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Designing Distributed Systems at Scale",
    "content": "Distributed systems require careful consideration of CAP theorem. In a network partition, you must choose between consistency and availability. Modern systems often use eventual consistency to achieve high availability while accepting temporary inconsistencies. Techniques such as vector clocks, CRDTs, and saga patterns help manage distributed state.",
    "author": "Alice Engineer",
    "category": "engineering",
    "tags": ["distributed-systems", "architecture", "backend", "consistency"],
    "metadata": {
      "version": "2.0",
      "language": "en",
      "source": "internal-wiki"
    }
  }')
echo $RESPONSE | jq .
DOC_ID=$(echo $RESPONSE | jq -r '.data.id')
echo "Created document ID: $DOC_ID"

echo ""
echo "=============================="
echo "3. Index Second Document (Tenant A)"
echo "=============================="
curl -s -X POST "$BASE_URL/documents" \
  -H "Authorization: $TOKEN" \
  -H "X-Tenant-ID: $TENANT_A" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Redis Caching Patterns for High-Traffic APIs",
    "content": "Redis provides multiple caching strategies: Cache-Aside (lazy loading), Write-Through, Write-Behind, and Refresh-Ahead. For search result caching with short TTLs, Cache-Aside works best. Use Redis Cluster for horizontal scaling and Sentinel for high availability in production.",
    "author": "Bob Devops",
    "category": "infrastructure",
    "tags": ["redis", "caching", "performance", "backend"]
  }' | jq .

echo ""
echo "=============================="
echo "4. Index Document (Tenant B — isolated)"
echo "=============================="
curl -s -X POST "$BASE_URL/documents" \
  -H "Authorization: $TOKEN" \
  -H "X-Tenant-ID: $TENANT_B" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Tenant B Private Document",
    "content": "This document belongs to Tenant B and should never appear in Tenant A searches.",
    "author": "Eve Tenant",
    "category": "private"
  }' | jq .

echo ""
echo "=============================="
echo "5. Search — Basic Query"
echo "=============================="
# Wait a moment for ES indexing (async via Kafka)
sleep 2
curl -s "$BASE_URL/search?q=distributed+systems&highlight=true" \
  -H "Authorization: $TOKEN" \
  -H "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=============================="
echo "6. Search — Fuzzy Match (misspelling)"
echo "=============================="
curl -s "$BASE_URL/search?q=distribted+sistems&fuzzy=true" \
  -H "Authorization: $TOKEN" \
  -H "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=============================="
echo "7. Search — Faceted (filter by category)"
echo "=============================="
curl -s "$BASE_URL/search?q=caching&category=infrastructure" \
  -H "Authorization: $TOKEN" \
  -H "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=============================="
echo "8. Search — Filter by tags"
echo "=============================="
curl -s "$BASE_URL/search?q=backend&tags=redis&tags=caching" \
  -H "Authorization: $TOKEN" \
  -H "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=============================="
echo "9. Search — Pagination"
echo "=============================="
curl -s "$BASE_URL/search?q=systems&page=0&size=5" \
  -H "Authorization: $TOKEN" \
  -H "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=============================="
echo "10. Get Document by ID"
echo "=============================="
curl -s "$BASE_URL/documents/$DOC_ID" \
  -H "Authorization: $TOKEN" \
  -H "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=============================="
echo "11. Tenant Isolation — Tenant B cannot access Tenant A doc"
echo "=============================="
curl -s "$BASE_URL/documents/$DOC_ID" \
  -H "Authorization: $TOKEN" \
  -H "X-Tenant-ID: $TENANT_B" | jq .

echo ""
echo "=============================="
echo "12. Rate Limiting Demo (run quickly)"
echo "=============================="
echo "Sending 5 rapid search requests to demonstrate rate limit headers..."
for i in {1..5}; do
  curl -s -o /dev/null -w "Request $i: HTTP %{http_code}\n" \
    "$BASE_URL/search?q=test" \
    -H "Authorization: $TOKEN" \
    -H "X-Tenant-ID: rate-limit-test"
done

echo ""
echo "=============================="
echo "13. Missing Tenant Header — should return 400"
echo "=============================="
curl -s "$BASE_URL/search?q=test" \
  -H "Authorization: $TOKEN" | jq .

echo ""
echo "=============================="
echo "14. Delete Document"
echo "=============================="
curl -s -X DELETE "$BASE_URL/documents/$DOC_ID" \
  -H "Authorization: $TOKEN" \
  -H "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=============================="
echo "15. Confirm deletion — should return 404"
echo "=============================="
curl -s "$BASE_URL/documents/$DOC_ID" \
  -H "Authorization: $TOKEN" \
  -H "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=============================="
echo "16. Prometheus Metrics"
echo "=============================="
curl -s http://localhost:8080/actuator/prometheus | grep "document\." | head -20

echo ""
echo "All requests complete."
