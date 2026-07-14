package com.jiramanager.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A Jira user cached from a background sync (see {@code JiraUserSyncRunner}), scoped by
 * {@code baseUrl} since different local accounts may point at different Jira Cloud sites.
 * Backs the "member" filter dropdown on Worklog / Worklog Calendar without hitting the
 * (potentially slow, hundreds-of-users) Jira {@code /users/search} endpoint on every page load.
 */
@Entity
@Table(name = "jira_cached_users",
        uniqueConstraints = @UniqueConstraint(columnNames = {"base_url", "account_id"}))
@Getter @Setter @NoArgsConstructor
public class JiraCachedUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Column(name = "account_id", nullable = false, length = 255)
    private String accountId;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;
}
