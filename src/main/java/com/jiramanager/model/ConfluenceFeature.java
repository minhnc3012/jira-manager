package com.jiramanager.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One Confluence page, synced in the background by {@code KnowledgeBaseSyncRunner} — a node in
 * the Spaces tree grid (called a "Feature" in the UI). Scoped by {@code baseUrl} since a Jira
 * site's Confluence content is shared across every local {@code AppUser} connected to it.
 */
@Entity
@Table(name = "confluence_features",
        uniqueConstraints = @UniqueConstraint(columnNames = {"base_url", "page_id"}))
@Getter @Setter @NoArgsConstructor
public class ConfluenceFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Column(name = "space_key", nullable = false, length = 100)
    private String spaceKey;

    @Column(name = "page_id", nullable = false, length = 255)
    private String pageId;

    @Column(nullable = false, length = 500)
    private String title;

    /** Immediate parent page ID within the space, or null for a space-root page. */
    @Column(name = "parent_page_id", length = 255)
    private String parentPageId;

    /** Confluence page version number, as of the last sync — drives unread/history detection. */
    @Column(nullable = false)
    private int version;

    @Column(name = "confluence_updated_at", nullable = false)
    private Instant confluenceUpdatedAt;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;

    @Column(length = 1000)
    private String url;
}
