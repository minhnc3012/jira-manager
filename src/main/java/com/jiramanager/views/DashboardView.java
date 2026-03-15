package com.jiramanager.views;

import com.jiramanager.model.JiraTicket;
import com.jiramanager.service.JiraService;
import com.jiramanager.service.SessionUserService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

import java.util.List;

@Route(value = "dashboard", layout = MainLayout.class)
@PageTitle("Dashboard – Jira Manager")
@RolesAllowed("USER")
public class DashboardView extends VerticalLayout {

    public DashboardView(JiraService jiraService, SessionUserService sessionUserService) {
        setSizeFull();
        setSpacing(false);
        setPadding(false);
        getStyle().set("background", "#f4f5f7").set("overflow-y", "auto");

        add(buildContent(jiraService, sessionUserService));
    }

    // ── Page content ──────────────────────────────────────────────────

    private Component buildContent(JiraService jiraService, SessionUserService sessionUserService) {
        VerticalLayout content = new VerticalLayout();
        content.setWidthFull();
        content.setSpacing(false);
        content.getStyle()
                .set("max-width", "1100px")
                .set("margin", "0 auto")
                .set("padding", "36px 28px");

        // Greeting header
        String name = sessionUserService.getCurrentUserName();
        H2 greeting = new H2("Welcome back, " + name + "!");
        greeting.getStyle().set("margin", "0 0 4px 0").set("color", "#172b4d");

        Paragraph subtitle = new Paragraph("Here's an overview of your Jira activity.");
        subtitle.getStyle().set("margin", "0 0 32px 0").set("color", "#6b778c").set("font-size", "14px");

        // Load tickets for stats — show setup banner if Jira is not yet configured
        List<JiraTicket> tickets;
        boolean jiraNotConfigured = false;
        try {
            tickets = jiraService.getMyTickets();
        } catch (com.jiramanager.service.JiraService.JiraNotConfiguredException ex) {
            tickets = List.of();
            jiraNotConfigured = true;
        } catch (Exception ex) {
            tickets = List.of();
        }

        // Stats cards
        HorizontalLayout statsRow = buildStatsRow(tickets);

        // Quick access section
        H3 quickAccessTitle = new H3("Quick Access");
        quickAccessTitle.getStyle()
                .set("color", "#172b4d")
                .set("margin", "36px 0 16px 0")
                .set("font-size", "16px");

        HorizontalLayout quickLinks = buildQuickLinks();

        content.add(greeting, subtitle);

        if (jiraNotConfigured) {
            Div banner = new Div();
            banner.getStyle()
                    .set("background", "#fff7d6")
                    .set("border", "1px solid #f0c040")
                    .set("border-radius", "6px")
                    .set("padding", "12px 16px")
                    .set("margin-bottom", "8px")
                    .set("font-size", "14px")
                    .set("color", "#5e4a00");
            banner.add(new Span("Jira is not configured yet. "));
            com.vaadin.flow.component.html.Anchor link = new com.vaadin.flow.component.html.Anchor("settings", "Go to Settings →");
            link.getStyle().set("color", "#0052cc").set("font-weight", "600");
            banner.add(link);
            content.add(banner);
        }

        content.add(statsRow, quickAccessTitle, quickLinks);
        return content;
    }

    // ── Stat cards ────────────────────────────────────────────────────

    private HorizontalLayout buildStatsRow(List<JiraTicket> tickets) {
        long total = tickets.size();

        long inProgress = tickets.stream()
                .filter(t -> t.getStatus() != null
                        && (t.getStatus().toLowerCase().contains("progress")
                        || t.getStatus().toLowerCase().contains("doing")))
                .count();

        long done = tickets.stream()
                .filter(t -> t.getStatus() != null
                        && (t.getStatus().toLowerCase().contains("done")
                        || t.getStatus().toLowerCase().contains("resolved")
                        || t.getStatus().toLowerCase().contains("closed")))
                .count();

        long blocked = tickets.stream()
                .filter(t -> t.getStatus() != null && t.getStatus().toLowerCase().contains("block"))
                .count();

        HorizontalLayout row = new HorizontalLayout(
                statCard("Total Assigned", total, "#0052cc", "#deebff", VaadinIcon.TICKET),
                statCard("In Progress",    inProgress, "#0747a6", "#e8f0fe", VaadinIcon.PLAY),
                statCard("Completed",      done,       "#006644", "#e3fcef", VaadinIcon.CHECK_CIRCLE),
                statCard("Blocked",        blocked,    "#bf2600", "#ffebe6", VaadinIcon.STOP)
        );
        row.setWidthFull();
        row.getStyle().set("gap", "16px").set("flex-wrap", "wrap");
        return row;
    }

    private Div statCard(String label, long count, String accentColor, String bgColor, VaadinIcon icon) {
        Div card = new Div();
        card.getStyle()
                .set("background", "white")
                .set("border-radius", "8px")
                .set("padding", "24px 20px")
                .set("flex", "1")
                .set("min-width", "180px")
                .set("box-shadow", "0 1px 3px rgba(9,30,66,.12)")
                .set("border-left", "4px solid " + accentColor);

        Icon ic = icon.create();
        ic.getStyle().set("color", accentColor).set("width", "20px").set("height", "20px");

        Span countSpan = new Span(String.valueOf(count));
        countSpan.getStyle()
                .set("display", "block")
                .set("font-size", "40px")
                .set("font-weight", "700")
                .set("color", accentColor)
                .set("line-height", "1.1")
                .set("margin-top", "10px");

        Span labelSpan = new Span(label);
        labelSpan.getStyle()
                .set("display", "block")
                .set("font-size", "13px")
                .set("color", "#6b778c")
                .set("margin-top", "4px");

        card.add(ic, countSpan, labelSpan);
        return card;
    }

    // ── Quick access cards ────────────────────────────────────────────

    private HorizontalLayout buildQuickLinks() {
        HorizontalLayout row = new HorizontalLayout(
                quickLinkCard("My Tickets",
                        "View and filter your assigned Jira tickets",
                        VaadinIcon.TICKET, "tickets", false),
                quickLinkCard("Worklog",
                        "View your daily worklog timeline",
                        VaadinIcon.CLOCK, "worklog", false),
                quickLinkCard("Reports",
                        "Sprint and progress reports — coming soon",
                        VaadinIcon.CHART, null, true)
        );
        row.setWidthFull();
        row.getStyle().set("gap", "16px").set("flex-wrap", "wrap");
        return row;
    }

    private Div quickLinkCard(String title, String description,
                              VaadinIcon icon, String route, boolean disabled) {
        Div card = new Div();
        card.getStyle()
                .set("background", disabled ? "#f8f9fb" : "white")
                .set("border-radius", "8px")
                .set("padding", "20px 24px")
                .set("flex", "1")
                .set("min-width", "200px")
                .set("box-shadow", "0 1px 3px rgba(9,30,66,.1)")
                .set("border", "1px solid #dfe1e6")
                .set("cursor", disabled ? "not-allowed" : "pointer")
                .set("transition", "box-shadow 0.15s, border-color 0.15s");

        Icon ic = icon.create();
        ic.getStyle()
                .set("color", disabled ? "#c1c7d0" : "#0052cc")
                .set("width", "24px").set("height", "24px");

        H4 cardTitle = new H4(title);
        cardTitle.getStyle()
                .set("margin", "12px 0 4px 0")
                .set("color", disabled ? "#a5adba" : "#172b4d")
                .set("font-size", "15px");

        Paragraph desc = new Paragraph(description);
        desc.getStyle()
                .set("margin", "0")
                .set("color", disabled ? "#a5adba" : "#6b778c")
                .set("font-size", "13px")
                .set("line-height", "1.5");

        if (!disabled && route != null) {
            card.addClickListener(e -> UI.getCurrent().navigate(route));
        }

        card.add(ic, cardTitle, desc);
        return card;
    }
}
