package com.jiramanager.model;

/**
 * Represents a Confluence page fetched via the Confluence REST API.
 *
 * @param id      Confluence page ID (numeric string)
 * @param title   Page title
 * @param url     Full browser URL to open the page
 * @param content Plain-text content extracted from the page body (max ~3000 chars)
 */
public record ConfluencePage(String id, String title, String url, String content) {}
