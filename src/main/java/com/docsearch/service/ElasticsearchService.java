package com.docsearch.service;

import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import com.docsearch.dto.DocumentDto;
import com.docsearch.model.DocumentIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticsearchService {

    private final ElasticsearchTemplate elasticsearchTemplate;
    private final co.elastic.clients.elasticsearch.ElasticsearchClient esClient;

    private static final String INDEX_NAME = "documents";
    private static final float TITLE_BOOST = 3.0f;
    private static final float CONTENT_BOOST = 1.0f;

    /**
     * Full-text search with BM25 relevance scoring, fuzzy matching,
     * multi-field boosting, and result highlighting.
     */
    public DocumentDto.SearchResponse search(DocumentDto.SearchRequest request) {
        long startMs = System.currentTimeMillis();

        try {
            // 1. Build the tenant-scoped filter (always applied — tenant isolation)
            Query tenantFilter = Query.of(q -> q.term(t -> t.field("tenantId").value(request.getTenantId())));
            Query statusFilter = Query.of(q -> q.term(t -> t.field("status").value("ACTIVE")));

            // 2. Build full-text query across title (boosted) + content
            Query textQuery = buildTextQuery(request.getQuery(), request.isFuzzy());

            // 3. Optional filters
            List<Query> filters = new ArrayList<>();
            filters.add(tenantFilter);
            filters.add(statusFilter);
            if (request.getCategory() != null) {
                filters.add(Query.of(q -> q.term(t -> t.field("category").value(request.getCategory()))));
            }
            if (request.getAuthor() != null) {
                filters.add(Query.of(q -> q.term(t -> t.field("author").value(request.getAuthor()))));
            }
            if (request.getTags() != null && !request.getTags().isEmpty()) {
                filters.add(Query.of(q -> q.terms(t -> t.field("tags")
                        .terms(tv -> tv.value(request.getTags().stream()
                                .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                                .collect(Collectors.toList()))))));
            }

            // 4. Compose: bool(must=textQuery, filter=tenantFilter+optional filters)
            Query finalQuery = Query.of(q -> q.bool(b -> b
                    .must(textQuery)
                    .filter(filters)));

            // 5. Build highlight config
            Map<String, HighlightField> highlightFields = Map.of(
                    "title", HighlightField.of(h -> h.numberOfFragments(0)),
                    "content", HighlightField.of(h -> h.numberOfFragments(3).fragmentSize(150))
            );

            // 6. Build and execute search request
            SearchRequest searchRequest = SearchRequest.of(s -> s
                    .index(INDEX_NAME)
                    .query(finalQuery)
                    .from(request.getPage() * request.getSize())
                    .size(request.getSize())
                    .highlight(h -> h
                            .preTags("<em>")
                            .postTags("</em>")
                            .fields(highlightFields))
                    .source(src -> src.filter(f -> f.excludes("content"))) // Don't return full content in search
            );

            SearchResponse<DocumentIndex> response = esClient.search(searchRequest, DocumentIndex.class);

            // 7. Map to response DTO
            List<DocumentDto.SearchResult> results = response.hits().hits().stream()
                    .map(this::mapHitToResult)
                    .collect(Collectors.toList());

            long totalHits = response.hits().total() != null ? response.hits().total().value() : 0;
            long tookMs = System.currentTimeMillis() - startMs;

            log.debug("Search for tenant={} query='{}' returned {} hits in {}ms",
                    request.getTenantId(), request.getQuery(), totalHits, tookMs);

            return DocumentDto.SearchResponse.builder()
                    .results(results)
                    .totalHits(totalHits)
                    .page(request.getPage())
                    .size(request.getSize())
                    .tookMs(tookMs)
                    .build();

        } catch (IOException e) {
            log.error("Elasticsearch search failed for tenant={}", request.getTenantId(), e);
            throw new RuntimeException("Search operation failed", e);
        }
    }

    /**
     * Build a multi-match query with optional fuzzy matching.
     * Uses best_fields strategy to rank documents where query terms
     * appear together in the same field higher.
     */
    private Query buildTextQuery(String queryString, boolean fuzzy) {
        if (fuzzy) {
            return Query.of(q -> q.multiMatch(m -> m
                    .query(queryString)
                    .fields("title^" + TITLE_BOOST, "content^" + CONTENT_BOOST, "author", "category", "tags")
                    .type(TextQueryType.BestFields)
                    .fuzziness("AUTO")
                    .minimumShouldMatch("75%")));
        } else {
            return Query.of(q -> q.multiMatch(m -> m
                    .query(queryString)
                    .fields("title^" + TITLE_BOOST, "content^" + CONTENT_BOOST, "author", "category", "tags")
                    .type(TextQueryType.BestFields)));
        }
    }

    private DocumentDto.SearchResult mapHitToResult(Hit<DocumentIndex> hit) {
        DocumentIndex doc = hit.source();
        if (doc == null) return DocumentDto.SearchResult.builder().id(hit.id()).build();

        Map<String, List<String>> highlights = new HashMap<>();
        if (hit.highlight() != null) {
            hit.highlight().forEach(highlights::put);
        }

        return DocumentDto.SearchResult.builder()
                .id(hit.id())
                .tenantId(doc.getTenantId())
                .title(doc.getTitle())
                .author(doc.getAuthor())
                .category(doc.getCategory())
                .tags(doc.getTags())
                .score(hit.score() != null ? hit.score().floatValue() : 0f)
                .highlights(highlights.isEmpty() ? null : highlights)
                .createdAt(doc.getCreatedAt())
                .build();
    }

    /**
     * Index or update a document in Elasticsearch.
     * Called asynchronously from Kafka consumer.
     */
    public void indexDocument(DocumentIndex documentIndex) {
        try {
            esClient.index(i -> i
                    .index(INDEX_NAME)
                    .id(documentIndex.getId())
                    .document(documentIndex));
            log.debug("Indexed document id={} tenant={}", documentIndex.getId(), documentIndex.getTenantId());
        } catch (IOException e) {
            log.error("Failed to index document id={}", documentIndex.getId(), e);
            throw new RuntimeException("Elasticsearch indexing failed", e);
        }
    }

    /**
     * Delete a document from the search index.
     */
    public void deleteDocument(String documentId, String tenantId) {
        try {
            // Verify tenant ownership before delete (belt-and-suspenders)
            Query verifyQuery = Query.of(q -> q.bool(b -> b
                    .must(Query.of(mq -> mq.term(t -> t.field("_id").value(documentId))))
                    .filter(Query.of(fq -> fq.term(t -> t.field("tenantId").value(tenantId))))));

            SearchResponse<DocumentIndex> verify = esClient.search(
                    s -> s.index(INDEX_NAME).query(verifyQuery).size(1), DocumentIndex.class);

            if (verify.hits().total() == null || verify.hits().total().value() == 0) {
                log.warn("Document id={} not found for tenant={} during delete", documentId, tenantId);
                return;
            }

            esClient.delete(d -> d.index(INDEX_NAME).id(documentId));
            log.debug("Deleted document id={} from search index", documentId);
        } catch (IOException e) {
            log.error("Failed to delete document id={} from Elasticsearch", documentId, e);
            throw new RuntimeException("Elasticsearch delete failed", e);
        }
    }
}
