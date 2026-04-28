package com.docsearch.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DocumentDto {

    // ===================== REQUEST DTOs =====================

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndexRequest {

        @NotBlank(message = "Title is required")
        @Size(max = 512, message = "Title cannot exceed 512 characters")
        private String title;

        @NotBlank(message = "Content is required")
        @Size(max = 10_000_000, message = "Content too large (max 10MB)")
        private String content;

        private String author;
        private String category;
        private List<String> tags;
        private Map<String, String> metadata;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchRequest {

        @NotBlank(message = "Query is required")
        @Size(max = 512, message = "Query too long")
        private String query;

        @NotBlank(message = "TenantId is required")
        private String tenantId;

        private String category;
        private List<String> tags;
        private String author;

        @Builder.Default
        private int page = 0;

        @Builder.Default
        private int size = 10;

        // Fuzzy matching enabled by default
        @Builder.Default
        private boolean fuzzy = true;

        // Highlight matches in results
        @Builder.Default
        private boolean highlight = true;
    }

    // ===================== RESPONSE DTOs =====================

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DocumentResponse {
        private UUID id;
        private String tenantId;
        private String title;
        private String content;
        private String author;
        private String category;
        private List<String> tags;
        private Map<String, String> metadata;
        private String status;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SearchResult {
        private String id;
        private String tenantId;
        private String title;
        private String author;
        private String category;
        private List<String> tags;
        private float score;
        private Map<String, List<String>> highlights;
        private Instant createdAt;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchResponse {
        private List<SearchResult> results;
        private long totalHits;
        private int page;
        private int size;
        private long tookMs;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ApiResponse<T> {
        private boolean success;
        private String message;
        private T data;
        private String requestId;
        private Instant timestamp;

        public static <T> ApiResponse<T> ok(T data) {
            return ApiResponse.<T>builder()
                    .success(true)
                    .data(data)
                    .timestamp(Instant.now())
                    .build();
        }

        public static <T> ApiResponse<T> ok(T data, String message) {
            return ApiResponse.<T>builder()
                    .success(true)
                    .message(message)
                    .data(data)
                    .timestamp(Instant.now())
                    .build();
        }

        public static <T> ApiResponse<T> error(String message) {
            return ApiResponse.<T>builder()
                    .success(false)
                    .message(message)
                    .timestamp(Instant.now())
                    .build();
        }
    }
}
