package com.jiramanager.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The generated {@code tickets.md} for one {@link SpaceItem} — aggregates the content of
 * every {@link TicketDocItem} attached to it. One doc per item (embedded in {@code SpacesView}).
 */
@Entity
@Table(name = "ticket_docs")
@Getter @Setter @NoArgsConstructor
public class TicketDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feature_id", nullable = false, unique = true)
    private SpaceItem feature;

    /** Local disk path of the generated markdown file, e.g. ./data/feature-tickets/{id}/tickets.md */
    @Column(name = "file_path", length = 1000)
    private String filePath;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_by_user_id")
    private AppUser generatedByUser;
}
