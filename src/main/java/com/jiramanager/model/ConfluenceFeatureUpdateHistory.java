package com.jiramanager.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Append-only log of every content change {@code KnowledgeBaseSyncRunner} detected on a
 * {@link ConfluenceFeature} (its Confluence version number increased vs. the previous sync).
 * Backs the "update history" timeline shown in the Spaces detail panel. Never written on first
 * discovery of a page — only on a real version bump on an already-known page.
 */
@Entity
@Table(name = "confluence_feature_update_history")
@Getter @Setter @NoArgsConstructor
public class ConfluenceFeatureUpdateHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feature_id", nullable = false)
    private ConfluenceFeature feature;

    @Column(name = "old_version")
    private Integer oldVersion;

    @Column(name = "new_version", nullable = false)
    private int newVersion;

    @Column(name = "new_updated_at", nullable = false)
    private Instant newUpdatedAt;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;
}
