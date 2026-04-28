package com.docsearch.repository;

import com.docsearch.model.DocumentIndex;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentSearchRepository extends ElasticsearchRepository<DocumentIndex, String> {
    // Complex queries handled via ElasticsearchOperations in service layer
    // Simple finders here for basic operations

    void deleteByIdAndTenantId(String id, String tenantId);

    long countByTenantId(String tenantId);
}
