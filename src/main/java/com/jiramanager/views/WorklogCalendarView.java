package com.jiramanager.views;

import com.jiramanager.model.WorklogEntry;
import com.jiramanager.service.JiraService;
import com.jiramanager.service.WorklogOverlapDetector;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Route(value = "worklog-calendar", layout = MainLayout.class)
@PageTitle("Worklog Calendar – Jira Manager")
@RolesAllowed("USER")
public class WorklogCalendarView extends VerticalLayout implements BeforeEnterObserver {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("MMMM yyyy");

    // < 8h  → orange/amber warning
    private static final String CLR_UNDER_BG     = "#fff8e6";
    private static final String CLR_UNDER_BORDER = "#FF8B00";
    private static final String CLR_UNDER_TEXT   = "#974F00";
    // 8–10h → green, normal
    private static final String CLR_OK_BG        = "#e3fcef";
    private static final String CLR_OK_BORDER    = "#006644";
    private static final String CLR_OK_TEXT      = "#006644";
    // > 10h → indigo, consider why
    private static final String CLR_OVER_BG      = "#eae6ff";
    private static final String CLR_OVER_BORDER  = "#403294";
    private static final String CLR_OVER_TEXT    = "#403294";

    private final JiraService jiraService;

    private YearMonth currentMonth = YearMonth.now();

    private final Span        monthLabel   = new Span();
    private final Span        totalBadge   = new Span();
    private final Div         calendarGrid = new Div();
    private final ProgressBar loadingBar   = new ProgressBar();

    public WorklogCalendarView(JiraService jiraService) {
        this.jiraService = jiraService;

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("background", "#f4f5f7");

        add(buildHeader(), buildLoadingBar(), buildCalendarWrapper());
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!jiraService.isConfigured()) {
            event.forwardTo("settings?jira_required=true");
            return;
        }
        currentMonth = YearMonth.now();
        monthLabel.setText(currentMonth.format(MONTH_FMT));
        loadMonth();
    }

    // ── Header bar ────────────────────────────────────────────────────

    private HorizontalLayout buildHeader() {
        monthLabel.getStyle()
                .set("font-size", "18px").set("font-weight", "700")
                .set("color", "#172b4d").set("min-width", "180px")
                .set("text-align", "center");

        Button prevBtn = new Button(VaadinIcon.CHEVRON_LEFT.create());
        prevBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        prevBtn.addClickListener(e -> navigateMonth(-1));

        Button nextBtn = new Button(VaadinIcon.CHEVRON_RIGHT.create());
        nextBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        nextBtn.addClickListener(e -> navigateMonth(1));

        Button todayBtn = new Button("Today");
        todayBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        todayBtn.getStyle().set("font-size", "13px");
        todayBtn.addClickListener(e -> {
            currentMonth = YearMonth.now();
            monthLabel.setText(currentMonth.format(MONTH_FMT));
            loadMonth();
        });

        totalBadge.getStyle()
                .set("font-size", "13px").set("font-weight", "700")
                .set("color", "#0052cc").set("background", "#e9f2ff")
                .set("border-radius", "12px").set("padding", "4px 12px")
                .set("margin-left", "10px").set("white-space", "nowrap");

        HorizontalLayout nav = new HorizontalLayout(prevBtn, monthLabel, nextBtn, todayBtn, totalBadge);
        nav.setAlignItems(FlexComponent.Alignment.CENTER);
        nav.setSpacing(false);
        nav.getStyle().set("gap", "6px");

        Span spacer = new Span();
        spacer.getStyle().set("flex", "1");

        HorizontalLayout header = new HorizontalLayout(nav, spacer, buildLegend());
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.getStyle()
                .set("padding", "12px 20px")
                .set("background", "white")
                .set("border-bottom", "1px solid #dfe1e6")
                .set("flex-shrink", "0");
        return header;
    }

    private HorizontalLayout buildLegend() {
        HorizontalLayout legend = new HorizontalLayout(
                legendDot(CLR_UNDER_BORDER, "< 8h"),
                legendDot(CLR_OK_BORDER,    "8–10h"),
                legendDot(CLR_OVER_BORDER,  "> 10h"),
                legendDot("#6b778c",        "⚡ Overlap")
        );
        legend.setAlignItems(FlexComponent.Alignment.CENTER);
        legend.setSpacing(false);
        legend.getStyle().set("gap", "14px");
        return legend;
    }

    private HorizontalLayout legendDot(String color, String label) {
        Div dot = new Div();
        dot.getStyle()
                .set("width", "12px").set("height", "12px")
                .set("border-radius", "3px").set("background", color)
                .set("flex-shrink", "0");
        Span text = new Span(label);
        text.getStyle().set("font-size", "12px").set("color", "#6b778c");
        HorizontalLayout row = new HorizontalLayout(dot, text);
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        row.setSpacing(false);
        row.getStyle().set("gap", "5px");
        return row;
    }

    private ProgressBar buildLoadingBar() {
        loadingBar.setIndeterminate(true);
        loadingBar.setVisible(false);
        loadingBar.setWidthFull();
        loadingBar.getStyle()
                .set("height", "3px").set("border-radius", "0").set("flex-shrink", "0");
        return loadingBar;
    }

    // ── Calendar grid wrapper ─────────────────────────────────────────

    private static final String COL_MIN_W = "160px";

    private Div buildCalendarWrapper() {
        // grid-auto-rows: 1fr distributes available height equally across all week rows
        calendarGrid.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "repeat(7, minmax(" + COL_MIN_W + ", 1fr))")
                .set("grid-auto-rows", "1fr")
                .set("gap", "8px")
                .set("flex", "1");

        // inner: flex column so calendarGrid stretches to fill remaining height
        Div inner = new Div(buildDayHeaders(), calendarGrid);
        inner.getStyle()
                .set("display", "flex").set("flex-direction", "column")
                .set("height", "100%");

        // wrapper: no vertical scroll; horizontal scroll only if viewport < 7×160px
        Div wrapper = new Div(inner);
        wrapper.getStyle()
                .set("flex", "1")
                .set("overflow-x", "auto").set("overflow-y", "hidden")
                .set("padding", "16px 20px")
                .set("display", "flex").set("flex-direction", "column");
        return wrapper;
    }

    private Div buildDayHeaders() {
        Div row = new Div();
        row.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "repeat(7, minmax(" + COL_MIN_W + ", 1fr))")
                .set("gap", "8px")
                .set("margin-bottom", "6px");
        for (String d : new String[]{"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"}) {
            Span s = new Span(d);
            s.getStyle()
                    .set("text-align", "center")
                    .set("font-size", "11px").set("font-weight", "700")
                    .set("color", "#6b778c").set("text-transform", "uppercase")
                    .set("letter-spacing", "0.5px").set("padding", "2px 0");
            row.add(s);
        }
        return row;
    }

    // ── Month navigation & data loading ───────────────────────────────

    private void navigateMonth(int delta) {
        currentMonth = currentMonth.plusMonths(delta);
        monthLabel.setText(currentMonth.format(MONTH_FMT));
        loadMonth();
    }

    private void loadMonth() {
        calendarGrid.removeAll();
        showSkeletonCells();
        loadingBar.setVisible(true);
        totalBadge.setText("Total: …");

        UI ui = UI.getCurrent();
        YearMonth month = currentMonth;

        Runnable task = DelegatingSecurityContextRunnable.create(() -> {
            Map<LocalDate, List<WorklogEntry>> results = new ConcurrentHashMap<>();

            LocalDate start = month.atDay(1);
            LocalDate end   = month.atEndOfMonth();

            // Fetch each day in parallel via virtual threads
            List<Thread> threads = new ArrayList<>();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                final LocalDate date = d;
                Thread t = Thread.ofVirtual().start(
                        DelegatingSecurityContextRunnable.create(() -> {
                            try {
                                results.put(date, jiraService.getWorklogsForDate(date));
                            } catch (Exception ex) {
                                results.put(date, List.of());
                            }
                        }, SecurityContextHolder.getContext())
                );
                threads.add(t);
            }
            for (Thread t : threads) {
                try { t.join(); } catch (InterruptedException ignored) {}
            }

            ui.access(() -> {
                loadingBar.setVisible(false);
                renderCalendar(month, results);
            });
        }, SecurityContextHolder.getContext());

        Thread.ofVirtual().start(task);
    }

    // ── Skeleton loading placeholders ─────────────────────────────────

    private void showSkeletonCells() {
        // Leading empty cells for the current month's starting day-of-week
        int startDow = currentMonth.atDay(1).getDayOfWeek().getValue(); // Mon=1..Sun=7
        for (int i = 1; i < startDow; i++) calendarGrid.add(emptyCell());
        // Skeleton day cells
        for (int d = 1; d <= currentMonth.lengthOfMonth(); d++) {
            Div skeleton = new Div();
            skeleton.getStyle()
                    .set("background", "#f0f1f3")
                    .set("border-radius", "8px")
                    .set("height", "100%")
                    .set("border", "1px solid #dfe1e6");
            calendarGrid.add(skeleton);
        }
    }

    // ── Calendar rendering ────────────────────────────────────────────

    private void renderCalendar(YearMonth month, Map<LocalDate, List<WorklogEntry>> data) {
        calendarGrid.removeAll();

        LocalDate today    = LocalDate.now();
        int       startDow = month.atDay(1).getDayOfWeek().getValue();

        // Leading blank cells
        for (int i = 1; i < startDow; i++) calendarGrid.add(emptyCell());

        int totalMonthMinutes = 0;
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            LocalDate date    = month.atDay(d);
            List<WorklogEntry> entries = data.getOrDefault(date, List.of());
            totalMonthMinutes += entries.stream().mapToInt(WorklogEntry::getMinutesSpent).sum();
            calendarGrid.add(buildDayCell(date, entries, today));
        }

        totalBadge.setText("Total: " + formatMinutes(totalMonthMinutes));
    }

    private Div emptyCell() {
        Div empty = new Div();
        empty.getStyle().set("height", "100%").set("background", "transparent");
        return empty;
    }

    private Div buildDayCell(LocalDate date, List<WorklogEntry> entries, LocalDate today) {
        int    totalMinutes = entries.stream().mapToInt(WorklogEntry::getMinutesSpent).sum();
        double totalHours   = totalMinutes / 60.0;
        long   ticketCount  = entries.stream().map(WorklogEntry::getTicketKey).distinct().count();
        boolean hasOverlap  = !WorklogOverlapDetector.findOverlappingIds(entries).isEmpty();
        boolean isToday     = date.equals(today);
        boolean isFuture    = date.isAfter(today);
        boolean isWeekend   = date.getDayOfWeek().getValue() >= 6;

        Div cell = new Div();
        cell.getStyle()
                .set("border-radius", "8px")
                .set("padding", "8px 10px")
                .set("height", "100%")
                .set("display", "flex").set("flex-direction", "column")
                .set("gap", "3px")
                .set("cursor", "pointer")
                .set("position", "relative")
                .set("box-sizing", "border-box")
                .set("overflow", "hidden");

        // Color coding
        applyCellColors(cell, totalHours, totalMinutes, isToday, isFuture, isWeekend);

        // ── Day number row ────────────────────────────────────────────
        HorizontalLayout dayRow = new HorizontalLayout();
        dayRow.setAlignItems(FlexComponent.Alignment.CENTER);
        dayRow.setWidthFull();
        dayRow.setPadding(false);
        dayRow.setSpacing(false);
        dayRow.getStyle().set("gap", "4px");

        Span dayNum = new Span(String.valueOf(date.getDayOfMonth()));
        dayNum.getStyle()
                .set("font-size", "13px").set("font-weight", isToday ? "800" : "600")
                .set("color", isToday ? "#0052cc" : (isWeekend ? "#6b778c" : "#172b4d"));

        if (isToday) {
            Div todayPill = new Div(dayNum);
            todayPill.getStyle()
                    .set("background", "#0052cc").set("color", "white")
                    .set("border-radius", "50%").set("width", "24px").set("height", "24px")
                    .set("display", "flex").set("align-items", "center")
                    .set("justify-content", "center").set("font-size", "12px")
                    .set("font-weight", "700");
            dayNum.getStyle().set("color", "white");
            dayRow.add(todayPill);
        } else {
            dayRow.add(dayNum);
        }

        // Push overlap icon to the right
        Span spacer = new Span();
        spacer.getStyle().set("flex", "1");
        dayRow.add(spacer);

        if (hasOverlap) {
            Span overlapIcon = new Span("⚡");
            overlapIcon.getStyle().set("font-size", "14px").set("line-height", "1");
            overlapIcon.setTitle("Overlapping worklogs detected");
            dayRow.add(overlapIcon);
        }

        cell.add(dayRow);

        // ── Stats ─────────────────────────────────────────────────────
        if (totalMinutes > 0) {
            Span hoursLabel = new Span(formatMinutes(totalMinutes));
            hoursLabel.getStyle()
                    .set("font-size", "20px").set("font-weight", "800")
                    .set("color", hoursTextColor(totalHours))
                    .set("line-height", "1.1").set("margin-top", "6px");

            Span ticketLabel = new Span(ticketCount + " ticket" + (ticketCount == 1 ? "" : "s"));
            ticketLabel.getStyle()
                    .set("font-size", "11px").set("color", "#6b778c").set("margin-top", "2px");

            cell.add(hoursLabel, ticketLabel);

            // Hours bar (visual indicator at bottom of cell)
            cell.add(buildHoursBar(totalHours));
        } else if (!isFuture) {
            Span noLog = new Span(isWeekend ? "Weekend" : "No log");
            noLog.getStyle()
                    .set("font-size", "11px").set("color", "#b3bac5")
                    .set("font-style", "italic").set("margin-top", "auto");
            cell.add(noLog);
        }

        // ── Click: navigate to worklog view for this date ─────────────
        cell.getElement().addEventListener("click",
                e -> UI.getCurrent().navigate("worklog?date=" + date));

        // Hover effect
        cell.getElement().executeJs(
                "var el=$0;" +
                "el.addEventListener('mouseenter',function(){" +
                "  el.style.transform='translateY(-1px)';" +
                "  el.style.boxShadow='0 4px 12px rgba(0,0,0,0.12)';" +
                "});" +
                "el.addEventListener('mouseleave',function(){" +
                "  el.style.transform='';" +
                "  el.style.boxShadow='';" +
                "});",
                cell.getElement());

        return cell;
    }

    private void applyCellColors(Div cell, double hours, int minutes,
                                 boolean isToday, boolean isFuture, boolean isWeekend) {
        if (isFuture) {
            cell.getStyle()
                    .set("background", "white")
                    .set("border", "1px dashed #dfe1e6");
        } else if (isWeekend && minutes == 0) {
            cell.getStyle()
                    .set("background", "#fafbfc")
                    .set("border", "1px solid #ebecf0");
        } else if (minutes == 0) {
            // Weekday with no log — neutral white
            cell.getStyle()
                    .set("background", "white")
                    .set("border", isToday ? "2px solid #0052cc" : "1px solid #dfe1e6");
        } else if (hours < 8) {
            cell.getStyle()
                    .set("background", CLR_UNDER_BG)
                    .set("border", "2px solid " + CLR_UNDER_BORDER);
        } else if (hours <= 10) {
            cell.getStyle()
                    .set("background", CLR_OK_BG)
                    .set("border", "2px solid " + CLR_OK_BORDER);
        } else {
            cell.getStyle()
                    .set("background", CLR_OVER_BG)
                    .set("border", "2px solid " + CLR_OVER_BORDER);
        }
    }

    private Div buildHoursBar(double hours) {
        double pct = Math.min(1.0, hours / 10.0) * 100;
        String fillColor;
        if      (hours < 8)  fillColor = CLR_UNDER_BORDER;
        else if (hours <= 10) fillColor = CLR_OK_BORDER;
        else                  fillColor = CLR_OVER_BORDER;

        Div track = new Div();
        track.getStyle()
                .set("width", "100%").set("height", "3px")
                .set("background", "rgba(0,0,0,0.08)").set("border-radius", "2px")
                .set("margin-top", "auto").set("overflow", "hidden");

        Div fill = new Div();
        fill.getStyle()
                .set("height", "100%").set("width", pct + "%")
                .set("background", fillColor).set("border-radius", "2px");

        track.add(fill);
        return track;
    }

    private String hoursTextColor(double hours) {
        if (hours < 8)  return CLR_UNDER_TEXT;
        if (hours <= 10) return CLR_OK_TEXT;
        return CLR_OVER_TEXT;
    }

    private String formatMinutes(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? h + "h" : h + "h " + m + "m";
    }
}
