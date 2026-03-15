package com.jiramanager.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Represents a single worklog entry for a Jira issue on a specific day.
 * Combines the worklog data (who logged, when, how long) with the
 * parent issue's metadata and time-tracking totals.
 */
@Data
@Builder
public class WorklogEntry {

    // ── Issue metadata ────────────────────────────────────────────────
    private String ticketKey;
    private String summary;
    private String status;
    private String priority;
    private String project;
    private String issueType;
    private String assignee;
    private String reporter;
    private String sprint;
    private String ticketUrl;

    // ── This specific worklog entry ───────────────────────────────────
    private String  worklogId;
    private Instant startTime;       // UTC instant when the work started
    private Instant endTime;         // startTime + minutesSpent
    private int     minutesSpent;    // duration in minutes
    private String  timeSpentFormatted; // e.g. "1h 15m"
    private String  worklogAuthor;

    // ── Issue-level time-tracking totals (from Jira timetracking field) ──
    private String originalEstimate;          // e.g. "4h"
    private String totalTimeSpent;            // total across all worklogs, e.g. "2h 30m"
    private String remainingEstimate;         // e.g. "1h 30m"
    private long   originalEstimateSeconds;
    private long   totalTimeSpentSeconds;
    private long   remainingEstimateSeconds;
}
