package com.jiramanager.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a pasted Confluence Cloud URL into a space key + optional page ID, for the "Add
 * space/page" manual-tracking fallback on {@code SpacesView} — used when the automatic
 * Jira-project-key ↔ Confluence-space-key match (see {@code KnowledgeBaseSyncRunner}) doesn't
 * find a match, e.g. because the two keys differ (a real, common case — "DEMO2" project key vs.
 * "DOCS" space key).
 *
 * Supports the modern Confluence Cloud URL shapes:
 * <ul>
 *   <li>{@code .../wiki/spaces/KEY} — whole space</li>
 *   <li>{@code .../wiki/spaces/KEY/pages/12345/Title} — one page</li>
 * </ul>
 * Folder links ({@code .../wiki/spaces/KEY/folder/12345/Title}) are recognized but rejected —
 * Confluence's newer "Folder" content type isn't modeled by the REST API v1 endpoints this app
 * uses ({@code JiraService.listSpacePages}/{@code getConfluencePageDetail}).
 */
public final class ConfluenceLinkParser {

    private static final Pattern PAGE_PATTERN =
            Pattern.compile("/wiki/spaces/([^/]+)/pages/(\\d+)");
    private static final Pattern FOLDER_PATTERN =
            Pattern.compile("/wiki/spaces/([^/]+)/folder/(\\d+)");
    private static final Pattern SPACE_PATTERN =
            Pattern.compile("/wiki/spaces/([^/?#]+)(?:/overview)?/?(?:[?#].*)?$");

    private ConfluenceLinkParser() {}

    public enum Kind { SPACE, PAGE, UNSUPPORTED_FOLDER, INVALID }

    public record ParsedLink(Kind kind, String spaceKey, String pageId) {}

    public static ParsedLink parse(String url) {
        if (url == null || url.isBlank()) return new ParsedLink(Kind.INVALID, null, null);
        String trimmed = url.trim();

        Matcher page = PAGE_PATTERN.matcher(trimmed);
        if (page.find()) return new ParsedLink(Kind.PAGE, page.group(1), page.group(2));

        Matcher folder = FOLDER_PATTERN.matcher(trimmed);
        if (folder.find()) return new ParsedLink(Kind.UNSUPPORTED_FOLDER, folder.group(1), folder.group(2));

        Matcher space = SPACE_PATTERN.matcher(trimmed);
        if (space.find()) return new ParsedLink(Kind.SPACE, space.group(1), null);

        return new ParsedLink(Kind.INVALID, null, null);
    }
}
