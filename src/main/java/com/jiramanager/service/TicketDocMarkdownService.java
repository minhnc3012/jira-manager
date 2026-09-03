package com.jiramanager.service;

import com.jiramanager.model.SpaceItem;
import com.jiramanager.model.JiraTicket;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates the per-{@link SpaceItem} {@code tickets.md} used by the Ticket Docs panel embedded
 * in {@code SpacesView} — concatenates every attached {@link JiraTicket}'s content into one
 * downloadable markdown file, stored locally under {@code ./data/feature-tickets/} (the app's
 * only on-disk convention, alongside the H2 database file).
 */
@Service
public class TicketDocMarkdownService {

    private final Path baseDir;

    public TicketDocMarkdownService() {
        this.baseDir = Path.of("data", "feature-tickets");
    }

    /** Package-private constructor for tests — writes under a caller-supplied (e.g. temp) directory. */
    TicketDocMarkdownService(Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * Writes {@code {baseDir}/{featureId}/tickets.md}, overwriting any previous file for the
     * same feature. Returns the written path.
     */
    public Path generate(SpaceItem feature, List<JiraTicket> tickets) throws IOException {
        Path dir = baseDir.resolve(String.valueOf(feature.getId()));
        Files.createDirectories(dir);
        Path file = dir.resolve("tickets.md");

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(feature.getName()).append(" — Tickets\n\n");

        for (JiraTicket t : tickets) {
            sb.append("## ").append(t.getKey()).append(": ").append(t.getSummary()).append("\n");
            sb.append("- Status: ").append(t.getStatus())
              .append("   Priority: ").append(t.getPriority())
              .append("   Assignee: ").append(t.getAssignee()).append("\n");
            sb.append("- Updated: ").append(t.getUpdated())
              .append("   URL: ").append(t.getUrl()).append("\n\n");

            // Full, untruncated description — the 500-char `description` field is only for
            // compact detail-panel display elsewhere in the app.
            String desc = t.getFullDescription();
            sb.append(desc != null && !desc.isBlank() ? desc : "_No description._").append("\n\n");
            sb.append("---\n\n");
        }

        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }
}
