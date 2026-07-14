package com.jiramanager.model;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class JiraTicket {
    private String key;
    private String summary;
    private String status;
    private String statusColor;
    private String priority;
    private String project;
    /** Jira project key (e.g. "DEMO"), distinct from {@link #project} (the project's display name). */
    private String projectKey;
    private String issueType;
    private String assignee;
    private String reporter;
    /** Truncated to 500 chars (+"...") for compact detail-panel display. */
    private String description;
    /** Untruncated description text — used by Ticket Docs markdown generation. */
    private String fullDescription;
    private String created;
    private String updated;
    /** Raw Jira "updated" instant (null if Jira didn't return one) — used for change detection. */
    private Instant updatedInstant;
    private String dueDate;
    private String sprint;
    private String url;

    // Confluence page IDs extracted from description links (same Atlassian instance)
    @Singular
    private List<String> confluencePageIds;

    // Time tracking
    private String originalEstimate;
    private long   originalEstimateSeconds;
    private String timeSpent;
    private long   timeSpentSeconds;
    private String remainingEstimate;
    private long   remainingEstimateSeconds;
}
