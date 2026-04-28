package com.docsearch.filter;

/**
 * Thread-local store for the current request's tenant context.
 * Set by TenantContextFilter early in the filter chain;
 * cleared after request completes to prevent thread pool leakage.
 */
public class TenantContext {

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();

    public static void setTenantId(String tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static String getTenantId() {
        return TENANT_ID.get();
    }

    public static void clear() {
        TENANT_ID.remove();
    }
}
