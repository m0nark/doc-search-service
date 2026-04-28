package com.docsearch.service;

import com.docsearch.dto.DocumentDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    // TTLs
    private static final Duration SEARCH_CACHE_TTL = Duration.ofSeconds(30);
    private static final Duration DOCUMENT_CACHE_TTL = Duration.ofMinutes(10);

    private static final String SEARCH_KEY_PREFIX = "search:";
    private static final String DOC_KEY_PREFIX = "doc:";
    private static final String RATE_LIMIT_KEY_PREFIX = "rl:";

    // ===================== SEARCH CACHE =====================

    public String buildSearchCacheKey(DocumentDto.SearchRequest request) {
        // Deterministic key from all search parameters
        return SEARCH_KEY_PREFIX + request.getTenantId() + ":" +
               request.getQuery().toLowerCase().trim() + ":" +
               request.getPage() + ":" + request.getSize() + ":" +
               request.getCategory() + ":" + request.isFuzzy();
    }

    public DocumentDto.SearchResponse getSearchResult(String key) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            return value instanceof DocumentDto.SearchResponse ? (DocumentDto.SearchResponse) value : null;
        } catch (Exception e) {
            log.warn("Redis read failed for key={}: {}", key, e.getMessage());
            return null; // Fail open — degrade to ES directly
        }
    }

    public void putSearchResult(String key, DocumentDto.SearchResponse result) {
        try {
            redisTemplate.opsForValue().set(key, result, SEARCH_CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis write failed for key={}: {}", key, e.getMessage());
            // Non-fatal — continue without caching
        }
    }

    // ===================== RATE LIMITING =====================

    /**
     * Sliding window rate limiter using Redis atomic increment.
     * Returns true if request is allowed, false if rate limit exceeded.
     */
    public boolean checkRateLimit(String tenantId, String operation, long maxRequests, long windowSeconds) {
        String key = RATE_LIMIT_KEY_PREFIX + tenantId + ":" + operation + ":" + 
                     (System.currentTimeMillis() / (windowSeconds * 1000));
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == 1) {
                // First request in this window — set expiry
                redisTemplate.expire(key, windowSeconds + 1, TimeUnit.SECONDS);
            }
            return count <= maxRequests;
        } catch (Exception e) {
            log.warn("Rate limit Redis check failed for tenant={}: {}", tenantId, e.getMessage());
            return true; // Fail open — allow request rather than block on Redis failure
        }
    }

    // ===================== DOCUMENT CACHE =====================

    public void evictDocument(String tenantId, String documentId) {
        try {
            redisTemplate.delete(DOC_KEY_PREFIX + tenantId + ":" + documentId);
        } catch (Exception e) {
            log.warn("Redis evict failed for doc={}: {}", documentId, e.getMessage());
        }
    }
}
