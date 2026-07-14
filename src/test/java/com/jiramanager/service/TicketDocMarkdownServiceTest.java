package com.jiramanager.service;

import com.jiramanager.model.ConfluenceFeature;
import com.jiramanager.model.JiraTicket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TicketDocMarkdownServiceTest {

    @Test
    void generate_writesMarkdownWithTicketContent(@TempDir Path tempDir) throws IOException {
        TicketDocMarkdownService service = new TicketDocMarkdownService(tempDir);

        ConfluenceFeature feature = new ConfluenceFeature();
        feature.setId(999L);
        feature.setTitle("Login Redesign");

        JiraTicket ticket = JiraTicket.builder()
                .key("DEMO-1")
                .summary("Fix login bug")
                .status("In Progress")
                .priority("High")
                .assignee("Minh")
                .updated("2025-06-01")
                .url("https://example.atlassian.net/browse/DEMO-1")
                .description("Steps to repro...") // truncated display copy — must NOT be used
                .fullDescription("Steps to reproduce...")
                .build();

        Path file = service.generate(feature, List.of(ticket));

        assertThat(file).exists();
        assertThat(file).hasFileName("tickets.md");
        String content = Files.readString(file);
        assertThat(content).contains("Login Redesign — Tickets");
        assertThat(content).contains("DEMO-1: Fix login bug");
        assertThat(content).contains("Status: In Progress");
        assertThat(content).contains("Priority: High");
        assertThat(content).contains("Steps to reproduce...");
    }

    @Test
    void generate_usesFullDescription_notTruncatedDisplayCopy(@TempDir Path tempDir) throws IOException {
        TicketDocMarkdownService service = new TicketDocMarkdownService(tempDir);

        ConfluenceFeature feature = new ConfluenceFeature();
        feature.setId(3L);
        feature.setTitle("Feature C");

        String longText = "Paragraph one. ".repeat(100); // well over the 500-char display cap
        JiraTicket ticket = JiraTicket.builder()
                .key("C-1").summary("Long description ticket").status("Open").priority("Low")
                .assignee("X").updated("2025-01-01").url("https://x/C-1")
                .description(longText.substring(0, 500) + "...") // simulates the truncated display field
                .fullDescription(longText)
                .build();

        Path file = service.generate(feature, List.of(ticket));

        String content = Files.readString(file);
        assertThat(content).contains(longText);
        assertThat(content).doesNotContain(longText.substring(0, 500) + "...");
    }

    @Test
    void generate_overwritesPreviousFile(@TempDir Path tempDir) throws IOException {
        TicketDocMarkdownService service = new TicketDocMarkdownService(tempDir);

        ConfluenceFeature feature = new ConfluenceFeature();
        feature.setId(1L);
        feature.setTitle("Feature A");

        JiraTicket first = JiraTicket.builder().key("A-1").summary("First").status("Open")
                .priority("Low").assignee("X").updated("2025-01-01").url("https://x/A-1")
                .fullDescription("v1").build();
        Path file1 = service.generate(feature, List.of(first));
        assertThat(Files.readString(file1)).contains("v1").doesNotContain("v2");

        JiraTicket second = JiraTicket.builder().key("A-1").summary("First").status("Open")
                .priority("Low").assignee("X").updated("2025-01-02").url("https://x/A-1")
                .fullDescription("v2").build();
        Path file2 = service.generate(feature, List.of(second));

        assertThat(file2).isEqualTo(file1);
        assertThat(Files.readString(file2)).contains("v2").doesNotContain("v1");
    }

    @Test
    void generate_missingDescription_usesPlaceholder(@TempDir Path tempDir) throws IOException {
        TicketDocMarkdownService service = new TicketDocMarkdownService(tempDir);

        ConfluenceFeature feature = new ConfluenceFeature();
        feature.setId(2L);
        feature.setTitle("Feature B");

        JiraTicket ticket = JiraTicket.builder().key("B-1").summary("No description")
                .status("Open").priority("Low").assignee("X").updated("2025-01-01")
                .url("https://x/B-1").fullDescription("").build();

        Path file = service.generate(feature, List.of(ticket));

        assertThat(Files.readString(file)).contains("_No description._");
    }
}
