package com.jiramanager.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class JiraTicket {
    private String key;
    private String summary;
    private String status;
    private String statusColor;
    private String priority;
    private String project;
    private String issueType;
    private String assignee;
    private String reporter;
    private String description;
    private String created;
    private String updated;
    private String dueDate;
    private String sprint;
    private String url;

    // Time tracking
    private String originalEstimate;
    private long   originalEstimateSeconds;
    private String timeSpent;
    private long   timeSpentSeconds;
    private String remainingEstimate;
    private long   remainingEstimateSeconds;
}
