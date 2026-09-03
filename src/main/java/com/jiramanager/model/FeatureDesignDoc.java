package com.jiramanager.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One version of an externally-generated HTML review/plan doc uploaded for a Feature —
 * typically produced by a separate tool that reads this Feature's Ticket Docs
 * {@code tickets.md} (§ 10b.6) plus its Confluence content and renders an overall
 * implementation doc for dev review.
 *
 * <p>A Feature can have many rows here — every upload creates a new row with
 * {@code version = previousMax + 1} rather than replacing the last one (see
 * {@code FeatureDesignDocStorage}), so past versions stay downloadable for tracing/comparison.
 * The row with the highest {@code version} for a given feature is "latest".
 *
 * <p>Served back for in-browser viewing via {@code DesignDocController}. As of the last product
 * decision this is served with no {@code Content-Security-Policy} restriction (deliberate,
 * local-only-tool tradeoff — see that controller's class javadoc for what to reinstate before
 * this app is ever published/exposed more broadly).
 */
@Entity
@Table(name = "feature_design_docs",
        uniqueConstraints = @UniqueConstraint(columnNames = {"feature_id", "version"}))
@Getter @Setter @NoArgsConstructor
public class FeatureDesignDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feature_id", nullable = false)
    private SpaceItem feature;

    /** 1, 2, 3, ... per feature — the highest value for a feature is its current/latest doc. */
    @Column(nullable = false)
    private int version;

    /** Original uploaded filename, kept for display only — not used to build the storage path. */
    @Column(name = "file_name", length = 500)
    private String fileName;

    @Column(name = "file_path", length = 1000)
    private String filePath;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_user_id")
    private AppUser uploadedByUser;
}
