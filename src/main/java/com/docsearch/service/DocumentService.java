package com.docsearch.service;

import com.docsearch.dto.DocumentDto;
import com.docsearch.dto.DocumentEvent;
import com.docsearch.exception.DocumentNotFoundException;
import com.docsearch.exception.TenantQuotaExceededException;
import com.docsearch.model.Document;
import com.docsearch.model.DocumentIndex;
import com.docsearch.model.TenantRateLimit;
import com.docsearch.repository.DocumentRepository;
import com.docsearch.repository.TenantRateLimitRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final TenantRateLimitRepository tenantRateLimitRepository;
    private final ElasticsearchService elasticsearchService;
    private final KafkaTemplate<String, DocumentEvent.DocumentIndexEvent> kafkaTemplate;
    private final CacheService cacheService;

    private static final String TOPIC_DOCUMENT_EVENTS = "document-events";
    private static final String CACHE_DOCUMENT = "documents";

    // ===================== INDEX =====================

    @Transactional
    public DocumentDto.DocumentResponse indexDocument(String tenantId, DocumentDto.IndexRequest request) {
        // 1. Quota check — prevent runaway indexing per tenant
        enforceDocumentQuota(tenantId);

        // 2. Persist to PostgreSQL (source of truth)
        Document document = Document.builder()
                .tenantId(tenantId)
                .title(request.getTitle())
                .content(request.getContent())
                .author(request.getAuthor())
                .category(request.getCategory())
                .tags(request.getTags() != null ? request.getTags() : java.util.Collections.emptyList())
                .metadata(request.getMetadata() != null ? request.getMetadata() : new java.util.HashMap<>())
                .status(Document.DocumentStatus.PROCESSING)
                .build();

        document = documentRepository.save(document);
        log.info("Document saved to DB: id={} tenant={}", document.getId(), tenantId);

        // 3. Publish Kafka event for async ES indexing
        // Decouples write path from ES latency; provides retry on ES failures
        publishIndexEvent(document.getId(), tenantId, DocumentEvent.EventType.DOCUMENT_INDEXED);

        // 4. Return immediately — don't block on ES
        return toDocumentResponse(document);
    }

    // ===================== SEARCH =====================

    @CircuitBreaker(name = "elasticsearch", fallbackMethod = "searchFallback")
    @Retry(name = "elasticsearch")
    public DocumentDto.SearchResponse search(DocumentDto.SearchRequest request) {
        // Check Redis cache first for identical repeated queries
        String cacheKey = cacheService.buildSearchCacheKey(request);
        DocumentDto.SearchResponse cached = cacheService.getSearchResult(cacheKey);
        if (cached != null) {
            log.debug("Search cache hit for key={}", cacheKey);
            return cached;
        }

        DocumentDto.SearchResponse result = elasticsearchService.search(request);

        // Cache short-lived (30s) — search results are near real-time
        cacheService.putSearchResult(cacheKey, result);
        return result;
    }

    /**
     * Fallback when Elasticsearch is unavailable.
     * Graceful degradation: return empty result rather than 500.
     */
    public DocumentDto.SearchResponse searchFallback(DocumentDto.SearchRequest request, Throwable t) {
        log.error("Elasticsearch circuit open, returning empty results for tenant={}: {}", 
                request.getTenantId(), t.getMessage());
        return DocumentDto.SearchResponse.builder()
                .results(java.util.Collections.emptyList())
                .totalHits(0)
                .page(request.getPage())
                .size(request.getSize())
                .tookMs(0)
                .build();
    }

    // ===================== GET BY ID =====================

    @Cacheable(value = CACHE_DOCUMENT, key = "#tenantId + ':' + #documentId")
    @Transactional(readOnly = true)
    public DocumentDto.DocumentResponse getDocument(String tenantId, UUID documentId) {
        Document document = documentRepository
                .findByIdAndTenantIdAndStatus(documentId, tenantId, Document.DocumentStatus.ACTIVE)
                .orElseThrow(() -> new DocumentNotFoundException(
                        "Document not found: id=" + documentId + " tenant=" + tenantId));
        return toDocumentResponse(document);
    }

    // ===================== DELETE =====================

    @Transactional
    @CacheEvict(value = CACHE_DOCUMENT, key = "#tenantId + ':' + #documentId")
    public void deleteDocument(String tenantId, UUID documentId) {
        int updated = documentRepository.softDeleteByIdAndTenantId(documentId, tenantId);

        if (updated == 0) {
            throw new DocumentNotFoundException(
                    "Document not found or already deleted: id=" + documentId + " tenant=" + tenantId);
        }

        log.info("Document soft-deleted in DB: id={} tenant={}", documentId, tenantId);

        // Async: remove from ES index via Kafka event
        publishIndexEvent(documentId, tenantId, DocumentEvent.EventType.DOCUMENT_DELETED);
    }

    // ===================== KAFKA PUBLISHING =====================

    @Async
    protected void publishIndexEvent(UUID documentId, String tenantId, DocumentEvent.EventType eventType) {
        DocumentEvent.DocumentIndexEvent event = DocumentEvent.DocumentIndexEvent.builder()
                .documentId(documentId)
                .tenantId(tenantId)
                .eventType(eventType)
                .occurredAt(Instant.now())
                .build();

        // Use tenantId as partition key → all events for a tenant go to same partition, preserving order
        kafkaTemplate.send(TOPIC_DOCUMENT_EVENTS, tenantId, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {} event for doc={}: {}", eventType, documentId, ex.getMessage());
                    } else {
                        log.debug("Published {} event for doc={} to partition={}",
                                eventType, documentId, result.getRecordMetadata().partition());
                    }
                });
    }

    // ===================== HELPERS =====================

    private void enforceDocumentQuota(String tenantId) {
        TenantRateLimit limit = tenantRateLimitRepository.findById(tenantId)
                .orElse(TenantRateLimit.builder().tenantId(tenantId).build());

        long currentCount = documentRepository.countByTenantIdAndStatus(tenantId, Document.DocumentStatus.ACTIVE);
        if (currentCount >= limit.getMaxDocuments()) {
            throw new TenantQuotaExceededException(
                    "Document quota exceeded for tenant=" + tenantId + 
                    " (max=" + limit.getMaxDocuments() + ")");
        }
    }

    private DocumentDto.DocumentResponse toDocumentResponse(Document document) {
        return DocumentDto.DocumentResponse.builder()
                .id(document.getId())
                .tenantId(document.getTenantId())
                .title(document.getTitle())
                .content(document.getContent())
                .author(document.getAuthor())
                .category(document.getCategory())
                .tags(document.getTags())
                .metadata(document.getMetadata())
                .status(document.getStatus().name())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }

    public DocumentIndex toDocumentIndex(Document document) {
        return DocumentIndex.builder()
                .id(document.getId().toString())
                .tenantId(document.getTenantId())
                .title(document.getTitle())
                .content(document.getContent())
                .author(document.getAuthor())
                .category(document.getCategory())
                .tags(document.getTags())
                .metadata(document.getMetadata())
                .status(document.getStatus().name())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }
}
