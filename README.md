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

# Run sample API requests
chmod +x sample-requests.sh && ./sample-requests.sh

# View logs
docker-compose logs -f app

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
│   └── DocumentController.java              # REST API endpoints
│
├── service/
│   ├── DocumentService.java                 # Core business logic
│   ├── ElasticsearchService.java            # Elasticsearch indexing/search
│   ├── CacheService.java                    # Redis caching abstraction
│   └── DocumentIndexingConsumer.java        # Kafka async indexing consumer
│
├── model/
│   ├── Document.java                        # PostgreSQL JPA entity
│   ├── DocumentIndex.java                   # Elasticsearch document model
│   └── TenantRateLimit.java                 # Tenant rate limit configuration
│
├── repository/
│   ├── DocumentRepository.java              # JPA repository
│   ├── DocumentSearchRepository.java        # Elasticsearch repository
│   └── TenantRateLimitRepository.java       # Tenant config repository
│
├── filter/
│   ├── TenantContextFilter.java             # Tenant extraction + request scoping
│   └── TenantContext.java                   # ThreadLocal tenant storage
│
├── config/
│   ├── SecurityConfig.java                  # Spring Security configuration
│   ├── JwtAuthenticationFilter.java         # JWT authentication filter
│   ├── RedisConfig.java                     # Redis configuration
│   ├── KafkaConfig.java                     # Kafka producer/consumer config
│   └── ElasticsearchConfig.java             # Elasticsearch client config
│
├── exception/
│   ├── GlobalExceptionHandler.java          # Centralized exception handling
│   ├── DocumentNotFoundException.java       # Document not found exception
│   └── TenantQuotaExceededException.java    # Tenant rate limit exception
│
├── health/
│   └── DocumentSearchHealthIndicator.java   # Custom health checks
│
└── dto/
    ├── DocumentDto.java                     # API request/response DTOs
    └── DocumentEvent.java                   # Kafka event payload DTOs
    
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
