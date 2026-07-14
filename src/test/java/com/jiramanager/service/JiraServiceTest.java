package com.jiramanager.service;

import com.jiramanager.model.ConfluencePageDetail;
import com.jiramanager.model.ConfluencePageMeta;
import com.jiramanager.model.ConfluenceSpaceInfo;
import com.jiramanager.model.JiraConfig;
import com.jiramanager.model.JiraTicket;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class JiraServiceTest {

    private MockWebServer mockServer;
    private JiraService jiraService;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        baseUrl = mockServer.url("/").toString().replaceAll("/$", "");
        WebClient webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic dGVzdDp0ZXN0") // test:test
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();

        jiraService = new JiraService(webClient, baseUrl);
    }

    /** {@code getConfluenceSpace}/{@code listSpacePages} take an explicit config (session-independent). */
    private JiraConfig testConfig() {
        return JiraConfig.builder()
                .baseUrl(baseUrl)
                .email("test@example.com")
                .apiToken("test-token")
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    // ── Success path ──────────────────────────────────────────────────

    @Test
    void getMyTickets_success_parsesAllFields() throws InterruptedException {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "issues": [
                            {
                              "key": "DEMO-123",
                              "fields": {
                                "summary": "Fix login bug",
                                "status": { "name": "In Progress", "statusCategory": { "colorName": "blue" } },
                                "priority": { "name": "High" },
                                "project": { "name": "Demo" },
                                "issuetype": { "name": "Bug" },
                                "assignee": { "displayName": "Minh Nguyen" },
                                "reporter": { "displayName": "Alice" },
                                "description": "This is a plain text description.",
                                "created": "2025-01-15T08:00:00.000+0700",
                                "updated": "2025-03-10T12:30:00.000+0700",
                                "duedate": "2025-04-01"
                              }
                            }
                          ]
                        }
                        """));

        List<JiraTicket> tickets = jiraService.getMyTickets();

        assertThat(tickets).hasSize(1);
        JiraTicket t = tickets.get(0);
        assertThat(t.getKey()).isEqualTo("DEMO-123");
        assertThat(t.getSummary()).isEqualTo("Fix login bug");
        assertThat(t.getStatus()).isEqualTo("In Progress");
        assertThat(t.getStatusColor()).isEqualTo("blue");
        assertThat(t.getPriority()).isEqualTo("High");
        assertThat(t.getProject()).isEqualTo("Demo");
        assertThat(t.getIssueType()).isEqualTo("Bug");
        assertThat(t.getAssignee()).isEqualTo("Minh Nguyen");
        assertThat(t.getReporter()).isEqualTo("Alice");
        assertThat(t.getDescription()).isEqualTo("This is a plain text description.");
        assertThat(t.getCreated()).isEqualTo("2025-01-15");
        assertThat(t.getUpdated()).isEqualTo("2025-03-10");
        assertThat(t.getDueDate()).isEqualTo("2025-04-01");
        assertThat(t.getUrl()).endsWith("/browse/DEMO-123");
    }

    @Test
    void getMyTickets_usesPostJqlEndpoint() throws InterruptedException {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"issues\":[]}"));

        jiraService.getMyTickets();

        RecordedRequest req = mockServer.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/rest/api/3/search/jql");
        assertThat(req.getBody().readUtf8()).contains("assignee = currentUser()");
    }

    // ── Empty results ─────────────────────────────────────────────────

    @Test
    void getMyTickets_emptyIssues_returnsEmptyList() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"issues\":[]}"));

        List<JiraTicket> tickets = jiraService.getMyTickets();

        assertThat(tickets).isEmpty();
    }

    @Test
    void getMyTickets_nullResponseBody_returnsEmptyList() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{}"));

        List<JiraTicket> tickets = jiraService.getMyTickets();

        assertThat(tickets).isEmpty();
    }

    // ── API error handling ────────────────────────────────────────────

    @Test
    void getMyTickets_401Unauthorized_throwsRuntimeException() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"message\":\"Unauthorized\"}"));

        assertThatThrownBy(() -> jiraService.getMyTickets())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to connect to Jira");
    }

    @Test
    void getMyTickets_410Gone_throwsRuntimeExceptionWithStatusCode() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(410)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"errorMessages\":[\"The requested API has been removed.\"]}"));

        assertThatThrownBy(() -> jiraService.getMyTickets())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to connect to Jira");
    }

    @Test
    void getMyTickets_500ServerError_throwsRuntimeException() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"message\":\"Internal Server Error\"}"));

        assertThatThrownBy(() -> jiraService.getMyTickets())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to connect to Jira");
    }

    // ── Default field values ──────────────────────────────────────────

    @Test
    void getMyTickets_missingOptionalFields_usesDefaults() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "issues": [
                            {
                              "key": "DEMO-1",
                              "fields": {
                                "summary": "Minimal ticket",
                                "status": {},
                                "priority": {},
                                "project": {},
                                "issuetype": {},
                                "assignee": null,
                                "reporter": null,
                                "description": null,
                                "created": null,
                                "updated": null,
                                "duedate": null
                              }
                            }
                          ]
                        }
                        """));

        List<JiraTicket> tickets = jiraService.getMyTickets();

        assertThat(tickets).hasSize(1);
        JiraTicket t = tickets.get(0);
        assertThat(t.getStatus()).isEqualTo("Unknown");
        assertThat(t.getPriority()).isEqualTo("Medium");
        assertThat(t.getAssignee()).isEqualTo("Unassigned");
        assertThat(t.getDescription()).isEmpty();
        assertThat(t.getCreated()).isEmpty();
        assertThat(t.getDueDate()).isEmpty();
    }

    // ── ADF description parsing ───────────────────────────────────────

    @Test
    void getMyTickets_adfDescription_extractsPlainText() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "issues": [
                            {
                              "key": "DEMO-2",
                              "fields": {
                                "summary": "ADF ticket",
                                "status": { "name": "Open", "statusCategory": { "colorName": "grey" } },
                                "priority": { "name": "Medium" },
                                "project": { "name": "Demo" },
                                "issuetype": { "name": "Story" },
                                "assignee": { "displayName": "Bob" },
                                "reporter": { "displayName": "Carol" },
                                "description": {
                                  "type": "doc",
                                  "content": [
                                    {
                                      "type": "paragraph",
                                      "content": [
                                        { "type": "text", "text": "Hello " },
                                        { "type": "text", "text": "World" }
                                      ]
                                    }
                                  ]
                                },
                                "created": "2025-02-01T09:00:00.000+0700",
                                "updated": "2025-02-02T09:00:00.000+0700",
                                "duedate": null
                              }
                            }
                          ]
                        }
                        """));

        List<JiraTicket> tickets = jiraService.getMyTickets();

        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).getDescription()).contains("Hello").contains("World");
    }

    @Test
    void getMyTickets_adfMultiParagraphAndList_preservesLineBreaks() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "issues": [
                            {
                              "key": "DEMO-5",
                              "fields": {
                                "summary": "Multi-paragraph ADF",
                                "status": { "name": "Open", "statusCategory": { "colorName": "grey" } },
                                "priority": { "name": "Medium" },
                                "project": { "name": "Demo" },
                                "issuetype": { "name": "Story" },
                                "assignee": { "displayName": "Bob" },
                                "reporter": { "displayName": "Carol" },
                                "description": {
                                  "type": "doc",
                                  "content": [
                                    { "type": "heading", "content": [ { "type": "text", "text": "Implementation notes" } ] },
                                    { "type": "paragraph", "content": [ { "type": "text", "text": "First paragraph." } ] },
                                    { "type": "paragraph", "content": [ { "type": "text", "text": "Second paragraph." } ] },
                                    { "type": "bulletList", "content": [
                                        { "type": "listItem", "content": [
                                            { "type": "paragraph", "content": [ { "type": "text", "text": "Given something" } ] }
                                        ] },
                                        { "type": "listItem", "content": [
                                            { "type": "paragraph", "content": [ { "type": "text", "text": "When something" } ] }
                                        ] }
                                    ] }
                                  ]
                                },
                                "created": "2025-02-01",
                                "updated": "2025-02-02",
                                "duedate": null
                              }
                            }
                          ]
                        }
                        """));

        List<JiraTicket> tickets = jiraService.getMyTickets();

        assertThat(tickets).hasSize(1);
        String full = tickets.get(0).getFullDescription();
        assertThat(full).contains("Implementation notes\nFirst paragraph.\nSecond paragraph.");
        assertThat(full).contains("- Given something");
        assertThat(full).contains("- When something");
        // Sanity check the exact bug being fixed: paragraphs must not be jammed together.
        assertThat(full).doesNotContain("notesFirst").doesNotContain("paragraph.Second");
    }

    // ── Description truncation ────────────────────────────────────────

    @Test
    void getMyTickets_longDescription_truncatesAt500Chars() {
        String longText = "A".repeat(600);
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "issues": [
                            {
                              "key": "DEMO-3",
                              "fields": {
                                "summary": "Long description",
                                "status": { "name": "Open", "statusCategory": { "colorName": "grey" } },
                                "priority": { "name": "Low" },
                                "project": { "name": "Demo" },
                                "issuetype": { "name": "Task" },
                                "assignee": { "displayName": "Dave" },
                                "reporter": { "displayName": "Eve" },
                                "description": "%s",
                                "created": "2025-01-01",
                                "updated": "2025-01-02",
                                "duedate": null
                              }
                            }
                          ]
                        }
                        """.formatted(longText)));

        List<JiraTicket> tickets = jiraService.getMyTickets();

        assertThat(tickets).hasSize(1);
        JiraTicket t = tickets.get(0);
        String description = t.getDescription();
        assertThat(description).hasSize(503); // 500 chars + "..."
        assertThat(description).endsWith("...");

        // fullDescription (used by Ticket Docs markdown generation) must NOT be truncated.
        assertThat(t.getFullDescription()).hasSize(600).isEqualTo(longText);
    }

    // ── Date formatting ───────────────────────────────────────────────

    @Test
    void getMyTickets_isoDate_extractsFirst10Chars() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "issues": [
                            {
                              "key": "DEMO-4",
                              "fields": {
                                "summary": "Date test",
                                "status": { "name": "Done", "statusCategory": { "colorName": "green" } },
                                "priority": { "name": "Low" },
                                "project": { "name": "Demo" },
                                "issuetype": { "name": "Task" },
                                "assignee": { "displayName": "Frank" },
                                "reporter": { "displayName": "Grace" },
                                "description": null,
                                "created": "2025-06-15T08:00:00.000+0700",
                                "updated": "2025-06-20T12:00:00.000+0700",
                                "duedate": "2025-07-01"
                              }
                            }
                          ]
                        }
                        """));

        List<JiraTicket> tickets = jiraService.getMyTickets();

        assertThat(tickets).hasSize(1);
        JiraTicket t = tickets.get(0);
        assertThat(t.getCreated()).isEqualTo("2025-06-15");
        assertThat(t.getUpdated()).isEqualTo("2025-06-20");
        assertThat(t.getDueDate()).isEqualTo("2025-07-01");
    }

    // ── Multiple tickets ──────────────────────────────────────────────

    @Test
    void getMyTickets_multipleIssues_parsesAll() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "issues": [
                            {
                              "key": "DEMO-10",
                              "fields": {
                                "summary": "Ticket one",
                                "status": { "name": "Open", "statusCategory": { "colorName": "grey" } },
                                "priority": { "name": "High" },
                                "project": { "name": "Demo" },
                                "issuetype": { "name": "Bug" },
                                "assignee": { "displayName": "Alice" },
                                "reporter": { "displayName": "Bob" },
                                "description": null,
                                "created": "2025-01-01",
                                "updated": "2025-01-02",
                                "duedate": null
                              }
                            },
                            {
                              "key": "DEMO-11",
                              "fields": {
                                "summary": "Ticket two",
                                "status": { "name": "Done", "statusCategory": { "colorName": "green" } },
                                "priority": { "name": "Low" },
                                "project": { "name": "Demo" },
                                "issuetype": { "name": "Story" },
                                "assignee": { "displayName": "Carol" },
                                "reporter": { "displayName": "Dave" },
                                "description": null,
                                "created": "2025-02-01",
                                "updated": "2025-02-02",
                                "duedate": "2025-03-01"
                              }
                            }
                          ]
                        }
                        """));

        List<JiraTicket> tickets = jiraService.getMyTickets();

        assertThat(tickets).hasSize(2);
        assertThat(tickets).extracting(JiraTicket::getKey)
                .containsExactly("DEMO-10", "DEMO-11");
    }

    // ── Confluence space / page-tree primitives ───────────────────────

    @Test
    void getConfluenceSpace_found_returnsInfo() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        { "id": "98305", "key": "DEMO", "name": "Demo Project" }
                        """));

        ConfluenceSpaceInfo space = jiraService.getConfluenceSpace(testConfig(), "DEMO");

        assertThat(space).isNotNull();
        assertThat(space.key()).isEqualTo("DEMO");
        assertThat(space.id()).isEqualTo("98305");
        assertThat(space.name()).isEqualTo("Demo Project");
    }

    @Test
    void getConfluenceSpace_404_returnsNull() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"message\":\"No space found\"}"));

        ConfluenceSpaceInfo space = jiraService.getConfluenceSpace(testConfig(), "NOPE");

        assertThat(space).isNull();
    }

    @Test
    void listSpacePages_parsesVersionAndParentFromAncestors() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "results": [
                            {
                              "id": "100",
                              "title": "Root Page",
                              "version": { "number": 3, "when": "2025-06-01T10:00:00.000Z" },
                              "ancestors": []
                            },
                            {
                              "id": "101",
                              "title": "Child Page",
                              "version": { "number": 1, "when": "2025-06-02T11:00:00.000Z" },
                              "ancestors": [ { "id": "100" } ]
                            }
                          ],
                          "_links": {}
                        }
                        """));

        List<ConfluencePageMeta> pages = jiraService.listSpacePages(testConfig(), "DEMO");

        assertThat(pages).hasSize(2);
        ConfluencePageMeta root = pages.get(0);
        assertThat(root.id()).isEqualTo("100");
        assertThat(root.parentPageId()).isNull();
        assertThat(root.version()).isEqualTo(3);

        ConfluencePageMeta child = pages.get(1);
        assertThat(child.id()).isEqualTo("101");
        assertThat(child.parentPageId()).isEqualTo("100");
    }

    @Test
    void listSpacePages_followsPaginationLink() throws InterruptedException {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "results": [ { "id": "1", "title": "Page 1", "version": { "number": 1, "when": "2025-06-01T10:00:00.000Z" }, "ancestors": [] } ],
                          "_links": { "next": "/wiki/rest/api/content?spaceKey=DEMO&start=100" }
                        }
                        """));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "results": [ { "id": "2", "title": "Page 2", "version": { "number": 1, "when": "2025-06-01T10:00:00.000Z" }, "ancestors": [] } ],
                          "_links": {}
                        }
                        """));

        List<ConfluencePageMeta> pages = jiraService.listSpacePages(testConfig(), "DEMO");

        assertThat(pages).extracting(ConfluencePageMeta::id).containsExactly("1", "2");
        mockServer.takeRequest(); // first page
        RecordedRequest second = mockServer.takeRequest();
        assertThat(second.getPath()).contains("start=100");
    }

    @Test
    void getConfluencePageDetail_found_returnsDetailWithSpaceKey() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "id": "110264391",
                          "title": "DEMO 2.0",
                          "space": { "key": "DOCS" },
                          "version": { "number": 5, "when": "2025-06-01T10:00:00.000Z" },
                          "ancestors": [ { "id": "100" }, { "id": "200" } ]
                        }
                        """));

        ConfluencePageDetail detail = jiraService.getConfluencePageDetail(testConfig(), "110264391");

        assertThat(detail).isNotNull();
        assertThat(detail.id()).isEqualTo("110264391");
        assertThat(detail.spaceKey()).isEqualTo("DOCS");
        assertThat(detail.title()).isEqualTo("DEMO 2.0");
        assertThat(detail.version()).isEqualTo(5);
        assertThat(detail.parentPageId()).isEqualTo("200");
    }

    @Test
    void getConfluencePageDetail_404_returnsNull() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"message\":\"No page found\"}"));

        ConfluencePageDetail detail = jiraService.getConfluencePageDetail(testConfig(), "999999");

        assertThat(detail).isNull();
    }
}
