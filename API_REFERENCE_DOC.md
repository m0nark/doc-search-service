# API Quick Reference

# Distributed Document Search Service

This document contains ready-to-run API requests for quickly testing the platform.

Base URL:

```text
http://localhost:8080
```

---

# 1. Health Check

```bash
curl --location --request GET 'http://localhost:8080/actuator/health'
```

---

# 2. Generate JWT Token

```bash
curl --location --request POST 'http://localhost:8080/api/v1/token?username=aadit&role=ADMIN' \
--header 'X-Tenant-ID: tenant-1'
```

## Sample Response

```json
{
  "tokenType": "Bearer",
  "token": "YOUR_JWT_TOKEN"
}
```

---

# 3. Create Document

Replace:

```text
YOUR_JWT_TOKEN
```

with the token generated above.

```bash
curl --location --request POST 'http://localhost:8080/api/v1/documents' \
--header 'Authorization: Bearer YOUR_JWT_TOKEN' \
--header 'X-Tenant-ID: tenant-1' \
--header 'Content-Type: application/json' \
--data-raw '{
    "title": "Truffle Mushroom Pizza",
    "content": "Wood fired pizza with truffle oil, parmesan foam and roasted mushrooms",
    "author": "Aadit",
    "category": "food",
    "tags": ["pizza", "truffle", "mushroom"],
    "metadata": {
        "city": "Delhi",
        "restaurant": "Labyrinth"
    }
}'
```

## Sample Response

```json
{
  "success": true,
  "message": "Document accepted for indexing. It will be searchable shortly.",
  "data": {
    "id": "40164597-a783-4b54-b0d2-752412f479d4",
    "tenantId": "tenant-1",
    "title": "Truffle Mushroom Pizza",
    "status": "PROCESSING"
  }
}
```

---

# 4. Search Documents

## Basic Search

```bash
curl --location --request GET 'http://localhost:8080/api/v1/search?q=truffle&page=0&size=10&fuzzy=true&highlight=true' \
--header 'Authorization: Bearer YOUR_JWT_TOKEN' \
--header 'X-Tenant-ID: tenant-1'
```

---

## Search With Category Filter

```bash
curl --location --request GET 'http://localhost:8080/api/v1/search?q=pizza&category=food&page=0&size=10&fuzzy=true&highlight=true' \
--header 'Authorization: Bearer YOUR_JWT_TOKEN' \
--header 'X-Tenant-ID: tenant-1'
```

---

## Search With Author Filter

```bash
curl --location --request GET 'http://localhost:8080/api/v1/search?q=truffle&author=Aadit&page=0&size=10&fuzzy=true&highlight=true' \
--header 'Authorization: Bearer YOUR_JWT_TOKEN' \
--header 'X-Tenant-ID: tenant-1'
```

---

## Search With Tag Filters

```bash
curl --location --request GET 'http://localhost:8080/api/v1/search?q=pizza&tags=truffle&tags=mushroom&page=0&size=10&fuzzy=true&highlight=true' \
--header 'Authorization: Bearer YOUR_JWT_TOKEN' \
--header 'X-Tenant-ID: tenant-1'
```

---

## Fuzzy Search Demo

```bash
curl --location --request GET 'http://localhost:8080/api/v1/search?q=trufle+piza&page=0&size=10&fuzzy=true&highlight=true' \
--header 'Authorization: Bearer YOUR_JWT_TOKEN' \
--header 'X-Tenant-ID: tenant-1'
```

## Sample Response

```json
{
  "success": true,
  "data": {
    "results": [
      {
        "id": "40164597-a783-4b54-b0d2-752412f479d4",
        "tenantId": "tenant-1",
        "title": "Truffle Mushroom Pizza",
        "author": "Aadit",
        "category": "food",
        "tags": [
          "pizza",
          "truffle",
          "mushroom"
        ],
        "score": 0.8630463,
        "highlights": {
          "title": [
            "<em>Truffle</em> Mushroom Pizza"
          ],
          "content": [
            "Wood fired pizza with <em>truffle</em> oil, parmesan foam and roasted mushrooms"
          ]
        }
      }
    ],
    "totalHits": 1,
    "page": 0,
    "size": 10,
    "tookMs": 215
  }
}
```

---

# 5. Retrieve Document By ID

Replace:

```text
DOCUMENT_ID
```

with the ID returned during document creation.

```bash
curl --location --request GET 'http://localhost:8080/api/v1/documents/DOCUMENT_ID' \
--header 'Authorization: Bearer YOUR_JWT_TOKEN' \
--header 'X-Tenant-ID: tenant-1'
```

---

# 6. Delete Document

```bash
curl --location --request DELETE 'http://localhost:8080/api/v1/documents/DOCUMENT_ID' \
--header 'Authorization: Bearer YOUR_JWT_TOKEN' \
--header 'X-Tenant-ID: tenant-1'
```

## Sample Response

```json
{
  "success": true,
  "message": "Document deleted successfully"
}
```

---

# 7. Elasticsearch Verification

Verify indexed documents directly in Elasticsearch.

```bash
curl --location --request GET 'http://localhost:9200/documents/_search?pretty'
```

---

# 8. Prometheus Metrics

```bash
curl --location --request GET 'http://localhost:8080/actuator/prometheus'
```

---

# 9. Multi-Tenant Isolation Demo

Create a document under:

```text
tenant-2
```

then search using:

```text
tenant-1
```

The document will not appear because all search queries are tenant-scoped.

---

# Notes

* Search indexing is asynchronous via Kafka.
* Newly created documents may take a few seconds to become searchable.
* Elasticsearch is used for full-text search.
* PostgreSQL acts as the transactional source of truth.
* Redis is used for caching.
* Kafka is used for asynchronous indexing and retry handling.
