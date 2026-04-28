package com.docsearch.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "tenant_rate_limits")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantRateLimit {

    @Id
    private String tenantId;

    @Column(nullable = false)
    @Builder.Default
    private long searchRequestsPerMinute = 100L;

    @Column(nullable = false)
    @Builder.Default
    private long indexRequestsPerMinute = 50L;

    @Column(nullable = false)
    @Builder.Default
    private long maxDocuments = 1_000_000L;

    @Column
    private Instant createdAt;

    @Column
    private Instant updatedAt;
}
