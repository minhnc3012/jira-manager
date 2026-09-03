package com.jiramanager.model;

/**
 * Minimal Confluence space lookup result. Currently unused by the Spaces feature (which now
 * only links one specific page at a time via {@code JiraService.getConfluencePageDetail}) —
 * kept (and still unit-tested) as a reusable building block for a future whole-space browse.
 *
 * @param key  Confluence space key
 * @param id   Confluence space ID
 * @param name Space display name
 */
public record ConfluenceSpaceInfo(String key, String id, String name) {}
