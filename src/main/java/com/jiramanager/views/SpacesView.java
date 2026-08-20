package com.jiramanager.views;

import com.jiramanager.model.AppUser;
import com.jiramanager.model.ConfluenceFeature;
import com.jiramanager.model.ConfluenceFeatureUpdateHistory;
import com.jiramanager.model.FeatureDesignDoc;
import com.jiramanager.model.FeatureReadStatus;
import com.jiramanager.model.JiraConfig;
import com.jiramanager.model.JiraTicket;
import com.jiramanager.model.ManualSyncTarget;
import com.jiramanager.model.TicketDoc;
import com.jiramanager.model.TicketDocItem;
import com.jiramanager.repository.ConfluenceFeatureRepository;
import com.jiramanager.repository.ConfluenceFeatureUpdateHistoryRepository;
import com.jiramanager.repository.FeatureDesignDocRepository;
import com.jiramanager.repository.FeatureReadStatusRepository;
import com.jiramanager.repository.JiraConfigRepository;
import com.jiramanager.repository.ManualSyncTargetRepository;
import com.jiramanager.repository.TicketDocItemRepository;
import com.jiramanager.repository.TicketDocRepository;
import com.jiramanager.service.ConfluenceLinkParser;
import com.jiramanager.service.ConfluenceLinkParser.Kind;
import com.jiramanager.service.ConfluenceLinkParser.ParsedLink;
import com.jiramanager.service.FeatureDesignDocStorage;
import com.jiramanager.service.JiraService;
import com.jiramanager.service.KnowledgeBaseSyncRunner;
import com.jiramanager.service.SessionUserService;
import com.jiramanager.service.TicketDocMarkdownService;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
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
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.StreamResource;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tree view of Confluence spaces/pages ("Features") synced in the background by
 * {@code KnowledgeBaseSyncRunner} for the projects the current user is assigned tickets in.
 * Selecting a Feature also shows its attached Ticket Docs (§ 10c in the PRD — folded into this
 * page rather than a separate route, since a ticket doc only ever makes sense in the context of
 * the Feature it's attached to).
 */
@Route(value = "spaces", layout = MainLayout.class)
@PageTitle("Spaces – Jira Manager")
@RolesAllowed("USER")
public class SpacesView extends VerticalLayout implements BeforeEnterObserver {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final JiraService jiraService;
    private final JiraConfigRepository jiraConfigRepo;
    private final ConfluenceFeatureRepository featureRepo;
    private final ConfluenceFeatureUpdateHistoryRepository historyRepo;
    private final FeatureReadStatusRepository readStatusRepo;
    private final ManualSyncTargetRepository manualSyncTargetRepo;
    private final TicketDocRepository ticketDocRepo;
    private final TicketDocItemRepository ticketDocItemRepo;
    private final TicketDocMarkdownService markdownService;
    private final FeatureDesignDocRepository designDocRepo;
    private final FeatureDesignDocStorage designDocStorage;
    private final KnowledgeBaseSyncRunner syncRunner;
    private final SessionUserService sessionUserService;

    private final HorizontalLayout spacesListPanel = new HorizontalLayout();
    private final TextField filterField = new TextField();
    private final TreeGrid<ConfluenceFeature> tree = new TreeGrid<>();
    private final VerticalLayout detailPanel = new VerticalLayout();
    private final VerticalLayout topDetailPanel = new VerticalLayout();
    private final VerticalLayout ticketDocsPanel = new VerticalLayout();
    private final Grid<TicketDocItem> itemsGrid = new Grid<>(TicketDocItem.class, false);
    private final Span docStatus = new Span();
    private final HorizontalLayout downloadSlot = new HorizontalLayout();
    private final Span countLabel = new Span();
    private final ProgressBar loadingBar = new ProgressBar();

    private JiraConfig currentConfig;
    private List<ConfluenceFeature> allFeatures = List.of();
    private List<String> allSpaceKeys = List.of();
    private String selectedSpaceKey;
    private Map<Long, Integer> readVersionByFeatureId = new HashMap<>();
    private ConfluenceFeature selectedFeature;
    private TicketDoc selectedDoc;

    public SpacesView(JiraService jiraService,
                       JiraConfigRepository jiraConfigRepo,
                       ConfluenceFeatureRepository featureRepo,
                       ConfluenceFeatureUpdateHistoryRepository historyRepo,
                       FeatureReadStatusRepository readStatusRepo,
                       ManualSyncTargetRepository manualSyncTargetRepo,
                       TicketDocRepository ticketDocRepo,
                       TicketDocItemRepository ticketDocItemRepo,
                       TicketDocMarkdownService markdownService,
                       FeatureDesignDocRepository designDocRepo,
                       FeatureDesignDocStorage designDocStorage,
                       KnowledgeBaseSyncRunner syncRunner,
                       SessionUserService sessionUserService) {
        this.jiraService = jiraService;
        this.jiraConfigRepo = jiraConfigRepo;
        this.featureRepo = featureRepo;
        this.historyRepo = historyRepo;
        this.readStatusRepo = readStatusRepo;
        this.manualSyncTargetRepo = manualSyncTargetRepo;
        this.ticketDocRepo = ticketDocRepo;
        this.ticketDocItemRepo = ticketDocItemRepo;
        this.markdownService = markdownService;
        this.designDocRepo = designDocRepo;
        this.designDocStorage = designDocStorage;
        this.syncRunner = syncRunner;
        this.sessionUserService = sessionUserService;

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("background", "#f4f5f7");

        add(buildHeader(), buildLoadingBar(), buildSplitLayout());
    }

    private ProgressBar buildLoadingBar() {
        loadingBar.setIndeterminate(true);
        loadingBar.setVisible(false);
        loadingBar.setWidthFull();
        loadingBar.getStyle().set("height", "3px").set("border-radius", "0");
        return loadingBar;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!jiraService.isConfigured()) {
            event.forwardTo("settings?jira_required=true");
            return;
        }
        AppUser user = sessionUserService.getCurrentUser();
        currentConfig = user != null ? jiraConfigRepo.findByUser(user).orElse(null) : null;

        int found = loadTree();
        if (found == 0) {
            // Nothing synced yet for this site (e.g. Jira was configured after the last
            // startup/scheduled sync) — trigger one immediately instead of leaving the page
            // silently empty until the next 4h cycle or a manual "Refresh" click.
            syncAndReload();
        }
        notifyPendingTicketDocChanges();
    }

    // ── Header ────────────────────────────────────────────────────────

    private HorizontalLayout buildHeader() {
        H3 title = new H3("Spaces");
        title.getStyle().set("margin", "0").set("color", "#172b4d");

        countLabel.getStyle().set("color", "#6b778c").set("font-size", "13px");

        Button refreshBtn = new Button("Refresh", VaadinIcon.REFRESH.create());
        refreshBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        refreshBtn.addClickListener(e -> syncAndReload());

        Button addTargetBtn = new Button("Add space/page", VaadinIcon.PLUS.create());
        addTargetBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        addTargetBtn.setTooltipText("Manually track a Confluence space or page, e.g. when its "
                + "key doesn't match your Jira project key");
        addTargetBtn.addClickListener(e -> openTargetFormDialog(null, null));

        Button manageLinksBtn = new Button("Manage links", VaadinIcon.LIST.create());
        manageLinksBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        manageLinksBtn.setTooltipText("Edit or remove manually tracked spaces/pages");
        manageLinksBtn.addClickListener(e -> openManageLinksDialog());

        Span spacer = new Span();
        spacer.getStyle().set("flex", "1");

        HorizontalLayout bar = new HorizontalLayout(title, countLabel, spacer, manageLinksBtn, addTargetBtn, refreshBtn);
        bar.setWidthFull();
        bar.setAlignItems(FlexComponent.Alignment.CENTER);
        bar.getStyle()
                .set("background", "white")
                .set("padding", "12px 20px")
                .set("border-bottom", "1px solid #dfe1e6")
                .set("gap", "12px");
        return bar;
    }

    // ── Split layout (tree + detail) ────────────────────────────────────

    private SplitLayout buildSplitLayout() {
        buildTree();

        spacesListPanel.setSpacing(false);
        spacesListPanel.getStyle()
                .set("padding", "10px 12px").set("background", "white")
                .set("border-bottom", "1px solid #dfe1e6").set("box-sizing", "border-box")
                .set("flex-wrap", "wrap").set("gap", "6px");
        spacesListPanel.setVisible(false); // hidden until loadTree() finds at least one space

        filterField.setPlaceholder("Filter by title...");
        filterField.setClearButtonVisible(true);
        filterField.setPrefixComponent(VaadinIcon.SEARCH.create());
        filterField.setWidthFull();
        filterField.setValueChangeMode(ValueChangeMode.LAZY);
        filterField.setValueChangeTimeout(250);
        filterField.addValueChangeListener(e -> applyTreeFilter(e.getValue()));
        filterField.getStyle().set("padding", "10px 12px").set("background", "white")
                .set("border-bottom", "1px solid #dfe1e6").set("box-sizing", "border-box");

        VerticalLayout treeWrap = new VerticalLayout(spacesListPanel, filterField, tree);
        treeWrap.setSizeFull();
        treeWrap.setPadding(false);
        treeWrap.setSpacing(false);
        treeWrap.setFlexGrow(1, tree);

        detailPanel.setSizeFull();
        detailPanel.setSpacing(false);
        detailPanel.getStyle()
                .set("background", "white")
                .set("border-left", "1px solid #dfe1e6")
                .set("overflow", "hidden");
        showPlaceholder();

        SplitLayout split = new SplitLayout(treeWrap, detailPanel);
        split.setSizeFull();
        split.setSplitterPosition(58);
        split.getStyle().set("flex", "1");
        return split;
    }

    private void buildTree() {
        tree.setSizeFull();
        tree.addHierarchyColumn(ConfluenceFeature::getTitle).setHeader("Folder").setFlexGrow(3);
        tree.addColumn(f -> f.getConfluenceUpdatedAt() != null ? DATE_FMT.format(f.getConfluenceUpdatedAt()) : "—")
                .setHeader("Updated").setFlexGrow(0).setWidth("160px");
        tree.addComponentColumn(this::statusBadge).setHeader("Status").setFlexGrow(0).setWidth("120px");

        // getItem() can be null if the tree's data provider was replaced (e.g. by a sync-triggered
        // reload) between the click firing client-side and being processed here — ignore stale clicks
        // rather than crash the whole view.
        tree.addItemClickListener(e -> {
            if (e.getItem() != null) showDetail(e.getItem());
        });
    }

    private Span statusBadge(ConfluenceFeature feature) {
        Span badge = new Span(isUnread(feature) ? "Updated" : "Read");
        badge.getStyle()
                .set("padding", "2px 10px")
                .set("border-radius", "12px")
                .set("font-size", "12px")
                .set("font-weight", "600");
        if (isUnread(feature)) {
            badge.getStyle().set("background", "#fff8e6").set("color", "#974f00");
        } else {
            badge.getStyle().set("background", "#f4f5f7").set("color", "#6b778c");
        }
        return badge;
    }

    private boolean isUnread(ConfluenceFeature feature) {
        int lastRead = readVersionByFeatureId.getOrDefault(feature.getId(), 0);
        return feature.getVersion() > lastRead;
    }

    // ── Manual sync trigger ("Refresh" — mirrors other views' re-fetch-from-source behavior) ─

    private void syncAndReload() {
        if (currentConfig == null) return;
        loadingBar.setVisible(true);
        UI ui = UI.getCurrent();
        JiraConfig cfg = currentConfig;

        Runnable task = DelegatingSecurityContextRunnable.create(() -> {
            try {
                syncRunner.syncOne(cfg);
                ui.access(() -> {
                    loadingBar.setVisible(false);
                    loadTree();
                    Notification.show("Sync complete", 2000, Notification.Position.BOTTOM_END);
                });
            } catch (Exception ex) {
                ui.access(() -> {
                    loadingBar.setVisible(false);
                    Notification n = Notification.show("Sync failed: " + ex.getMessage(),
                            4000, Notification.Position.BOTTOM_CENTER);
                    n.addThemeVariants(NotificationVariant.LUMO_ERROR);
                });
            }
        }, SecurityContextHolder.getContext());
        Thread.ofVirtual().start(task);
    }

    // ── Manual space/page tracking (fallback when project-key auto-match fails) ─────────

    /**
     * Single-purpose form used for both adding a new manually-tracked link and correcting an
     * existing one — {@code existing == null} means "add", otherwise "edit". Kept deliberately
     * separate from the list/manage view (§ {@link #openManageLinksDialog()}) rather than
     * combining "browse everything" and "add one thing" into a single dialog.
     */
    private void openTargetFormDialog(ManualSyncTarget existing, Runnable onSaved) {
        if (currentConfig == null || currentConfig.getBaseUrl() == null) return;
        String baseUrl = currentConfig.getBaseUrl();
        AppUser user = sessionUserService.getCurrentUser();
        boolean editing = existing != null;

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(editing ? "Edit tracked space/page" : "Add space/page");
        dialog.setWidth("480px");

        Span help = new Span("Paste a Confluence link — either a whole space "
                + "(.../wiki/spaces/KEY) or one specific page (.../wiki/spaces/KEY/pages/123/Title). "
                + "Folder links aren't supported yet (Confluence's Folder content type needs a newer "
                + "API this app doesn't use) — pick a page inside the folder instead.");
        help.getStyle().set("font-size", "12px").set("color", "#6b778c").set("display", "block")
                .set("margin-bottom", "10px");

        TextField urlField = new TextField("Confluence link");
        urlField.setWidthFull();
        urlField.setPlaceholder("https://yoursite.atlassian.net/wiki/spaces/DOCS/pages/12345/Title");
        if (editing && existing.getSourceUrl() != null) urlField.setValue(existing.getSourceUrl());

        Button cancelBtn = new Button("Cancel", e -> dialog.close());
        Button saveBtn = new Button(editing ? "Save" : "Add", e -> {
            ParsedLink link = ConfluenceLinkParser.parse(urlField.getValue());
            switch (link.kind()) {
                case INVALID -> Notification.show(
                        "Not a recognized Confluence link — paste the full URL from your browser's address bar.",
                        4000, Notification.Position.MIDDLE);
                case UNSUPPORTED_FOLDER -> {
                    Notification n = Notification.show(
                            "Folder links aren't supported yet — Confluence's Folder type needs a newer API "
                                    + "this app doesn't use. Use a Space link or a specific Page link inside the folder instead.",
                            5000, Notification.Position.MIDDLE);
                    n.addThemeVariants(NotificationVariant.LUMO_ERROR);
                }
                case SPACE, PAGE -> {
                    String pageId = link.kind() == Kind.PAGE ? link.pageId() : null;
                    boolean duplicate = manualSyncTargetRepo.findByBaseUrl(baseUrl).stream()
                            .anyMatch(t -> (!editing || !t.getId().equals(existing.getId()))
                                    && Objects.equals(t.getSpaceKey(), link.spaceKey())
                                    && Objects.equals(t.getPageId(), pageId));
                    if (duplicate) {
                        Notification.show("Another entry already tracks that space/page",
                                3000, Notification.Position.MIDDLE);
                        return;
                    }
                    ManualSyncTarget target = editing ? existing : new ManualSyncTarget();
                    target.setBaseUrl(baseUrl);
                    target.setSpaceKey(link.spaceKey());
                    target.setPageId(pageId);
                    target.setSourceUrl(urlField.getValue());
                    if (!editing) {
                        target.setAddedByUser(user);
                        target.setAddedAt(Instant.now());
                    }
                    manualSyncTargetRepo.save(target);
                    dialog.close();
                    if (onSaved != null) onSaved.run();
                    syncAndReload();
                    Notification.show(editing ? "Updated" : "Added", 2000, Notification.Position.BOTTOM_END);
                }
            }
        });
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        dialog.add(help, urlField);
        dialog.getFooter().add(saveBtn, cancelBtn);
        dialog.open();
    }

    /** Lists every manually-tracked space/page for this site, with per-row Edit/Remove actions. */
    private void openManageLinksDialog() {
        if (currentConfig == null || currentConfig.getBaseUrl() == null) return;
        String baseUrl = currentConfig.getBaseUrl();

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Manually tracked spaces/pages");
        dialog.setWidth("480px");

        VerticalLayout list = new VerticalLayout();
        list.setPadding(false);
        list.setSpacing(false);
        list.getStyle().set("gap", "6px");
        renderTargetList(list, baseUrl);

        Button closeBtn = new Button("Close", e -> dialog.close());
        dialog.add(list);
        dialog.getFooter().add(closeBtn);
        dialog.open();
    }

    private void renderTargetList(VerticalLayout list, String baseUrl) {
        list.removeAll();
        List<ManualSyncTarget> targets = manualSyncTargetRepo.findByBaseUrl(baseUrl);
        if (targets.isEmpty()) {
            Span none = new Span("No manually tracked spaces/pages yet.");
            none.getStyle().set("color", "#6b778c").set("font-size", "12px").set("font-style", "italic");
            list.add(none);
            return;
        }
        for (ManualSyncTarget target : targets) {
            list.add(buildTargetRow(list, baseUrl, target));
        }
    }

    private HorizontalLayout buildTargetRow(VerticalLayout list, String baseUrl, ManualSyncTarget target) {
        String label = target.getPageId() == null
                ? "Space " + target.getSpaceKey()
                : "Page " + target.getPageId() + " (space " + target.getSpaceKey() + ")";
        Span text = new Span(label);
        text.getStyle().set("font-size", "13px").set("color", "#172b4d").set("flex", "1");

        Button editBtn = new Button(VaadinIcon.PENCIL.create());
        editBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        editBtn.setTooltipText("Edit this link, e.g. if it was mistyped");
        editBtn.addClickListener(e -> openTargetFormDialog(target, () -> renderTargetList(list, baseUrl)));

        Button removeBtn = new Button(VaadinIcon.TRASH.create());
        removeBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
        removeBtn.addClickListener(e -> {
            manualSyncTargetRepo.delete(target);
            renderTargetList(list, baseUrl);
            Notification.show("Removed from tracking (already-synced data stays until next sync)",
                    3000, Notification.Position.BOTTOM_END);
        });

        HorizontalLayout row = new HorizontalLayout(text, editBtn, removeBtn);
        row.setWidthFull();
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        row.getStyle()
                .set("padding", "6px 10px").set("border", "1px solid #dfe1e6")
                .set("border-radius", "4px").set("background", "#f8f9fa");
        return row;
    }

    // ── Data loading (local DB reads — fast, no async needed) ──────────

    /** Returns the number of features loaded, so callers can decide whether to trigger a sync. */
    private int loadTree() {
        AppUser user = sessionUserService.getCurrentUser();
        if (currentConfig == null || currentConfig.getBaseUrl() == null) {
            countLabel.setText("0 features");
            allFeatures = List.of();
            allSpaceKeys = List.of();
            selectedSpaceKey = null;
            renderSpacesList();
            applyTreeFilter(filterField.getValue());
            return 0;
        }
        String baseUrl = currentConfig.getBaseUrl();

        allFeatures = featureRepo.findByBaseUrl(baseUrl);
        readVersionByFeatureId = new HashMap<>();
        for (FeatureReadStatus rs : readStatusRepo.findByUser(user)) {
            readVersionByFeatureId.put(rs.getFeature().getId(), rs.getLastReadVersion());
        }

        allSpaceKeys = allFeatures.stream()
                .map(ConfluenceFeature::getSpaceKey)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (selectedSpaceKey == null || !allSpaceKeys.contains(selectedSpaceKey)) {
            // Default to the first space — keeps the tree scoped to one space at a time instead
            // of mixing pages from every matched project together, which is hard to make sense of
            // once more than one space is being tracked.
            selectedSpaceKey = allSpaceKeys.isEmpty() ? null : allSpaceKeys.get(0);
        }
        renderSpacesList();
        applyTreeFilter(filterField.getValue());

        if (allFeatures.isEmpty()) {
            // A space is only synced when its Confluence space KEY exactly matches a Jira
            // project key you have tickets assigned in — this is a common, valid outcome
            // (no error), not a failure, so spell out the two likely reasons here.
            countLabel.setText("0 features — no Confluence space's key matches a Jira project "
                    + "you have tickets assigned in. Check that the space key (e.g. \"DOCS\") "
                    + "matches your Jira project key exactly, and that you have at least one "
                    + "ticket assigned to you in that project.");
        } else {
            countLabel.setText(allFeatures.size() + " feature" + (allFeatures.size() == 1 ? "" : "s")
                    + " (synced from Confluence)");
        }
        showPlaceholder();
        return allFeatures.size();
    }

    /** Chip row letting the user pick which tracked Space the tree/filter below should scope to. */
    private void renderSpacesList() {
        spacesListPanel.removeAll();
        if (allSpaceKeys.isEmpty()) {
            spacesListPanel.setVisible(false);
            return;
        }
        spacesListPanel.setVisible(true);
        for (String spaceKey : allSpaceKeys) {
            long count = allFeatures.stream().filter(f -> spaceKey.equals(f.getSpaceKey())).count();
            boolean selected = spaceKey.equals(selectedSpaceKey);

            Span chip = new Span(spaceKey + " (" + count + ")");
            chip.getStyle()
                    .set("padding", "4px 12px")
                    .set("border-radius", "14px")
                    .set("font-size", "12px")
                    .set("font-weight", "600")
                    .set("cursor", "pointer")
                    .set("white-space", "nowrap")
                    .set("background", selected ? "#0052cc" : "#f4f5f7")
                    .set("color", selected ? "white" : "#42526e");
            chip.addClickListener(e -> selectSpace(spaceKey));
            spacesListPanel.add(chip);
        }
    }

    private void selectSpace(String spaceKey) {
        if (Objects.equals(selectedSpaceKey, spaceKey)) return;
        selectedSpaceKey = spaceKey;
        renderSpacesList();
        applyTreeFilter(filterField.getValue());
        showPlaceholder();
    }

    /**
     * Applies the selected Space (§ chip row) and the "Filter by title" text to the tree. Blank
     * title query shows the full hierarchy for that space; non-blank switches to a flat list of
     * matches — a real hierarchical filter would hide a matching page whose ancestor title
     * doesn't also match (the tree can only reach a node by first expanding its parent), so
     * flattening is what actually lets you find a page buried several levels deep.
     */
    private void applyTreeFilter(String query) {
        List<ConfluenceFeature> scoped = selectedSpaceKey == null
                ? allFeatures
                : allFeatures.stream().filter(f -> selectedSpaceKey.equals(f.getSpaceKey())).toList();

        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.isEmpty()) {
            tree.setDataProvider(new TreeDataProvider<>(buildTreeData(scoped)));
            tree.expandRecursively(scoped, 1);
            return;
        }
        List<ConfluenceFeature> matches = scoped.stream()
                .filter(f -> f.getTitle() != null && f.getTitle().toLowerCase().contains(q))
                .toList();
        TreeData<ConfluenceFeature> flat = new TreeData<>();
        for (ConfluenceFeature f : matches) flat.addItem(null, f);
        tree.setDataProvider(new TreeDataProvider<>(flat));
    }

    /** Builds a TreeData from a flat list using parentPageId, tolerating unknown/missing parents. */
    private TreeData<ConfluenceFeature> buildTreeData(List<ConfluenceFeature> all) {
        TreeData<ConfluenceFeature> treeData = new TreeData<>();
        Map<String, ConfluenceFeature> byPageId = new HashMap<>();
        for (ConfluenceFeature f : all) byPageId.put(f.getPageId(), f);

        Set<String> added = new HashSet<>();
        List<ConfluenceFeature> remaining = new ArrayList<>(all);
        boolean progress = true;
        while (!remaining.isEmpty() && progress) {
            progress = false;
            Iterator<ConfluenceFeature> it = remaining.iterator();
            while (it.hasNext()) {
                ConfluenceFeature f = it.next();
                ConfluenceFeature parent = f.getParentPageId() != null ? byPageId.get(f.getParentPageId()) : null;
                boolean parentReady = parent == null || added.contains(parent.getPageId());
                if (parentReady) {
                    treeData.addItem(parent, f);
                    added.add(f.getPageId());
                    it.remove();
                    progress = true;
                }
            }
        }
        // Leftovers (e.g. a parent-of-a-parent cycle, which shouldn't happen in practice) —
        // surface them as roots rather than silently dropping data.
        for (ConfluenceFeature f : remaining) treeData.addItem(null, f);
        return treeData;
    }

    // ── Detail panel: top (feature info) + bottom (ticket docs) ────────

    private void showPlaceholder() {
        selectedFeature = null;
        selectedDoc = null;
        detailPanel.removeAll();
        detailPanel.setPadding(true);
        detailPanel.setAlignItems(Alignment.CENTER);
        detailPanel.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
        Span icon = new Span("🗂");
        icon.getStyle().set("font-size", "40px").set("margin-bottom", "12px");
        Span msg = new Span("Select a page to view its details and ticket docs");
        msg.getStyle().set("color", "#6b778c").set("font-size", "14px").set("font-style", "italic");
        detailPanel.add(icon, msg);
    }

    private void showDetail(ConfluenceFeature feature) {
        selectedFeature = feature;
        selectedDoc = ticketDocRepo.findByFeature(feature).orElse(null);

        detailPanel.removeAll();
        detailPanel.setPadding(false);
        detailPanel.setAlignItems(Alignment.STRETCH);
        detailPanel.setJustifyContentMode(FlexComponent.JustifyContentMode.START);

        renderFeatureInfo(feature);
        renderTicketDocsPanel();

        SplitLayout innerSplit = new SplitLayout(topDetailPanel, ticketDocsPanel);
        innerSplit.setOrientation(SplitLayout.Orientation.VERTICAL);
        innerSplit.setSizeFull();
        innerSplit.setSplitterPosition(50);

        detailPanel.add(innerSplit);
        detailPanel.setFlexGrow(1, innerSplit);
    }

    private void renderFeatureInfo(ConfluenceFeature feature) {
        topDetailPanel.removeAll();
        topDetailPanel.setPadding(true);
        topDetailPanel.setSpacing(false);
        topDetailPanel.getStyle().set("overflow-y", "auto");

        Anchor titleLink = new Anchor(feature.getUrl(), feature.getTitle());
        titleLink.setTarget("_blank");
        titleLink.getStyle()
                .set("font-weight", "700").set("font-size", "15px")
                .set("color", "#0052cc").set("text-decoration", "none");

        Span meta = new Span("Space " + feature.getSpaceKey() + " · v" + feature.getVersion());
        meta.getStyle().set("color", "#6b778c").set("font-size", "12px").set("display", "block")
                .set("margin", "4px 0 16px 0");

        Hr divider = new Hr();
        divider.getStyle().set("margin", "0 0 16px 0").set("border-color", "#dfe1e6");

        H5 historyTitle = new H5("Update History");
        historyTitle.getStyle().set("margin", "0 0 8px 0").set("color", "#172b4d").set("font-size", "13px");

        VerticalLayout historyList = new VerticalLayout();
        historyList.setPadding(false);
        historyList.setSpacing(false);
        historyList.getStyle().set("gap", "6px");

        List<ConfluenceFeatureUpdateHistory> history = historyRepo.findByFeatureOrderByDetectedAtDesc(feature);
        if (history.isEmpty()) {
            Span none = new Span("No updates detected since this page was first synced.");
            none.getStyle().set("color", "#6b778c").set("font-size", "12px").set("font-style", "italic");
            historyList.add(none);
        } else {
            for (ConfluenceFeatureUpdateHistory h : history) {
                Span line = new Span("v" + h.getOldVersion() + " → v" + h.getNewVersion()
                        + "  ·  updated " + DATE_FMT.format(h.getNewUpdatedAt())
                        + "  ·  detected " + DATE_FMT.format(h.getDetectedAt()));
                line.getStyle().set("font-size", "12px").set("color", "#172b4d")
                        .set("padding", "6px 8px").set("background", "#f4f5f7").set("border-radius", "4px");
                historyList.add(line);
            }
        }

        boolean unread = isUnread(feature);
        Button markReadBtn = new Button(unread ? "Mark as read" : "Already read");
        markReadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        markReadBtn.setEnabled(unread);
        markReadBtn.getStyle().set("margin-top", "20px");
        markReadBtn.addClickListener(e -> markRead(feature));

        Hr docDivider = new Hr();
        docDivider.getStyle().set("margin", "20px 0 16px 0").set("border-color", "#dfe1e6");

        topDetailPanel.add(titleLink, meta, divider, historyTitle, historyList, markReadBtn,
                docDivider, buildDesignDocSection(feature));
    }

    // ── Design Doc (externally-generated HTML review/plan doc, uploaded per Feature) ────
    // Every upload is a new version — nothing is overwritten, so past versions stay viewable
    // for tracing/comparison later. The highest version number is "latest".

    private VerticalLayout buildDesignDocSection(ConfluenceFeature feature) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(false);
        section.getStyle().set("gap", "6px");

        H5 title = new H5("Design Doc");
        title.getStyle().set("margin", "0").set("color", "#172b4d").set("font-size", "13px");

        Span help = new Span("Upload the HTML review doc generated externally from this feature's "
                + "Ticket Docs + Confluence content, then open it here for dev review. Each upload "
                + "is kept as a new version — older ones stay available below.");
        help.getStyle().set("font-size", "12px").set("color", "#6b778c").set("display", "block");

        section.add(title, help);

        List<FeatureDesignDoc> versions = designDocRepo.findByFeatureOrderByVersionDesc(feature);
        FeatureDesignDoc latest = versions.isEmpty() ? null : versions.get(0);

        if (latest != null) {
            Anchor viewLink = new Anchor("/design-docs/" + feature.getId(),
                    "Latest: v" + latest.getVersion() + " — "
                            + (latest.getFileName() != null ? latest.getFileName() : "design doc") + " ↗");
            viewLink.setTarget("_blank");
            viewLink.getStyle()
                    .set("font-weight", "600").set("font-size", "13px")
                    .set("color", "#0052cc").set("text-decoration", "none")
                    .set("margin-top", "4px").set("display", "inline-block");

            Span uploadedMeta = new Span("Uploaded " + DATE_FMT.format(latest.getUploadedAt())
                    + (latest.getUploadedByUser() != null ? " by " + latest.getUploadedByUser().getFirstName() : ""));
            uploadedMeta.getStyle().set("font-size", "11px").set("color", "#6b778c").set("display", "block");

            section.add(viewLink, uploadedMeta);
        }

        if (versions.size() > 1) {
            VerticalLayout historyList = new VerticalLayout();
            historyList.setPadding(false);
            historyList.setSpacing(false);
            historyList.getStyle().set("gap", "4px");

            // Skip index 0 — that's "latest", already shown above.
            for (FeatureDesignDoc old : versions.subList(1, versions.size())) {
                Anchor oldLink = new Anchor("/design-docs/" + feature.getId() + "/" + old.getVersion(),
                        "v" + old.getVersion() + " — " + (old.getFileName() != null ? old.getFileName() : "design doc")
                                + "  ·  " + DATE_FMT.format(old.getUploadedAt())
                                + (old.getUploadedByUser() != null ? " · " + old.getUploadedByUser().getFirstName() : "")
                                + " ↗");
                oldLink.setTarget("_blank");
                oldLink.getStyle()
                        .set("font-size", "12px").set("color", "#42526e").set("text-decoration", "none")
                        .set("display", "block");
                historyList.add(oldLink);
            }

            Details history = new Details("Version history (" + (versions.size() - 1) + " older)", historyList);
            history.setOpened(false);
            history.getStyle().set("margin-top", "4px");
            section.add(history);
        }

        int nextVersion = (latest != null ? latest.getVersion() : 0) + 1;

        MemoryBuffer buffer = new MemoryBuffer();
        Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes(".html", ".htm", "text/html");
        upload.setMaxFiles(1);
        upload.setMaxFileSize(20 * 1024 * 1024); // 20 MB — generous for a doc with embedded images
        upload.setDropAllowed(true);
        upload.getStyle().set("margin-top", "8px");
        upload.setUploadButton(new Button(latest != null ? "Upload new version" : "Upload file"));

        upload.addSucceededListener(event -> {
            try (var in = buffer.getInputStream()) {
                Path saved = designDocStorage.save(feature.getId(), nextVersion, event.getFileName(), in);

                FeatureDesignDoc newVersion = new FeatureDesignDoc();
                newVersion.setFeature(feature);
                newVersion.setVersion(nextVersion);
                newVersion.setFileName(event.getFileName());
                newVersion.setFilePath(saved.toString());
                newVersion.setUploadedAt(Instant.now());
                newVersion.setUploadedByUser(sessionUserService.getCurrentUser());
                designDocRepo.save(newVersion);

                Notification.show("Uploaded as v" + nextVersion, 2500, Notification.Position.BOTTOM_END);
                renderFeatureInfo(feature);
            } catch (IOException ex) {
                Notification n = Notification.show("Failed to save uploaded file: " + ex.getMessage(),
                        4000, Notification.Position.BOTTOM_CENTER);
                n.addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        upload.addFileRejectedListener(event -> {
            Notification n = Notification.show(event.getErrorMessage(), 4000, Notification.Position.MIDDLE);
            n.addThemeVariants(NotificationVariant.LUMO_ERROR);
        });

        section.add(upload);
        return section;
    }

    private void markRead(ConfluenceFeature feature) {
        AppUser user = sessionUserService.getCurrentUser();
        if (user == null) return;

        FeatureReadStatus status = readStatusRepo.findByFeatureAndUser(feature, user).orElseGet(FeatureReadStatus::new);
        status.setFeature(feature);
        status.setUser(user);
        status.setLastReadVersion(feature.getVersion());
        status.setReadAt(Instant.now());
        readStatusRepo.save(status);

        readVersionByFeatureId.put(feature.getId(), feature.getVersion());
        tree.getDataProvider().refreshItem(feature);
        renderFeatureInfo(feature);
        Notification.show("Marked as read", 1800, Notification.Position.BOTTOM_END);
    }

    // ── Ticket Docs panel (bottom half of the detail panel) ─────────────

    private void renderTicketDocsPanel() {
        ticketDocsPanel.removeAll();
        ticketDocsPanel.setPadding(true);
        ticketDocsPanel.setSpacing(false);
        ticketDocsPanel.setSizeFull();
        ticketDocsPanel.getStyle()
                .set("background", "#fafbfc")
                .set("border-top", "1px solid #dfe1e6")
                .set("overflow", "hidden");

        List<TicketDocItem> items = selectedDoc != null
                ? ticketDocItemRepo.findByTicketDoc(selectedDoc)
                : List.of();

        boolean needsRegenerate = items.stream().anyMatch(TicketDocItem::isNeedsRegenerate);
        docStatus.getStyle()
                .set("padding", "2px 10px").set("border-radius", "12px")
                .set("font-size", "12px").set("font-weight", "600");
        if (selectedDoc == null || selectedDoc.getGeneratedAt() == null) {
            docStatus.setText("Not generated yet");
            docStatus.getStyle().set("background", "#f4f5f7").set("color", "#6b778c");
        } else if (needsRegenerate) {
            docStatus.setText("Regenerate recommended");
            docStatus.getStyle().set("background", "#fff8e6").set("color", "#974f00");
        } else {
            docStatus.setText("Up to date · generated " + DATE_FMT.format(selectedDoc.getGeneratedAt()));
            docStatus.getStyle().set("background", "#e3fcef").set("color", "#006644");
        }

        H5 sectionTitle = new H5("Ticket Docs");
        sectionTitle.getStyle().set("margin", "0").set("color", "#172b4d").set("font-size", "13px");

        Button addBtn = new Button("Add tickets", VaadinIcon.PLUS.create());
        addBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        addBtn.addClickListener(this::openAddTicketsDialog);

        Button regenerateBtn = new Button("Regenerate", VaadinIcon.REFRESH.create());
        regenerateBtn.addThemeVariants(ButtonVariant.LUMO_SMALL);
        regenerateBtn.setEnabled(!items.isEmpty());
        regenerateBtn.addClickListener(e -> regenerate());

        downloadSlot.removeAll();
        downloadSlot.setSpacing(false);
        if (selectedDoc != null && selectedDoc.getFilePath() != null) {
            downloadSlot.add(buildDownloadAnchor(selectedDoc));
        }

        HorizontalLayout actions = new HorizontalLayout(addBtn, regenerateBtn, downloadSlot, docStatus);
        actions.setAlignItems(FlexComponent.Alignment.CENTER);
        actions.getStyle().set("gap", "8px").set("flex-wrap", "wrap");

        HorizontalLayout headerRow = new HorizontalLayout(sectionTitle);
        headerRow.getStyle().set("margin-bottom", "8px");

        buildItemsGrid();
        itemsGrid.setItems(items);

        VerticalLayout content = new VerticalLayout(headerRow, actions, itemsGrid);
        content.setSizeFull();
        content.setPadding(false);
        content.setSpacing(false);
        content.getStyle().set("gap", "8px");
        content.setFlexGrow(1, itemsGrid);

        ticketDocsPanel.add(content);
    }

    private void buildItemsGrid() {
        itemsGrid.removeAllColumns();
        itemsGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        itemsGrid.setSizeFull();

        // Ticket key is the main content here — give it nearly all the width instead of the
        // fixed narrow column used when this grid lived on its own dedicated page.
        itemsGrid.addColumn(TicketDocItem::getTicketKey)
                .setHeader("Ticket").setFlexGrow(1).setAutoWidth(false);
        itemsGrid.addComponentColumn(i -> {
            Span badge = new Span(i.isNeedsRegenerate() ? "Changed on Jira" : "Up to date");
            badge.getStyle()
                    .set("padding", "2px 10px").set("border-radius", "12px")
                    .set("font-size", "12px").set("font-weight", "600").set("white-space", "nowrap");
            if (i.isNeedsRegenerate()) {
                badge.getStyle().set("background", "#fff8e6").set("color", "#974f00");
            } else {
                badge.getStyle().set("background", "#e3fcef").set("color", "#006644");
            }
            return badge;
        }).setHeader("Status").setWidth("140px").setFlexGrow(0);
        itemsGrid.addComponentColumn(i -> {
            Button removeBtn = new Button(VaadinIcon.TRASH.create());
            removeBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
            removeBtn.addClickListener(e -> removeItem(i));
            return removeBtn;
        }).setHeader("").setWidth("50px").setFlexGrow(0);
    }

    private Anchor buildDownloadAnchor(TicketDoc doc) {
        StreamResource resource = new StreamResource("tickets.md", () -> {
            try {
                return Files.newInputStream(Path.of(doc.getFilePath()));
            } catch (IOException e) {
                throw new RuntimeException("Could not read generated file: " + e.getMessage(), e);
            }
        });
        Anchor download = new Anchor(resource, "");
        download.getElement().setAttribute("download", true);
        Button downloadBtn = new Button("Download", VaadinIcon.DOWNLOAD.create());
        downloadBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        download.removeAll();
        download.add(downloadBtn);
        return download;
    }

    // ── Add tickets ──────────────────────────────────────────────────

    private void openAddTicketsDialog(ClickEvent<Button> event) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Add tickets to " + selectedFeature.getTitle());
        dialog.setWidth("480px");

        TextArea keysField = new TextArea("Ticket keys");
        keysField.setPlaceholder("DEMO-101, DEMO-102\nor one per line");
        keysField.setWidthFull();
        keysField.setHeight("140px");

        Button cancelBtn = new Button("Cancel", e -> dialog.close());
        Button submitBtn = new Button("Add", e -> {
            List<String> keys = parseKeys(keysField.getValue());
            if (keys.isEmpty()) {
                Notification.show("Enter at least one ticket key", 2500, Notification.Position.MIDDLE);
                return;
            }
            dialog.close();
            addTickets(keys);
        });
        submitBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout buttons = new HorizontalLayout(submitBtn, cancelBtn);
        dialog.add(keysField);
        dialog.getFooter().add(buttons);
        dialog.open();
    }

    private List<String> parseKeys(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("[,\\n\\r\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(String::toUpperCase)
                .distinct()
                .collect(Collectors.toList());
    }

    private void addTickets(List<String> keys) {
        loadingBar.setVisible(true);
        UI ui = UI.getCurrent();
        ConfluenceFeature feature = selectedFeature;
        AppUser user = sessionUserService.getCurrentUser();

        Runnable task = DelegatingSecurityContextRunnable.create(() -> {
            try {
                TicketDoc doc = ticketDocRepo.findByFeature(feature).orElseGet(() -> {
                    TicketDoc d = new TicketDoc();
                    d.setFeature(feature);
                    return ticketDocRepo.save(d);
                });

                Set<String> existingKeys = ticketDocItemRepo.findByTicketDoc(doc).stream()
                        .map(TicketDocItem::getTicketKey).collect(Collectors.toSet());

                int added = 0;
                for (String key : keys) {
                    if (existingKeys.contains(key)) continue;
                    JiraTicket ticket;
                    try {
                        ticket = jiraService.getTicketByKey(key);
                    } catch (Exception ex) {
                        continue; // unknown/inaccessible key — skip silently, rest still proceed
                    }
                    TicketDocItem item = new TicketDocItem();
                    item.setTicketDoc(doc);
                    item.setTicketKey(key);
                    item.setLastKnownUpdated(ticket.getUpdatedInstant());
                    item.setAddedAt(Instant.now());
                    item.setAddedByUser(user);
                    ticketDocItemRepo.save(item);
                    added++;
                }

                if (added > 0) regenerateDoc(doc, feature);
                int finalAdded = added;

                ui.access(() -> {
                    loadingBar.setVisible(false);
                    selectedDoc = ticketDocRepo.findByFeature(feature).orElse(null);
                    renderTicketDocsPanel();
                    Notification.show(finalAdded + " ticket(s) added", 2500, Notification.Position.BOTTOM_END);
                });
            } catch (Exception ex) {
                ui.access(() -> {
                    loadingBar.setVisible(false);
                    Notification n = Notification.show("Failed to add tickets: " + ex.getMessage(),
                            4000, Notification.Position.BOTTOM_CENTER);
                    n.addThemeVariants(NotificationVariant.LUMO_ERROR);
                });
            }
        }, SecurityContextHolder.getContext());
        Thread.ofVirtual().start(task);
    }

    // ── Regenerate ───────────────────────────────────────────────────

    private void regenerate() {
        if (selectedDoc == null || selectedFeature == null) return;
        loadingBar.setVisible(true);
        UI ui = UI.getCurrent();
        TicketDoc doc = selectedDoc;
        ConfluenceFeature feature = selectedFeature;

        Runnable task = DelegatingSecurityContextRunnable.create(() -> {
            try {
                regenerateDoc(doc, feature);
                ui.access(() -> {
                    loadingBar.setVisible(false);
                    selectedDoc = ticketDocRepo.findByFeature(feature).orElse(null);
                    renderTicketDocsPanel();
                    Notification.show("Regenerated tickets.md", 2500, Notification.Position.BOTTOM_END);
                });
            } catch (Exception ex) {
                ui.access(() -> {
                    loadingBar.setVisible(false);
                    Notification n = Notification.show("Failed to regenerate: " + ex.getMessage(),
                            4000, Notification.Position.BOTTOM_CENTER);
                    n.addThemeVariants(NotificationVariant.LUMO_ERROR);
                });
            }
        }, SecurityContextHolder.getContext());
        Thread.ofVirtual().start(task);
    }

    /** Refetches every attached ticket fresh and rewrites the doc's markdown file. */
    private void regenerateDoc(TicketDoc doc, ConfluenceFeature feature) throws IOException {
        List<TicketDocItem> items = ticketDocItemRepo.findByTicketDoc(doc);
        List<String> keys = items.stream().map(TicketDocItem::getTicketKey).toList();
        List<JiraTicket> tickets = jiraService.getTicketsByKeys(currentConfig, keys);
        Map<String, JiraTicket> byKey = tickets.stream()
                .collect(Collectors.toMap(JiraTicket::getKey, t -> t, (a, b) -> a));

        Path path = markdownService.generate(feature, tickets);

        Instant now = Instant.now();
        for (TicketDocItem item : items) {
            JiraTicket fresh = byKey.get(item.getTicketKey());
            item.setLastKnownUpdated(fresh != null ? fresh.getUpdatedInstant() : item.getLastKnownUpdated());
            item.setNeedsRegenerate(false);
            item.setNotifiedAt(null);
            ticketDocItemRepo.save(item);
        }

        doc.setFilePath(path.toString());
        doc.setGeneratedAt(now);
        doc.setGeneratedByUser(sessionUserService.getCurrentUser());
        ticketDocRepo.save(doc);
    }

    private void removeItem(TicketDocItem item) {
        // Use the view's own selectedDoc (loaded fresh via a direct repository query) rather than
        // item.getTicketDoc() — item is a stale Grid row from an earlier request/session, so its
        // lazy TicketDoc association is an uninitialized proxy that would throw
        // LazyInitializationException the moment any setter runs on it inside regenerateDoc().
        TicketDoc doc = selectedDoc;
        ticketDocItemRepo.delete(item);
        try {
            List<TicketDocItem> remaining = ticketDocItemRepo.findByTicketDoc(doc);
            if (!remaining.isEmpty()) {
                regenerateDoc(doc, selectedFeature);
            }
        } catch (Exception ex) {
            Notification n = Notification.show("Removed, but regeneration failed: " + ex.getMessage(),
                    4000, Notification.Position.BOTTOM_CENTER);
            n.addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
        selectedDoc = ticketDocRepo.findByFeature(selectedFeature).orElse(null);
        renderTicketDocsPanel();
    }

    // ── Pending-change notification (shown once per item, on next visit) ─

    private void notifyPendingTicketDocChanges() {
        if (currentConfig == null || currentConfig.getBaseUrl() == null) return;
        List<TicketDocItem> pending = ticketDocItemRepo.findByFeatureBaseUrl(currentConfig.getBaseUrl()).stream()
                .filter(i -> i.isNeedsRegenerate() && i.getNotifiedAt() == null)
                .toList();
        if (pending.isEmpty()) return;

        Instant now = Instant.now();
        for (TicketDocItem item : pending) {
            item.setNotifiedAt(now);
            ticketDocItemRepo.save(item);
        }
        Notification n = Notification.show(
                pending.size() + " ticket(s) changed on Jira since their doc was last generated — regenerate recommended.",
                5000, Notification.Position.TOP_CENTER);
        n.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }
}
