package com.jiramanager.views;

import com.jiramanager.model.JiraTicket;
import com.jiramanager.service.JiraService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

import java.util.List;
import java.util.Set;

@Route(value = "tickets", layout = MainLayout.class)
@PageTitle("My Tickets – Jira Manager")
@RolesAllowed("USER")
public class MainView extends VerticalLayout implements BeforeEnterObserver {

    private final JiraService jiraService;

    private List<JiraTicket> allTickets;
    private ListDataProvider<JiraTicket> dataProvider;
    private JiraTicket selectedTicket;

    // Grid
    private final Grid<JiraTicket> grid = new Grid<>(JiraTicket.class, false);

    // Filters — project stays single-select; status/priority/sprint become multi-select
    private final TextField searchField            = new TextField();
    private final MultiSelectComboBox<String> statusFilter   = new MultiSelectComboBox<>("Status");
    private final ComboBox<String>            projectFilter  = new ComboBox<>("Project");
    private final MultiSelectComboBox<String> priorityFilter = new MultiSelectComboBox<>("Priority");
    private final MultiSelectComboBox<String> sprintFilter   = new MultiSelectComboBox<>("Sprint");

    private final Span ticketCount = new Span();
    private final ProgressBar loadingBar = new ProgressBar();

    // Detail panel (right side of split layout)
    private final VerticalLayout detailPanel = new VerticalLayout();

    public MainView(JiraService jiraService) {
        this.jiraService = jiraService;

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("background", "#f4f5f7");

        add(buildFilters(), buildLoadingBar(), buildSplitLayout());
        // loadTickets() is called in beforeEnter() after Jira config check
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!jiraService.isConfigured()) {
            event.forwardTo("settings?jira_required=true");
            return;
        }
        loadTickets();
    }

    // ── Filter bar ────────────────────────────────────────────────────

    private HorizontalLayout buildFilters() {
        searchField.setPlaceholder("🔍 Search by key or summary...");
        searchField.setValueChangeMode(ValueChangeMode.LAZY);
        searchField.setValueChangeTimeout(300);
        searchField.addValueChangeListener(e -> applyFilters());
        searchField.setWidth("280px");

        // Multi-select: status
        statusFilter.setPlaceholder("All statuses");
        statusFilter.setClearButtonVisible(true);
        statusFilter.setWidth("180px");
        statusFilter.addValueChangeListener(e -> applyFilters());

        // Single-select: project (usually only a few values)
        projectFilter.setPlaceholder("All projects");
        projectFilter.setClearButtonVisible(true);
        projectFilter.setWidth("190px");
        projectFilter.addValueChangeListener(e -> applyFilters());

        // Multi-select: priority
        priorityFilter.setPlaceholder("All priorities");
        priorityFilter.setClearButtonVisible(true);
        priorityFilter.setWidth("170px");
        priorityFilter.addValueChangeListener(e -> applyFilters());

        // Multi-select: sprint
        sprintFilter.setPlaceholder("All sprints");
        sprintFilter.setClearButtonVisible(true);
        sprintFilter.setWidth("220px");
        sprintFilter.addValueChangeListener(e -> applyFilters());

        Button refreshBtn = new Button("Refresh", VaadinIcon.REFRESH.create());
        refreshBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        refreshBtn.addClickListener(e -> loadTickets());

        ticketCount.getStyle()
                .set("color", "#6b778c")
                .set("font-size", "13px")
                .set("align-self", "center")
                .set("white-space", "nowrap");

        HorizontalLayout bar = new HorizontalLayout(
                searchField, statusFilter, projectFilter, priorityFilter, sprintFilter,
                refreshBtn, ticketCount);
        bar.setWidthFull();
        bar.setAlignItems(Alignment.END);
        bar.getStyle()
                .set("background", "white")
                .set("padding", "12px 20px")
                .set("border-bottom", "1px solid #dfe1e6")
                .set("flex-wrap", "wrap")
                .set("gap", "8px");
        return bar;
    }

    private ProgressBar buildLoadingBar() {
        loadingBar.setIndeterminate(true);
        loadingBar.setVisible(false);
        loadingBar.setWidthFull();
        loadingBar.getStyle().set("height", "3px").set("border-radius", "0");
        return loadingBar;
    }

    // ── Split layout (master + detail) ────────────────────────────────

    private SplitLayout buildSplitLayout() {
        SplitLayout split = new SplitLayout(buildMasterPanel(), buildDetailPanel());
        split.setSizeFull();
        split.setSplitterPosition(62); // 62% grid, 38% detail
        split.getStyle().set("flex", "1");
        return split;
    }

    private VerticalLayout buildMasterPanel() {
        buildGrid();

        VerticalLayout master = new VerticalLayout(grid);
        master.setSizeFull();
        master.setPadding(false);
        master.setSpacing(false);
        return master;
    }

    // ── Grid ──────────────────────────────────────────────────────────

    private void buildGrid() {
        grid.setSizeFull();
        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        grid.getStyle().set("background", "white");

        grid.addComponentColumn(t -> {
            Anchor link = new Anchor(t.getUrl(), t.getKey());
            link.setTarget("_blank");
            link.getStyle()
                    .set("font-weight", "600")
                    .set("color", "#0052cc")
                    .set("text-decoration", "none");
            return link;
        }).setHeader("Key").setWidth("110px").setFlexGrow(0);

        grid.addColumn(JiraTicket::getSummary)
                .setHeader("Summary").setFlexGrow(3).setTooltipGenerator(JiraTicket::getSummary);

        grid.addComponentColumn(t -> statusBadge(t.getStatus()))
                .setHeader("Status").setWidth("140px").setFlexGrow(0);

        grid.addComponentColumn(t -> priorityBadge(t.getPriority()))
                .setHeader("Priority").setWidth("120px").setFlexGrow(0);

        grid.addColumn(JiraTicket::getProject).setHeader("Project").setWidth("130px").setFlexGrow(0);
        grid.addColumn(JiraTicket::getUpdated).setHeader("Updated").setWidth("110px").setFlexGrow(0);
        grid.addColumn(JiraTicket::getDueDate).setHeader("Due").setWidth("100px").setFlexGrow(0);

        // Single-select mode keeps the row highlighted when a ticket is chosen
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);
        grid.addSelectionListener(e -> {
            e.getFirstSelectedItem().ifPresentOrElse(
                    this::showTicketDetail,
                    this::showDetailPlaceholder
            );
        });
    }

    // ── Detail panel ──────────────────────────────────────────────────

    private VerticalLayout buildDetailPanel() {
        detailPanel.setSizeFull();
        detailPanel.setPadding(true);
        detailPanel.setSpacing(false);
        detailPanel.getStyle()
                .set("background", "white")
                .set("border-left", "1px solid #dfe1e6")
                .set("overflow-y", "auto");

        showDetailPlaceholder();
        return detailPanel;
    }

    private void showDetailPlaceholder() {
        detailPanel.removeAll();
        detailPanel.setAlignItems(Alignment.CENTER);
        detailPanel.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);

        Span icon = new Span("🎫");
        icon.getStyle().set("font-size", "40px").set("margin-bottom", "12px");

        Span msg = new Span("Select a ticket to view its details");
        msg.getStyle()
                .set("color", "#6b778c")
                .set("font-size", "14px")
                .set("font-style", "italic");

        detailPanel.add(icon, msg);
    }

    private void showTicketDetail(JiraTicket t) {
        selectedTicket = t;
        detailPanel.removeAll();
        detailPanel.setAlignItems(Alignment.STRETCH);
        detailPanel.setJustifyContentMode(FlexComponent.JustifyContentMode.START);

        // ── Header ────────────────────────────────────────────────────
        Anchor keyLink = new Anchor(t.getUrl(), t.getKey());
        keyLink.setTarget("_blank");
        keyLink.getStyle()
                .set("font-weight", "700")
                .set("font-size", "13px")
                .set("color", "#0052cc")
                .set("text-decoration", "none")
                .set("letter-spacing", "0.5px");

        Paragraph summary = new Paragraph(t.getSummary());
        summary.getStyle()
                .set("margin", "4px 0 16px 0")
                .set("font-size", "15px")
                .set("font-weight", "600")
                .set("color", "#172b4d")
                .set("line-height", "1.4");

        HorizontalLayout badges = new HorizontalLayout(
                statusBadge(t.getStatus()), priorityBadge(t.getPriority()));
        badges.getStyle().set("gap", "6px").set("margin-bottom", "20px");

        Hr divider = new Hr();
        divider.getStyle().set("margin", "0 0 16px 0").set("border-color", "#dfe1e6");

        // ── Fields ────────────────────────────────────────────────────
        VerticalLayout fields = new VerticalLayout();
        fields.setPadding(false);
        fields.setSpacing(false);
        fields.getStyle().set("gap", "4px");

        fields.add(
                detailRow("Project",  t.getProject()),
                detailRow("Type",     t.getIssueType()),
                detailRow("Assignee", t.getAssignee()),
                detailRow("Reporter", t.getReporter()),
                detailRow("Sprint",   t.getSprint()),
                detailRow("Created",  t.getCreated()),
                detailRow("Updated",  t.getUpdated())
        );
        if (t.getDueDate() != null && !t.getDueDate().isBlank()) {
            fields.add(detailRow("Due date", t.getDueDate()));
        }

        // ── Actions ───────────────────────────────────────────────────
        Button openInJira = new Button("Open in Jira ↗",
                e -> UI.getCurrent().getPage().open(t.getUrl(), "_blank"));
        openInJira.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        openInJira.getStyle().set("margin-top", "24px");

        detailPanel.add(keyLink, summary, badges, divider, fields, openInJira);
    }

    // ── Data loading ──────────────────────────────────────────────────

    private void loadTickets() {
        loadingBar.setVisible(true);
        selectedTicket = null;
        grid.deselectAll();
        showDetailPlaceholder();
        try {
            allTickets = jiraService.getMyTickets();
            dataProvider = new ListDataProvider<>(allTickets);
            grid.setDataProvider(dataProvider);
            populateFilterOptions();
            ticketCount.setText(allTickets.size() + " tickets");
            Notification.show("Loaded " + allTickets.size() + " tickets", 2500, Notification.Position.BOTTOM_END);
        } catch (Exception e) {
            Notification n = Notification.show("Jira connection error: " + e.getMessage(),
                    5000, Notification.Position.BOTTOM_CENTER);
            n.addThemeVariants(NotificationVariant.LUMO_ERROR);
        } finally {
            loadingBar.setVisible(false);
        }
    }

    private void populateFilterOptions() {
        if (allTickets == null) return;
        statusFilter.setItems(allTickets.stream().map(JiraTicket::getStatus)
                .filter(s -> s != null && !s.isBlank()).distinct().sorted().toList());
        projectFilter.setItems(allTickets.stream().map(JiraTicket::getProject)
                .filter(p -> p != null && !p.isBlank()).distinct().sorted().toList());
        priorityFilter.setItems(allTickets.stream().map(JiraTicket::getPriority)
                .filter(p -> p != null && !p.isBlank()).distinct().toList());
        sprintFilter.setItems(allTickets.stream().map(JiraTicket::getSprint)
                .filter(s -> s != null && !s.isBlank()).distinct().sorted().toList());
    }

    private void applyFilters() {
        if (dataProvider == null) return;
        String search      = searchField.getValue().toLowerCase();
        Set<String> statuses   = statusFilter.getValue();
        String project     = projectFilter.getValue();
        Set<String> priorities = priorityFilter.getValue();
        Set<String> sprints    = sprintFilter.getValue();

        dataProvider.setFilter(t ->
                (search.isBlank()
                        || t.getKey().toLowerCase().contains(search)
                        || t.getSummary().toLowerCase().contains(search))
                && (statuses.isEmpty()   || statuses.contains(t.getStatus()))
                && (project == null      || t.getProject().equals(project))
                && (priorities.isEmpty() || priorities.contains(t.getPriority()))
                && (sprints.isEmpty()    || sprints.contains(t.getSprint()))
        );
        ticketCount.setText(
                dataProvider.size(new com.vaadin.flow.data.provider.Query<>()) + " tickets");
    }

    // ── Badge helpers ─────────────────────────────────────────────────

    private Span statusBadge(String status) {
        Span b = new Span(status);
        b.getStyle()
                .set("padding", "2px 10px")
                .set("border-radius", "12px")
                .set("font-size", "12px")
                .set("font-weight", "600");
        String s = status != null ? status.toLowerCase() : "";
        if (s.contains("progress") || s.contains("doing"))
            b.getStyle().set("background", "#deebff").set("color", "#0052cc");
        else if (s.contains("done") || s.contains("closed") || s.contains("resolved"))
            b.getStyle().set("background", "#e3fcef").set("color", "#006644");
        else if (s.contains("review") || s.contains("testing"))
            b.getStyle().set("background", "#fffae6").set("color", "#172b4d");
        else if (s.contains("block"))
            b.getStyle().set("background", "#ffebe6").set("color", "#bf2600");
        else
            b.getStyle().set("background", "#f4f5f7").set("color", "#6b778c");
        return b;
    }

    private Span priorityBadge(String priority) {
        Span b = new Span(priority);
        b.getStyle()
                .set("padding", "2px 10px")
                .set("border-radius", "12px")
                .set("font-size", "12px")
                .set("font-weight", "500");
        String p = priority != null ? priority.toLowerCase() : "";
        if (p.contains("highest") || p.contains("blocker"))
            b.getStyle().set("background", "#ffebe6").set("color", "#bf2600");
        else if (p.contains("high") || p.contains("critical"))
            b.getStyle().set("background", "#fff1e6").set("color", "#c56600");
        else if (p.contains("medium") || p.contains("major"))
            b.getStyle().set("background", "#deebff").set("color", "#0747a6");
        else if (p.contains("low") || p.contains("minor"))
            b.getStyle().set("background", "#e3fcef").set("color", "#006644");
        else
            b.getStyle().set("background", "#f4f5f7").set("color", "#6b778c");
        return b;
    }

    private HorizontalLayout detailRow(String label, String value) {
        Span l = new Span(label);
        l.getStyle()
                .set("color", "#6b778c")
                .set("font-size", "12px")
                .set("min-width", "80px")
                .set("font-weight", "500")
                .set("text-transform", "uppercase")
                .set("letter-spacing", "0.5px");
        Span v = new Span(value != null && !value.isBlank() ? value : "—");
        v.getStyle().set("color", "#172b4d").set("font-size", "13px");
        HorizontalLayout row = new HorizontalLayout(l, v);
        row.getStyle().set("padding", "5px 0").set("gap", "12px").set("align-items", "baseline");
        return row;
    }
}
