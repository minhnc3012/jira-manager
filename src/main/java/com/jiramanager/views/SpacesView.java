package com.jiramanager.views;

import com.jiramanager.model.AppUser;
import com.jiramanager.model.ConfluencePageDetail;
import com.jiramanager.model.FeatureDesignDoc;
import com.jiramanager.model.JiraConfig;
import com.jiramanager.model.JiraTicket;
import com.jiramanager.model.Space;
import com.jiramanager.model.SpaceItem;
import com.jiramanager.model.SpaceItemReadStatus;
import com.jiramanager.model.SpaceItemUpdateHistory;
import com.jiramanager.model.TicketDoc;
import com.jiramanager.model.TicketDocItem;
import com.jiramanager.repository.FeatureDesignDocRepository;
import com.jiramanager.repository.JiraConfigRepository;
import com.jiramanager.repository.SpaceItemReadStatusRepository;
import com.jiramanager.repository.SpaceItemRepository;
import com.jiramanager.repository.SpaceItemUpdateHistoryRepository;
import com.jiramanager.repository.SpaceRepository;
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
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.dnd.GridDropEvent;
import com.vaadin.flow.component.grid.dnd.GridDropLocation;
import com.vaadin.flow.component.grid.dnd.GridDropMode;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.BoxSizing;
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
 * Manual Spaces tree: the user creates/edits/deletes {@link Space}s (each roughly a project) and
 * builds each space's tree of {@link SpaceItem}s by hand — root "+ New item", per-row "+" to add
 * a child, per-row edit/delete, and Explorer-style drag & drop to reparent/reorder. There is no
 * auto-discovery any more; an item only auto-syncs (version/update badge, Update History) when
 * the user explicitly attaches it to one specific Confluence page.
 *
 * <p>Selecting an item also shows its attached Ticket Docs (embedded rather than a separate
 * route, since a ticket doc only ever makes sense in the context of the item it's attached to).
 */
@Route(value = "spaces", layout = MainLayout.class)
@PageTitle("Spaces – Jira Manager")
@RolesAllowed("USER")
public class SpacesView extends VerticalLayout implements BeforeEnterObserver {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final JiraService jiraService;
    private final JiraConfigRepository jiraConfigRepo;
    private final SpaceRepository spaceRepo;
    private final SpaceItemRepository spaceItemRepo;
    private final SpaceItemUpdateHistoryRepository historyRepo;
    private final SpaceItemReadStatusRepository readStatusRepo;
    private final TicketDocRepository ticketDocRepo;
    private final TicketDocItemRepository ticketDocItemRepo;
    private final TicketDocMarkdownService markdownService;
    private final FeatureDesignDocRepository designDocRepo;
    private final FeatureDesignDocStorage designDocStorage;
    private final KnowledgeBaseSyncRunner syncRunner;
    private final SessionUserService sessionUserService;

    private final HorizontalLayout spacesListPanel = new HorizontalLayout();
    private final TextField filterField = new TextField();
    private final TreeGrid<SpaceItem> tree = new TreeGrid<>();
    private final VerticalLayout detailPanel = new VerticalLayout();
    private final VerticalLayout topDetailPanel = new VerticalLayout();
    private final VerticalLayout ticketDocsPanel = new VerticalLayout();
    private final Grid<TicketDocItem> itemsGrid = new Grid<>(TicketDocItem.class, false);
    private final Span docStatus = new Span();
    private final HorizontalLayout downloadSlot = new HorizontalLayout();
    private final Span countLabel = new Span();
    private final ProgressBar loadingBar = new ProgressBar();
    private final Button newItemBtn = new Button("New item", VaadinIcon.PLUS.create());

    private JiraConfig currentConfig;
    private List<Space> allSpaces = List.of();
    private Space selectedSpace;
    private List<SpaceItem> allItems = List.of();
    private Map<Long, Integer> readVersionByItemId = new HashMap<>();
    private SpaceItem selectedItem;
    private TicketDoc selectedDoc;
    private SpaceItem draggedItem;

    public SpacesView(JiraService jiraService,
                       JiraConfigRepository jiraConfigRepo,
                       SpaceRepository spaceRepo,
                       SpaceItemRepository spaceItemRepo,
                       SpaceItemUpdateHistoryRepository historyRepo,
                       SpaceItemReadStatusRepository readStatusRepo,
                       TicketDocRepository ticketDocRepo,
                       TicketDocItemRepository ticketDocItemRepo,
                       TicketDocMarkdownService markdownService,
                       FeatureDesignDocRepository designDocRepo,
                       FeatureDesignDocStorage designDocStorage,
                       KnowledgeBaseSyncRunner syncRunner,
                       SessionUserService sessionUserService) {
        this.jiraService = jiraService;
        this.jiraConfigRepo = jiraConfigRepo;
        this.spaceRepo = spaceRepo;
        this.spaceItemRepo = spaceItemRepo;
        this.historyRepo = historyRepo;
        this.readStatusRepo = readStatusRepo;
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

        loadSpaces();
        notifyPendingTicketDocChanges();
    }

    // ── Header ────────────────────────────────────────────────────────

    private HorizontalLayout buildHeader() {
        H3 title = new H3("Spaces");
        title.getStyle().set("margin", "0").set("color", "#172b4d");

        countLabel.getStyle().set("color", "#6b778c").set("font-size", "13px");

        Button refreshBtn = new Button("Refresh", VaadinIcon.REFRESH.create());
        refreshBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        refreshBtn.setTooltipText("Re-fetch version/update status for every item linked to a Confluence page");
        refreshBtn.addClickListener(e -> syncAndReload());

        newItemBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        newItemBtn.setEnabled(false);
        newItemBtn.addClickListener(e -> openItemFormDialog(null, null));

        Button newSpaceBtn = new Button("New space", VaadinIcon.FOLDER_ADD.create());
        newSpaceBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        newSpaceBtn.addClickListener(e -> openSpaceFormDialog(null));

        Button manageSpacesBtn = new Button("Manage spaces", VaadinIcon.COG.create());
        manageSpacesBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        manageSpacesBtn.addClickListener(e -> openManageSpacesDialog());

        Span spacer = new Span();
        spacer.getStyle().set("flex", "1");

        HorizontalLayout bar = new HorizontalLayout(title, countLabel, spacer,
                manageSpacesBtn, newSpaceBtn, newItemBtn, refreshBtn);
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
        spacesListPanel.setVisible(false); // hidden until at least one space exists

        filterField.setPlaceholder("Filter by name...");
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
        tree.addHierarchyColumn(SpaceItem::getName).setHeader("Folder").setFlexGrow(3);
        tree.addColumn(i -> i.getConfluenceUpdatedAt() != null ? DATE_FMT.format(i.getConfluenceUpdatedAt()) : "—")
                .setHeader("Updated").setFlexGrow(0).setWidth("160px");
        tree.addComponentColumn(this::statusBadge).setHeader("Status").setFlexGrow(0).setWidth("110px");
        tree.addComponentColumn(this::buildItemActions).setHeader("").setFlexGrow(0).setWidth("140px");

        // getItem() can be null if the tree's data provider was replaced (e.g. by a create/edit/
        // delete-triggered reload) between the click firing client-side and being processed here.
        tree.addItemClickListener(e -> {
            if (e.getItem() != null) showDetail(e.getItem());
        });

        tree.setRowsDraggable(true);
        tree.setDropMode(GridDropMode.ON_TOP_OR_BETWEEN);
        tree.addDragStartListener(e ->
                draggedItem = e.getDraggedItems().isEmpty() ? null : e.getDraggedItems().get(0));
        tree.addDragEndListener(e -> draggedItem = null);
        tree.addDropListener(this::handleDrop);
    }

    private Component statusBadge(SpaceItem item) {
        if (!item.isLinked()) {
            Span dash = new Span("—");
            dash.getStyle().set("color", "#a5adba").set("font-size", "12px");
            return dash;
        }
        Span badge = new Span(isUnread(item) ? "Updated" : "Read");
        badge.getStyle()
                .set("padding", "2px 10px")
                .set("border-radius", "12px")
                .set("font-size", "12px")
                .set("font-weight", "600");
        if (isUnread(item)) {
            badge.getStyle().set("background", "#fff8e6").set("color", "#974f00");
        } else {
            badge.getStyle().set("background", "#f4f5f7").set("color", "#6b778c");
        }
        return badge;
    }

    private Component buildItemActions(SpaceItem item) {
        Button addBtn = new Button(VaadinIcon.PLUS.create());
        addBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        addBtn.setTooltipText("Add sub-item");
        addBtn.addClickListener(e -> openItemFormDialog(item, null));

        Button editBtn = new Button(VaadinIcon.PENCIL.create());
        editBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        editBtn.setTooltipText("Edit");
        editBtn.addClickListener(e -> openItemFormDialog(null, item));

        Button deleteBtn = new Button(VaadinIcon.MINUS.create());
        deleteBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
        deleteBtn.setTooltipText("Delete");
        deleteBtn.addClickListener(e -> confirmDeleteItem(item));

        HorizontalLayout actions = new HorizontalLayout(addBtn, editBtn, deleteBtn);
        actions.setSpacing(false);
        actions.setWidthFull();
        actions.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        actions.setBoxSizing(BoxSizing.BORDER_BOX);
        actions.getStyle().set("gap", "2px");
        return actions;
    }

    private boolean isUnread(SpaceItem item) {
        if (!item.isLinked() || item.getVersion() == null) return false;
        int lastRead = readVersionByItemId.getOrDefault(item.getId(), 0);
        return item.getVersion() > lastRead;
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
                    loadItemsAndRebuildTree();
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

    // ── Space CRUD ───────────────────────────────────────────────────

    private void openSpaceFormDialog(Space existing) {
        if (currentConfig == null || currentConfig.getBaseUrl() == null) return;
        boolean editing = existing != null;

        Dialog dialog = newDialog();
        dialog.setHeaderTitle(editing ? "Edit space" : "New space");
        dialog.setWidth("420px");

        TextField nameField = new TextField("Name");
        nameField.setWidthFull();
        if (editing) nameField.setValue(existing.getName());

        TextField linkField = new TextField("Jira link (optional)");
        linkField.setWidthFull();
        linkField.setPlaceholder("https://yoursite.atlassian.net/browse/DEMO");
        linkField.setHelperText("Informational only — shown on the space, drives no automation.");
        if (editing && existing.getJiraLink() != null) linkField.setValue(existing.getJiraLink());

        Button cancelBtn = new Button("Cancel", e -> dialog.close());
        Button saveBtn = new Button(editing ? "Save" : "Create", e -> {
            String name = nameField.getValue() == null ? "" : nameField.getValue().trim();
            if (name.isEmpty()) {
                Notification.show("Name is required", 2500, Notification.Position.MIDDLE);
                return;
            }
            Space space = editing ? existing : new Space();
            space.setBaseUrl(currentConfig.getBaseUrl());
            space.setName(name);
            space.setJiraLink(blankToNull(linkField.getValue()));
            if (!editing) {
                space.setCreatedByUser(sessionUserService.getCurrentUser());
                space.setCreatedAt(Instant.now());
            }
            spaceRepo.save(space);
            dialog.close();
            selectedSpace = space;
            loadSpaces();
            Notification.show(editing ? "Updated" : "Created", 2000, Notification.Position.BOTTOM_END);
        });
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        dialog.add(nameField, linkField);
        dialog.getFooter().add(saveBtn, cancelBtn);
        dialog.open();
    }

    private void openManageSpacesDialog() {
        if (currentConfig == null || currentConfig.getBaseUrl() == null) return;

        Dialog dialog = newDialog();
        dialog.setHeaderTitle("Manage spaces");
        dialog.setWidth("480px");

        VerticalLayout list = new VerticalLayout();
        list.setPadding(false);
        list.setSpacing(false);
        list.getStyle().set("gap", "6px");
        renderSpaceRows(list);

        Button closeBtn = new Button("Close", e -> dialog.close());
        dialog.add(list);
        dialog.getFooter().add(closeBtn);
        dialog.open();
    }

    private void renderSpaceRows(VerticalLayout list) {
        list.removeAll();
        if (allSpaces.isEmpty()) {
            Span none = new Span("No spaces yet.");
            none.getStyle().set("color", "#6b778c").set("font-size", "12px").set("font-style", "italic");
            list.add(none);
            return;
        }
        for (Space space : allSpaces) {
            list.add(buildSpaceRow(list, space));
        }
    }

    private HorizontalLayout buildSpaceRow(VerticalLayout list, Space space) {
        Span text = new Span(space.getName());
        text.getStyle().set("font-size", "13px").set("color", "#172b4d").set("flex", "1");

        Button editBtn = new Button(VaadinIcon.PENCIL.create());
        editBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        editBtn.addClickListener(e -> openSpaceFormDialog(space));

        Button removeBtn = new Button(VaadinIcon.TRASH.create());
        removeBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
        removeBtn.addClickListener(e -> confirmDeleteSpace(space, () -> renderSpaceRows(list)));

        HorizontalLayout row = new HorizontalLayout(text, editBtn, removeBtn);
        row.setWidthFull();
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        row.getStyle()
                .set("padding", "6px 10px").set("border", "1px solid #dfe1e6")
                .set("border-radius", "4px").set("background", "#f8f9fa");
        return row;
    }

    private void confirmDeleteSpace(Space space, Runnable onDone) {
        int itemCount = spaceItemRepo.findBySpace(space).size();
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Delete space");
        confirm.setText("Delete \"" + space.getName() + "\""
                + (itemCount > 0 ? " and all " + itemCount + " item(s) in it" : "")
                + "? This also removes their Ticket Docs and Design Docs. This action cannot be undone.");
        confirm.setCancelable(true);
        confirm.setCancelText("Cancel");
        confirm.setConfirmText("Delete");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(e -> {
            for (SpaceItem root : spaceItemRepo.findBySpace_IdAndParentIsNull(space.getId())) {
                deleteItemCascade(root);
            }
            spaceRepo.delete(space);
            if (selectedSpace != null && selectedSpace.getId().equals(space.getId())) {
                selectedSpace = null;
            }
            onDone.run();
            loadSpaces();
            Notification.show("Deleted", 2000, Notification.Position.BOTTOM_END);
        });
        confirm.open();
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** All Spaces dialogs are explicit-close-only — outside click / Esc must not discard an in-progress form. */
    private Dialog newDialog() {
        Dialog dialog = new Dialog();
        dialog.setCloseOnOutsideClick(false);
        dialog.setCloseOnEsc(false);
        return dialog;
    }

    // ── Item CRUD ────────────────────────────────────────────────────

    /** {@code parent == null} means "root item"; {@code existing == null} means "create". */
    private void openItemFormDialog(SpaceItem parent, SpaceItem existing) {
        boolean editing = existing != null;
        Space space = editing ? existing.getSpace() : selectedSpace;
        if (space == null) return;

        Dialog dialog = newDialog();
        dialog.setHeaderTitle(editing ? "Edit item" : (parent != null ? "New sub-item" : "New item"));
        dialog.setWidth("480px");

        Span help = new Span("Link one specific Confluence page to auto-track its version and "
                + "update status here. Leave blank for a plain organizational item.");
        help.getStyle().set("font-size", "12px").set("color", "#6b778c").set("display", "block")
                .set("margin-bottom", "10px");

        TextField nameField = new TextField("Name");
        nameField.setWidthFull();
        if (editing) nameField.setValue(existing.getName());

        TextField linkField = new TextField("Confluence page link (optional)");
        linkField.setWidthFull();
        linkField.setPlaceholder("https://yoursite.atlassian.net/wiki/spaces/DOCS/pages/12345/Title");
        if (editing && existing.getUrl() != null) linkField.setValue(existing.getUrl());

        Button cancelBtn = new Button("Cancel", e -> dialog.close());
        Button saveBtn = new Button(editing ? "Save" : "Create", e -> {
            String name = nameField.getValue() == null ? "" : nameField.getValue().trim();
            if (name.isEmpty()) {
                Notification.show("Name is required", 2500, Notification.Position.MIDDLE);
                return;
            }
            String rawLink = linkField.getValue() == null ? "" : linkField.getValue().trim();
            ParsedLink link = null;
            if (!rawLink.isEmpty()) {
                link = ConfluenceLinkParser.parse(rawLink);
                if (link.kind() != Kind.PAGE) {
                    String message = switch (link.kind()) {
                        case UNSUPPORTED_FOLDER -> "That's a link to a Confluence \"Folder\", not a page — "
                                + "folders only group pages and aren't modeled by the Confluence API this app "
                                + "uses. Open the folder in Confluence, click the specific page you want to "
                                + "track, and paste that page's link instead.";
                        case SPACE -> "That's a link to a whole Confluence space, not one page — "
                                + "an item links to exactly one specific page. Open the page you want to "
                                + "track inside that space and paste its link instead.";
                        default -> "Not a recognized Confluence page link — paste the full URL from your "
                                + "browser's address bar (.../wiki/spaces/KEY/pages/12345/Title).";
                    };
                    Notification n = Notification.show(message, 6000, Notification.Position.MIDDLE);
                    n.addThemeVariants(NotificationVariant.LUMO_ERROR);
                    return;
                }
            }

            String previousPageId = editing ? existing.getConfluencePageId() : null;
            boolean linkChanged = !Objects.equals(previousPageId, link != null ? link.pageId() : null);

            SpaceItem item = editing ? existing : new SpaceItem();
            item.setName(name);
            if (!editing) {
                item.setSpace(space);
                item.setParent(parent);
                item.setSortOrder(nextSortOrder(space, parent));
                item.setCreatedByUser(sessionUserService.getCurrentUser());
                item.setCreatedAt(Instant.now());
            }
            if (link != null) {
                item.setConfluenceSpaceKey(link.spaceKey());
                item.setConfluencePageId(link.pageId());
            } else {
                item.setConfluenceSpaceKey(null);
                item.setConfluencePageId(null);
                item.setUrl(null);
                item.setVersion(null);
                item.setConfluenceUpdatedAt(null);
                item.setLastSyncedAt(null);
            }
            spaceItemRepo.save(item);
            dialog.close();
            loadItemsAndRebuildTree();
            Notification.show(editing ? "Updated" : "Created", 2000, Notification.Position.BOTTOM_END);

            if (link != null && linkChanged) {
                refreshLinkedItemOnce(item);
            }
        });
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        dialog.add(help, nameField, linkField);
        dialog.getFooter().add(saveBtn, cancelBtn);
        dialog.open();
    }

    private int nextSortOrder(Space space, SpaceItem parent) {
        List<SpaceItem> siblings = parent != null
                ? spaceItemRepo.findByParent_Id(parent.getId())
                : spaceItemRepo.findBySpace_IdAndParentIsNull(space.getId());
        return siblings.stream().mapToInt(SpaceItem::getSortOrder).max().orElse(-1) + 1;
    }

    /** Fetches the newly-linked page once, in the background, so the badge doesn't wait for the next scheduled sync. */
    private void refreshLinkedItemOnce(SpaceItem item) {
        if (currentConfig == null) return;
        UI ui = UI.getCurrent();
        JiraConfig cfg = currentConfig;
        Long itemId = item.getId();
        String pageId = item.getConfluencePageId();

        Runnable task = DelegatingSecurityContextRunnable.create(() -> {
            ConfluencePageDetail detail;
            try {
                detail = jiraService.getConfluencePageDetail(cfg, pageId);
            } catch (Exception ex) {
                detail = null;
            }
            ConfluencePageDetail finalDetail = detail;
            ui.access(() -> {
                if (finalDetail == null) return;
                spaceItemRepo.findById(itemId).ifPresent(fresh -> {
                    fresh.setVersion(finalDetail.version());
                    fresh.setConfluenceUpdatedAt(finalDetail.updatedAt());
                    fresh.setLastSyncedAt(Instant.now());
                    fresh.setUrl(cfg.getBaseUrl() + "/wiki/spaces/" + fresh.getConfluenceSpaceKey()
                            + "/pages/" + fresh.getConfluencePageId());
                    spaceItemRepo.save(fresh);
                    loadItemsAndRebuildTree();
                    if (selectedItem != null && selectedItem.getId().equals(itemId)) {
                        showDetail(fresh);
                    }
                });
            });
        }, SecurityContextHolder.getContext());
        Thread.ofVirtual().start(task);
    }

    private void confirmDeleteItem(SpaceItem item) {
        int descendants = countDescendants(item);
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Delete item");
        confirm.setText("Delete \"" + item.getName() + "\""
                + (descendants > 0 ? " and its " + descendants + " sub-item(s)" : "")
                + "? This also removes their Ticket Docs and Design Docs. This action cannot be undone.");
        confirm.setCancelable(true);
        confirm.setCancelText("Cancel");
        confirm.setConfirmText("Delete");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(e -> {
            boolean wasSelected = selectedItem != null && selectedItem.getId().equals(item.getId());
            deleteItemCascade(item);
            if (wasSelected) showPlaceholder();
            loadItemsAndRebuildTree();
            Notification.show("Deleted", 2000, Notification.Position.BOTTOM_END);
        });
        confirm.open();
    }

    private int countDescendants(SpaceItem item) {
        int count = 0;
        for (SpaceItem child : spaceItemRepo.findByParent_Id(item.getId())) {
            count += 1 + countDescendants(child);
        }
        return count;
    }

    /** Deletes an item's entire subtree bottom-up (children before parents, for FK safety), along with each item's Ticket Doc and Design Doc rows/files. */
    private void deleteItemCascade(SpaceItem item) {
        for (SpaceItem child : spaceItemRepo.findByParent_Id(item.getId())) {
            deleteItemCascade(child);
        }
        historyRepo.findByItemOrderByDetectedAtDesc(item).forEach(historyRepo::delete);
        readStatusRepo.findByItem(item).forEach(readStatusRepo::delete);
        ticketDocRepo.findByFeature(item).ifPresent(doc -> {
            ticketDocItemRepo.findByTicketDoc(doc).forEach(ticketDocItemRepo::delete);
            ticketDocRepo.delete(doc);
        });
        designDocRepo.findByFeatureOrderByVersionDesc(item).forEach(designDocRepo::delete);
        deleteDirectoryQuietly(Path.of("data", "feature-tickets", String.valueOf(item.getId())));
        deleteDirectoryQuietly(Path.of("data", "feature-design-docs", String.valueOf(item.getId())));
        spaceItemRepo.delete(item);
    }

    private void deleteDirectoryQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best-effort cleanup — a leftover file on disk isn't worth failing the delete
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    // ── Drag & drop reordering/reparenting ──────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleDrop(GridDropEvent<SpaceItem> event) {
        SpaceItem dragged = draggedItem;
        draggedItem = null;
        if (dragged == null) return;

        SpaceItem target = event.getDropTargetItem().orElse(null);
        if (target == null) return;

        TreeDataProvider<SpaceItem> provider = (TreeDataProvider<SpaceItem>) tree.getDataProvider();
        TreeData<SpaceItem> treeData = provider.getTreeData();

        if (isSelfOrDescendant(treeData, dragged, target)) {
            Notification.show("Can't move an item into itself", 2500, Notification.Position.MIDDLE);
            return;
        }

        SpaceItem oldParent = treeData.getParent(dragged);
        GridDropLocation location = event.getDropLocation();
        SpaceItem newParent = location == GridDropLocation.ON_TOP ? target : treeData.getParent(target);

        treeData.setParent(dragged, newParent);
        if (location == GridDropLocation.ABOVE) {
            treeData.moveAfterSibling(dragged, siblingBefore(treeData, newParent, target));
        } else if (location == GridDropLocation.BELOW) {
            treeData.moveAfterSibling(dragged, target);
        }
        // ON_TOP: setParent already appended dragged as the last child of target.

        provider.refreshAll();

        dragged.setParent(newParent);
        List<SpaceItem> toSave = new ArrayList<>(renumbered(treeData.getChildren(newParent)));
        if (!Objects.equals(idOf(oldParent), idOf(newParent))) {
            toSave.addAll(renumbered(treeData.getChildren(oldParent)));
        }
        spaceItemRepo.saveAll(toSave);
    }

    /** True if {@code candidate} is {@code subject} itself or lies within its subtree — dropping there would create a cycle. */
    private boolean isSelfOrDescendant(TreeData<SpaceItem> treeData, SpaceItem subject, SpaceItem candidate) {
        SpaceItem p = candidate;
        while (p != null) {
            if (p.getId().equals(subject.getId())) return true;
            p = treeData.getParent(p);
        }
        return false;
    }

    private SpaceItem siblingBefore(TreeData<SpaceItem> treeData, SpaceItem parent, SpaceItem target) {
        List<SpaceItem> children = treeData.getChildren(parent);
        int idx = children.indexOf(target);
        return idx > 0 ? children.get(idx - 1) : null;
    }

    private List<SpaceItem> renumbered(List<SpaceItem> children) {
        for (int i = 0; i < children.size(); i++) children.get(i).setSortOrder(i);
        return children;
    }

    private Long idOf(SpaceItem item) {
        return item != null ? item.getId() : null;
    }

    // ── Data loading (local DB reads — fast, no async needed) ──────────

    private void loadSpaces() {
        if (currentConfig == null || currentConfig.getBaseUrl() == null) {
            allSpaces = List.of();
            selectedSpace = null;
            renderSpacesList();
            loadItemsAndRebuildTree();
            return;
        }
        allSpaces = spaceRepo.findByBaseUrlOrderByNameAsc(currentConfig.getBaseUrl());
        Space stillValid = selectedSpace == null ? null : allSpaces.stream()
                .filter(s -> s.getId().equals(selectedSpace.getId())).findFirst().orElse(null);
        selectedSpace = stillValid != null ? stillValid : (allSpaces.isEmpty() ? null : allSpaces.get(0));

        renderSpacesList();
        loadItemsAndRebuildTree();
    }

    private void loadItemsAndRebuildTree() {
        AppUser user = sessionUserService.getCurrentUser();
        allItems = selectedSpace != null ? spaceItemRepo.findBySpace(selectedSpace) : List.of();

        readVersionByItemId = new HashMap<>();
        if (user != null) {
            for (SpaceItemReadStatus rs : readStatusRepo.findByUser(user)) {
                readVersionByItemId.put(rs.getItem().getId(), rs.getLastReadVersion());
            }
        }

        newItemBtn.setEnabled(selectedSpace != null);
        if (allSpaces.isEmpty()) {
            countLabel.setText("No spaces yet — click \"New space\" to create one");
        } else if (selectedSpace == null) {
            countLabel.setText("");
        } else if (allItems.isEmpty()) {
            countLabel.setText("No items yet — click \"New item\" to add one");
        } else {
            countLabel.setText(allItems.size() + " item" + (allItems.size() == 1 ? "" : "s"));
        }

        applyTreeFilter(filterField.getValue());
        showPlaceholder();
    }

    /** Chip row letting the user pick which Space the tree/filter below should scope to. */
    private void renderSpacesList() {
        spacesListPanel.removeAll();
        if (allSpaces.isEmpty()) {
            spacesListPanel.setVisible(false);
            return;
        }
        spacesListPanel.setVisible(true);
        for (Space space : allSpaces) {
            boolean selected = selectedSpace != null && space.getId().equals(selectedSpace.getId());

            Span chip = new Span(space.getName());
            chip.getStyle()
                    .set("padding", "4px 12px")
                    .set("border-radius", "14px")
                    .set("font-size", "12px")
                    .set("font-weight", "600")
                    .set("cursor", "pointer")
                    .set("white-space", "nowrap")
                    .set("background", selected ? "#0052cc" : "#f4f5f7")
                    .set("color", selected ? "white" : "#42526e");
            chip.addClickListener(e -> selectSpace(space));
            spacesListPanel.add(chip);
        }
    }

    private void selectSpace(Space space) {
        if (selectedSpace != null && selectedSpace.getId().equals(space.getId())) return;
        selectedSpace = space;
        renderSpacesList();
        loadItemsAndRebuildTree();
    }

    /**
     * Applies the "Filter by name" text to the selected space's items. Blank shows the full
     * hierarchy; non-blank switches to a flat list of matches — a real hierarchical filter would
     * hide a matching item whose ancestor's name doesn't also match (the tree can only reach a
     * node by first expanding its parent), so flattening is what actually lets you find an item
     * buried several levels deep. Drag & drop is disabled while filtering, since the flattened
     * view doesn't reflect the real parent/child structure.
     */
    private void applyTreeFilter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase();
        boolean filtering = !q.isEmpty();
        tree.setRowsDraggable(!filtering);

        if (!filtering) {
            tree.setDataProvider(new TreeDataProvider<>(buildTreeData(allItems)));
            tree.expandRecursively(allItems, 1);
            return;
        }
        List<SpaceItem> matches = allItems.stream()
                .filter(i -> i.getName() != null && i.getName().toLowerCase().contains(q))
                .toList();
        TreeData<SpaceItem> flat = new TreeData<>();
        for (SpaceItem i : matches) flat.addItem(null, i);
        tree.setDataProvider(new TreeDataProvider<>(flat));
    }

    /** Builds a TreeData from a flat list using each item's {@code parent}, sorted by {@code sortOrder}. */
    private TreeData<SpaceItem> buildTreeData(List<SpaceItem> all) {
        TreeData<SpaceItem> treeData = new TreeData<>();
        Map<Long, SpaceItem> byId = all.stream().collect(Collectors.toMap(SpaceItem::getId, i -> i));

        List<SpaceItem> remaining = new ArrayList<>(all);
        remaining.sort(Comparator.comparingInt(SpaceItem::getSortOrder));

        Set<Long> added = new HashSet<>();
        boolean progress = true;
        while (!remaining.isEmpty() && progress) {
            progress = false;
            Iterator<SpaceItem> it = remaining.iterator();
            while (it.hasNext()) {
                SpaceItem item = it.next();
                Long parentId = item.getParent() != null ? item.getParent().getId() : null;
                SpaceItem parent = parentId != null ? byId.get(parentId) : null;
                boolean parentReady = parent == null || added.contains(parent.getId());
                if (parentReady) {
                    treeData.addItem(parent, item);
                    added.add(item.getId());
                    it.remove();
                    progress = true;
                }
            }
        }
        // Leftovers (e.g. a parent-of-a-parent cycle, which shouldn't happen in practice) —
        // surface them as roots rather than silently dropping data.
        for (SpaceItem leftover : remaining) treeData.addItem(null, leftover);
        return treeData;
    }

    // ── Detail panel: top (item info) + bottom (ticket docs) ────────

    private void showPlaceholder() {
        selectedItem = null;
        selectedDoc = null;
        detailPanel.removeAll();
        detailPanel.setPadding(true);
        detailPanel.setAlignItems(Alignment.CENTER);
        detailPanel.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
        Span icon = new Span("🗂");
        icon.getStyle().set("font-size", "40px").set("margin-bottom", "12px");
        Span msg = new Span("Select an item to view its details and ticket docs");
        msg.getStyle().set("color", "#6b778c").set("font-size", "14px").set("font-style", "italic");
        detailPanel.add(icon, msg);
    }

    private void showDetail(SpaceItem item) {
        selectedItem = item;
        selectedDoc = ticketDocRepo.findByFeature(item).orElse(null);

        detailPanel.removeAll();
        detailPanel.setPadding(false);
        detailPanel.setAlignItems(Alignment.STRETCH);
        detailPanel.setJustifyContentMode(FlexComponent.JustifyContentMode.START);

        renderItemInfo(item);
        renderTicketDocsPanel();

        SplitLayout innerSplit = new SplitLayout(topDetailPanel, ticketDocsPanel);
        innerSplit.setOrientation(SplitLayout.Orientation.VERTICAL);
        innerSplit.setSizeFull();
        innerSplit.setSplitterPosition(50);

        detailPanel.add(innerSplit);
        detailPanel.setFlexGrow(1, innerSplit);
    }

    private void renderItemInfo(SpaceItem item) {
        topDetailPanel.removeAll();
        topDetailPanel.setPadding(true);
        topDetailPanel.setSpacing(false);
        topDetailPanel.getStyle().set("overflow-y", "auto");

        Component titleComp;
        if (item.getUrl() != null) {
            Anchor titleLink = new Anchor(item.getUrl(), item.getName());
            titleLink.setTarget("_blank");
            titleLink.getStyle()
                    .set("font-weight", "700").set("font-size", "15px")
                    .set("color", "#0052cc").set("text-decoration", "none");
            titleComp = titleLink;
        } else {
            Span titleSpan = new Span(item.getName());
            titleSpan.getStyle().set("font-weight", "700").set("font-size", "15px").set("color", "#172b4d");
            titleComp = titleSpan;
        }

        // item.getSpace() is a lazy JPA relation and this runs outside an open Hibernate session
        // (Vaadin's own servlet, not routed through Spring MVC's OpenEntityManagerInViewInterceptor) —
        // use the already-loaded selectedSpace (every item in the tree belongs to it) instead of
        // triggering a lazy load, which would throw LazyInitializationException.
        Span meta = new Span((selectedSpace != null ? selectedSpace.getName() : "")
                + (item.isLinked() ? " · v" + item.getVersion() : " · not linked to Confluence"));
        meta.getStyle().set("color", "#6b778c").set("font-size", "12px").set("display", "block")
                .set("margin", "4px 0 16px 0");

        topDetailPanel.add(titleComp, meta);

        if (item.isLinked()) {
            Hr divider = new Hr();
            divider.getStyle().set("margin", "0 0 16px 0").set("border-color", "#dfe1e6");

            H5 historyTitle = new H5("Update History");
            historyTitle.getStyle().set("margin", "0 0 8px 0").set("color", "#172b4d").set("font-size", "13px");

            VerticalLayout historyList = new VerticalLayout();
            historyList.setPadding(false);
            historyList.setSpacing(false);
            historyList.getStyle().set("gap", "6px");

            List<SpaceItemUpdateHistory> history = historyRepo.findByItemOrderByDetectedAtDesc(item);
            if (history.isEmpty()) {
                Span none = new Span("No updates detected since this page was linked.");
                none.getStyle().set("color", "#6b778c").set("font-size", "12px").set("font-style", "italic");
                historyList.add(none);
            } else {
                for (SpaceItemUpdateHistory h : history) {
                    Span line = new Span("v" + h.getOldVersion() + " → v" + h.getNewVersion()
                            + "  ·  updated " + DATE_FMT.format(h.getNewUpdatedAt())
                            + "  ·  detected " + DATE_FMT.format(h.getDetectedAt()));
                    line.getStyle().set("font-size", "12px").set("color", "#172b4d")
                            .set("padding", "6px 8px").set("background", "#f4f5f7").set("border-radius", "4px");
                    historyList.add(line);
                }
            }

            boolean unread = isUnread(item);
            Button markReadBtn = new Button(unread ? "Mark as read" : "Already read");
            markReadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
            markReadBtn.setEnabled(unread);
            markReadBtn.getStyle().set("margin-top", "20px");
            markReadBtn.addClickListener(e -> markRead(item));

            topDetailPanel.add(divider, historyTitle, historyList, markReadBtn);
        }

        Hr docDivider = new Hr();
        docDivider.getStyle().set("margin", "20px 0 16px 0").set("border-color", "#dfe1e6");
        topDetailPanel.add(docDivider, buildDesignDocSection(item));
    }

    // ── Design Doc (externally-generated HTML review/plan doc, uploaded per item) ────
    // Every upload is a new version — nothing is overwritten, so past versions stay viewable
    // for tracing/comparison later. The highest version number is "latest".

    private VerticalLayout buildDesignDocSection(SpaceItem item) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(false);
        section.getStyle().set("gap", "6px");

        H5 title = new H5("Design Doc");
        title.getStyle().set("margin", "0").set("color", "#172b4d").set("font-size", "13px");

        Span help = new Span("Upload the HTML review doc generated externally from this item's "
                + "Ticket Docs + Confluence content, then open it here for dev review. Each upload "
                + "is kept as a new version — older ones stay available below.");
        help.getStyle().set("font-size", "12px").set("color", "#6b778c").set("display", "block");

        section.add(title, help);

        List<FeatureDesignDoc> versions = designDocRepo.findByFeatureOrderByVersionDesc(item);
        FeatureDesignDoc latest = versions.isEmpty() ? null : versions.get(0);

        if (latest != null) {
            Anchor viewLink = new Anchor("/design-docs/" + item.getId(),
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
                Anchor oldLink = new Anchor("/design-docs/" + item.getId() + "/" + old.getVersion(),
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
                Path saved = designDocStorage.save(item.getId(), nextVersion, event.getFileName(), in);

                FeatureDesignDoc newVersion = new FeatureDesignDoc();
                newVersion.setFeature(item);
                newVersion.setVersion(nextVersion);
                newVersion.setFileName(event.getFileName());
                newVersion.setFilePath(saved.toString());
                newVersion.setUploadedAt(Instant.now());
                newVersion.setUploadedByUser(sessionUserService.getCurrentUser());
                designDocRepo.save(newVersion);

                Notification.show("Uploaded as v" + nextVersion, 2500, Notification.Position.BOTTOM_END);
                renderItemInfo(item);
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

    private void markRead(SpaceItem item) {
        AppUser user = sessionUserService.getCurrentUser();
        if (user == null || item.getVersion() == null) return;

        SpaceItemReadStatus status = readStatusRepo.findByItemAndUser(item, user).orElseGet(SpaceItemReadStatus::new);
        status.setItem(item);
        status.setUser(user);
        status.setLastReadVersion(item.getVersion());
        status.setReadAt(Instant.now());
        readStatusRepo.save(status);

        readVersionByItemId.put(item.getId(), item.getVersion());
        tree.getDataProvider().refreshItem(item);
        renderItemInfo(item);
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
        itemsGrid.addComponentColumn(i -> {
            String baseUrl = currentConfig != null ? currentConfig.getBaseUrl() : null;
            if (baseUrl == null) {
                return new Span(i.getTicketKey());
            }
            Anchor keyLink = new Anchor(baseUrl + "/browse/" + i.getTicketKey(), i.getTicketKey());
            keyLink.setTarget("_blank");
            keyLink.getStyle()
                    .set("font-weight", "600")
                    .set("color", "#0052cc").set("text-decoration", "none");
            return keyLink;
        }).setHeader("Ticket").setFlexGrow(1).setAutoWidth(false);
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
            removeBtn.addClickListener(e -> removeTicketItem(i));
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
        Dialog dialog = newDialog();
        dialog.setHeaderTitle("Add tickets to " + selectedItem.getName());
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
        SpaceItem item = selectedItem;
        AppUser user = sessionUserService.getCurrentUser();

        Runnable task = DelegatingSecurityContextRunnable.create(() -> {
            try {
                TicketDoc doc = ticketDocRepo.findByFeature(item).orElseGet(() -> {
                    TicketDoc d = new TicketDoc();
                    d.setFeature(item);
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
                    TicketDocItem docItem = new TicketDocItem();
                    docItem.setTicketDoc(doc);
                    docItem.setTicketKey(key);
                    docItem.setLastKnownUpdated(ticket.getUpdatedInstant());
                    docItem.setAddedAt(Instant.now());
                    docItem.setAddedByUser(user);
                    ticketDocItemRepo.save(docItem);
                    added++;
                }

                if (added > 0) regenerateDoc(doc, item);
                int finalAdded = added;

                ui.access(() -> {
                    loadingBar.setVisible(false);
                    selectedDoc = ticketDocRepo.findByFeature(item).orElse(null);
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
        if (selectedDoc == null || selectedItem == null) return;
        loadingBar.setVisible(true);
        UI ui = UI.getCurrent();
        TicketDoc doc = selectedDoc;
        SpaceItem item = selectedItem;

        Runnable task = DelegatingSecurityContextRunnable.create(() -> {
            try {
                regenerateDoc(doc, item);
                ui.access(() -> {
                    loadingBar.setVisible(false);
                    selectedDoc = ticketDocRepo.findByFeature(item).orElse(null);
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
    private void regenerateDoc(TicketDoc doc, SpaceItem item) throws IOException {
        List<TicketDocItem> items = ticketDocItemRepo.findByTicketDoc(doc);
        List<String> keys = items.stream().map(TicketDocItem::getTicketKey).toList();
        List<JiraTicket> tickets = jiraService.getTicketsByKeys(currentConfig, keys);
        Map<String, JiraTicket> byKey = tickets.stream()
                .collect(Collectors.toMap(JiraTicket::getKey, t -> t, (a, b) -> a));

        Path path = markdownService.generate(item, tickets);

        Instant now = Instant.now();
        for (TicketDocItem docItem : items) {
            JiraTicket fresh = byKey.get(docItem.getTicketKey());
            docItem.setLastKnownUpdated(fresh != null ? fresh.getUpdatedInstant() : docItem.getLastKnownUpdated());
            docItem.setNeedsRegenerate(false);
            docItem.setNotifiedAt(null);
            ticketDocItemRepo.save(docItem);
        }

        doc.setFilePath(path.toString());
        doc.setGeneratedAt(now);
        doc.setGeneratedByUser(sessionUserService.getCurrentUser());
        ticketDocRepo.save(doc);
    }

    private void removeTicketItem(TicketDocItem docItem) {
        // Use the view's own selectedDoc (loaded fresh via a direct repository query) rather than
        // docItem.getTicketDoc() — docItem is a stale Grid row from an earlier request/session, so
        // its lazy TicketDoc association is an uninitialized proxy that would throw
        // LazyInitializationException the moment any setter runs on it inside regenerateDoc().
        TicketDoc doc = selectedDoc;
        ticketDocItemRepo.delete(docItem);
        try {
            List<TicketDocItem> remaining = ticketDocItemRepo.findByTicketDoc(doc);
            if (!remaining.isEmpty()) {
                regenerateDoc(doc, selectedItem);
            }
        } catch (Exception ex) {
            Notification n = Notification.show("Removed, but regeneration failed: " + ex.getMessage(),
                    4000, Notification.Position.BOTTOM_CENTER);
            n.addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
        selectedDoc = ticketDocRepo.findByFeature(selectedItem).orElse(null);
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
