package com.docsearch.exception;

public class TenantQuotaExceededException extends RuntimeException {
    public TenantQuotaExceededException(String message) {
        super(message);
    }
}
