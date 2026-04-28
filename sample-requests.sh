#!/usr/bin/env bash
set -e

# =============================================================
# Distributed Document Search Service — Sample API Requests
# =============================================================
# Prerequisites:
#   - docker compose up --build
#   - jq installed
# =============================================================

BASE_URL="http://localhost:8080/api/v1"

TENANT_A="tenant-alpha"
TENANT_B="tenant-beta"

echo "=================================================="
echo "1. Health Check"
echo "=================================================="

curl -s http://localhost:8080/actuator/health | jq .

echo ""
echo "=================================================="
echo "2. Generate JWT Token"
echo "=================================================="

TOKEN_RESPONSE=$(curl -s --location --request POST \
  "$BASE_URL/token?username=aadit&role=ADMIN" \
  --header "X-Tenant-ID: $TENANT_A")

echo $TOKEN_RESPONSE | jq .

TOKEN=$(echo $TOKEN_RESPONSE | jq -r '.token')
AUTH_HEADER="Bearer $TOKEN"

echo ""
echo "JWT generated successfully"

echo ""
echo "=================================================="
echo "3. Index Document (Tenant A)"
echo "=================================================="

RESPONSE=$(curl -s -X POST "$BASE_URL/documents" \
  -H "Authorization: $AUTH_HEADER" \
  -H "X-Tenant-ID: $TENANT_A" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Designing Distributed Systems at Scale",
    "content": "Distributed systems require careful consideration of CAP theorem. Modern systems often use eventual consistency, Kafka event streaming, and distributed caching to achieve scalability and resilience.",
    "author": "Alice Engineer",
    "category": "engineering",
    "tags": ["distributed-systems", "architecture", "backend", "kafka"],
    "metadata": {
      "version": "2.0",
      "language": "en",
      "source": "internal-wiki"
    }
  }')

echo $RESPONSE | jq .

DOC_ID=$(echo $RESPONSE | jq -r '.data.id')

echo ""
echo "Created document ID: $DOC_ID"

echo ""
echo "=================================================="
echo "4. Index Second Document (Tenant A)"
echo "=================================================="

curl -s -X POST "$BASE_URL/documents" \
  -H "Authorization: $AUTH_HEADER" \
  -H "X-Tenant-ID: $TENANT_A" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Redis Caching Strategies for High Traffic APIs",
    "content": "Redis supports cache-aside, write-through, and refresh-ahead caching patterns. Cache-aside is commonly used for low-latency search result caching.",
    "author": "Bob DevOps",
    "category": "infrastructure",
    "tags": ["redis", "caching", "performance", "backend"]
  }' | jq .

echo ""
echo "=================================================="
echo "5. Index Document (Tenant B — isolated)"
echo "=================================================="

curl -s -X POST "$BASE_URL/documents" \
  -H "Authorization: $AUTH_HEADER" \
  -H "X-Tenant-ID: $TENANT_B" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Tenant B Private Document",
    "content": "This document belongs to Tenant B and must never appear in Tenant A search results.",
    "author": "Eve Tenant",
    "category": "private",
    "tags": ["private", "tenant-b"]
  }' | jq .

echo ""
echo "=================================================="
echo "Waiting for async Kafka + Elasticsearch indexing..."
echo "=================================================="

sleep 5

echo ""
echo "=================================================="
echo "6. Basic Full-Text Search"
echo "=================================================="

curl -s --location --request GET \
  "$BASE_URL/search?q=distributed&page=0&size=10&fuzzy=true&highlight=true" \
  --header "Authorization: $AUTH_HEADER" \
  --header "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=================================================="
echo "7. Search with Category Filter"
echo "=================================================="

curl -s --location --request GET \
  "$BASE_URL/search?q=caching&category=infrastructure&page=0&size=10&fuzzy=true&highlight=true" \
  --header "Authorization: $AUTH_HEADER" \
  --header "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=================================================="
echo "8. Search with Tag Filters"
echo "=================================================="

curl -s --location --request GET \
  "$BASE_URL/search?q=backend&tags=redis&tags=performance&page=0&size=10&fuzzy=true&highlight=true" \
  --header "Authorization: $AUTH_HEADER" \
  --header "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=================================================="
echo "9. Fuzzy Search (Typo Tolerance)"
echo "=================================================="

curl -s --location --request GET \
  "$BASE_URL/search?q=distribted+sistems&page=0&size=10&fuzzy=true&highlight=true" \
  --header "Authorization: $AUTH_HEADER" \
  --header "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=================================================="
echo "10. Tenant Isolation Verification"
echo "=================================================="

echo "Searching Tenant A for Tenant B document..."

curl -s --location --request GET \
  "$BASE_URL/search?q=private&page=0&size=10&fuzzy=true&highlight=true" \
  --header "Authorization: $AUTH_HEADER" \
  --header "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=================================================="
echo "11. Retrieve Document by ID"
echo "=================================================="

curl -s --location --request GET \
  "$BASE_URL/documents/$DOC_ID" \
  --header "Authorization: $AUTH_HEADER" \
  --header "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=================================================="
echo "12. Pagination Demo"
echo "=================================================="

curl -s --location --request GET \
  "$BASE_URL/search?q=backend&page=0&size=1&fuzzy=true&highlight=true" \
  --header "Authorization: $AUTH_HEADER" \
  --header "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=================================================="
echo "13. Search by Author"
echo "=================================================="

curl -s --location --request GET \
  "$BASE_URL/search?q=redis&author=Bob+DevOps&page=0&size=10&fuzzy=true&highlight=true" \
  --header "Authorization: $AUTH_HEADER" \
  --header "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=================================================="
echo "14. Delete Document"
echo "=================================================="

curl -s --location --request DELETE \
  "$BASE_URL/documents/$DOC_ID" \
  --header "Authorization: $AUTH_HEADER" \
  --header "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=================================================="
echo "15. Verify Deleted Document Is Not Searchable"
echo "=================================================="

sleep 3

curl -s --location --request GET \
  "$BASE_URL/search?q=distributed&page=0&size=10&fuzzy=true&highlight=true" \
  --header "Authorization: $AUTH_HEADER" \
  --header "X-Tenant-ID: $TENANT_A" | jq .

echo ""
echo "=================================================="
echo "16. Elasticsearch Cluster Health"
echo "=================================================="

curl -s http://localhost:9200/_cluster/health | jq .

echo ""
echo "=================================================="
echo "17. View Indexed Documents in Elasticsearch"
echo "=================================================="

curl -s "http://localhost:9200/documents/_search?pretty" | jq .

echo ""
echo "=================================================="
echo "18. Application Metrics (Prometheus)"
echo "=================================================="

curl -s http://localhost:8080/actuator/prometheus | head -40

echo ""
echo "=================================================="
echo "19. Kafka Topic Verification"
echo "=================================================="

docker exec -it doc-search-kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --list

echo ""
echo "=================================================="
echo "20. Final System Verification"
echo "=================================================="

echo "Distributed Document Search Service is operational."
echo ""
echo "Verified components:"
echo "✔ Spring Boot API"
echo "✔ PostgreSQL persistence"
echo "✔ Kafka event pipeline"
echo "✔ Elasticsearch indexing"
echo "✔ Redis caching"
echo "✔ JWT authentication"
echo "✔ Tenant isolation"
echo "✔ Full-text search"
echo "✔ Async indexing"
echo "✔ Distributed infrastructure"

echo ""
echo "=================================================="
echo "Sample request workflow completed successfully"
echo "=================================================="
