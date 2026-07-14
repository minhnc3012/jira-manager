package com.jiramanager.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A manually-added Confluence space or page to sync — the fallback for when the automatic
 * Jira-project-key ↔ Confluence-space-key match ({@code KnowledgeBaseSyncRunner}) doesn't find
 * a space (e.g. the keys genuinely differ, like project "DEMO2" vs. space "DOCS").
 * Added via {@code SpacesView}'s "Add space/page" dialog, parsed from a pasted Confluence URL
 * ({@code ConfluenceLinkParser}). {@code pageId == null} means "track the whole space";
 * otherwise only that one page is tracked.
 */
@Entity
@Table(name = "manual_sync_targets")
@Getter @Setter @NoArgsConstructor
public class ManualSyncTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Column(name = "space_key", nullable = false, length = 100)
    private String spaceKey;

    /** Null = track the whole space; set = track only this one page. */
    @Column(name = "page_id", length = 255)
    private String pageId;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by_user_id", nullable = false)
    private AppUser addedByUser;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;
}
