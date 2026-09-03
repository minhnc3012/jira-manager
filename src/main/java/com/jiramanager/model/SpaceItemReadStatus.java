package com.jiramanager.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Per-user "mark as read" marker for a linked {@link SpaceItem}. An item is unread for a given
 * user whenever {@code item.version > lastReadVersion} (or the user has no row at all, treated
 * as {@code lastReadVersion = 0}) — so a later real content change automatically makes it unread
 * again after being read.
 */
@Entity
@Table(name = "space_item_read_status",
        uniqueConstraints = @UniqueConstraint(columnNames = {"item_id", "user_id"}))
@Getter @Setter @NoArgsConstructor
public class SpaceItemReadStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private SpaceItem item;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "last_read_version", nullable = false)
    private int lastReadVersion;

    @Column(name = "read_at", nullable = false)
    private Instant readAt;
}
