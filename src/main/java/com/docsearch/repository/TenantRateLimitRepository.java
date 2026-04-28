package com.docsearch.repository;

import com.docsearch.model.TenantRateLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TenantRateLimitRepository extends JpaRepository<TenantRateLimit, String> {
}
