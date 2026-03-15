package com.jiramanager.service;

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

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        String baseUrl = mockServer.url("/").toString().replaceAll("/$", "");
        WebClient webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic dGVzdDp0ZXN0") // test:test
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();

        jiraService = new JiraService(webClient, baseUrl);
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
                              "key": "KERB-123",
                              "fields": {
                                "summary": "Fix login bug",
                                "status": { "name": "In Progress", "statusCategory": { "colorName": "blue" } },
                                "priority": { "name": "High" },
                                "project": { "name": "Kerb" },
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
        assertThat(t.getKey()).isEqualTo("KERB-123");
        assertThat(t.getSummary()).isEqualTo("Fix login bug");
        assertThat(t.getStatus()).isEqualTo("In Progress");
        assertThat(t.getStatusColor()).isEqualTo("blue");
        assertThat(t.getPriority()).isEqualTo("High");
        assertThat(t.getProject()).isEqualTo("Kerb");
        assertThat(t.getIssueType()).isEqualTo("Bug");
        assertThat(t.getAssignee()).isEqualTo("Minh Nguyen");
        assertThat(t.getReporter()).isEqualTo("Alice");
        assertThat(t.getDescription()).isEqualTo("This is a plain text description.");
        assertThat(t.getCreated()).isEqualTo("2025-01-15");
        assertThat(t.getUpdated()).isEqualTo("2025-03-10");
        assertThat(t.getDueDate()).isEqualTo("2025-04-01");
        assertThat(t.getUrl()).endsWith("/browse/KERB-123");
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
                              "key": "KERB-1",
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
                              "key": "KERB-2",
                              "fields": {
                                "summary": "ADF ticket",
                                "status": { "name": "Open", "statusCategory": { "colorName": "grey" } },
                                "priority": { "name": "Medium" },
                                "project": { "name": "Kerb" },
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
                              "key": "KERB-3",
                              "fields": {
                                "summary": "Long description",
                                "status": { "name": "Open", "statusCategory": { "colorName": "grey" } },
                                "priority": { "name": "Low" },
                                "project": { "name": "Kerb" },
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
        String description = tickets.get(0).getDescription();
        assertThat(description).hasSize(503); // 500 chars + "..."
        assertThat(description).endsWith("...");
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
                              "key": "KERB-4",
                              "fields": {
                                "summary": "Date test",
                                "status": { "name": "Done", "statusCategory": { "colorName": "green" } },
                                "priority": { "name": "Low" },
                                "project": { "name": "Kerb" },
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
                              "key": "KERB-10",
                              "fields": {
                                "summary": "Ticket one",
                                "status": { "name": "Open", "statusCategory": { "colorName": "grey" } },
                                "priority": { "name": "High" },
                                "project": { "name": "Kerb" },
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
                              "key": "KERB-11",
                              "fields": {
                                "summary": "Ticket two",
                                "status": { "name": "Done", "statusCategory": { "colorName": "green" } },
                                "priority": { "name": "Low" },
                                "project": { "name": "Kerb" },
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
                .containsExactly("KERB-10", "KERB-11");
    }
}
