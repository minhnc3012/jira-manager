package com.jiramanager.views;

import com.jiramanager.model.ConfluencePage;
import com.jiramanager.model.JiraTicket;
import com.jiramanager.model.WorklogEntry;
import com.jiramanager.service.JiraService;
import com.vaadin.flow.component.UI;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContextHolder;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

import com.jiramanager.service.WorklogOverlapDetector;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Route(value = "worklog", layout = MainLayout.class)
@PageTitle("Worklog – Jira Manager")
@RolesAllowed("USER")
public class WorklogView extends VerticalLayout implements BeforeEnterObserver {

    // ── Chart layout constants ─────────────────────────────────────────
    // Adjust HOUR_PX to scale the whole timeline proportionally.
    // At 90 px/h → 5 min = 7.5 px  |  full day = 24 × 90 = 2 160 px
    private static final int    HOUR_PX        = 90;
    private static final int    LABEL_PX       = 220;   // label column width
    private static final int    ROW_H          = 52;    // px – tall enough for 2-line labels
    private static final int    BAR_H          = 26;    // worklog bar height
    private static final double MIN_PX         = HOUR_PX / 60.0; // 1.5 px per minute
    // Auto-resize: chartPanel height = FILTER_BAR_H + CHART_OVERHEAD + clamp(rows,2,10) * ROW_H
    private static final int    FILTER_BAR_H   = 60;   // filter bar approx height px
    private static final int    CHART_OVERHEAD = 75;   // header(36) + dividers(3) + totalRow(36)
    private static final int    MIN_ROWS       = 2;
    private static final int    MAX_ROWS       = 10;

    // CSS repeating-gradient: strong line every 1 h + faint line every 5 min
    private static final String GRID_BG =
            "repeating-linear-gradient(90deg,rgba(0,0,0,0.09) 0,rgba(0,0,0,0.09) 1px," +
            "transparent 1px,transparent " + HOUR_PX + "px)," +
            "repeating-linear-gradient(90deg,rgba(0,0,0,0.03) 0,rgba(0,0,0,0.03) 1px," +
            "transparent 1px,transparent " + (HOUR_PX / 12.0) + "px)";

    private static final ZoneId           ZONE     = ZoneId.systemDefault();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // ── Mutable state ──────────────────────────────────────────────────
    private final JiraService jiraService;
    private Div              selectedBar    = null;
    private List<WorklogEntry> currentEntries = List.of();
    private Set<String>      overlappingIds = Set.of();
    private List<JiraTicket> myTickets      = List.of();

    // ── Persistent UI components ───────────────────────────────────────
    private final DatePicker     datePicker        = new DatePicker("Date");
    private final ProgressBar    loadingBar        = new ProgressBar();
    private final Span           totalLabel        = new Span();
    private final VerticalLayout chartPanel        = new VerticalLayout();
    private final VerticalLayout chartArea         = new VerticalLayout();
    private final VerticalLayout detailPanel       = new VerticalLayout();
    private final ProgressBar    loadingBarTickets = new ProgressBar();
    private final VerticalLayout ticketsToLogPanel = new VerticalLayout();
    private final VerticalLayout ticketsListArea   = new VerticalLayout();
    private final Span           ticketsToLogCount = new Span();

    public WorklogView(JiraService jiraService) {
        this.jiraService = jiraService;

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("background", "#f4f5f7");

        chartPanel.add(buildFilterBar(), buildLoadingBar(), buildChartArea());
        chartPanel.setSizeFull();
        chartPanel.setPadding(false);
        chartPanel.setSpacing(false);

        // Left side: vertical SplitLayout — gantt chart (top) | tickets to log (bottom)
        SplitLayout leftSplit = new SplitLayout(chartPanel, buildTicketsToLogPanel());
        leftSplit.setOrientation(SplitLayout.Orientation.VERTICAL);
        leftSplit.setSizeFull();
        // Default top height ≈ 5 rows; set via JS because setSplitterPosition uses % of unknown viewport
        int defaultTopPx = FILTER_BAR_H + CHART_OVERHEAD + 4 * ROW_H;
        leftSplit.getElement().executeJs(
            "setTimeout(function(){" +
            "  var h=$0.offsetHeight;" +
            "  if(h>0){" +
            "    var pct=Math.min(85,Math.max(15,($1/h)*100));" +
            "    $0.style.setProperty('--vaadin-split-layout-splitter-position',pct+'%');" +
            "  }" +
            "},100);",
            leftSplit.getElement(), defaultTopPx);

        // Outer horizontal split: left content | shared detail panel (right)
        initDetailPanel();
        SplitLayout outerSplit = new SplitLayout(leftSplit, detailPanel);
        outerSplit.setSizeFull();
        outerSplit.setSplitterPosition(65);
        outerSplit.getStyle().set("flex", "1");
        add(outerSplit);

        datePicker.setValue(LocalDate.now());
        // loadWorklogs() is called in beforeEnter() after Jira config check
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!jiraService.isConfigured()) {
            event.forwardTo("settings?jira_required=true");
            return;
        }
        loadMyTickets();
        loadWorklogs(LocalDate.now());
    }

    // ── Filter bar ────────────────────────────────────────────────────

    private HorizontalLayout buildFilterBar() {
        datePicker.addValueChangeListener(e -> {
            if (e.getValue() != null) loadWorklogs(e.getValue());
        });

        Button refreshBtn = new Button("Refresh", VaadinIcon.REFRESH.create());
        refreshBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        refreshBtn.addClickListener(e -> {
            if (datePicker.getValue() != null) loadWorklogs(datePicker.getValue());
        });

        totalLabel.getStyle()
                .set("font-size", "14px").set("font-weight", "700")
                .set("color", "#172b4d").set("align-self", "center");

        Span spacer = new Span();
        spacer.getStyle().set("flex", "1");

        HorizontalLayout bar = new HorizontalLayout(datePicker, refreshBtn, spacer, totalLabel);
        bar.setWidthFull();
        bar.setAlignItems(Alignment.END);
        bar.getStyle()
                .set("background", "white").set("padding", "12px 20px")
                .set("border-bottom", "1px solid #dfe1e6").set("flex-shrink", "0");
        return bar;
    }

    private ProgressBar buildLoadingBar() {
        loadingBar.setIndeterminate(true);
        loadingBar.setVisible(false);
        loadingBar.setWidthFull();
        loadingBar.getStyle()
                .set("height", "3px").set("border-radius", "0").set("flex-shrink", "0");
        return loadingBar;
    }

    private VerticalLayout buildChartArea() {
        chartArea.setSizeFull();
        chartArea.setPadding(false);
        chartArea.setSpacing(false);
        chartArea.getStyle().set("background", "white").set("overflow", "hidden");
        showChartPlaceholder("Select a date to view worklog entries.");
        return chartArea;
    }

    // ── Gantt chart (pure HTML / CSS) ─────────────────────────────────

    private void renderGanttChart(List<WorklogEntry> entries, LocalDate date) {
        selectedBar    = null;
        currentEntries = entries;
        overlappingIds = WorklogOverlapDetector.findOverlappingIds(entries);
        chartArea.removeAll();

        if (entries.isEmpty()) {
            showChartPlaceholder("No worklogs found for " + date + ".");
            return;
        }

        chartArea.setAlignItems(Alignment.STRETCH);
        chartArea.setJustifyContentMode(FlexComponent.JustifyContentMode.START);

        // Overlap warning banner — shown above the chart whenever overlaps exist
        if (!overlappingIds.isEmpty()) {
            chartArea.add(buildOverlapBanner(overlappingIds.size()));
        }

        // Scrollable outer wrapper
        Div wrapper = new Div();
        wrapper.getStyle()
                .set("overflow-x", "auto").set("overflow-y", "auto")
                .set("width", "100%").set("flex", "1");

        // Inner fixed-width column
        Div table = new Div();
        table.getStyle()
                .set("display", "flex").set("flex-direction", "column")
                .set("min-width", (LABEL_PX + 24 * HOUR_PX) + "px");

        // Unique tickets ordered by their first start time (list is already sorted)
        List<String> ticketKeys = new ArrayList<>();
        for (WorklogEntry e : entries) {
            if (!ticketKeys.contains(e.getTicketKey())) ticketKeys.add(e.getTicketKey());
        }

        table.add(buildHeaderRow(date));
        table.add(headerDivider());

        for (int i = 0; i < ticketKeys.size(); i++) {
            String key = ticketKeys.get(i);
            List<WorklogEntry> forKey = entries.stream()
                    .filter(e -> e.getTicketKey().equals(key)).toList();
            table.add(buildTicketRow(key, forKey, i, date));
        }

        table.add(footerDivider());
        table.add(buildTotalRow(entries));

        wrapper.add(table);
        chartArea.add(wrapper);

        // ── Auto-scroll: jump to first worklog minus 30 min ──────────
        // Falls back to 8:30 AM when no entries exist.
        ZonedDateTime firstStart = entries.get(0).getStartTime().atZone(ZONE);
        int scrollMinute = Math.max(0, firstStart.getHour() * 60 + firstStart.getMinute() - 30);
        double scrollPx = scrollMinute * MIN_PX;

        // Use setTimeout so the DOM is fully painted before scrolling
        wrapper.getElement().executeJs(
                "setTimeout(function(){ $0.scrollLeft = $1; }, 80);",
                wrapper.getElement(), scrollPx);
    }

    // ── Header row:  [Ticket] | 0   1   2  … 12  … 23 ───────────────

    private Div buildHeaderRow(LocalDate date) {
        Div row = new Div();
        row.getStyle()
                .set("display", "flex").set("align-items", "stretch")
                .set("background", "#f4f5f7")
                .set("position", "sticky").set("top", "0").set("z-index", "10");

        // Label column header — sticky so it stays visible on horizontal scroll
        Div labelCell = new Div(new Span("Ticket"));
        labelCell.getStyle()
                .set("width", LABEL_PX + "px").set("min-width", LABEL_PX + "px")
                .set("height", "36px").set("padding", "0 12px").set("display", "flex")
                .set("align-items", "center").set("flex-shrink", "0")
                .set("border-right", "1px solid #dfe1e6")
                .set("font-size", "11px").set("font-weight", "600")
                .set("color", "#6b778c").set("text-transform", "uppercase")
                .set("letter-spacing", "0.5px")
                // frozen column
                .set("position", "sticky").set("left", "0").set("z-index", "11")
                .set("background", "#f4f5f7");

        // Timeline header with hour labels + optional "now" indicator
        Div timelineHeader = new Div();
        timelineHeader.getStyle()
                .set("position", "relative")
                .set("min-width", (24 * HOUR_PX) + "px")
                .set("height", "36px").set("flex", "1");

        for (int h = 0; h < 24; h++) {
            Div label = new Div(new Span(String.valueOf(h)));
            label.getStyle()
                    .set("position", "absolute").set("left", (h * HOUR_PX) + "px")
                    .set("top", "0").set("height", "100%")
                    .set("display", "flex").set("align-items", "center")
                    .set("padding-left", "4px")
                    .set("font-size", "11px").set("color", "#6b778c").set("font-weight", "500")
                    .set("min-width", HOUR_PX + "px")
                    .set("border-left", h == 0 ? "none" : "1px solid #dfe1e6");
            timelineHeader.add(label);
        }

        // "Now" dot marker in header
        if (date.equals(LocalDate.now())) {
            ZonedDateTime now = ZonedDateTime.now(ZONE);
            double x = (now.getHour() * 60 + now.getMinute()) * MIN_PX;
            Div dot = new Div();
            dot.getStyle()
                    .set("position", "absolute").set("left", x + "px")
                    .set("top", "50%").set("transform", "translate(-50%, -50%)")
                    .set("width", "8px").set("height", "8px")
                    .set("border-radius", "50%").set("background", "#de350b")
                    .set("z-index", "6").set("pointer-events", "none");
            timelineHeader.add(dot);
        }

        row.add(labelCell, timelineHeader);
        return row;
    }

    // ── Ticket row: [KEY\nSummary] | ── bar ── ───────────────────────

    private Div buildTicketRow(String key, List<WorklogEntry> entries, int rowIdx, LocalDate date) {
        Div row = new Div();
        row.getStyle()
                .set("display", "flex").set("align-items", "stretch")
                .set("height", ROW_H + "px")
                .set("background", rowIdx % 2 == 0 ? "white" : "#fafbfc")
                .set("border-bottom", "1px solid #f0f1f3");

        WorklogEntry first = entries.get(0);

        // ── Label cell: [type icon + key] / summary ────────────────────
        Span typeIcon = issueTypeIcon(first.getIssueType(), "14px");

        Span keySpan = new Span(key);
        keySpan.getStyle()
                .set("font-size", "12px").set("font-weight", "700")
                .set("color", "#0052cc").set("white-space", "nowrap");

        Div keyRow = new Div(typeIcon, keySpan);
        keyRow.getStyle()
                .set("display", "flex").set("align-items", "center").set("gap", "5px");

        Span sumSpan = new Span(first.getSummary());
        sumSpan.getStyle()
                .set("font-size", "11px").set("color", "#6b778c")
                .set("white-space", "nowrap").set("overflow", "hidden")
                .set("text-overflow", "ellipsis").set("display", "block")
                .set("max-width", (LABEL_PX - 24) + "px");

        Div labelContent = new Div(keyRow, sumSpan);
        labelContent.getStyle()
                .set("display", "flex").set("flex-direction", "column")
                .set("justify-content", "center").set("gap", "2px")
                .set("overflow", "hidden");

        // Frozen label column — background must be opaque so scrolled content doesn't bleed through
        String rowBg = rowIdx % 2 == 0 ? "white" : "#fafbfc";
        Div labelCell = new Div(labelContent);
        labelCell.getStyle()
                .set("width", LABEL_PX + "px").set("min-width", LABEL_PX + "px")
                .set("padding", "0 12px").set("display", "flex")
                .set("align-items", "center").set("flex-shrink", "0")
                .set("border-right", "1px solid #dfe1e6")
                .set("cursor", "pointer").set("overflow", "hidden")
                // frozen column
                .set("position", "sticky").set("left", "0").set("z-index", "2")
                .set("background", rowBg);
        labelCell.getElement().setAttribute("title", key + ": " + first.getSummary());
        labelCell.addClickListener(e -> showDetail(first));

        // ── Timeline cell: grid background + bars + optional now line ──
        Div timeline = new Div();
        timeline.getStyle()
                .set("flex", "1").set("position", "relative")
                .set("min-width", (24 * HOUR_PX) + "px")
                .set("background-image", GRID_BG);

        // Red "now" indicator line (today only)
        if (date.equals(LocalDate.now())) {
            ZonedDateTime now = ZonedDateTime.now(ZONE);
            double x = (now.getHour() * 60 + now.getMinute()) * MIN_PX;
            Div nowLine = new Div();
            nowLine.getStyle()
                    .set("position", "absolute").set("left", x + "px")
                    .set("top", "0").set("bottom", "0")
                    .set("width", "2px").set("background", "#de350b")
                    .set("z-index", "5").set("pointer-events", "none")
                    .set("opacity", "0.7");
            timeline.add(nowLine);
        }

        for (WorklogEntry e : entries) {
            timeline.add(buildBar(e));
        }

        row.add(labelCell, timeline);
        return row;
    }

    // ── Worklog bar ───────────────────────────────────────────────────

    private Div buildBar(WorklogEntry e) {
        ZonedDateTime zStart = e.getStartTime().atZone(ZONE);
        int startMinute = zStart.getHour() * 60 + zStart.getMinute();
        double left  = startMinute * MIN_PX;
        double width = Math.max(e.getMinutesSpent() * MIN_PX, 4.0);
        int   barTop = (ROW_H - BAR_H) / 2;

        boolean isOverlap = e.getWorklogId() != null && overlappingIds.contains(e.getWorklogId());

        Div bar = new Div();
        // Base background: solid color for normal bars, diagonal-stripe overlay for overlapping ones
        String background = isOverlap
                ? "repeating-linear-gradient(-45deg, rgba(255,140,0,0.55) 0, rgba(255,140,0,0.55) 5px, transparent 5px, transparent 10px), "
                  + barColor(e.getStatus())
                : barColor(e.getStatus());

        bar.getStyle()
                .set("position", "absolute")
                .set("left", left + "px").set("top", barTop + "px")
                .set("width", width + "px").set("height", BAR_H + "px")
                .set("background", background)
                .set("border-radius", "5px").set("cursor", "pointer")
                .set("display", "flex").set("align-items", "center")
                .set("padding-left", "6px").set("overflow", "hidden")
                .set("box-sizing", "border-box")
                .set("transition", "box-shadow 0.12s, filter 0.12s");

        if (isOverlap) {
            bar.getStyle()
                    .set("outline", "2px solid #ff8b00")
                    .set("outline-offset", "-2px");
        }

        // Warning icon for overlapping bars
        if (isOverlap) {
            Span warnIcon = new Span("⚠");
            warnIcon.getStyle()
                    .set("font-size", "11px").set("margin-right", "3px")
                    .set("pointer-events", "none");
            bar.add(warnIcon);
        }

        // Duration label inside bar (truncated if bar is narrow)
        Span durationLabel = new Span(e.getTimeSpentFormatted());
        durationLabel.getStyle()
                .set("font-size", "11px").set("color", "white")
                .set("font-weight", "600").set("white-space", "nowrap")
                .set("pointer-events", "none");
        bar.add(durationLabel);

        // Native tooltip — overlap entries get extra warning text
        String tooltip = e.getTicketKey() + "  " +
                localTime(e.getStartTime()) + " → " + localTime(e.getEndTime()) +
                "  (" + e.getTimeSpentFormatted() + ")";
        if (isOverlap) {
            tooltip = "⚠ TIME OVERLAP  " + tooltip;
        }
        bar.getElement().setAttribute("title", tooltip);

        bar.addClickListener(ev -> {
            if (selectedBar != null) {
                selectedBar.getStyle().remove("box-shadow").remove("filter");
            }
            bar.getStyle()
                    .set("box-shadow", "0 0 0 2px #172b4d, 0 2px 8px rgba(0,0,0,0.25)")
                    .set("filter", "brightness(1.1)");
            selectedBar = bar;
            showDetail(e);
        });

        return bar;
    }

    // ── Total row ─────────────────────────────────────────────────────

    private Div buildTotalRow(List<WorklogEntry> entries) {
        int totalMinutes = entries.stream().mapToInt(WorklogEntry::getMinutesSpent).sum();

        Div row = new Div();
        row.getStyle()
                .set("display", "flex").set("align-items", "center")
                .set("height", "36px").set("background", "#f4f5f7");

        // "Total: 8h" lives entirely inside the sticky label column
        // so it's always visible regardless of horizontal scroll position.
        Span totalTxt = new Span("Total: ");
        totalTxt.getStyle().set("font-size", "12px").set("font-weight", "700")
                .set("color", "#172b4d").set("text-transform", "uppercase")
                .set("letter-spacing", "0.5px");

        Span totalValue = new Span(JiraService.formatDuration(totalMinutes));
        totalValue.getStyle()
                .set("font-size", "14px").set("font-weight", "700").set("color", "#0052cc");

        Div labelCell = new Div(totalTxt, totalValue);
        labelCell.getStyle()
                .set("width", LABEL_PX + "px").set("min-width", LABEL_PX + "px")
                .set("flex-shrink", "0").set("padding", "0 12px")
                .set("display", "flex").set("align-items", "center").set("gap", "4px")
                .set("border-right", "1px solid #dfe1e6")
                // frozen column
                .set("position", "sticky").set("left", "0").set("z-index", "2")
                .set("background", "#f4f5f7");

        // Right area stays empty — it just carries the grid lines for visual continuity
        Div right = new Div();
        right.getStyle()
                .set("flex", "1").set("min-width", (24 * HOUR_PX) + "px")
                .set("background-image", GRID_BG);

        row.add(labelCell, right);
        return row;
    }

    // ── Detail panel ──────────────────────────────────────────────────

    private void initDetailPanel() {
        detailPanel.setSizeFull();
        detailPanel.setPadding(true);
        detailPanel.setSpacing(false);
        detailPanel.getStyle()
                .set("background", "white")
                .set("border-left", "1px solid #dfe1e6")
                .set("overflow-y", "auto");
        showDetailPlaceholder();
    }

    private void showDetailPlaceholder() {
        detailPanel.removeAll();
        detailPanel.setAlignItems(Alignment.CENTER);
        detailPanel.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
        Span icon = new Span("⏱");
        icon.getStyle().set("font-size", "40px").set("margin-bottom", "12px");
        Span msg = new Span("Click a worklog bar or ticket to view details");
        msg.getStyle().set("color", "#6b778c").set("font-size", "14px").set("font-style", "italic");
        detailPanel.add(icon, msg);
    }

    private void showTicketDetail(JiraTicket t) {
        detailPanel.removeAll();
        detailPanel.setSizeFull();
        detailPanel.setPadding(false);
        detailPanel.setSpacing(false);

        // ── Top panel: ticket info (scrollable) ───────────────────────
        VerticalLayout ticketInfoPanel = new VerticalLayout();
        ticketInfoPanel.setPadding(true);
        ticketInfoPanel.setSpacing(false);
        ticketInfoPanel.getStyle().set("overflow-y", "auto").set("background", "white");

        Span typeIcon = issueTypeIcon(t.getIssueType(), "18px");
        Anchor keyLink = new Anchor(t.getUrl(), t.getKey());
        keyLink.setTarget("_blank");
        keyLink.getStyle()
                .set("font-weight", "700").set("font-size", "13px")
                .set("color", "#0052cc").set("text-decoration", "none");

        Button openInJira = new Button("Open in Jira ↗",
                ev -> UI.getCurrent().getPage().open(t.getUrl(), "_blank"));
        openInJira.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);

        Span headerSpacer = new Span();
        headerSpacer.getStyle().set("flex", "1");

        HorizontalLayout keyRow = new HorizontalLayout(typeIcon, keyLink, headerSpacer, openInJira);
        keyRow.setAlignItems(Alignment.CENTER);
        keyRow.setWidthFull();
        keyRow.setSpacing(false);
        keyRow.getStyle().set("gap", "6px");

        Paragraph summary = new Paragraph(t.getSummary());
        summary.getStyle()
                .set("margin", "4px 0 14px 0").set("font-size", "15px")
                .set("font-weight", "600").set("color", "#172b4d").set("line-height", "1.4");

        HorizontalLayout badges = new HorizontalLayout(
                statusBadge(t.getStatus()), priorityBadge(t.getPriority()));
        badges.getStyle().set("gap", "6px").set("margin-bottom", "16px");

        H5 ticketSection = sectionTitle("Ticket Info");
        VerticalLayout ticketFields = fieldGroup(
                detailRow("Project",  t.getProject()),
                detailRowWithIcon("Type", issueTypeIcon(t.getIssueType(), "13px"), t.getIssueType()),
                detailRow("Assignee", t.getAssignee()),
                detailRow("Reporter", t.getReporter()),
                detailRow("Sprint",   t.getSprint()),
                detailRow("Due date", t.getDueDate())
        );

        H5 ttSection = sectionTitle("Time Tracking");
        VerticalLayout ttFields = new VerticalLayout();
        ttFields.setPadding(false);
        ttFields.setSpacing(false);
        ttFields.getStyle().set("gap", "4px");
        ttFields.add(
                detailRow("Original estimate", t.getOriginalEstimate().isBlank() ? "—" : t.getOriginalEstimate()),
                detailRow("Time spent",        t.getTimeSpent().isBlank()        ? "—" : t.getTimeSpent()),
                detailRow("Remaining",         t.getRemainingEstimate().isBlank() ? "—" : t.getRemainingEstimate())
        );
        long original = t.getOriginalEstimateSeconds();
        long spent    = t.getTimeSpentSeconds();
        if (original > 0) {
            double pct  = Math.min(1.0, (double) spent / original);
            boolean over = spent > original;
            ProgressBar pb = new ProgressBar(0, 1, pct);
            pb.setWidthFull();
            pb.getStyle().set("height", "8px").set("border-radius", "4px").set("margin-top", "8px");
            if (over) pb.getStyle().set("--lumo-primary-color", "#de350b");
            String pctText = String.format("%.0f%% of estimate used", pct * 100);
            if (over) pctText += " — over estimate!";
            Span pctLabel = new Span(pctText);
            pctLabel.getStyle().set("font-size", "11px").set("color", over ? "#bf2600" : "#6b778c");
            ttFields.add(pb, pctLabel);
        }

        ticketInfoPanel.add(keyRow, summary, badges,
                divider(), ticketSection, ticketFields,
                divider(), ttSection, ttFields);

        // ── Bottom panel: Confluence References (scrollable) ──────────
        VerticalLayout confluencePanel = new VerticalLayout();
        confluencePanel.setPadding(true);
        confluencePanel.setSpacing(false);
        confluencePanel.getStyle()
                .set("overflow-y", "auto").set("background", "#f8f9fa")
                .set("border-top", "1px solid #dfe1e6");

        H5 confluenceTitle = sectionTitle("Confluence References");
        VerticalLayout confluenceArea = new VerticalLayout();
        confluenceArea.setPadding(false);
        confluenceArea.setSpacing(false);
        confluenceArea.getStyle().set("gap", "8px");

        Span loadingMsg = new Span("Loading...");
        loadingMsg.getStyle().set("color", "#6b778c").set("font-size", "12px").set("font-style", "italic");
        confluenceArea.add(loadingMsg);
        confluencePanel.add(confluenceTitle, confluenceArea);

        // ── SplitLayout between the two panels ────────────────────────
        SplitLayout detailSplit = new SplitLayout(ticketInfoPanel, confluencePanel);
        detailSplit.setOrientation(SplitLayout.Orientation.VERTICAL);
        detailSplit.setSizeFull();
        detailSplit.setSplitterPosition(55);
        detailPanel.add(detailSplit);

        // ── Async Confluence fetch ─────────────────────────────────────
        UI ui = UI.getCurrent();
        String ticketKey = t.getKey();
        List<String> descPageIds = t.getConfluencePageIds();

        Runnable task = DelegatingSecurityContextRunnable.create(() -> {
            List<String> remoteIds = jiraService.getConfluenceRemoteLinks(ticketKey);
            LinkedHashSet<String> allIds = new LinkedHashSet<>(remoteIds);
            allIds.addAll(descPageIds);

            List<ConfluencePage> pages = allIds.stream()
                    .map(jiraService::getConfluencePage)
                    .filter(java.util.Objects::nonNull)
                    .toList();

            ui.access(() -> {
                confluenceTitle.setText("Confluence References (" + pages.size() + ")");
                confluenceArea.removeAll();
                if (pages.isEmpty()) {
                    Span noLinks = new Span("No Confluence pages linked to this ticket.");
                    noLinks.getStyle().set("color", "#6b778c").set("font-size", "12px").set("font-style", "italic");
                    confluenceArea.add(noLinks);
                } else {
                    pages.forEach(p -> confluenceArea.add(buildConfluenceCard(p)));
                }
            });
        }, SecurityContextHolder.getContext());
        Thread.ofVirtual().start(task);
    }

    private void showDetail(WorklogEntry e) {
        detailPanel.removeAll();
        detailPanel.setAlignItems(Alignment.STRETCH);
        detailPanel.setJustifyContentMode(FlexComponent.JustifyContentMode.START);

        // ── Overlap warning (shown first, if applicable) ───────────────
        boolean isOverlap = e.getWorklogId() != null && overlappingIds.contains(e.getWorklogId());
        if (isOverlap) {
            List<WorklogEntry> partners = WorklogOverlapDetector.findOverlapPartners(e, currentEntries);
            detailPanel.add(buildDetailOverlapWarning(e, partners));
        }

        // ── Header ────────────────────────────────────────────────────
        Span detailTypeIcon = issueTypeIcon(e.getIssueType(), "18px");

        Anchor keyLink = new Anchor(e.getTicketUrl(), e.getTicketKey());
        keyLink.setTarget("_blank");
        keyLink.getStyle()
                .set("font-weight", "700").set("font-size", "13px")
                .set("color", "#0052cc").set("text-decoration", "none");

        HorizontalLayout keyRow = new HorizontalLayout(detailTypeIcon, keyLink);
        keyRow.setAlignItems(Alignment.CENTER);
        keyRow.setSpacing(false);
        keyRow.getStyle().set("gap", "6px");

        Paragraph summary = new Paragraph(e.getSummary());
        summary.getStyle()
                .set("margin", "4px 0 14px 0").set("font-size", "15px")
                .set("font-weight", "600").set("color", "#172b4d").set("line-height", "1.4");

        HorizontalLayout badges = new HorizontalLayout(
                statusBadge(e.getStatus()), priorityBadge(e.getPriority()));
        badges.getStyle().set("gap", "6px").set("margin-bottom", "16px");

        // ── Ticket info ────────────────────────────────────────────────
        Hr div1 = divider();
        H5 ticketSection = sectionTitle("Ticket Info");
        VerticalLayout ticketFields = fieldGroup(
                detailRow("Project",  e.getProject()),
                detailRowWithIcon("Type", issueTypeIcon(e.getIssueType(), "13px"), e.getIssueType()),
                detailRow("Assignee", e.getAssignee()),
                detailRow("Reporter", e.getReporter()),
                detailRow("Sprint",   e.getSprint())
        );

        // ── This worklog entry ─────────────────────────────────────────
        Hr div2 = divider();
        H5 logSection = sectionTitle("This Worklog Entry");
        String timeRange = localTime(e.getStartTime()) + " → " + localTime(e.getEndTime());
        VerticalLayout logFields = fieldGroup(
                detailRow("Date",      localDate(e.getStartTime())),
                detailRow("Time",      timeRange),
                detailRow("Duration",  e.getTimeSpentFormatted()),
                detailRow("Logged by", e.getWorklogAuthor())
        );

        // ── Time tracking (Jira-style) ─────────────────────────────────
        Hr div3 = divider();
        H5 ttSection = sectionTitle("Time Tracking");
        VerticalLayout ttFields = buildTimeTrackingSection(e);

        // ── Open in Jira ───────────────────────────────────────────────
        Button openInJira = new Button("Open in Jira ↗",
                ev -> UI.getCurrent().getPage().open(e.getTicketUrl(), "_blank"));
        openInJira.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        openInJira.getStyle().set("margin-top", "20px");

        detailPanel.add(
                keyRow, summary, badges,
                div1, ticketSection, ticketFields,
                div2, logSection, logFields,
                div3, ttSection, ttFields,
                openInJira);
    }

    // ── Overlap warning UI helpers ────────────────────────────────────

    /**
     * Orange banner shown at the top of the chart when overlapping worklogs are detected.
     */
    private Div buildOverlapBanner(int overlapCount) {
        Span icon = new Span("⚠");
        icon.getStyle().set("font-size", "16px").set("margin-right", "8px");

        Span msg = new Span(overlapCount + " worklog entr" + (overlapCount == 1 ? "y has" : "ies have")
                + " overlapping times. Overlapping bars are marked with ⚠ and a striped pattern."
                + " Click a bar to see which entries conflict, then correct the times in Jira.");
        msg.getStyle().set("font-size", "13px").set("color", "#7a4100");

        Div banner = new Div(icon, msg);
        banner.getStyle()
                .set("display", "flex").set("align-items", "center")
                .set("background", "#fff8e1")
                .set("border-left", "4px solid #ff8b00")
                .set("padding", "10px 16px")
                .set("flex-shrink", "0");
        return banner;
    }

    /**
     * Inline warning box shown at the top of the detail panel when the selected worklog
     * overlaps with at least one other entry.
     */
    private Div buildDetailOverlapWarning(WorklogEntry entry, List<WorklogEntry> partners) {
        String ownRange = localTime(entry.getStartTime()) + " → " + localTime(entry.getEndTime());

        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);
        content.setSpacing(false);
        content.getStyle().set("gap", "4px");

        Span title = new Span("⚠  Time Overlap Detected");
        title.getStyle()
                .set("font-size", "13px").set("font-weight", "700").set("color", "#7a4100");

        Span subtitle = new Span("This entry (" + ownRange + ") overlaps with:");
        subtitle.getStyle().set("font-size", "12px").set("color", "#7a4100");

        content.add(title, subtitle);

        for (WorklogEntry p : partners) {
            String range = localTime(p.getStartTime()) + " → " + localTime(p.getEndTime());
            Span partnerLine = new Span("• " + p.getTicketKey() + "  " + range
                    + "  (" + p.getTimeSpentFormatted() + ")");
            partnerLine.getStyle()
                    .set("font-size", "12px").set("color", "#7a4100")
                    .set("font-weight", "600").set("padding-left", "8px");
            content.add(partnerLine);
        }

        Span hint = new Span("Please open the conflicting entries in Jira and adjust the start time or duration.");
        hint.getStyle()
                .set("font-size", "11px").set("color", "#7a4100")
                .set("font-style", "italic").set("margin-top", "4px");
        content.add(hint);

        Div box = new Div(content);
        box.getStyle()
                .set("background", "#fff8e1")
                .set("border", "1px solid #ff8b00")
                .set("border-left", "4px solid #ff8b00")
                .set("border-radius", "4px")
                .set("padding", "10px 12px")
                .set("margin-bottom", "12px");
        return box;
    }

    /** Time-tracking section: text rows + Jira-style progress bar. */
    private VerticalLayout buildTimeTrackingSection(WorklogEntry e) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(false);
        section.getStyle().set("gap", "4px");

        section.add(
                detailRow("Original estimate",  e.getOriginalEstimate()),
                detailRow("Time spent (total)", e.getTotalTimeSpent()),
                detailRow("Remaining",          e.getRemainingEstimate())
        );

        long original = e.getOriginalEstimateSeconds();
        long spent    = e.getTotalTimeSpentSeconds();
        if (original > 0) {
            double pct  = Math.min(1.0, (double) spent / original);
            boolean over = spent > original;

            ProgressBar pb = new ProgressBar(0, 1, pct);
            pb.setWidthFull();
            pb.getStyle().set("height", "8px").set("border-radius", "4px").set("margin-top", "8px");
            if (over) pb.getStyle().set("--lumo-primary-color", "#de350b");

            String pctText = String.format("%.0f%% of estimate used", pct * 100);
            if (over) pctText += " — over estimate!";
            Span pctLabel = new Span(pctText);
            pctLabel.getStyle()
                    .set("font-size", "11px")
                    .set("color", over ? "#bf2600" : "#6b778c");

            section.add(pb, pctLabel);
        }
        return section;
    }

    // ── Data loading ──────────────────────────────────────────────────

    private void loadWorklogs(LocalDate date) {
        loadingBar.setVisible(true);
        selectedBar = null;
        showDetailPlaceholder();
        try {
            List<WorklogEntry> entries = jiraService.getWorklogsForDate(date);
            int totalMinutes = entries.stream().mapToInt(WorklogEntry::getMinutesSpent).sum();
            totalLabel.setText("Total: " + JiraService.formatDuration(totalMinutes));
            renderGanttChart(entries, date);
            renderTicketsToLog(date);
            if (!entries.isEmpty()) {
                Notification.show("Loaded " + entries.size() + " worklog entries",
                        2500, Notification.Position.BOTTOM_END);
            }
        } catch (Exception ex) {
            Notification n = Notification.show("Failed to load worklogs: " + ex.getMessage(),
                    5000, Notification.Position.BOTTOM_CENTER);
            n.addThemeVariants(NotificationVariant.LUMO_ERROR);
            showChartPlaceholder("Could not load worklogs. Check connection.");
        } finally {
            loadingBar.setVisible(false);
        }
    }

    private void loadMyTickets() {
        loadingBarTickets.setVisible(true);
        try {
            myTickets = jiraService.getMyTickets();
        } catch (Exception ex) {
            myTickets = List.of();
        } finally {
            loadingBarTickets.setVisible(false);
        }
    }

    // ── Confluence card ───────────────────────────────────────────────

    private Div buildConfluenceCard(ConfluencePage page) {
        // Header: icon + title link
        Span icon = new Span("📄");
        icon.getStyle().set("font-size", "14px").set("flex-shrink", "0");

        Anchor titleLink = new Anchor(page.url(), page.title());
        titleLink.setTarget("_blank");
        titleLink.getStyle()
                .set("font-size", "13px").set("font-weight", "600")
                .set("color", "#0052cc").set("text-decoration", "none")
                .set("flex", "1").set("overflow", "hidden")
                .set("text-overflow", "ellipsis").set("white-space", "nowrap");

        HorizontalLayout header = new HorizontalLayout(icon, titleLink);
        header.setAlignItems(Alignment.CENTER);
        header.setWidthFull();
        header.setSpacing(false);
        header.getStyle().set("gap", "6px");

        // Rendered HTML content — confluence body.view is already formatted
        com.vaadin.flow.component.Html htmlContent =
                new com.vaadin.flow.component.Html(
                        "<div style='" +
                        "font-size:12px;line-height:1.7;color:#172b4d;" +
                        "font-family:inherit;" +
                        "'>" + page.content() + "</div>");

        Div contentWrapper = new Div(htmlContent);
        contentWrapper.getStyle()
                .set("margin-top", "8px").set("padding-top", "8px")
                .set("border-top", "1px solid #ebecf0");
        // Inline styles for common HTML elements inside the rendered content
        contentWrapper.getElement().executeJs(
                "var el = $0;" +
                "el.querySelectorAll('h1,h2,h3,h4,h5,h6').forEach(h => {" +
                "  h.style.fontSize='13px';h.style.fontWeight='700';" +
                "  h.style.color='#172b4d';h.style.margin='10px 0 4px 0';" +
                "});" +
                "el.querySelectorAll('p').forEach(p => p.style.margin='4px 0');" +
                "el.querySelectorAll('ul,ol').forEach(l => {l.style.paddingLeft='18px';l.style.margin='4px 0';});" +
                "el.querySelectorAll('li').forEach(i => i.style.margin='2px 0');" +
                "el.querySelectorAll('code,pre').forEach(c => {" +
                "  c.style.background='#f4f5f7';c.style.borderRadius='3px';" +
                "  c.style.padding='1px 4px';c.style.fontSize='11px';" +
                "});" +
                "el.querySelectorAll('a').forEach(a => {" +
                "  a.style.color='#0052cc';a.target='_blank';" +
                "});" +
                "el.querySelectorAll('table').forEach(t => {" +
                "  t.style.borderCollapse='collapse';t.style.width='100%';t.style.fontSize='11px';" +
                "});" +
                "el.querySelectorAll('td,th').forEach(c => {" +
                "  c.style.border='1px solid #dfe1e6';c.style.padding='4px 8px';" +
                "});",
                contentWrapper.getElement());

        Div card = new Div(header, contentWrapper);
        card.getStyle()
                .set("border", "1px solid #dfe1e6").set("border-left", "3px solid #0052cc")
                .set("border-radius", "4px").set("padding", "10px 12px")
                .set("background", "#f8f9fa");

        return card;
    }

    // ── Tickets to Log panel ──────────────────────────────────────────

    private VerticalLayout buildTicketsToLogPanel() {
        // Header bar
        Span titleSpan = new Span("Tickets to Log");
        titleSpan.getStyle()
                .set("font-size", "12px").set("font-weight", "700").set("color", "#172b4d")
                .set("text-transform", "uppercase").set("letter-spacing", "0.5px");

        ticketsToLogCount.getStyle()
                .set("font-size", "11px").set("font-weight", "600")
                .set("background", "#dfe1e6").set("color", "#6b778c")
                .set("padding", "1px 7px").set("border-radius", "10px");

        HorizontalLayout headerBar = new HorizontalLayout(titleSpan, ticketsToLogCount);
        headerBar.setAlignItems(Alignment.CENTER);
        headerBar.getStyle()
                .set("padding", "7px 16px")
                .set("border-bottom", "1px solid #dfe1e6")
                .set("background", "#f4f5f7")
                .set("flex-shrink", "0")
                .set("gap", "8px");

        loadingBarTickets.setIndeterminate(true);
        loadingBarTickets.setVisible(false);
        loadingBarTickets.setWidthFull();
        loadingBarTickets.getStyle()
                .set("height", "3px").set("border-radius", "0").set("flex-shrink", "0");

        ticketsListArea.setPadding(false);
        ticketsListArea.setSpacing(false);
        ticketsListArea.getStyle()
                .set("flex", "1").set("overflow-y", "auto")
                .set("padding", "8px 12px").set("gap", "5px");

        ticketsToLogPanel.setPadding(false);
        ticketsToLogPanel.setSpacing(false);
        ticketsToLogPanel.setSizeFull();
        ticketsToLogPanel.getStyle().set("background", "white");
        ticketsToLogPanel.add(headerBar, loadingBarTickets, ticketsListArea);
        return ticketsToLogPanel;
    }

    private void renderTicketsToLog(LocalDate date) {
        ticketsListArea.removeAll();

        if (myTickets.isEmpty()) {
            Span msg = new Span("No tickets found.");
            msg.getStyle().set("color", "#6b778c").set("font-style", "italic").set("font-size", "13px")
               .set("padding", "4px 0");
            ticketsToLogCount.setText("");
            ticketsListArea.add(msg);
            return;
        }

        Set<String> loggedKeys = currentEntries.stream()
                .map(WorklogEntry::getTicketKey)
                .collect(Collectors.toSet());

        // Show tickets not logged today OR still have remaining estimate
        // Exclude tickets with "Passed QA" status
        List<JiraTicket> toLog = myTickets.stream()
                .filter(t -> !Set.of("passed qa", "deployed (prod)", "won't do")
                        .contains(t.getStatus().toLowerCase()))
                .filter(t -> !loggedKeys.contains(t.getKey()) || t.getRemainingEstimateSeconds() > 0)
                .sorted(Comparator.comparingLong(JiraTicket::getRemainingEstimateSeconds).reversed())
                .collect(Collectors.toList());

        ticketsToLogCount.setText(String.valueOf(toLog.size()));

        if (toLog.isEmpty()) {
            ticketsToLogCount.getStyle().set("background", "#e3fcef").set("color", "#006644");
            Span msg = new Span("All tickets logged for " + date + ".");
            msg.getStyle().set("color", "#006644").set("font-size", "13px").set("font-weight", "600")
               .set("padding", "4px 0");
            ticketsListArea.add(msg);
            return;
        }

        ticketsToLogCount.getStyle().set("background", "#dfe1e6").set("color", "#6b778c");
        for (JiraTicket ticket : toLog) {
            ticketsListArea.add(buildTicketToLogRow(ticket, loggedKeys.contains(ticket.getKey())));
        }
    }

    private Div buildTicketToLogRow(JiraTicket ticket, boolean loggedToday) {
        Div row = new Div();
        row.getStyle()
                .set("display", "flex").set("align-items", "center")
                .set("gap", "8px").set("padding", "5px 8px")
                .set("border-radius", "4px")
                .set("border", "1px solid #dfe1e6").set("background", "white")
                .set("margin-bottom", "4px").set("cursor", "pointer")
                .set("transition", "background 0.12s");
        row.getElement().addEventListener("mouseover",
                e -> row.getStyle().set("background", "#f4f5f7"));
        row.getElement().addEventListener("mouseout",
                e -> row.getStyle().set("background", "white"));
        row.addClickListener(e -> showTicketDetail(ticket));

        row.add(issueTypeIcon(ticket.getIssueType(), "16px"));

        // Key (plain span, not anchor — click on row handles navigation via detail panel)
        Span keyLink = new Span(ticket.getKey());
        keyLink.getStyle()
                .set("font-weight", "700").set("font-size", "12px")
                .set("color", "#0052cc").set("text-decoration", "none")
                .set("white-space", "nowrap").set("flex-shrink", "0");

        Span sumSpan = new Span(ticket.getSummary());
        sumSpan.getStyle()
                .set("font-size", "12px").set("color", "#172b4d")
                .set("overflow", "hidden").set("text-overflow", "ellipsis")
                .set("white-space", "nowrap").set("flex", "1");

        Div keyAndSummary = new Div(keyLink, sumSpan);
        keyAndSummary.getStyle()
                .set("display", "flex").set("align-items", "center")
                .set("gap", "6px").set("flex", "1").set("overflow", "hidden");
        row.add(keyAndSummary);

        // Status badge
        row.add(statusBadge(ticket.getStatus()));

        // Remaining estimate
        if (ticket.getRemainingEstimateSeconds() > 0) {
            Span rem = new Span("rem: " + ticket.getRemainingEstimate());
            rem.getStyle()
                    .set("font-size", "11px").set("color", "#6b778c").set("white-space", "nowrap")
                    .set("background", "#f4f5f7").set("padding", "2px 6px").set("border-radius", "3px");
            row.add(rem);
        }

        // "Logged today" tag when ticket is already in today's worklog but still has remaining time
        if (loggedToday) {
            Span loggedBadge = new Span("logged today");
            loggedBadge.getStyle()
                    .set("font-size", "11px").set("color", "#006644").set("white-space", "nowrap")
                    .set("background", "#e3fcef").set("padding", "2px 6px").set("border-radius", "3px");
            row.add(loggedBadge);
        }

        return row;
    }

    // ── Bar & badge color helpers ─────────────────────────────────────

    private String barColor(String status) {
        String s = status != null ? status.toLowerCase() : "";
        if (s.contains("progress") || s.contains("doing"))              return "#0052cc";
        if (s.contains("done") || s.contains("resolved") || s.contains("closed")) return "#006644";
        if (s.contains("review") || s.contains("testing"))              return "#ff8b00";
        if (s.contains("block"))                                         return "#bf2600";
        return "#6554c0";
    }

    private Span statusBadge(String status) {
        Span b = new Span(status);
        b.getStyle().set("padding", "2px 10px").set("border-radius", "12px")
                .set("font-size", "12px").set("font-weight", "600");
        String s = status != null ? status.toLowerCase() : "";
        if (s.contains("progress") || s.contains("doing"))
            b.getStyle().set("background", "#deebff").set("color", "#0052cc");
        else if (s.contains("done") || s.contains("closed") || s.contains("resolved"))
            b.getStyle().set("background", "#e3fcef").set("color", "#006644");
        else if (s.contains("review") || s.contains("testing"))
            b.getStyle().set("background", "#fffae6").set("color", "#172b4d");
        else if (s.contains("block"))
            b.getStyle().set("background", "#ffebe6").set("color", "#bf2600");
        else b.getStyle().set("background", "#f4f5f7").set("color", "#6b778c");
        return b;
    }

    private Span priorityBadge(String priority) {
        Span b = new Span(priority);
        b.getStyle().set("padding", "2px 10px").set("border-radius", "12px")
                .set("font-size", "12px").set("font-weight", "500");
        String p = priority != null ? priority.toLowerCase() : "";
        if (p.contains("highest") || p.contains("blocker"))
            b.getStyle().set("background", "#ffebe6").set("color", "#bf2600");
        else if (p.contains("high") || p.contains("critical"))
            b.getStyle().set("background", "#fff1e6").set("color", "#c56600");
        else if (p.contains("medium") || p.contains("major"))
            b.getStyle().set("background", "#deebff").set("color", "#0747a6");
        else if (p.contains("low") || p.contains("minor"))
            b.getStyle().set("background", "#e3fcef").set("color", "#006644");
        else b.getStyle().set("background", "#f4f5f7").set("color", "#6b778c");
        return b;
    }

    // ── Issue type icon (Jira-style colored badge) ─────────────────────

    private Span issueTypeIcon(String issueType, String size) {
        String t = issueType != null ? issueType.toLowerCase() : "";

        VaadinIcon vIcon;
        String bgColor;

        if (t.contains("bug")) {
            vIcon   = VaadinIcon.BUG;       bgColor = "#e5493a";
        } else if (t.contains("epic")) {
            vIcon   = VaadinIcon.BOLT;      bgColor = "#904ee2";
        } else if (t.contains("story")) {
            vIcon   = VaadinIcon.BOOKMARK;  bgColor = "#63ba3c";
        } else if (t.contains("sub")) {
            vIcon   = VaadinIcon.ARROW_RIGHT; bgColor = "#4bade8";
        } else if (t.contains("improvement")) {
            vIcon   = VaadinIcon.ARROW_UP;  bgColor = "#4bade8";
        } else if (t.contains("feature") || t.contains("new feature")) {
            vIcon   = VaadinIcon.STAR;      bgColor = "#63ba3c";
        } else if (t.contains("question") || t.contains("support")) {
            vIcon   = VaadinIcon.QUESTION;  bgColor = "#4bade8";
        } else if (t.contains("task")) {
            vIcon   = VaadinIcon.CHECK;     bgColor = "#4bade8";
        } else if (t.contains("test")) {
            vIcon   = VaadinIcon.FLASK;     bgColor = "#f79232";
        } else if (t.contains("change") || t.contains("request")) {
            vIcon   = VaadinIcon.EXCHANGE;  bgColor = "#4bade8";
        } else if (t.contains("risk")) {
            vIcon   = VaadinIcon.WARNING;   bgColor = "#f79232";
        } else {
            vIcon   = VaadinIcon.FILE_O;    bgColor = "#8993a4";
        }

        int px = 16;
        try { px = Integer.parseInt(size.replace("px", "")); } catch (NumberFormatException ignored) {}

        Icon icon = vIcon.create();
        icon.setSize((px - 4) + "px");
        icon.setColor("white");

        Span badge = new Span(icon);
        badge.getStyle()
                .set("display", "inline-flex")
                .set("align-items", "center")
                .set("justify-content", "center")
                .set("width", size).set("height", size).set("min-width", size)
                .set("border-radius", "3px")
                .set("background", bgColor)
                .set("flex-shrink", "0");
        badge.getElement().setAttribute("title", issueType != null ? issueType : "");
        return badge;
    }

    private HorizontalLayout detailRowWithIcon(String label, Span icon, String value) {
        Span l = new Span(label);
        l.getStyle().set("color", "#6b778c").set("font-size", "11px").set("min-width", "130px")
                .set("font-weight", "600").set("text-transform", "uppercase")
                .set("letter-spacing", "0.4px");
        Span v = new Span(value != null && !value.isBlank() ? value : "—");
        v.getStyle().set("color", "#172b4d").set("font-size", "13px");

        HorizontalLayout valueRow = new HorizontalLayout(icon, v);
        valueRow.setAlignItems(Alignment.CENTER);
        valueRow.setSpacing(false);
        valueRow.getStyle().set("gap", "5px");

        HorizontalLayout row = new HorizontalLayout(l, valueRow);
        row.getStyle().set("padding", "4px 0").set("gap", "8px").set("align-items", "center");
        return row;
    }

    // ── Layout helpers ────────────────────────────────────────────────

    private HorizontalLayout detailRow(String label, String value) {
        Span l = new Span(label);
        l.getStyle().set("color", "#6b778c").set("font-size", "11px").set("min-width", "130px")
                .set("font-weight", "600").set("text-transform", "uppercase")
                .set("letter-spacing", "0.4px");
        Span v = new Span(value != null && !value.isBlank() ? value : "—");
        v.getStyle().set("color", "#172b4d").set("font-size", "13px");
        HorizontalLayout row = new HorizontalLayout(l, v);
        row.getStyle().set("padding", "4px 0").set("gap", "8px").set("align-items", "baseline");
        return row;
    }

    private VerticalLayout fieldGroup(HorizontalLayout... rows) {
        VerticalLayout vl = new VerticalLayout(rows);
        vl.setPadding(false);
        vl.setSpacing(false);
        vl.getStyle().set("gap", "3px");
        return vl;
    }

    private H5 sectionTitle(String text) {
        H5 h = new H5(text);
        h.getStyle().set("margin", "8px 0 6px 0").set("color", "#172b4d").set("font-size", "13px");
        return h;
    }

    private Hr divider() {
        Hr hr = new Hr();
        hr.getStyle().set("margin", "12px 0 8px 0").set("border-color", "#dfe1e6");
        return hr;
    }

    private Div headerDivider() {
        Div d = new Div();
        d.getStyle().set("border-top", "2px solid #dfe1e6").set("flex-shrink", "0");
        return d;
    }

    private Div footerDivider() {
        Div d = new Div();
        d.getStyle().set("border-top", "1px solid #c1c7d0").set("flex-shrink", "0");
        return d;
    }

    private void showChartPlaceholder(String message) {
        chartArea.removeAll();
        chartArea.setAlignItems(Alignment.CENTER);
        chartArea.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
        Span msg = new Span(message);
        msg.getStyle().set("color", "#6b778c").set("font-size", "14px").set("font-style", "italic");
        chartArea.add(msg);
    }

    private String localTime(Instant instant) {
        return instant.atZone(ZONE).format(TIME_FMT);
    }

    private String localDate(Instant instant) {
        return instant.atZone(ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
}
