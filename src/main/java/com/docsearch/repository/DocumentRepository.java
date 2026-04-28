package com.docsearch.repository;

import com.docsearch.model.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByIdAndTenantId(UUID id, String tenantId);

    Optional<Document> findByIdAndTenantIdAndStatus(UUID id, String tenantId, Document.DocumentStatus status);

    Page<Document> findByTenantIdAndStatus(String tenantId, Document.DocumentStatus status, Pageable pageable);

    long countByTenantIdAndStatus(String tenantId, Document.DocumentStatus status);

    // Soft delete: mark as DELETED rather than physical removal
    @Modifying
    @Query("UPDATE Document d SET d.status = 'DELETED' WHERE d.id = :id AND d.tenantId = :tenantId AND d.status = 'ACTIVE'")
    int softDeleteByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") String tenantId);

    @Query("SELECT COUNT(d) > 0 FROM Document d WHERE d.id = :id AND d.tenantId = :tenantId AND d.status = 'ACTIVE'")
    boolean existsActiveByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") String tenantId);
}
