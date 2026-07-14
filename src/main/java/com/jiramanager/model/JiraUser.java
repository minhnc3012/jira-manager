package com.jiramanager.model;

/**
 * A Jira user, used to populate the "member" filter on Worklog / Worklog Calendar views.
 *
 * @param accountId   Jira Cloud account ID (used in JQL: {@code worklogAuthor = "<accountId>"})
 * @param displayName Human-readable name shown in the UI
 */
public record JiraUser(String accountId, String displayName) {

    @Override
    public String toString() {
        return displayName;
    }
}
