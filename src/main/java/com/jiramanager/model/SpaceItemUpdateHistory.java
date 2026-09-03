package com.jiramanager.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Append-only log of every content change {@code KnowledgeBaseSyncRunner} detected on a linked
 * {@link SpaceItem} (its Confluence version number increased vs. the previous sync). Backs the
 * "update history" timeline shown in the Spaces detail panel. Never written on first sync of a
 * newly-linked item — only on a real version bump on an already-synced item.
 */
@Entity
@Table(name = "space_item_update_history")
@Getter @Setter @NoArgsConstructor
public class SpaceItemUpdateHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private SpaceItem item;

    @Column(name = "old_version")
    private Integer oldVersion;

    @Column(name = "new_version", nullable = false)
    private int newVersion;

    @Column(name = "new_updated_at", nullable = false)
    private Instant newUpdatedAt;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;
}
