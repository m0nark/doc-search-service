# Architecture Design Document

# Distributed Document Search Service

## Overview

This document describes the architecture and design decisions for a distributed multi-tenant document search platform capable of handling millions of documents with low-latency full-text search.

The system is designed around:

* asynchronous event-driven indexing
* horizontal scalability
* tenant isolation
* fault tolerance
* eventual consistency
* distributed caching

The implementation uses:

* Java 17
* Spring Boot 3
* PostgreSQL
* Elasticsearch
* Apache Kafka
* Redis
* Docker Compose

---

# High-Level Architecture

```text
                           ┌──────────────────────┐
                           │      Clients         │
                           │----------------------│
                           │ REST / HTTP Requests │
                           └──────────┬───────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │ Spring Boot API Service         │
                    │---------------------------------│
                    │ - JWT Authentication            │
                    │ - Tenant Isolation              │
                    │ - Request Validation            │
                    │ - Rate Limiting                 │
                    │ - Search APIs                   │
                    │ - Caching Layer                 │
                    └───────┬─────────────┬──────────┘
                            │             │
                ┌───────────┘             └──────────────┐
                ▼                                        ▼
      ┌──────────────────┐                   ┌──────────────────┐
      │ PostgreSQL       │                   │ Redis            │
      │------------------│                   │------------------│
      │ Source of Truth  │                   │ Search Cache     │
      │ Document Storage │                   │ Rate Limiting    │
      └────────┬─────────┘                   └──────────────────┘
               │
               │ Publish Event
               ▼
      ┌──────────────────┐
      │ Apache Kafka     │
      │------------------│
      │ document-events  │
      │ Retry Topics     │
      │ Dead Letter Queue│
      └────────┬─────────┘
               │
               ▼
      ┌──────────────────────────┐
      │ DocumentIndexingConsumer │
      │--------------------------│
      │ Async ES Indexing        │
      │ Retry Handling           │
      │ DLT Processing           │
      └──────────┬───────────────┘
                 │
                 ▼
      ┌──────────────────────────┐
      │ Elasticsearch            │
      │--------------------------│
      │ Full-Text Search         │
      │ Relevance Ranking        │
      │ Highlighting             │
      │ Fuzzy Matching           │
      └──────────────────────────┘
```

---

# Indexing Flow

The platform uses asynchronous indexing to decouple write latency from search indexing operations.

## Document Indexing Sequence

```text
Client
  │
  │ POST /documents
  ▼
Spring Boot API
  │
  │ Persist document
  ▼
PostgreSQL
  │
  │ Publish Kafka event
  ▼
Kafka Topic: document-events
  │
  ▼
DocumentIndexingConsumer
  │
  │ Transform entity → search document
  ▼
Elasticsearch
  │
  │ Mark ACTIVE
  ▼
PostgreSQL
```

## Key Characteristics

### Low Write Latency

The API returns `202 Accepted` immediately after persistence and event publication.

This prevents Elasticsearch indexing delays from affecting API responsiveness.

---

### Eventual Consistency

The search index is eventually consistent with PostgreSQL.

This tradeoff was chosen because:

* search indexing is asynchronous
* distributed indexing scales better
* failures can be retried independently
* Kafka provides durable event persistence

---

### Retry Handling

Kafka consumers use:

* exponential backoff
* retry topics
* dead letter topics (DLT)

Retry pattern:

```text
1s → 2s → 4s → DLT
```

This prevents transient Elasticsearch failures from causing data loss.

---

# Search Flow

## Search Request Sequence

```text
Client
  │
  │ GET /search?q=distributed
  ▼
Spring Boot API
  │
  │ Check Redis cache
  ▼
Redis
  │
  ├── Cache Hit → Return Results
  │
  └── Cache Miss
           │
           ▼
     Elasticsearch
           │
           ▼
     Cache Results
           │
           ▼
         Client
```

---

# Storage Strategy

## PostgreSQL

PostgreSQL acts as the primary transactional datastore.

### Responsibilities

* source of truth
* tenant-scoped document persistence
* strong consistency for writes
* transactional guarantees
* metadata storage

### Why PostgreSQL?

* ACID guarantees
* mature transactional engine
* reliable persistence
* efficient relational querying
* strong ecosystem support

---

## Elasticsearch

Elasticsearch is used as a dedicated search projection layer.

### Responsibilities

* full-text search
* BM25 relevance scoring
* fuzzy matching
* result highlighting
* distributed indexing
* low-latency retrieval

### Why Elasticsearch?

* optimized inverted indexes
* scalable distributed search
* advanced text analysis
* efficient ranking algorithms
* near real-time indexing

---

## Redis

Redis is used for:

* search result caching
* document caching
* rate limiting
* temporary low-latency state

### Caching Strategy

The system implements a cache-aside pattern.

Search results are cached for short durations because:

* search workloads are highly repetitive
* Elasticsearch queries are expensive
* eventual consistency is acceptable

---

## Kafka

Kafka is used for asynchronous event processing.

### Responsibilities

* durable event persistence
* decoupled indexing pipeline
* retry handling
* replay capability
* scalable consumers

### Why Kafka?

* high throughput
* horizontal scalability
* partitioned ordering
* durable message retention
* distributed fault tolerance

---

# Multi-Tenancy Strategy

The platform supports logical tenant isolation.

## Tenant Identification

Tenant identity is provided through:

```text
X-Tenant-ID
```

header propagation.

---

## Isolation Strategy

### PostgreSQL Isolation

All queries are tenant-scoped:

```sql
WHERE tenant_id = ?
```

---

### Elasticsearch Isolation

All search requests apply tenant filters:

```text
term query on tenantId
```

This prevents cross-tenant document leakage.

---

## Production Considerations

For production systems:

* tenant identity should be derived from JWT claims
* row-level security may be enabled
* tenant-specific indexes may be used for very large tenants

---

# Consistency Model

The platform uses:

```text
Strong consistency for writes
Eventual consistency for search
```

## Rationale

### Why not synchronous indexing?

Synchronous Elasticsearch indexing would:

* increase API latency
* tightly couple write availability to Elasticsearch
* reduce resilience during ES outages

### Benefits of eventual consistency

* lower latency
* better fault isolation
* scalable indexing pipeline
* retryable workflows

### Tradeoff

Recently created documents may not appear immediately in search results.

This is acceptable for most search-driven systems.

---

# API Design

## Create Document

```http
POST /api/v1/documents
```

### Response

```json
{
  "success": true,
  "message": "Document accepted for indexing"
}
```

Returns:

```text
202 Accepted
```

because indexing is asynchronous.

---

## Search Documents

```http
GET /api/v1/search?q=distributed
```

### Features

* fuzzy matching
* pagination
* highlighting
* category filtering
* author filtering
* tag filtering

---

## Retrieve Document

```http
GET /api/v1/documents/{id}
```

Returns the canonical PostgreSQL document.

---

## Delete Document

```http
DELETE /api/v1/documents/{id}
```

Soft deletes document and asynchronously removes it from Elasticsearch.

---

# Caching Strategy

## Cache Layers

| Layer            | Purpose                         |
| ---------------- | ------------------------------- |
| Search Cache     | Cache repeated ES queries       |
| Document Cache   | Cache frequent document lookups |
| Rate Limit Cache | Store tenant counters           |

---

## Cache Invalidation

### Search Results

Short TTL-based invalidation.

### Document Cache

Evicted during:

* update
* delete
* reindex

---

# Resilience Strategy

## Circuit Breakers

Resilience4j protects Elasticsearch operations.

Benefits:

* prevents cascading failures
* enables graceful degradation
* avoids thread exhaustion

---

## Retry Handling

Retries are enabled for:

* transient ES failures
* network instability
* temporary downstream outages

---

## Dead Letter Topics

Failed events are routed to DLTs for:

* replay
* debugging
* operational intervention

---

# Horizontal Scalability

## API Layer

The API service is stateless and horizontally scalable.

Scaling approach:

* multiple Spring Boot instances
* load balancer distribution
* shared Redis/Kafka/Elasticsearch clusters

---

## Kafka Scaling

Kafka partitions allow:

* parallel consumers
* tenant-based ordering
* scalable indexing throughput

Events are partitioned using:

```text
tenantId
```

as the partition key.

---

## Elasticsearch Scaling

Elasticsearch supports:

* shard scaling
* replica scaling
* distributed querying
* distributed indexing

---

# Security Considerations

## Authentication

JWT-based authentication is implemented.

---

## Authorization

Tenant-scoped filtering prevents cross-tenant access.

---

## Production Security Improvements

Future enhancements include:

* TLS everywhere
* encrypted secrets
* OAuth2/OpenID Connect
* role-based access control
* audit logging
* API gateway integration

---

# Observability

## Metrics

Micrometer + Prometheus metrics include:

* search latency
* indexing throughput
* JVM metrics
* cache statistics
* Kafka consumer metrics

---

## Logging

Structured logging includes:

* tenant identifiers
* request identifiers
* correlation IDs

---

## Health Checks

Health indicators monitor:

* PostgreSQL
* Redis
* Elasticsearch
* application readiness

---

# Key Architectural Tradeoffs

| Decision                 | Benefit               | Tradeoff              |
| ------------------------ | --------------------- | --------------------- |
| Async indexing           | Low latency writes    | Eventual consistency  |
| Redis caching            | Faster searches       | Potential stale data  |
| Elasticsearch projection | Advanced search       | Dual-write complexity |
| Kafka retries            | Resilience            | Operational overhead  |
| Multi-tenant filtering   | Shared infrastructure | Query complexity      |

---

# Conclusion

The Distributed Document Search Service demonstrates enterprise-grade backend engineering patterns focused on scalability, resiliency, and operational maturity.

The system combines:
- transactional persistence using PostgreSQL
- asynchronous event-driven indexing using Kafka
- low-latency distributed search using Elasticsearch
- distributed caching using Redis
- tenant isolation and JWT-based security
- retry/DLT handling for fault tolerance
- observability through metrics, health checks, and structured logging

The architecture intentionally separates:
- write workloads
- search workloads
- asynchronous indexing pipelines

to improve scalability and reduce coupling between components.

---

# Architectural Tradeoffs Summary

| Area | Decision | Benefit | Tradeoff |
|---|---|---|---|
| Search Indexing | Asynchronous Kafka pipeline | Low write latency and decoupled indexing | Eventual consistency |
| Search Engine | Elasticsearch projection layer | Advanced full-text search and relevance ranking | Additional infrastructure complexity |
| Caching | Redis cache-aside strategy | Faster repeated searches and reduced ES load | Potential stale reads |
| Multi-Tenancy | Shared infrastructure with tenant filtering | Lower operational cost and simpler deployment | Additional query filtering overhead |
| Retry Handling | Kafka retry topics + DLT | Resilience against transient failures | Increased operational complexity |
| API Design | Stateless Spring Boot services | Horizontal scalability | Externalized session/state management |
| Storage Model | PostgreSQL as source of truth | Strong transactional guarantees | Dual-write synchronization challenges |
| Distributed Messaging | Kafka event streaming | Durable asynchronous workflows | More moving infrastructure components |

---

# Final Notes

The prototype is intentionally designed to demonstrate:
- distributed systems thinking
- scalability awareness
- production-readiness considerations
- fault-tolerant architecture patterns
- real-world backend engineering tradeoffs

While simplified for assessment scope, the architecture can evolve into a production-grade platform through:
- Kubernetes deployment
- autoscaling
- OpenTelemetry tracing
- advanced security hardening
- multi-region replication
- vector/semantic search
- blue-green deployments
- SLA-driven infrastructure scaling