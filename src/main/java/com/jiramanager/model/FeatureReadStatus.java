package com.jiramanager.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Per-user "mark as read" marker for a {@link ConfluenceFeature}. A feature is unread for a
 * given user whenever {@code feature.version > lastReadVersion} (or the user has no row at
 * all, treated as {@code lastReadVersion = 0}) — so a later real content change automatically
 * makes it unread again after being read.
 */
@Entity
@Table(name = "feature_read_status",
        uniqueConstraints = @UniqueConstraint(columnNames = {"feature_id", "user_id"}))
@Getter @Setter @NoArgsConstructor
public class FeatureReadStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feature_id", nullable = false)
    private ConfluenceFeature feature;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "last_read_version", nullable = false)
    private int lastReadVersion;

    @Column(name = "read_at", nullable = false)
    private Instant readAt;
}
