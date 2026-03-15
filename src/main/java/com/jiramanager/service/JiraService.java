package com.jiramanager.service;

import com.jiramanager.model.AppUser;
import com.jiramanager.model.JiraConfig;
import com.jiramanager.model.JiraTicket;
import com.jiramanager.model.WorklogEntry;
import com.jiramanager.repository.JiraConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.*;

@Slf4j
@Service
public class JiraService {

    private final JiraConfigRepository jiraConfigRepo;
    private final SessionUserService   sessionUserService;

    /** Non-null only when instantiated via the test constructor. */
    private ConfigContext testContext;

    @Autowired
    public JiraService(JiraConfigRepository jiraConfigRepo, SessionUserService sessionUserService) {
        this.jiraConfigRepo     = jiraConfigRepo;
        this.sessionUserService = sessionUserService;
    }

    /** Package-private constructor for unit tests — accepts a pre-configured WebClient. */
    JiraService(WebClient webClient, String baseUrl) {
        this.jiraConfigRepo     = null;
        this.sessionUserService = null;
        this.testContext        = new ConfigContext(webClient, baseUrl);
    }

    // Formatter that handles Jira's "+0700" timezone offset (no colon)
    private static final DateTimeFormatter JIRA_TS_FMT = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
            .optionalStart()
                .appendFraction(ChronoField.MILLI_OF_SECOND, 1, 3, true)
            .optionalEnd()
            .appendPattern("XX")   // handles "+0700" and "+07:00"
            .toFormatter();

    // ── Config resolution ─────────────────────────────────────────────

    /**
     * Holds a ready-to-use WebClient and the base URL for the current user's Jira instance.
     */
    private record ConfigContext(WebClient webClient, String baseUrl) {}

    /**
     * Returns true if the current user has a complete Jira configuration saved in the DB.
     * Safe to call from navigation guards — never throws.
     */
    public boolean isConfigured() {
        if (testContext != null) return true;
        AppUser user = sessionUserService.getCurrentUser();
        if (user == null) return false;
        return jiraConfigRepo.findByUser(user)
                .map(cfg -> !isBlank(cfg.getBaseUrl())
                        && !isBlank(cfg.getEmail())
                        && !isBlank(cfg.getApiToken()))
                .orElse(false);
    }

    private ConfigContext resolveContext() {
        // In unit tests a pre-built context is injected directly
        if (testContext != null) return testContext;

        var user = sessionUserService.getCurrentUser();
        if (user == null) {
            throw new JiraNotConfiguredException("Not logged in.");
        }
        JiraConfig cfg = jiraConfigRepo.findByUser(user)
                .orElseThrow(() -> new JiraNotConfiguredException(
                        "Jira is not configured. Please go to Settings to add your Jira connection."));

        if (isBlank(cfg.getBaseUrl()) || isBlank(cfg.getEmail()) || isBlank(cfg.getApiToken())) {
            throw new JiraNotConfiguredException(
                    "Jira configuration is incomplete. Please fill in Base URL, Email, and API Token in Settings.");
        }

        String credentials = Base64.getEncoder()
                .encodeToString((cfg.getEmail() + ":" + cfg.getApiToken()).getBytes());

        WebClient client = WebClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        return new ConfigContext(client, cfg.getBaseUrl());
    }

    // ── My Tickets ────────────────────────────────────────────────────

    public List<JiraTicket> getMyTickets() {
        ConfigContext ctx = resolveContext();
        try {
            String requestBody = """
                    {
                      "jql": "assignee = currentUser() ORDER BY updated DESC",
                      "maxResults": 50,
                      "fields": ["summary","status","priority","project","issuetype",
                                 "assignee","reporter","created","updated","duedate","customfield_10020"]
                    }
                    """;

            JsonNode response = ctx.webClient().post()
                    .uri("/rest/api/3/search/jql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, cr ->
                            cr.bodyToMono(String.class).map(body -> {
                                log.error("Jira API error — status: {}, body: {}", cr.statusCode(), body);
                                return new RuntimeException("Jira " + cr.statusCode() + ": " + body);
                            }))
                    .bodyToMono(JsonNode.class)
                    .block();

            List<JiraTicket> tickets = new ArrayList<>();
            if (response != null && response.has("issues")) {
                for (JsonNode issue : response.get("issues")) {
                    tickets.add(parseTicket(issue, ctx.baseUrl()));
                }
            }
            return tickets;
        } catch (JiraNotConfiguredException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching Jira tickets: {}", e.getMessage());
            throw new RuntimeException("Failed to connect to Jira: " + e.getMessage(), e);
        }
    }

    // ── Worklogs ──────────────────────────────────────────────────────

    /**
     * Returns all worklog entries logged by the current user on the given date,
     * sorted by start time ascending.
     */
    public List<WorklogEntry> getWorklogsForDate(LocalDate date) {
        ConfigContext ctx = resolveContext();
        try {
            String accountId = getCurrentUserAccountId(ctx.webClient());

            String dateStr = date.toString(); // "2024-01-15"
            String searchBody = String.format("""
                    {
                      "jql": "worklogDate = \\"%s\\" AND worklogAuthor = currentUser()",
                      "maxResults": 50,
                      "fields": ["summary","status","priority","project","issuetype",
                                 "assignee","reporter","timetracking","customfield_10020"]
                    }
                    """, dateStr);

            JsonNode searchResponse = ctx.webClient().post()
                    .uri("/rest/api/3/search/jql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(searchBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, cr ->
                            cr.bodyToMono(String.class).map(e -> {
                                log.error("Jira search error for worklog date {}: {}", dateStr, e);
                                return new RuntimeException("Jira search error: " + e);
                            }))
                    .bodyToMono(JsonNode.class)
                    .block();

            List<WorklogEntry> result = new ArrayList<>();
            if (searchResponse == null || !searchResponse.has("issues")) return result;

            ZoneId zone = ZoneId.systemDefault();

            for (JsonNode issue : searchResponse.get("issues")) {
                String key = issue.path("key").asText();
                JsonNode fields = issue.path("fields");

                JsonNode worklogResp = ctx.webClient().get()
                        .uri("/rest/api/3/issue/" + key + "/worklog")
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, cr ->
                                cr.bodyToMono(String.class).map(e ->
                                        new RuntimeException("Worklog fetch error for " + key + ": " + e)))
                        .bodyToMono(JsonNode.class)
                        .block();

                if (worklogResp == null || !worklogResp.has("worklogs")) continue;

                JsonNode tt = fields.path("timetracking");

                for (JsonNode wl : worklogResp.get("worklogs")) {
                    String wlAuthorId = wl.path("author").path("accountId").asText();
                    if (!accountId.equals(wlAuthorId)) continue;

                    String started = wl.path("started").asText();
                    if (started.isBlank()) continue;

                    Instant startInstant = parseJiraTimestamp(started);
                    LocalDate wlDate = startInstant.atZone(zone).toLocalDate();
                    if (!wlDate.equals(date)) continue;

                    int seconds = wl.path("timeSpentSeconds").asInt(0);
                    Instant endInstant = startInstant.plusSeconds(seconds);

                    result.add(WorklogEntry.builder()
                            .ticketKey(key)
                            .summary(text(fields, "summary"))
                            .status(fields.path("status").path("name").asText("Unknown"))
                            .priority(fields.path("priority").path("name").asText("Medium"))
                            .project(fields.path("project").path("name").asText(""))
                            .issueType(fields.path("issuetype").path("name").asText(""))
                            .assignee(fields.path("assignee").path("displayName").asText("Unassigned"))
                            .reporter(fields.path("reporter").path("displayName").asText(""))
                            .sprint(parseSprint(fields.path("customfield_10020")))
                            .ticketUrl(ctx.baseUrl() + "/browse/" + key)
                            .worklogId(wl.path("id").asText())
                            .startTime(startInstant)
                            .endTime(endInstant)
                            .minutesSpent(seconds / 60)
                            .timeSpentFormatted(formatDuration(seconds / 60))
                            .worklogAuthor(wl.path("author").path("displayName").asText(""))
                            .originalEstimate(tt.path("originalEstimate").asText("—"))
                            .totalTimeSpent(tt.path("timeSpent").asText("—"))
                            .remainingEstimate(tt.path("remainingEstimate").asText("—"))
                            .originalEstimateSeconds(tt.path("originalEstimateSeconds").asLong(0))
                            .totalTimeSpentSeconds(tt.path("timeSpentSeconds").asLong(0))
                            .remainingEstimateSeconds(tt.path("remainingEstimateSeconds").asLong(0))
                            .build());
                }
            }

            result.sort(Comparator.comparing(WorklogEntry::getStartTime));
            return result;

        } catch (JiraNotConfiguredException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching worklogs for {}: {}", date, e.getMessage());
            throw new RuntimeException("Failed to fetch worklogs: " + e.getMessage(), e);
        }
    }

    /** Returns the accountId of the currently authenticated Jira user. */
    private String getCurrentUserAccountId(WebClient webClient) {
        JsonNode myself = webClient.get()
                .uri("/rest/api/3/myself")
                .retrieve()
                .onStatus(HttpStatusCode::isError, cr ->
                        cr.bodyToMono(String.class).map(e ->
                                new RuntimeException("Cannot resolve current user: " + e)))
                .bodyToMono(JsonNode.class)
                .block();
        return myself != null ? myself.path("accountId").asText("") : "";
    }

    // ── Parsing helpers ───────────────────────────────────────────────

    private JiraTicket parseTicket(JsonNode issue, String baseUrl) {
        JsonNode fields = issue.path("fields");
        String key = text(issue, "key");

        return JiraTicket.builder()
                .key(key)
                .summary(text(fields, "summary"))
                .status(fields.path("status").path("name").asText("Unknown"))
                .statusColor(fields.path("status").path("statusCategory").path("colorName").asText(""))
                .priority(fields.path("priority").path("name").asText("Medium"))
                .project(fields.path("project").path("name").asText(""))
                .issueType(fields.path("issuetype").path("name").asText(""))
                .assignee(fields.path("assignee").path("displayName").asText("Unassigned"))
                .reporter(fields.path("reporter").path("displayName").asText(""))
                .description(extractDescription(fields.path("description")))
                .created(formatDate(text(fields, "created")))
                .updated(formatDate(text(fields, "updated")))
                .dueDate(formatDate(text(fields, "duedate")))
                .sprint(parseSprint(fields.path("customfield_10020")))
                .url(baseUrl + "/browse/" + key)
                .build();
    }

    private String parseSprint(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isArray() || node.isEmpty()) return "";
        JsonNode active = null;
        JsonNode last   = null;
        for (JsonNode sprint : node) {
            last = sprint;
            if ("active".equalsIgnoreCase(sprint.path("state").asText())) active = sprint;
        }
        JsonNode chosen = active != null ? active : last;
        return chosen != null ? chosen.path("name").asText("") : "";
    }

    /**
     * Parses a Jira timestamp string (e.g. "2024-01-15T09:00:00.000+0700") into a UTC Instant.
     */
    private Instant parseJiraTimestamp(String ts) {
        try {
            return OffsetDateTime.parse(ts, JIRA_TS_FMT).toInstant();
        } catch (Exception e1) {
            try {
                return OffsetDateTime.parse(ts, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            } catch (Exception e2) {
                log.warn("Could not parse Jira timestamp '{}': {}", ts, e2.getMessage());
                return Instant.now();
            }
        }
    }

    /** Formats a duration in minutes as "1h 30m", "45m", or "2h". */
    public static String formatDuration(int minutes) {
        if (minutes <= 0) return "0m";
        if (minutes < 60) return minutes + "m";
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? h + "h" : h + "h " + m + "m";
    }

    private String text(JsonNode node, String field) { return node.path(field).asText(""); }

    private String formatDate(String iso) {
        if (iso == null || iso.isBlank()) return "";
        return iso.length() >= 10 ? iso.substring(0, 10) : iso;
    }

    private String extractDescription(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return "";
        if (node.isTextual()) {
            String text = node.asText().trim();
            return text.length() > 500 ? text.substring(0, 500) + "..." : text;
        }
        StringBuilder sb = new StringBuilder();
        extractAdfText(node, sb);
        String result = sb.toString().trim();
        return result.length() > 500 ? result.substring(0, 500) + "..." : result;
    }

    private void extractAdfText(JsonNode node, StringBuilder sb) {
        if (node.has("text")) sb.append(node.get("text").asText()).append(" ");
        if (node.has("content")) for (JsonNode c : node.get("content")) extractAdfText(c, sb);
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    // ── Exception ─────────────────────────────────────────────────────

    public static class JiraNotConfiguredException extends RuntimeException {
        public JiraNotConfiguredException(String msg) { super(msg); }
    }
}
