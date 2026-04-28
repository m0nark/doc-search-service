# Document Search Service

A production-grade distributed document search microservice built with Spring Boot 3, Elasticsearch, Redis, Kafka, and PostgreSQL.

## Architecture Overview

```
Client
  │
  ▼
[Spring Boot REST API]
  │     │
  │     ├─── TenantContextFilter (tenant isolation + rate limiting via Redis)
  │     ├─── JwtAuthenticationFilter (auth)
  │     └─── GlobalExceptionHandler
  │
  ├─── [PostgreSQL]        — Source of truth, document storage
  ├─── [Redis]             — Search result cache (30s TTL), rate limit counters
  ├─── [Kafka]             — Async ES indexing events (decouples write latency)
  └─── [Elasticsearch]     — Full-text search index with BM25 ranking
            ▲
            └──── [DocumentIndexingConsumer] (Kafka consumer → ES write)
```

### Write Path
1. `POST /documents` → validate → persist to PostgreSQL (PROCESSING status)
2. Publish `DOCUMENT_INDEXED` event to Kafka (tenant-keyed partition)
3. Return `202 Accepted` immediately — no ES latency on write path
4. Kafka consumer reads event → fetches doc → indexes to ES → updates status to ACTIVE

### Read/Search Path
1. `GET /search` → check Redis cache (30s TTL)
2. Cache miss → query Elasticsearch with BM25 + fuzzy + tenant filter
3. Cache populated → return results

## Features

- **Multi-tenancy**: Strict tenant isolation via `X-Tenant-ID` header; PostgreSQL row-level filtering + ES query-level tenant filter
- **Full-text search**: BM25 relevance ranking, fuzzy matching (AUTO fuzziness), result highlighting, faceted filters
- **Caching**: Redis search result cache (30s TTL), document cache (10m TTL), fail-open on Redis unavailability
- **Rate limiting**: Per-tenant sliding window rate limits via Redis atomic increment
- **Async indexing**: Kafka decouples HTTP write latency from ES indexing latency
- **Resilience**: Circuit breaker (Resilience4j) on ES with fallback to empty results; Kafka retry with exponential backoff + DLT
- **Observability**: MDC logging (tenantId + requestId), Micrometer metrics, Prometheus endpoint, percentile histograms
- **Security**: JWT auth, stateless sessions, security headers, soft-delete (no data loss)

# Quick Start

## Prerequisites

Make sure the following are installed:

- Docker Desktop
- Java 17
- Maven 3.9+
- Git

---

## Clone Repository

```bash
git clone https://github.com/m0nark/doc-search-service
cd document-search-service

docker compose up --build

docker compose --profile dev-tools up --build

## API Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/documents` | Index a new document |
| `GET`  | `/api/v1/search?q={query}` | Full-text search |
| `GET`  | `/api/v1/documents/{id}` | Get document by ID |
| `DELETE` | `/api/v1/documents/{id}` | Soft-delete document |
| `GET`  | `/actuator/health` | Health check with dependency status |
| `GET`  | `/actuator/prometheus` | Prometheus metrics |

### Required Headers

| Header | Description |
|--------|-------------|
| `X-Tenant-ID` | Tenant identifier (alphanumeric, max 64 chars) |
| `Authorization` | `Bearer <jwt-token>` |

### Search Parameters

| Param | Default | Description |
|-------|---------|-------------|
| `q` | required | Search query |
| `page` | 0 | Page number |
| `size` | 10 | Results per page (max 100) |
| `category` | - | Filter by category |
| `author` | - | Filter by author |
| `tags` | - | Filter by tags (repeatable) |
| `fuzzy` | true | Enable fuzzy matching |
| `highlight` | true | Return highlighted excerpts |

## Tech Stack

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Application | Spring Boot 3.2, Java 17 | REST API, business logic |
| Primary DB | PostgreSQL 16 | Source of truth, ACID guarantees |
| Search | Elasticsearch 8.12 | Full-text BM25 search |
| Cache | Redis 7 | Search cache, rate limiting |
| Messaging | Apache Kafka | Async ES indexing pipeline |
| Migrations | Flyway | Schema versioning |
| Resilience | Resilience4j | Circuit breakers, retry |
| Metrics | Micrometer + Prometheus | Observability |
| Auth | JWT (jjwt) | Stateless authentication |

## Project Structure

```
src/main/java/com/docsearch/
├── controller/
│   └── DocumentController.java     # REST endpoints
├── service/
│   ├── DocumentService.java         # Core business logic
│   ├── ElasticsearchService.java    # ES query building
│   ├── CacheService.java            # Redis cache abstraction
│   └── DocumentIndexingConsumer.java # Kafka consumer
├── model/
│   ├── Document.java               # JPA entity
│   ├── DocumentIndex.java          # ES document model
│   └── TenantRateLimit.java        # Tenant config entity
├── repository/
│   ├── DocumentRepository.java
│   ├── DocumentSearchRepository.java
│   └── TenantRateLimitRepository.java
├── filter/
│   ├── TenantContextFilter.java    # Tenant extraction + rate limiting
│   └── TenantContext.java          # ThreadLocal tenant store
├── config/
│   ├── SecurityConfig.java
│   ├── JwtAuthenticationFilter.java
│   ├── RedisConfig.java
│   ├── KafkaConfig.java
│   └── ElasticsearchConfig.java 
├── exception/
│   ├── GlobalExceptionHandler.java
│   ├── DocumentNotFoundException.java
│   └── TenantQuotaExceededException.java
├── health/
│   └── DocumentSearchHealthIndicator.java
└── dto/
    ├── DocumentDto.java             # Request/response DTOs
    └── DocumentEvent.java           # Kafka event DTOs
```

## Configuration

Key environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | localhost | PostgreSQL host |
| `REDIS_HOST` | localhost | Redis host |
| `SPRING_ELASTICSEARCH_URIS` | http://localhost:9200 | Elasticsearch URI(s) |
| `KAFKA_BROKERS` | localhost:9092 | Kafka bootstrap servers |
| `JWT_SECRET` | (dev secret) | JWT signing secret (min 256 bits) |

## Rate Limits (per tenant)

| Operation | Default Limit |
|-----------|--------------|
| Search | 100 req/min |
| Write (index/delete) | 50 req/min |
| Max documents | 1,000,000 |

Configurable per-tenant in the `tenant_rate_limits` table.
