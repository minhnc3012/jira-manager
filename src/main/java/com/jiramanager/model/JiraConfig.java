package com.jiramanager.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Per-user Jira connection settings.
 * Replaces the old static application.properties Jira credentials.
 */
@Entity
@Table(name = "jira_configs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JiraConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private AppUser user;

    /** e.g. "https://mycompany.atlassian.net" */
    @Column(length = 500)
    private String baseUrl;

    /** Jira account email used for API authentication */
    @Column(length = 255)
    private String email;

    /** Jira API token (https://id.atlassian.com/manage-profile/security/api-tokens) */
    @Column(length = 1000)
    private String apiToken;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
