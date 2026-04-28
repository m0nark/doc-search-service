package com.docsearch.service;

import com.docsearch.dto.DocumentEvent;
import com.docsearch.model.Document;
import com.docsearch.model.DocumentIndex;
import com.docsearch.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIndexingConsumer {

    private final DocumentRepository documentRepository;
    private final ElasticsearchService elasticsearchService;
    private final DocumentService documentService;

    /**
     * Retryable Kafka listener with exponential backoff.
     * Retries up to 3 times with DLT (Dead Letter Topic) on permanent failure.
     *
     * Retry schedule: 1s → 2s → 4s → DLT
     */
    @RetryableTopic(
        attempts = "4",
        backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 30000),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltTopicSuffix = ".DLT"
    )
    @KafkaListener(
        topics = "document-events",
        groupId = "document-indexer",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleDocumentEvent(
            @Payload DocumentEvent.DocumentIndexEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.debug("Processing {} event for doc={} tenant={} partition={} offset={}",
                event.getEventType(), event.getDocumentId(), event.getTenantId(), partition, offset);

        try {
            switch (event.getEventType()) {
                case DOCUMENT_INDEXED, DOCUMENT_REINDEX -> handleIndex(event);
                case DOCUMENT_DELETED -> handleDelete(event);
                default -> log.warn("Unknown event type: {}", event.getEventType());
            }
        } catch (Exception e) {
            log.error("Failed to process {} event for doc={}: {}",
                    event.getEventType(), event.getDocumentId(), e.getMessage());
            throw e; // Re-throw to trigger Kafka retry
        }
    }

    /**
     * DLT handler — logs permanently failed events for manual investigation/replay.
     */
    @KafkaListener(topics = "document-events.DLT", groupId = "document-indexer-dlt")
    public void handleDlt(@Payload DocumentEvent.DocumentIndexEvent event,
                          @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("DLT: Permanently failed {} event for doc={} tenant={}. Manual intervention required.",
                event.getEventType(), event.getDocumentId(), event.getTenantId());
        // In production: alert PagerDuty, write to ops audit log
    }

    private void handleIndex(DocumentEvent.DocumentIndexEvent event) {

        UUID docId = event.getDocumentId();
        String tenantId = event.getTenantId();

        Document document = documentRepository
                .findByIdAndTenantId(docId, tenantId)
                .orElse(null);

        if (document == null) {
            log.warn("Document not found for indexing: id={} tenant={}", docId, tenantId);
            return;
        }

        // Set ACTIVE before indexing
        document.setStatus(Document.DocumentStatus.ACTIVE);

        DocumentIndex index = documentService.toDocumentIndex(document);

        elasticsearchService.indexDocument(index);

        documentRepository.save(document);

        log.info("Document indexed successfully: id={} tenant={}", docId, tenantId);
    }

    private void handleDelete(DocumentEvent.DocumentIndexEvent event) {
        elasticsearchService.deleteDocument(
                event.getDocumentId().toString(),
                event.getTenantId());
        log.info("Document removed from search index: id={} tenant={}",
                event.getDocumentId(), event.getTenantId());
    }
}
