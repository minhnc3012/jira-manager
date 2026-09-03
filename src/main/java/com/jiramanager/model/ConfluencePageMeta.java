package com.jiramanager.model;

import java.time.Instant;

/**
 * Lightweight metadata for one page in a Confluence space's page tree, as returned by
 * {@code JiraService.listSpacePages()}. Currently unused by the Spaces feature (which now only
 * links one specific page at a time via {@code JiraService.getConfluencePageDetail}) — kept
 * (and still unit-tested) as a reusable building block for a future whole-space browse/import.
 *
 * @param id            Confluence page ID
 * @param title         Page title
 * @param parentPageId  Immediate parent page ID, or null for a space-root page
 * @param version       Confluence page version number (increments on every edit)
 * @param updatedAt     When this version was last saved, per Confluence
 */
public record ConfluencePageMeta(String id, String title, String parentPageId, int version, Instant updatedAt) {}
