package com.jiramanager.model;

/**
 * Minimal Confluence space lookup result — used only to confirm a space exists for a given
 * key before crawling its page tree. Not persisted (see {@code ConfluenceFeature} for the
 * persisted per-page cache).
 *
 * @param key  Confluence space key (matched against Jira project keys)
 * @param id   Confluence space ID
 * @param name Space display name
 */
public record ConfluenceSpaceInfo(String key, String id, String name) {}
