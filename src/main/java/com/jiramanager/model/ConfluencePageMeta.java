package com.jiramanager.model;

import java.time.Instant;

/**
 * Lightweight metadata for one page in a Confluence space's page tree, as returned by
 * {@code JiraService.listSpacePages()}. Used to populate/refresh {@code ConfluenceFeature} rows
 * during the background sync — this is the live-fetched shape, not the persisted one.
 *
 * @param id            Confluence page ID
 * @param title         Page title ("folder name" in the Spaces tree grid)
 * @param parentPageId  Immediate parent page ID, or null for a space-root page
 * @param version       Confluence page version number (increments on every edit)
 * @param updatedAt     When this version was last saved, per Confluence
 */
public record ConfluencePageMeta(String id, String title, String parentPageId, int version, Instant updatedAt) {}
