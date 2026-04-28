package com.docsearch.filter;

import com.docsearch.service.CacheService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class TenantContextFilter implements Filter {

    private final CacheService cacheService;

    // Header used for tenant identification (alternative: JWT claim)
    public static final String TENANT_HEADER = "X-Tenant-ID";
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    private static final long SEARCH_RATE_LIMIT = 100L;   // per minute
    private static final long INDEX_RATE_LIMIT  = 50L;    // per minute
    private static final long WINDOW_SECONDS    = 60L;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request  = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        // Skip tenant enforcement for health/actuator endpoints
        String path = request.getRequestURI();
        if (path.startsWith("/actuator") || path.startsWith("/health")) {
            chain.doFilter(req, res);
            return;
        }

        // 1. Extract tenant
        String tenantId = request.getHeader(TENANT_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("""
                {"success":false,"message":"Missing required header: X-Tenant-ID"}
                """);
            return;
        }

        // Sanitize — prevent injection via tenant header
        if (!tenantId.matches("^[a-zA-Z0-9_-]{1,64}$")) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("""
                {"success":false,"message":"Invalid tenant ID format"}
                """);
            return;
        }

        // 2. Rate limiting per tenant per operation type
        String method = request.getMethod();
        boolean isSearch = path.contains("/search");
        boolean isWrite  = "POST".equals(method) || "DELETE".equals(method);

        if (isSearch) {
            if (!cacheService.checkRateLimit(tenantId, "search", SEARCH_RATE_LIMIT, WINDOW_SECONDS)) {
                response.setStatus(429);
                response.setHeader("Retry-After", "60");
                response.setContentType("application/json");
                response.getWriter().write("""
                    {"success":false,"message":"Rate limit exceeded. Max %d search requests per minute."}
                    """.formatted(SEARCH_RATE_LIMIT));
                return;
            }
        } else if (isWrite) {
            if (!cacheService.checkRateLimit(tenantId, "write", INDEX_RATE_LIMIT, WINDOW_SECONDS)) {
                response.setStatus(429);
                response.setHeader("Retry-After", "60");
                response.setContentType("application/json");
                response.getWriter().write("""
                    {"success":false,"message":"Rate limit exceeded. Max %d write requests per minute."}
                    """.formatted(INDEX_RATE_LIMIT));
                return;
            }
        }

        // 3. Set tenant in thread-local for downstream use
        TenantContext.setTenantId(tenantId);

        // 4. Add tenant to MDC for structured logging
        org.slf4j.MDC.put("tenantId", tenantId);
        org.slf4j.MDC.put("requestId", request.getHeader(REQUEST_ID_HEADER) != null
                ? request.getHeader(REQUEST_ID_HEADER) : java.util.UUID.randomUUID().toString());

        try {
            chain.doFilter(req, res);
        } finally {
            // 5. Always clean up — prevents thread pool leakage
            TenantContext.clear();
            org.slf4j.MDC.clear();
        }
    }
}
