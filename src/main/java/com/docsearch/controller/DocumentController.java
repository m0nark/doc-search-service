package com.docsearch.controller;

import com.docsearch.dto.DocumentDto;
import com.docsearch.filter.JwtTokenProvider;
import com.docsearch.filter.TenantContext;
import com.docsearch.service.DocumentService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/token")
    public ResponseEntity<?> generateToken(
            @RequestParam(defaultValue = "aadit") String username,
            @RequestParam(defaultValue = "USER") String role
    ) {

        String token = jwtTokenProvider.generateToken(
                username,
                List.of(role)
        );

        return ResponseEntity.ok(Map.of(
                "token", token,
                "tokenType", "Bearer"
        ));
    }

    /**
     * POST /api/v1/documents
     * Index a new document for the current tenant.
     *
     * Tenant is extracted from X-Tenant-ID header (set by TenantContextFilter).
     * Write is synchronous to DB; ES indexing is async via Kafka.
     */
    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Timed(value = "document.index", description = "Time to index a document")
    public ResponseEntity<DocumentDto.ApiResponse<DocumentDto.DocumentResponse>> indexDocument(
            @Valid @RequestBody DocumentDto.IndexRequest request) {

        String tenantId = TenantContext.getTenantId();
        log.info("Index request: tenant={} title={}", tenantId, request.getTitle());

        DocumentDto.DocumentResponse response = documentService.indexDocument(tenantId, request);

        return ResponseEntity.accepted()
                .body(DocumentDto.ApiResponse.ok(response,
                        "Document accepted for indexing. It will be searchable shortly."));
    }

    /**
     * GET /api/v1/search?q={query}&tenant={tenantId}
     * Full-text search with optional filters.
     *
     * Supports: fuzzy matching, result highlighting, faceted filtering
     * by category, author, tags.
     */
    @GetMapping("/search")
    @Timed(value = "document.search", description = "Time to execute search query")
    public ResponseEntity<DocumentDto.ApiResponse<DocumentDto.SearchResponse>> search(
            @RequestParam("q") String query,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "tags", required = false) java.util.List<String> tags,
            @RequestParam(value = "fuzzy", defaultValue = "true") boolean fuzzy,
            @RequestParam(value = "highlight", defaultValue = "true") boolean highlight) {

        String tenantId = TenantContext.getTenantId();

        DocumentDto.SearchRequest searchRequest = DocumentDto.SearchRequest.builder()
                .query(query)
                .tenantId(tenantId)
                .page(Math.max(0, page))
                .size(Math.min(100, Math.max(1, size))) // Clamp: 1-100
                .category(category)
                .author(author)
                .tags(tags)
                .fuzzy(fuzzy)
                .highlight(highlight)
                .build();

        DocumentDto.SearchResponse result = documentService.search(searchRequest);
        return ResponseEntity.ok(DocumentDto.ApiResponse.ok(result));
    }

    /**
     * GET /api/v1/documents/{id}
     * Retrieve full document by ID.
     * Tenant-scoped — cannot access another tenant's document.
     */
    @GetMapping("/documents/{id}")
    @Timed(value = "document.get", description = "Time to retrieve a document")
    public ResponseEntity<DocumentDto.ApiResponse<DocumentDto.DocumentResponse>> getDocument(
            @PathVariable UUID id) {

        String tenantId = TenantContext.getTenantId();
        DocumentDto.DocumentResponse response = documentService.getDocument(tenantId, id);
        return ResponseEntity.ok(DocumentDto.ApiResponse.ok(response));
    }

    /**
     * DELETE /api/v1/documents/{id}
     * Soft-delete a document (marked DELETED in DB, removed from ES index).
     */
    @DeleteMapping("/documents/{id}")
    @Timed(value = "document.delete", description = "Time to delete a document")
    public ResponseEntity<DocumentDto.ApiResponse<Void>> deleteDocument(
            @PathVariable UUID id) {

        String tenantId = TenantContext.getTenantId();
        log.info("Delete request: tenant={} documentId={}", tenantId, id);

        documentService.deleteDocument(tenantId, id);
        return ResponseEntity.ok(DocumentDto.ApiResponse.ok(null, "Document deleted successfully"));
    }
}
