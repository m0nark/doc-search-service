package com.docsearch.dto;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

public class DocumentEvent {

    public enum EventType {
        DOCUMENT_INDEXED,
        DOCUMENT_DELETED,
        DOCUMENT_REINDEX
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentIndexEvent {
        private UUID documentId;
        private String tenantId;
        private EventType eventType;
        private Instant occurredAt;

        @Builder.Default
        private int retryCount = 0;
    }
}
