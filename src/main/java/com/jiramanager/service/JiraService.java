package com.jiramanager.service;

import com.jiramanager.model.AppUser;
import com.jiramanager.model.JiraConfig;
import com.jiramanager.model.JiraTicket;
import com.jiramanager.model.JiraUser;
import com.jiramanager.model.WorklogEntry;
import com.jiramanager.model.JiraCachedUser;
import com.jiramanager.repository.JiraCachedUserRepository;
import com.jiramanager.repository.JiraConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.jiramanager.model.ConfluencePage;
import com.jiramanager.model.ConfluencePageDetail;
import com.jiramanager.model.ConfluencePageMeta;
import com.jiramanager.model.ConfluenceSpaceInfo;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class JiraService {

    private final JiraConfigRepository jiraConfigRepo;
    private final JiraCachedUserRepository jiraCachedUserRepo;
    private final SessionUserService   sessionUserService;

    /** Non-null only when instantiated via the test constructor. */
    private ConfigContext testContext;

    @Autowired
    public JiraService(JiraConfigRepository jiraConfigRepo,
                        JiraCachedUserRepository jiraCachedUserRepo,
                        SessionUserService sessionUserService) {
        this.jiraConfigRepo     = jiraConfigRepo;
        this.jiraCachedUserRepo = jiraCachedUserRepo;
        this.sessionUserService = sessionUserService;
    }

    /** Package-private constructor for unit tests — accepts a pre-configured WebClient. */
    JiraService(WebClient webClient, String baseUrl) {
        this.jiraConfigRepo     = null;
        this.jiraCachedUserRepo = null;
        this.sessionUserService = null;
        this.testContext        = new ConfigContext(webClient, baseUrl);
    }

    /** Matches Confluence page URLs: extracts the numeric page ID. */
    private static final Pattern CONFLUENCE_PAGE_ID =
            Pattern.compile("/wiki/(?:spaces/[^/]+/pages|pages)/([0-9]+)");

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
        // Jira config is resolved as a single shared record, independent of who is
        // logged in — login is currently disabled (see SecurityConfig / AutoLoginFilter).
        return jiraConfigRepo.findFirstByOrderByIdAsc()
                .map(cfg -> !isBlank(cfg.getBaseUrl())
                        && !isBlank(cfg.getEmail())
                        && !isBlank(cfg.getApiToken()))
                .orElse(false);
    }

    private ConfigContext resolveContext() {
        // In unit tests a pre-built context is injected directly
        if (testContext != null) return testContext;

        // Jira config is resolved as a single shared record, independent of who is
        // logged in — login is currently disabled (see SecurityConfig / AutoLoginFilter).
        JiraConfig cfg = jiraConfigRepo.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new JiraNotConfiguredException(
                        "Jira is not configured. Please go to Settings to add your Jira connection."));

        if (isBlank(cfg.getBaseUrl()) || isBlank(cfg.getEmail()) || isBlank(cfg.getApiToken())) {
            throw new JiraNotConfiguredException(
                    "Jira configuration is incomplete. Please fill in Base URL, Email, and API Token in Settings.");
        }

        return buildContext(cfg);
    }

    /**
     * Builds a Jira API client directly from a given config, independent of the current HTTP
     * session. Used by the background user-sync job (see {@code JiraUserSyncRunner}), which runs
     * outside any request/session scope and must be able to target an arbitrary user's config.
     */
    ConfigContext buildContext(JiraConfig cfg) {
        String credentials = Base64.getEncoder()
                .encodeToString((cfg.getEmail() + ":" + cfg.getApiToken()).getBytes());

        WebClient client = WebClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .exchangeStrategies(org.springframework.web.reactive.function.client.ExchangeStrategies.builder()
                        .codecs(cfg2 -> cfg2.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 10 MB
                        .build())
                .build();

        return new ConfigContext(client, cfg.getBaseUrl());
    }

    // ── My Tickets ────────────────────────────────────────────────────

    private static final String TICKET_FIELDS =
            "summary,status,priority,project,issuetype,assignee,reporter,created,updated,duedate,customfield_10020,timetracking,description";

    public List<JiraTicket> getMyTickets() {
        return fetchMyTickets(resolveContext());
    }

    /**
     * Session-independent variant of {@link #getMyTickets()} — used by background jobs that act
     * on behalf of an arbitrary user's config, outside of any HTTP session (mirrors
     * {@link #fetchAssignableUsersFromJira(JiraConfig)}).
     */
    public List<JiraTicket> getMyTickets(JiraConfig cfg) {
        return fetchMyTickets(buildContext(cfg));
    }

    private List<JiraTicket> fetchMyTickets(ConfigContext ctx) {
        try {
            String requestBody = """
                    {
                      "jql": "assignee = currentUser() ORDER BY updated DESC",
                      "maxResults": 50,
                      "fields": ["%s"]
                    }
                    """.formatted(String.join("\",\"", TICKET_FIELDS.split(",")));

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

    /**
     * Fetches a single ticket fresh from Jira (bypasses any previously-loaded ticket list).
     * Used wherever time-tracking figures must reflect the latest state, e.g. the "Tickets to
     * Log" panel on the Worklog view, instead of the snapshot loaded when the page was opened.
     */
    public JiraTicket getTicketByKey(String key) {
        return fetchTicket(resolveContext(), key);
    }

    /** Session-independent variant of {@link #getTicketByKey(String)} — for background jobs. */
    public JiraTicket getTicketByKey(JiraConfig cfg, String key) {
        return fetchTicket(buildContext(cfg), key);
    }

    private JiraTicket fetchTicket(ConfigContext ctx, String key) {
        try {
            JsonNode issue = ctx.webClient().get()
                    .uri("/rest/api/3/issue/" + key + "?fields=" + TICKET_FIELDS)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, cr ->
                            cr.bodyToMono(String.class).map(body -> {
                                log.error("Jira API error fetching {} — status: {}, body: {}", key, cr.statusCode(), body);
                                return new RuntimeException("Jira " + cr.statusCode() + ": " + body);
                            }))
                    .bodyToMono(JsonNode.class)
                    .block();
            if (issue == null) throw new RuntimeException("Empty response fetching " + key);
            return parseTicket(issue, ctx.baseUrl());
        } catch (JiraNotConfiguredException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching ticket {}: {}", key, e.getMessage());
            throw new RuntimeException("Failed to fetch ticket " + key + ": " + e.getMessage(), e);
        }
    }

    /**
     * Bulk-fetches multiple tickets by key in as few round trips as possible (batched at 50 keys
     * per JQL call, Jira's per-request result cap). Used by the background ticket-doc
     * change-detection pass instead of one {@link #getTicketByKey(JiraConfig, String)} call per
     * attached ticket. Unknown/inaccessible keys are silently omitted from the result.
     */
    public List<JiraTicket> getTicketsByKeys(JiraConfig cfg, List<String> keys) {
        if (keys == null || keys.isEmpty()) return List.of();
        ConfigContext ctx = buildContext(cfg);
        List<JiraTicket> tickets = new ArrayList<>();
        try {
            for (int i = 0; i < keys.size(); i += 50) {
                List<String> batch = keys.subList(i, Math.min(i + 50, keys.size()));
                String jql = "key in (" + String.join(",", batch) + ")";
                String requestBody = """
                        {
                          "jql": "%s",
                          "maxResults": 50,
                          "fields": ["%s"]
                        }
                        """.formatted(jql, String.join("\",\"", TICKET_FIELDS.split(",")));

                JsonNode response = ctx.webClient().post()
                        .uri("/rest/api/3/search/jql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(requestBody)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, cr ->
                                cr.bodyToMono(String.class).map(body ->
                                        new RuntimeException("Jira " + cr.statusCode() + ": " + body)))
                        .bodyToMono(JsonNode.class)
                        .block();

                if (response != null && response.has("issues")) {
                    for (JsonNode issue : response.get("issues")) {
                        tickets.add(parseTicket(issue, ctx.baseUrl()));
                    }
                }
            }
            return tickets;
        } catch (Exception e) {
            log.warn("Error bulk-fetching tickets {}: {}", keys, e.getMessage());
            return tickets;
        }
    }

    // ── Users ─────────────────────────────────────────────────────────

    /** Returns the accountId + display name of the currently authenticated Jira user. */
    public JiraUser getCurrentJiraUser() {
        ConfigContext ctx = resolveContext();
        JsonNode myself = ctx.webClient().get()
                .uri("/rest/api/3/myself")
                .retrieve()
                .onStatus(HttpStatusCode::isError, cr ->
                        cr.bodyToMono(String.class).map(e ->
                                new RuntimeException("Cannot resolve current user: " + e)))
                .bodyToMono(JsonNode.class)
                .block();
        if (myself == null) throw new RuntimeException("Cannot resolve current user.");
        return new JiraUser(myself.path("accountId").asText(""), myself.path("displayName").asText(""));
    }

    /**
     * Returns the Jira "member" list for the current user's Jira site, read entirely from the
     * local DB cache populated by the background sync job ({@code JiraUserSyncRunner}) — this
     * never calls Jira live, so it stays fast regardless of how many users the site has.
     * Always includes the current user, even if the last sync predates their account.
     */
    public List<JiraUser> searchAssignableUsers() {
        // Jira config is resolved as a single shared record, independent of who is
        // logged in — login is currently disabled (see SecurityConfig / AutoLoginFilter).
        JiraConfig cfg = jiraConfigRepo.findFirstByOrderByIdAsc().orElse(null);
        if (cfg == null || isBlank(cfg.getBaseUrl())) return List.of();

        LinkedHashMap<String, JiraUser> byId = new LinkedHashMap<>();
        for (JiraCachedUser c : jiraCachedUserRepo.findByBaseUrlOrderByDisplayNameAsc(cfg.getBaseUrl())) {
            byId.put(c.getAccountId(), new JiraUser(c.getAccountId(), c.getDisplayName()));
        }

        // Ensure the current user is always present, even if the cache is stale or empty
        // (e.g. this Jira config was added after the last background sync ran).
        try {
            JiraUser me = getCurrentJiraUser();
            byId.putIfAbsent(me.accountId(), me);
        } catch (Exception ignored) {}

        List<JiraUser> users = new ArrayList<>(byId.values());
        users.sort(Comparator.comparing(JiraUser::displayName, String.CASE_INSENSITIVE_ORDER));
        return users;
    }

    /**
     * Fetches the live Jira user list directly from a given config, bypassing the current HTTP
     * session entirely. Only called by the background sync job — never on a request thread —
     * since {@code /users/search} can be slow on large Jira sites.
     */
    public List<JiraUser> fetchAssignableUsersFromJira(JiraConfig cfg) {
        ConfigContext ctx = buildContext(cfg);
        JsonNode resp = ctx.webClient().get()
                .uri(uriBuilder -> uriBuilder.path("/rest/api/3/users/search")
                        .queryParam("maxResults", 200)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, cr ->
                        cr.bodyToMono(String.class).map(body ->
                                new RuntimeException("User search error: " + body)))
                .bodyToMono(JsonNode.class)
                .block();

        List<JiraUser> users = new ArrayList<>();
        if (resp != null && resp.isArray()) {
            for (JsonNode u : resp) {
                if (!"atlassian".equals(u.path("accountType").asText())) continue;
                if (!u.path("active").asBoolean(true)) continue;
                String accountId = u.path("accountId").asText("");
                String name      = u.path("displayName").asText("");
                if (accountId.isBlank() || name.isBlank()) continue;
                users.add(new JiraUser(accountId, name));
            }
        }
        return users;
    }

    // ── Worklogs ──────────────────────────────────────────────────────

    /**
     * Returns all worklog entries logged by the current user on the given date,
     * sorted by start time ascending.
     */
    public List<WorklogEntry> getWorklogsForDate(LocalDate date) {
        String accountId = getCurrentUserAccountId(resolveContext().webClient());
        return getWorklogsForDate(date, accountId);
    }

    /**
     * Returns all worklog entries logged by the given Jira account on the given date,
     * sorted by start time ascending. Used by the Worklog / Worklog Calendar "member" filter
     * to inspect a teammate's logged time.
     */
    public List<WorklogEntry> getWorklogsForDate(LocalDate date, String accountId) {
        ConfigContext ctx = resolveContext();
        try {
            String dateStr = date.toString(); // "2024-01-15"
            String searchBody = String.format("""
                    {
                      "jql": "worklogDate = \\"%s\\" AND worklogAuthor = \\"%s\\"",
                      "maxResults": 50,
                      "fields": ["summary","status","priority","project","issuetype",
                                 "assignee","reporter","timetracking","customfield_10020"]
                    }
                    """, dateStr, accountId);

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

        JsonNode tt = fields.path("timetracking");
        String updatedRaw = text(fields, "updated");

        return JiraTicket.builder()
                .key(key)
                .summary(text(fields, "summary"))
                .status(fields.path("status").path("name").asText("Unknown"))
                .statusColor(fields.path("status").path("statusCategory").path("colorName").asText(""))
                .priority(fields.path("priority").path("name").asText("Medium"))
                .project(fields.path("project").path("name").asText(""))
                .projectKey(fields.path("project").path("key").asText(""))
                .issueType(fields.path("issuetype").path("name").asText(""))
                .assignee(fields.path("assignee").path("displayName").asText("Unassigned"))
                .reporter(fields.path("reporter").path("displayName").asText(""))
                .description(extractDescription(fields.path("description")))
                .fullDescription(extractFullDescriptionText(fields.path("description")))
                .created(formatDate(text(fields, "created")))
                .updated(formatDate(updatedRaw))
                .updatedInstant(updatedRaw.isBlank() ? null : parseJiraTimestamp(updatedRaw))
                .dueDate(formatDate(text(fields, "duedate")))
                .sprint(parseSprint(fields.path("customfield_10020")))
                .url(baseUrl + "/browse/" + key)
                .confluencePageIds(extractConfluencePageIds(fields.path("description"), baseUrl))
                .originalEstimate(tt.path("originalEstimate").asText(""))
                .originalEstimateSeconds(tt.path("originalEstimateSeconds").asLong(0))
                .timeSpent(tt.path("timeSpent").asText(""))
                .timeSpentSeconds(tt.path("timeSpentSeconds").asLong(0))
                .remainingEstimate(tt.path("remainingEstimate").asText(""))
                .remainingEstimateSeconds(tt.path("remainingEstimateSeconds").asLong(0))
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

    // ── Confluence ────────────────────────────────────────────────────

    /**
     * Returns Confluence page IDs linked to a Jira issue via Jira's Remote Links feature
     * (the "Confluence Pages" section visible in the Jira issue sidebar).
     * This is the primary way Confluence pages are associated with Jira tickets — they are
     * NOT stored in the description ADF but as separate remote link objects.
     */
    /** Pattern to extract Confluence page ID from globalId: "appId=xxx&pageId=187203592" */
    private static final Pattern GLOBAL_ID_PAGE = Pattern.compile("pageId=([0-9]+)");

    public List<String> getConfluenceRemoteLinks(String issueKey) {
        ConfigContext ctx = resolveContext();
        try {
            JsonNode resp = ctx.webClient().get()
                    .uri("/rest/api/3/issue/" + issueKey + "/remotelink")
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, cr ->
                            cr.bodyToMono(String.class).map(body ->
                                    new RuntimeException("Remote links error for " + issueKey + ": " + body)))
                    .bodyToMono(JsonNode.class)
                    .block();

            if (resp == null || !resp.isArray()) {
                log.debug("No remote links found for {}", issueKey);
                return List.of();
            }

            log.debug("Remote links for {}: {} entries", issueKey, resp.size());
            List<String> ids = new ArrayList<>();

            for (JsonNode link : resp) {
                String url      = link.path("object").path("url").asText("");
                String globalId = link.path("globalId").asText("");
                String appType  = link.path("application").path("type").asText("");

                // Log every entry so we can see exactly what Jira returns
                log.debug("  remotelink app={} globalId={} url={}", appType, globalId, url);

                // Source 1: URL in object
                addIfConfluencePage(url, ids);

                // Source 2: globalId like "appId=xxx&pageId=187203592"
                // This is how Confluence-linked pages appear when linked from Confluence side
                Matcher gm = GLOBAL_ID_PAGE.matcher(globalId);
                if (gm.find()) {
                    String id = gm.group(1);
                    if (!ids.contains(id)) {
                        log.debug("  -> Confluence page found via globalId: {}", id);
                        ids.add(id);
                    }
                }
            }
            return ids.isEmpty() ? List.of() : List.copyOf(ids);
        } catch (Exception e) {
            log.warn("Could not fetch remote links for {}: {}", issueKey, e.getMessage());
            return List.of();
        }
    }

    /**
     * Fetches a Confluence page by ID using the same credentials as Jira.
     * Returns null if the page cannot be retrieved.
     */
    public ConfluencePage getConfluencePage(String pageId) {
        ConfigContext ctx = resolveContext();
        try {
            // body.view = Confluence-rendered HTML (much cleaner than body.storage)
            JsonNode resp = ctx.webClient().get()
                    .uri("/wiki/rest/api/content/" + pageId + "?expand=body.view,space")
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, cr ->
                            cr.bodyToMono(String.class).map(body ->
                                    new RuntimeException("Confluence " + cr.statusCode() + ": " + body)))
                    .bodyToMono(JsonNode.class)
                    .block();

            if (resp == null) return null;

            String title    = resp.path("title").asText("Untitled");
            String spaceKey = resp.path("space").path("key").asText("");
            String url      = ctx.baseUrl() + "/wiki/spaces/" + spaceKey + "/pages/" + pageId;
            String viewHtml = resp.path("body").path("view").path("value").asText("");
            String content  = sanitizeViewHtml(viewHtml);

            return new ConfluencePage(pageId, title, url, content);
        } catch (Exception e) {
            log.warn("Could not fetch Confluence page {}: {}", pageId, e.getMessage());
            return null;
        }
    }

    /**
     * Looks up a Confluence space by key — used by the background Feature-sync job to check
     * whether a Jira project key has a matching Confluence space before crawling it.
     * Returns null when no such space exists (a common, expected outcome, not an error).
     */
    public ConfluenceSpaceInfo getConfluenceSpace(JiraConfig cfg, String spaceKey) {
        ConfigContext ctx = buildContext(cfg);
        try {
            JsonNode resp = ctx.webClient().get()
                    .uri("/wiki/rest/api/space/" + spaceKey)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            if (resp == null) return null;
            return new ConfluenceSpaceInfo(
                    resp.path("key").asText(spaceKey),
                    resp.path("id").asText(""),
                    resp.path("name").asText(""));
        } catch (WebClientResponseException.NotFound e) {
            return null;
        } catch (Exception e) {
            log.warn("Could not look up Confluence space {}: {}", spaceKey, e.getMessage());
            return null;
        }
    }

    /**
     * Crawls every current page in a Confluence space, following pagination, and returns each
     * page's tree position (parent from {@code ancestors}) and version metadata. Used by the
     * background Feature-sync job to build/refresh the Spaces tree grid — never called on a
     * request thread since a large space can take several round trips.
     */
    public List<ConfluencePageMeta> listSpacePages(JiraConfig cfg, String spaceKey) {
        ConfigContext ctx = buildContext(cfg);
        List<ConfluencePageMeta> pages = new ArrayList<>();
        try {
            String uri = "/wiki/rest/api/content?spaceKey=" + spaceKey
                    + "&type=page&status=current&expand=version,ancestors&limit=100";

            while (uri != null) {
                JsonNode resp = ctx.webClient().get()
                        .uri(uri)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, cr ->
                                cr.bodyToMono(String.class).map(body ->
                                        new RuntimeException("Confluence content list error for " + spaceKey + ": " + body)))
                        .bodyToMono(JsonNode.class)
                        .block();
                if (resp == null || !resp.has("results")) break;

                for (JsonNode page : resp.get("results")) {
                    String id      = page.path("id").asText("");
                    String title   = page.path("title").asText("");
                    int    version = page.path("version").path("number").asInt(1);
                    String when    = page.path("version").path("when").asText("");
                    Instant updatedAt = when.isBlank() ? Instant.now() : parseConfluenceTimestamp(when);

                    JsonNode ancestors = page.path("ancestors");
                    String parentId = (ancestors.isArray() && !ancestors.isEmpty())
                            ? ancestors.get(ancestors.size() - 1).path("id").asText(null)
                            : null;

                    pages.add(new ConfluencePageMeta(id, title, parentId, version, updatedAt));
                }

                String next = resp.path("_links").path("next").asText("");
                uri = next.isBlank() ? null : next; // relative path, resolved against baseUrl
            }
            return pages;
        } catch (Exception e) {
            log.warn("Could not list pages for Confluence space {}: {}", spaceKey, e.getMessage());
            return pages;
        }
    }

    /**
     * Fetches a single Confluence page by ID, including its space key — used to link a Spaces
     * tree item ({@code SpaceItem}) to one specific Confluence page, and to keep that link's
     * version/updated timestamp fresh in {@code KnowledgeBaseSyncRunner}.
     */
    public ConfluencePageDetail getConfluencePageDetail(JiraConfig cfg, String pageId) {
        ConfigContext ctx = buildContext(cfg);
        try {
            JsonNode resp = ctx.webClient().get()
                    .uri("/wiki/rest/api/content/" + pageId + "?expand=version,ancestors,space")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            if (resp == null) return null;

            String spaceKey = resp.path("space").path("key").asText("");
            String title    = resp.path("title").asText("");
            int    version  = resp.path("version").path("number").asInt(1);
            String when     = resp.path("version").path("when").asText("");
            Instant updatedAt = when.isBlank() ? Instant.now() : parseConfluenceTimestamp(when);

            JsonNode ancestors = resp.path("ancestors");
            String parentId = (ancestors.isArray() && !ancestors.isEmpty())
                    ? ancestors.get(ancestors.size() - 1).path("id").asText(null)
                    : null;

            return new ConfluencePageDetail(pageId, spaceKey, title, parentId, version, updatedAt);
        } catch (WebClientResponseException.NotFound e) {
            return null;
        } catch (Exception e) {
            log.warn("Could not fetch Confluence page detail {}: {}", pageId, e.getMessage());
            return null;
        }
    }

    /** Parses a Confluence timestamp (typically "2024-01-15T10:30:00.000Z"). */
    private Instant parseConfluenceTimestamp(String ts) {
        try {
            return Instant.parse(ts);
        } catch (Exception e1) {
            try {
                return OffsetDateTime.parse(ts, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            } catch (Exception e2) {
                log.warn("Could not parse Confluence timestamp '{}': {}", ts, e2.getMessage());
                return Instant.now();
            }
        }
    }

    /**
     * Extracts Confluence page IDs from an ADF description node.
     * Handles both inlineCard nodes and link marks on text nodes.
     */
    private List<String> extractConfluencePageIds(JsonNode descNode, String baseUrl) {
        if (descNode == null || descNode.isNull() || descNode.isMissingNode()) return List.of();
        List<String> ids = new ArrayList<>();
        collectConfluenceLinks(descNode, ids);
        return ids.isEmpty() ? List.of() : List.copyOf(ids);
    }

    private void collectConfluenceLinks(JsonNode node, List<String> ids) {
        String type = node.path("type").asText("");

        // Smart link cards: inlineCard, blockCard, embedCard
        // {"type":"blockCard","attrs":{"url":"https://.../wiki/.../pages/123456/..."}}
        if ("inlineCard".equals(type) || "blockCard".equals(type) || "embedCard".equals(type)) {
            String url = node.path("attrs").path("url").asText("");
            log.debug("ADF card node type={} url={}", type, url);
            addIfConfluencePage(url, ids);
        }

        // link mark on text: {"marks":[{"type":"link","attrs":{"href":"..."}}]}
        if (node.has("marks")) {
            for (JsonNode mark : node.get("marks")) {
                if ("link".equals(mark.path("type").asText())) {
                    String href = mark.path("attrs").path("href").asText("");
                    log.debug("ADF link mark href={}", href);
                    addIfConfluencePage(href, ids);
                }
            }
        }

        if (node.has("content")) {
            for (JsonNode child : node.get("content")) collectConfluenceLinks(child, ids);
        }
    }

    private void addIfConfluencePage(String url, List<String> ids) {
        if (url == null || url.isBlank()) return;
        Matcher m = CONFLUENCE_PAGE_ID.matcher(url);
        if (m.find()) {
            String id = m.group(1);
            if (!ids.contains(id)) ids.add(id);
        }
    }

    /**
     * Sanitizes Confluence body.view HTML for safe embedding in the UI.
     * Removes dangerous tags/attributes while keeping headings, lists, paragraphs,
     * tables, emphasis, code blocks, and links.
     */
    private String sanitizeViewHtml(String html) {
        if (html == null || html.isBlank()) return "";
        String clean = html
                // Remove dangerous tags entirely (with content)
                .replaceAll("(?is)<script[^>]*>.*?</script>", "")
                .replaceAll("(?is)<style[^>]*>.*?</style>",   "")
                .replaceAll("(?is)<iframe[^>]*>.*?</iframe>", "")
                // Strip event handlers and javascript: hrefs
                .replaceAll("(?i)\\s+on[a-zA-Z]+\\s*=\\s*\"[^\"]*\"", "")
                .replaceAll("(?i)\\s+on[a-zA-Z]+\\s*=\\s*'[^']*'",   "")
                .replaceAll("(?i)(href|src)\\s*=\\s*\"javascript:[^\"]*\"", "href=\"#\"")
                // Remove Confluence-specific wrapper divs/spans but keep their content
                .replaceAll("(?i)</?div[^>]*>",  "")
                .replaceAll("(?i)</?span[^>]*>", "")
                .replaceAll("(?i)</?section[^>]*>", "")
                // Clean up attributes from allowed tags (keep them simple)
                .replaceAll("(<(?:h[1-6]|p|ul|ol|li|table|thead|tbody|tr|td|th|blockquote|pre|code))[^>]*>", "$1>")
                // Trim excessive whitespace
                .replaceAll("(\\s*\\n\\s*){3,}", "\n\n")
                .trim();
        return clean.length() > 20000 ? clean.substring(0, 20000) + "…" : clean;
    }

    /** Strips Confluence storage-format XML/HTML to readable plain text. */
    private String stripStorageFormat(String html) {
        if (html == null || html.isBlank()) return "";
        String text = html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|h[1-6]|li|tr|div)>", "\n")
                .replaceAll("<[^>]+>", "")
                .replaceAll("&amp;",  "&").replaceAll("&lt;",   "<")
                .replaceAll("&gt;",   ">").replaceAll("&nbsp;", " ")
                .replaceAll("&quot;", "\"").replaceAll("&#[0-9]+;", "")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        return text.length() > 15000 ? text.substring(0, 15000) + "…" : text;
    }

    /** Truncated to 500 chars (+"...") — for compact detail-panel display only. */
    private String extractDescription(JsonNode node) {
        String full = extractFullDescriptionText(node);
        return full.length() > 500 ? full.substring(0, 500) + "..." : full;
    }

    /** Full, untruncated plain-text description — used by Ticket Docs markdown generation. */
    private String extractFullDescriptionText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return "";
        if (node.isTextual()) return node.asText().trim();
        StringBuilder sb = new StringBuilder();
        extractAdfText(node, sb);
        return sb.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    /** ADF block-level node types that should end with a line break in the extracted plain text. */
    private static final Set<String> ADF_BLOCK_TYPES = Set.of(
            "paragraph", "heading", "codeBlock", "blockquote", "listItem", "rule", "tableRow");

    /**
     * Walks an ADF (Atlassian Document Format) node tree and appends its plain text to {@code sb},
     * preserving paragraph/heading/list-item breaks as newlines (ADF text nodes don't carry any
     * line-break info themselves — without this, an entire multi-paragraph description collapses
     * into one unreadable run-on line).
     */
    private void extractAdfText(JsonNode node, StringBuilder sb) {
        String type = node.path("type").asText("");

        if ("hardBreak".equals(type)) {
            sb.append("\n");
            return;
        }
        if ("listItem".equals(type)) {
            sb.append("- ");
        }
        if (node.has("text")) sb.append(node.get("text").asText());
        if (node.has("content")) for (JsonNode c : node.get("content")) extractAdfText(c, sb);
        if (ADF_BLOCK_TYPES.contains(type)) sb.append("\n");
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    // ── Exception ─────────────────────────────────────────────────────

    public static class JiraNotConfiguredException extends RuntimeException {
        public JiraNotConfiguredException(String msg) { super(msg); }
    }
}
