package com.jiramanager.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One Jira ticket attached to a {@link TicketDoc}. {@code KnowledgeBaseSyncRunner} periodically
 * re-checks {@code lastKnownUpdated} against Jira's current "updated" timestamp; a mismatch sets
 * {@code needsRegenerate}, and {@code TicketDocsView} shows a one-time toast (tracked via
 * {@code notifiedAt}) the next time the affected user opens the page.
 */
@Entity
@Table(name = "ticket_doc_items",
        uniqueConstraints = @UniqueConstraint(columnNames = {"ticket_doc_id", "ticket_key"}))
@Getter @Setter @NoArgsConstructor
public class TicketDocItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_doc_id", nullable = false)
    private TicketDoc ticketDoc;

    @Column(name = "ticket_key", nullable = false, length = 50)
    private String ticketKey;

    @Column(name = "last_known_updated")
    private Instant lastKnownUpdated;

    @Column(name = "needs_regenerate", nullable = false)
    private boolean needsRegenerate;

    /** Set once the "needs regenerate" toast has been shown to the owning user; avoids repeats. */
    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by_user_id", nullable = false)
    private AppUser addedByUser;
}
