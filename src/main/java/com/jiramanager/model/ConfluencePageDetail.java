package com.jiramanager.model;

import java.time.Instant;

/**
 * Full detail of a single Confluence page fetched directly by ID — used by the manual
 * "track this page" fallback ({@code JiraService.getConfluencePageDetail}), as opposed to
 * {@link ConfluencePageMeta} which is one entry among many from a space-wide crawl.
 */
public record ConfluencePageDetail(String id, String spaceKey, String title,
                                    String parentPageId, int version, Instant updatedAt) {}
