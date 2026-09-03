package com.jiramanager.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One node in a {@link Space}'s tree, called a "Feature" in the UI — created, renamed, moved
 * (drag & drop) and deleted manually by the user via {@code SpacesView}. {@code name} is always
 * user-owned and never overwritten by background sync.
 *
 * <p>Optionally linked to one Confluence page ({@code confluencePageId} non-null) — when linked,
 * {@code version}/{@code confluenceUpdatedAt} are kept fresh by {@code KnowledgeBaseSyncRunner}
 * (single-page refresh via {@code JiraService.getConfluencePageDetail}), driving the Updated/Read
 * badge and Update History exactly as before. An unlinked item is a plain organizational node
 * with no sync.
 */
@Entity
@Table(name = "space_items")
@Getter @Setter @NoArgsConstructor
public class SpaceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "space_id", nullable = false)
    private Space space;

    /** Immediate parent item, manually set (create/edit/drag-drop) — null = root of the space. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private SpaceItem parent;

    /** Sibling order under the same parent — maintained on create and on drag & drop reorder. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false, length = 500)
    private String name;

    /** Optional Confluence link — all three of these are set together, or all null. */
    @Column(name = "confluence_space_key", length = 100)
    private String confluenceSpaceKey;

    @Column(name = "confluence_page_id", length = 255)
    private String confluencePageId;

    @Column(length = 1000)
    private String url;

    /** Populated only when linked — Confluence page version number as of the last sync. */
    @Column
    private Integer version;

    @Column(name = "confluence_updated_at")
    private Instant confluenceUpdatedAt;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private AppUser createdByUser;

    public boolean isLinked() {
        return confluencePageId != null;
    }
}
