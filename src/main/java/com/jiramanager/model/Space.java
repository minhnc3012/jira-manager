package com.jiramanager.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A user-created top-level grouping in the Spaces page, typically representing one project.
 * Unlike the old auto-discovered Confluence space, this is fully manual — created/edited/deleted
 * by the user via {@code SpacesView}. {@code jiraLink} is purely informational (rendered as a
 * clickable link) and drives no automation; scoped by {@code baseUrl} like the rest of this
 * feature area.
 */
@Entity
@Table(name = "spaces")
@Getter @Setter @NoArgsConstructor
public class Space {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "jira_link", length = 1000)
    private String jiraLink;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private AppUser createdByUser;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
