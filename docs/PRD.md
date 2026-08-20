# Product Requirements Document — Jira Manager v2

**Stack:** Vaadin Flow 24.6.6 · Spring Boot 3.4.3 · Spring Security · Spring Data JPA · H2 (file-based)
**Last updated:** 2026-07-14 (Spaces: read-only Confluence knowledge-base sync incl. embedded Ticket Docs, title filter, 4h background job)

---

## 1. Overview

Jira Manager v2 is a multi-user web application that lets each user connect their own Jira Cloud account, view their assigned tickets, inspect daily worklogs on a Gantt-style timeline, and browse the Confluence spaces tied to their projects — including generating downloadable ticket-content docs — all read-only against Jira/Confluence.
Access is role-gated: **ADMIN** users manage the user base; **USER** accounts use the Jira features.

**Deployment model**: runs locally (single Spring Boot process, file-based H2 DB — see § 12) for a small team, not deployed as an internet-facing multi-tenant SaaS product. Its purpose is to fill specific gaps in what Jira/Confluence's own UI provides (e.g. a Gantt-style daily worklog view, cross-teammate worklog visibility, a Confluence knowledge-base tree scoped to a user's own projects, per-feature ticket/design-doc aggregation) — not to replace Jira itself. This context is why some trade-offs in § 15 (e.g. plaintext API token storage, no horizontal scaling) are acceptable for now rather than oversights.

---

## 2. Authentication & Security

### 2.1 Local (form) login
- Email + password login via Spring Security form login.
- Passwords stored as BCrypt hashes.
- OAuth2-only accounts block form login gracefully.

### 2.2 OAuth2 login (prepared, currently disabled)
- Infrastructure for Google / GitHub / Facebook login exists (`CustomOAuth2UserService`, `AuthProvider` entity).
- Three scenarios handled: (1) provider already linked → login, (2) email exists → account linking prompt, (3) new email → auto-create + link.
- Re-enable by uncommenting `spring-boot-starter-oauth2-client` in `pom.xml` and credentials in `application.properties`.

### 2.3 Logout
- Server-side logout via `SecurityContextLogoutHandler` (invalidates HTTP session, clears `SecurityContext`).
- UI reference is captured **before** session invalidation to avoid `NullPointerException`.
- Redirects to `/login` after logout.

### 2.4 Post-login routing
- Route `""` → `RootView` (no layout, `@PermitAll`).
- `RootView.beforeEnter()` reads role from `SecurityContextHolder` and forwards:
  - `ADMIN` → `UserManagementView` (`/users`)
  - `USER`  → `DashboardView` (`/dashboard`)
- Uses `event.forwardTo(Class)` (compile-time safe).

### 2.5 Request cache
- Custom `HttpSessionRequestCache` excludes `/logout` from saved requests to prevent the post-login → `/logout` redirect loop.

---

## 3. Role System

| Role  | Default | Who assigns | Accessible features |
|-------|---------|-------------|---------------------|
| ADMIN | No      | Admin only  | User Management, My Profile |
| USER  | Yes     | Registration / Admin | Dashboard, My Tickets, Worklog, Worklog Calendar, Spaces (incl. Ticket Docs), Reports (stub), Settings, My Profile |

- Role is stored in `app_users.role` (VARCHAR 20, DB default `'USER'`).
- Spring Security authority: `ROLE_ADMIN` / `ROLE_USER`.
- Vaadin views protected via `@RolesAllowed("ADMIN")` / `@RolesAllowed("USER")` / `@PermitAll`.

---

## 4. Default Admin Account

- Created automatically on first startup by `DataInitializer` (`ApplicationRunner`).
- Email: `admin@localhost.com` · Password: `123456`
- No-op if the account already exists (safe to restart).

---

## 5. User Management (ADMIN only — `/users`)

- Grid: Full name, Email, Phone, **Role** badge (ADMIN = amber, USER = blue), Status badge (Active / Disabled), Created at.
- **Add user** dialog: First name*, Last name, Email*, Password* (≥8 chars), Phone, Role (`USER`/`ADMIN` combo), Active checkbox.
- **Edit user** dialog: same fields minus password; role is editable.
- **Delete user** with `ConfirmDialog`.
- `UserService.registerLocal(email, pass, firstName, lastName, phone, role)` — admin overload.
- `UserService.updateUser(id, firstName, lastName, email, phone, enabled, role)` — role-aware overload.
- `EmailAlreadyExistsException` surfaced in-dialog.

---

## 6. My Profile (`/profile` — all roles)

- **Personal information card:** First name*, Last name, Phone (editable). Email shown read-only. Role shown as badge.
- **Change password card:** Current password, New password (≥8 chars), Confirm. Only shown for local accounts (hidden for OAuth2-only).
- Password re-hashed with BCrypt on save.

---

## 7. Settings — Jira Configuration (`/settings` — USER only)

- Per-user Jira connection stored in `jira_configs` table (one row per user, `OneToOne`).
- Fields: **Base URL** (e.g. `https://company.atlassian.net`), **Jira Email**, **API Token**.
- **Test Connection** button — saves config then calls `JiraService.getMyTickets()`; shows success / error status inline.
- **Save** button — upserts `JiraConfig` for the current user.
- Link to Atlassian API token generation page.
- When navigated to from a Jira feature (My Tickets, Worklog) → shows blue banner: _"Jira connection required. Configure your credentials below…"_ via `?jira_required=true` query param.
- Jira credentials are **not** in `application.properties` (removed).

### Jira config guard
- `JiraService.isConfigured()` — safe check, never throws.
- `MainView` and `WorklogView` implement `BeforeEnterObserver`:
  - Not configured → `event.forwardTo("settings?jira_required=true")`
  - Configured → load data normally
- Data loading calls (`loadTickets()`, `loadWorklogs()`) are **deferred to `beforeEnter()`**, not called in the constructor, to avoid hitting Jira before the guard runs.

---

## 8. Dashboard (`/dashboard` — USER only)

- **Greeting header**: "Welcome back, {name}!"
- **4 stat cards**: Total Assigned, In Progress, Completed, Blocked — counts derived from `JiraService.getMyTickets()`.
- **Jira not configured banner**: yellow warning with link to Settings (shown when Jira is unconfigured; stats show as 0).
- **Quick Access cards**: My Tickets, Worklog (active), Reports (stub/disabled).

---

## 9. My Tickets (`/tickets` — USER only)

- SplitLayout: 62 % grid / 38 % detail panel.

### 9.1 Filters (toolbar)
| Filter | Component | Behaviour |
|--------|-----------|-----------|
| Search | `TextField` | Matches key or summary, debounced 300 ms |
| Status | `MultiSelectComboBox` | AND filter across selected values |
| Project | `ComboBox` (single) | Exact match |
| Priority | `MultiSelectComboBox` | AND filter |
| Sprint | `MultiSelectComboBox` | AND filter |
| Work Type | `MultiSelectComboBox` | Filters by `issueType` (Bug, Story, Task, Epic, …) |
| Has remaining time | `Checkbox` | When checked, hides tickets where `remainingEstimateSeconds == 0` |

All filter options are populated from the loaded ticket list (distinct, sorted). Ticket count label ("N tickets") updates on every filter change.

### 9.2 Grid columns
- **Key** — `[issue-type icon] KEY-123` clickable link (opens Jira in new tab). Column 140 px.
- **Summary** — truncated, tooltip on hover.
- **Original**, **Logged**, **Remaining** — time-tracking columns (right-aligned, 90 px each), sourced from the same `timetracking` snapshot as the rest of the row (e.g. `"4h"`, `"2h 30m"`, `"—"` when blank). Immediately follow Summary.
- **Status** — colour-coded badge.
- **Priority** — colour-coded badge.
- **Project**, **Updated**, **Due date**.

### 9.3 Issue-type icon
Jira-style 16 × 16 px coloured rounded square (border-radius 3 px) with a white VaadinIcon inside.

| Issue type | Icon | Background |
|------------|------|------------|
| Bug | `BUG` | `#e5493a` red |
| Epic | `BOLT` | `#904ee2` purple |
| Story | `BOOKMARK` | `#63ba3c` green |
| Task | `CHECK` | `#4bade8` blue |
| Sub-task | `ARROW_RIGHT` | `#4bade8` blue |
| Improvement | `ARROW_UP` | `#4bade8` blue |
| New Feature | `STAR` | `#63ba3c` green |
| Test | `FLASK` | `#f79232` orange |
| Question / Support | `QUESTION` | `#4bade8` blue |
| Change / Request | `EXCHANGE` | `#4bade8` blue |
| Risk | `WARNING` | `#f79232` orange |
| *(fallback)* | `FILE_O` | `#8993a4` grey |

Icon has a `title` attribute (native browser tooltip) set to the raw issue-type string.

### 9.4 Detail panel
Shown on row select; placeholder when nothing is selected. Sections:

**Header**: `[issue-type icon 18 px]  KEY-123` link → summary paragraph → status + priority badges.

**Fields**: Project · Type (`[icon 14 px] Bug`) · Assignee · Reporter · Sprint · Created · Updated · Due date (if set).

**Time Tracking section** (new):
- Rows: Original Estimate · Logged · Remaining.
- `ProgressBar` (0–100 %) when `originalEstimateSeconds > 0`:
  - Blue `#0052cc` ≤ 74 %
  - Orange `#ff8b00` 75–99 %
  - Red `#de350b` ≥ 100 % (over-estimate)
- Percentage label "X% logged" below the bar.

**Actions**: "Open in Jira ↗" primary button.

### 9.5 Data loading
- `JiraService.getMyTickets()` → `POST /rest/api/3/search/jql` with `assignee = currentUser()`, `maxResults: 50`.
- Fields requested: `summary, status, priority, project, issuetype, assignee, reporter, created, updated, duedate, customfield_10020 (sprint), timetracking`.
- Loading `ProgressBar` (3 px, indeterminate) shown during fetch.

---

## 10. Worklog (`/worklog` — USER only)

### 10.1 Gantt chart
- Custom pure-HTML/CSS Gantt chart (no external library).
- Timeline: 0–23 h, 5-minute granularity.
- Constants: `HOUR_PX=90`, `LABEL_PX=220`, `ROW_H=52`, `BAR_H=26`, `MIN_PX=1.5`.
- CSS repeating-linear-gradient: strong line per hour + faint line per 5 min.
- Date picker (default = today); user can browse any date.
- **Member filter** (`ComboBox<JiraUser>`) next to the date picker — lets the user view a teammate's logged time. Populated from `JiraService.searchAssignableUsers()`, which reads the **DB-cached** user list (§ 12, § 13.1) rather than calling Jira live — defaults to the logged-in user (`JiraService.getCurrentJiraUser()`, one lightweight live `/myself` call). Changing date or member reloads the Gantt chart for that member's worklogs via `JiraService.getWorklogsForDate(date, accountId)`.

### 10.2 Ticket column (frozen)
- Two-line label: **`[issue-type icon 14 px]  key`** (bold, blue) + summary (truncated, gray).
- Issue-type icon uses the same colour/icon mapping as My Tickets (§ 9.3).
- `position: sticky; left: 0` — stays visible on horizontal scroll.
- Total row: "Total: Xh Ym" label inside the sticky cell.

### 10.3 "Now" indicator (today only)
- Red dot in timeline header + red vertical line in each row at current time.
- Updated at render time.

### 10.4 Auto-scroll
- On load, scrolls to first worklog start − 30 min (or 8:30 AM if no worklogs).
- Implemented via `element.executeJs("setTimeout(fn, 80)")` after DOM render.

### 10.5 Bar interaction
- Click bar → highlight (box-shadow + brightness filter) + populate detail panel.
- **Detail panel header**: `[issue-type icon 18 px]  KEY-123` link → summary → status + priority badges.
- **Ticket Info section**: Project · Type (`[icon 13 px] Bug`) · Assignee · Reporter · Sprint.
- **Worklog Entry section**: Date · Time range · Duration · Logged by.
- **Time Tracking section**: Original estimate · Time spent (total) · Remaining + `ProgressBar` (over-estimate → `--lumo-primary-color: #de350b`).

### 10.6 Overlap detection
- Implemented in `WorklogOverlapDetector` (pure utility, no Spring dependency).
- Overlap condition: `A.startTime < B.endTime AND A.endTime > B.startTime` (strict — touching boundaries are **not** overlaps).
- Detection runs every time worklogs are loaded (`renderGanttChart`).
- **Overlapping bars** receive three visual cues:
  1. Diagonal-stripe amber overlay (`repeating-linear-gradient(-45deg, …)`) on top of the status-based bar color.
  2. Orange `outline: 2px solid #ff8b00` on the bar border.
  3. `⚠` icon at the left of the bar label; tooltip prefixed with `⚠ TIME OVERLAP`.
- **Chart banner**: orange-left-border panel above the Gantt table — _"X worklog entries have overlapping times…"_ — shown whenever at least one overlap exists.
- **Detail panel warning**: when a conflicting bar is clicked, an amber box at the top of the detail panel lists every overlapping partner (ticket key + time range + duration) and instructs the user to correct the entries in Jira.
- Public API:
  - `findOverlappingIds(List<WorklogEntry>)` → `Set<String>` of overlapping worklog IDs.
  - `overlaps(WorklogEntry a, WorklogEntry b)` → boolean pair check.
  - `findOverlapPartners(WorklogEntry target, List<WorklogEntry> all)` → partners for a specific entry.

### 10.7 Tickets to Log panel
- Bottom pane of the left-side vertical `SplitLayout` (Gantt chart above, this panel below).
- Lists the **logged-in user's own** assigned tickets (`JiraService.getMyTickets()`, loaded once per page visit) that either have no worklog yet for the selected date or still have `remainingEstimateSeconds > 0`. Tickets in `Passed QA`, `Deployed (Prod)`, `Won't Do` are excluded.
- Always scoped to the logged-in user regardless of the member filter (§ 10.1) — when viewing a teammate's Gantt chart, their entries are not read as "logged today" against the current user's own ticket list.
- Clicking a row calls `JiraService.getTicketByKey(key)` to refetch the ticket **live** before rendering the detail panel, so the Time Tracking figures (Original / Logged / Remaining) reflect Jira's current state rather than the snapshot taken when the page loaded. Falls back to the cached ticket + an error notification if the refetch fails.

### 10.8 Jira API
- JQL (own worklogs): `worklogDate = "YYYY-MM-DD" AND worklogAuthor = currentUser()`
- JQL (member filter): `worklogDate = "YYYY-MM-DD" AND worklogAuthor = "<accountId>"` — `JiraService.getWorklogsForDate(date, accountId)` overload.
- Per-issue: `GET /rest/api/3/issue/{key}/worklog` filtered by `accountId` + date.
- Single-ticket refetch: `GET /rest/api/3/issue/{key}?fields=...` (`JiraService.getTicketByKey`), used by the Tickets to Log panel.
- User list: `JiraService.searchAssignableUsers()` reads from the local `jira_cached_users` DB cache — no live Jira call on the request path (§ 12, § 13.1). Current user resolved via `GET /rest/api/3/myself` (`JiraService.getCurrentJiraUser`).
- Timezone: Jira timestamps converted to local `ZoneId.systemDefault()`.
- Jira timestamp parser handles `+0700` (no colon) and `+07:00` formats.

---

## 10a. Worklog Calendar (`/worklog-calendar` — USER only)

- Monthly grid (Mon–Sun columns) — one cell per day showing total logged hours, distinct ticket count, and a colour-coded hours bar (< 8h amber, 8–10h green, > 10h indigo).
- **Member filter** (`ComboBox<JiraUser>`) in the header, same data source and default-to-self behaviour as the Worklog page (§ 10.1). Changing it reloads the whole month for the selected member via `JiraService.getWorklogsForDate(date, accountId)`, fetched per-day in parallel using virtual threads.
- Clicking a day navigates to `/worklog?date=YYYY-MM-DD` (note: the Worklog page's own member filter defaults back to the logged-in user on navigation — it does not currently carry over the calendar's selected member).

---

## 10b. Spaces (`/spaces` — USER only)

Tree view of the Confluence spaces/pages relevant to the projects the current user has tickets in, **with each page's attached Ticket Docs managed inline** — there is no separate Ticket Docs page/route; a ticket doc only ever makes sense in the context of the Feature it's attached to, so selecting a Feature shows both its info and its ticket doc in one place. Every synced page is called a **Feature**. **All Jira/Confluence access anywhere in this feature is read-only (`GET` requests, or the `POST` search-JQL endpoint which is a query, never a mutation) — nothing is ever written back to Jira or Confluence.**

### 10b.1 Space discovery
- Space matching is a heuristic: for each local user with a Jira config, `JiraService.getMyTickets(cfg)` is used to collect the distinct Jira **project keys** they have tickets in (`JiraTicket.projectKey`, § 12), and each key is checked against Confluence via `JiraService.getConfluenceSpace(cfg, key)` (`GET /wiki/rest/api/space/{key}`). A match (same key) means that Confluence space is crawled; no match is silently skipped (common, not an error).
- Matched spaces are crawled page-by-page via `JiraService.listSpacePages(cfg, spaceKey)` (`GET /wiki/rest/api/content?spaceKey=...&expand=version,ancestors`, paginated via `_links.next`), capturing each page's title, Confluence version number, "updated" timestamp (**as of the sync**, not real-time), and parent page (from `ancestors`).

### 10b.2 Space picker + tree grid
- **Space picker** — a row of clickable chips above the filter field, one per distinct `spaceKey` currently synced for this site (e.g. `DOCS (12)`), derived from the loaded Features rather than a separate query. Selecting a chip scopes everything below (filter + tree) to that one space; the selected chip is highlighted. Hidden entirely when only zero spaces are loaded. **Default: the first space (alphabetically) is auto-selected** whenever the currently-selected one becomes invalid (first load, or it disappears after a sync) — without this, mixing pages from every matched project into one tree was hard to make sense of once more than one space was being tracked.
- `TreeGrid<ConfluenceFeature>` built from a flat list (scoped to the selected space) using `parentPageId` (handles arbitrary depth; a page whose parent wasn't itself crawled — e.g. the space's home page — is shown as a root).
- Columns: **Folder** (page title, hierarchy column) · **Updated** (Confluence's timestamp, captured at sync time) · **Status** badge — "Updated" (amber) vs "Read" (grey).
- **Unread logic**: a Feature is "Updated" for the current viewer whenever `feature.version > FeatureReadStatus.lastReadVersion` for that user (missing row = `0`, so a never-read Feature — including brand-new ones — starts as "Updated"). This is intentionally per-user, inbox-style: everyone sees their own read/unread state on shared content.
- **Filter by title** — a `TextField` above the tree (debounced 250 ms), applied within the selected space. Blank shows the full hierarchy; a non-blank query switches to a **flat** list of matching Features (parent = null for all matches), because a strict hierarchical filter would hide a matching deeply-nested page whenever its ancestor's title doesn't also match — the tree can only reach a node by first expanding its parent. Flattening is what actually lets you find a specific page in a large tree.
- The tree's item-click listener ignores a `null` clicked item (`e.getItem() == null`) rather than crashing the view — this can legitimately happen if the tree's data provider was replaced (e.g. by a sync-triggered reload, or the auto-sync-if-empty on first visit, § 10b.4) between the click firing client-side and being processed server-side.

### 10b.3 Detail panel (split top/bottom)
Selecting a Feature splits the right-hand panel into a vertical `SplitLayout`:
- **Top — Feature info**: page title (links to Confluence) · space key · version · **Update History** (every `ConfluenceFeatureUpdateHistory` row for the page, newest first — `vX → vY`, the new Confluence "updated" timestamp, and when the sync detected it; only written when a sync finds the Confluence version increased on an *already-known* page, not on first discovery) · **Mark as read** button (sets `FeatureReadStatus.lastReadVersion = feature.version` for the current user; disabled once already read; a later real content change makes it unread again automatically).
- **Bottom — Ticket Docs** (§ 10b.6): the selected Feature's attached ticket doc — actions + grid, no separate page chrome (no feature picker; the tree selection already scopes it).

### 10b.4 Refresh
- "Refresh" triggers `KnowledgeBaseSyncRunner.syncOne(cfg)` for the current user's site (on a virtual thread), then reloads the tree from the DB — consistent with how "Refresh" works elsewhere in the app (re-fetch from source, not just re-read the cache).
- If nothing has been synced yet for the current site when the page loads (`loadTree()` returns 0), a sync is triggered automatically instead of leaving the page silently empty until the next scheduled cycle.
- Saving a Jira connection in Settings also triggers `KnowledgeBaseSyncRunner.syncOne(saved)` immediately (alongside the existing `JiraUserSyncRunner` sync), so a freshly-configured or re-saved connection doesn't have to wait.

### 10b.5 Manual space/page tracking (fallback for key mismatches)
The project-key ↔ space-key auto-match (§ 10b.1) is a heuristic and has a real, common failure mode: a Jira project's key and its Confluence space's key don't match (e.g. Jira project `DEMO2` whose wiki lives in Confluence space `DOCS`). For this case, two toolbar buttons handle it — deliberately kept as two single-purpose dialogs rather than one combined "add + browse everything" dialog, which was hard to make sense of:

- **"Add space/page"** — opens a form with just a Confluence URL field + Add/Cancel. Nothing else in this dialog.
- **"Manage links"** — opens a list of every currently-tracked `ManualSyncTarget` for this site, each with **Edit** (reopens the *same* single-field form, pre-filled, in edit mode — updates the same row in place rather than deleting/recreating it; blocked if the corrected space/page is already tracked by a different row) and **Remove** (stops future syncs for that target; already-synced `ConfluenceFeature` rows are left in place, not deleted).
- Both dialogs share one form-building method (`openTargetFormDialog(existing, onSaved)` — `existing == null` means "add"), so Add and Edit behave identically apart from which button opened them and whether a row is pre-filled.
- **`ConfluenceLinkParser`** (pure utility, unit-tested) parses the pasted URL into one of: `SPACE` (`.../wiki/spaces/KEY`), `PAGE` (`.../wiki/spaces/KEY/pages/{id}/...`), `UNSUPPORTED_FOLDER` (`.../wiki/spaces/KEY/folder/{id}/...`), or `INVALID`.
- A parsed `SPACE` or `PAGE` link is saved as a **`ManualSyncTarget`** (`base_url`, `space_key`, nullable `page_id`, `source_url`, `added_by_user_id`, `added_at`) and immediately triggers a sync.
- `KnowledgeBaseSyncRunner.syncFeatures` includes manual targets alongside the auto-detected ones: manual **space** keys are unioned into the same crawl loop as auto-detected project keys (`listSpacePages`); manual **page** targets are fetched directly by ID via `JiraService.getConfluencePageDetail(cfg, pageId)` (`GET /wiki/rest/api/content/{id}?expand=version,ancestors,space`), bypassing the space-wide crawl entirely — this works even when the page's space was never matched.
- **Folder links are explicitly rejected**, not silently ignored: Confluence's "Folder" content type (the newer content-tree grouping construct, distinct from a Page) isn't modeled by the REST API v1 endpoints this app uses (`/wiki/rest/api/content`). Supporting it would require Confluence's newer v2 API, untested against a live site — deferred rather than implemented blind. The dialog tells the user to pick a Page link inside the folder instead.

### 10b.5a Design Doc upload + version history (top panel, per Feature)
A "Design Doc" section under Update History in the top (Feature info) panel — for an HTML review/plan doc produced **outside this app** by a separate tool that reads a Feature's Ticket Docs `tickets.md` (§ 10b.6) plus its Confluence content and renders an overall implementation write-up for dev review. Every upload is kept as a new **version** rather than overwriting the previous one, so past versions stay available for later tracing/comparison.

- **`FeatureDesignDoc`** (`ManyToOne` to Feature — many rows per Feature, one per version): composite unique `(feature_id, version)`, `version` (int, 1-based, increases per upload), `file_name` (original upload name, display only), `file_path`, `uploaded_at`, `uploaded_by_user_id`.
- **Upload** — a Vaadin `Upload` component (`.html`/`.htm`, capped at 20 MB to allow embedded images/diagrams). On success the next version number is `(highest existing version for this Feature) + 1` (or `1` if none exist yet); `FeatureDesignDocStorage` writes the file to `./data/feature-design-docs/{featureId}/v{version}/{sanitizedFileName}` and **nothing is ever deleted** — each version lives in its own subdirectory. The uploaded filename is sanitized by taking only `Path.getFileName()` (strips any directory components — defends against a crafted name like `../../etc/passwd`) then stripping characters outside `[a-zA-Z0-9._-]`.
- **Latest link** — an `Anchor` (`target="_blank"`) reading "Latest: v{N} — {fileName}" to `/design-docs/{featureId}` (always resolves to the highest version) opens the current doc in a new tab, with its uploaded-by/uploaded-at metadata underneath.
- **Version history** — when more than one version exists, a collapsible Vaadin `Details` ("Version history (N older)", collapsed by default) lists every older version, each a separate link to `/design-docs/{featureId}/{version}` with its own filename/timestamp/uploader — lets a dev open an old version to trace what changed or investigate an issue raised against a prior doc.
- **`DesignDocController`** (`com.jiramanager.web`, Spring MVC `@RestController`, not a Vaadin route): `GET /design-docs/{featureId}` (`viewLatest`) resolves the highest version via `findTopByFeature_IdOrderByVersionDesc`; `GET /design-docs/{featureId}/{version}` (`viewVersion`) resolves one exact version via `findByFeature_IdAndVersion`. Both share a `serve()` helper that 404s on a missing row or a file no longer present on disk, and otherwise responds with `Content-Disposition: inline` so the doc opens in-tab rather than downloading.
- **Auth**: `/design-docs/**` isn't a Vaadin `@Route`, so it relies on `SecurityConfig`'s `VaadinWebSecurity` base behavior (`.anyRequest().authenticated()`) — verified live (unauthenticated `curl` gets a `302` to `/login`, same as every other page in this app).
- **No `Content-Security-Policy` sandboxing — deliberate, explicit product decision, not an oversight.** The file's content is arbitrary HTML (produced by the team's own external tooling from their own Jira/Confluence data), and rendering it with full script execution on the app's own origin is a real stored-XSS-shaped risk in general. It's accepted here because this app runs **local-only for one small trusted team** (§ 1) and the uploader is that same trusted team, not an arbitrary public user. **Before this app is ever published / exposed beyond that / opened to untrusted uploaders**, reinstate a `Content-Security-Policy: sandbox` response header in `DesignDocController` (blocks script execution + cookie/session access while still rendering headings/tables/inline CSS normally — the standard mitigation for safely rendering untrusted uploaded HTML). See the class javadoc on `DesignDocController` for the exact header to add back.

### 10b.6 Ticket Docs (bottom half of the detail panel)
Per-Feature Jira ticket collections, embedded in the split detail panel (§ 10b.3) rather than a separate page — attach one or more Jira ticket keys to the currently-selected Feature, and the system generates one `tickets.md` for it, concatenating every attached ticket's key, summary, status/priority/assignee, updated date, URL, and **full, untruncated description** (`JiraTicket.fullDescription`, § 12 — distinct from the 500-char `description` field used elsewhere) — stored locally on disk and downloadable. **Read-only against Jira**, same as § 10b — tickets are only ever fetched (`GET`/search), never modified.

- **Add tickets** — a dialog with a free-text area accepting comma/newline/space-separated ticket keys. Each new key is fetched fresh via `JiraService.getTicketByKey(key)`; unknown/inaccessible keys are skipped silently rather than failing the whole batch. Runs on a virtual thread (`WorklogCalendarView`-style `DelegatingSecurityContextRunnable` + `ui.access()`), since several sequential Jira calls can take a moment.
- **Ticket grid**: **Ticket** key column (`setFlexGrow(1)`, given nearly all the available width — it's the main identifying content in this compact panel) · **Status** badge ("Up to date" / "Changed on Jira", fixed 140px) · remove button (fixed 50px). The "last known update" timestamp column from the original standalone-page design was dropped to keep the key column readable in the narrower embedded space. Removing an item regenerates the doc immediately so the file stays in sync with the grid — the removal handler uses the view's own `selectedDoc` field rather than `item.getTicketDoc()` (the clicked Grid row is a stale object from an earlier request, so its lazy `TicketDoc` association would throw `LazyInitializationException` the moment a setter ran on it during regeneration).
- **Regenerate** — re-fetches every attached ticket (`getTicketsByKeys`, batched) and rewrites the markdown file; clears each item's "needs regenerate" flag.
- **Download** — `Anchor` + `StreamResource` reading the generated file straight off disk; rebuilt whenever the selected Feature (and its file) changes.
- No separate "Sync now" button here — the page-level "Refresh" (§ 10b.4) already re-syncs both the Feature tree and ticket-doc change-detection together via `KnowledgeBaseSyncRunner.syncOne`.

### 10b.7 Ticket doc change detection & notification
- `KnowledgeBaseSyncRunner`'s background pass (§ 13.2) re-fetches every attached ticket's current Jira "updated" timestamp and compares it to `TicketDocItem.lastKnownUpdated` (captured at the last generate). A mismatch sets `needsRegenerate = true` — shown as a badge on the item and on the doc's overall status ("Regenerate recommended").
- **Toast notification**: rather than a live cross-thread UI push (this app has no Vaadin `@Push` infrastructure, so a background-thread `ui.access()` call wouldn't render until the next round-trip anyway, and risks `UIDetachedException` if the session ended), the toast fires the next time the affected site's Spaces page is opened — `beforeEnter` checks for any `needsRegenerate && notifiedAt == null` items site-wide and shows one grouped toast, stamping `notifiedAt` so it isn't repeated.

---

## 11. Navigation Layout (`MainLayout`)

- `AppLayout` with drawer (left) + navbar (top).
- **Drawer**: app logo/name, role-based `SideNav`, version footer.
- **Navbar**: drawer toggle, spacer, user avatar + name + email, sign-out button.

### Role-based sidebar
| Role  | Nav items shown |
|-------|----------------|
| ADMIN | User Management · ─── · My Profile |
| USER  | Dashboard · My Tickets ⚠ · Worklog ⚠ · Worklog Calendar ⚠ · Spaces ⚠ · Reports (disabled) · Settings* · ─── · My Profile |

- My Tickets, Worklog, Worklog Calendar, and Spaces all show **⚠ badge** when Jira is not configured (same `jiraNavItem(...)` helper). Ticket Docs has no nav item of its own — it's embedded in Spaces (§ 10b.6), reached by selecting a Feature.
- Settings item shown in **bold primary color** when Jira is not configured.
- Reports item is permanently disabled (styled, pointer-events: none) — planned for future.

---

## 12. Data Model

### `app_users`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | auto |
| email | VARCHAR(255) UNIQUE NOT NULL | lowercase trimmed |
| first_name | VARCHAR(100) NOT NULL | |
| last_name | VARCHAR(100) | nullable |
| phone_number | VARCHAR(20) | nullable |
| password_hash | VARCHAR(255) | null for OAuth2-only |
| role | VARCHAR(20) DEFAULT 'USER' NOT NULL | |
| enabled | BOOLEAN DEFAULT true NOT NULL | |
| created_at | TIMESTAMP NOT NULL | set on create, not updated |

### `auth_providers`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| user_id | FK → app_users | |
| provider | VARCHAR(50) | `"local"`, `"google"`, … |
| provider_id | VARCHAR(255) | OAuth2 UID; null for local |
| provider_email | VARCHAR(255) | |

### `JiraTicket` (in-memory model — not persisted)
| Field | Type | Notes |
|-------|------|-------|
| key, summary, status, statusColor | String | |
| priority, project, projectKey, issueType | String | `project` = display name, `projectKey` = e.g. `"DEMO"` (used to match Confluence space keys, § 10b.1) |
| assignee, reporter | String | |
| description | String | **truncated** to 500 chars + "..." — compact detail-panel display only (My Tickets, Worklog) |
| fullDescription | String | **untruncated** plain-text description — used only by Ticket Docs markdown generation (§ 10b.6), never for on-screen display. ADF paragraphs/headings/list items are converted to real line breaks (`extractAdfText`), not run together on one line |
| created, updated, dueDate, sprint, url | String | |
| updatedInstant | Instant | raw parsed `updated` timestamp (null if Jira didn't return one); used for change detection, not display |
| originalEstimate | String | e.g. `"4h"` — from Jira `timetracking` field |
| originalEstimateSeconds | long | raw seconds |
| timeSpent | String | e.g. `"2h 30m"` |
| timeSpentSeconds | long | raw seconds |
| remainingEstimate | String | e.g. `"1h 30m"` |
| remainingEstimateSeconds | long | raw seconds; used by "Has remaining time" filter |

### `jira_configs`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| user_id | FK → app_users UNIQUE | one per user |
| base_url | VARCHAR(500) | e.g. `https://co.atlassian.net` |
| email | VARCHAR(255) | Jira account email |
| api_token | VARCHAR(1000) | Atlassian API token |
| updated_at | TIMESTAMP NOT NULL | |

### `jira_cached_users`
Background-synced mirror of each Jira site's user list — backs the Worklog / Worklog Calendar member filter (§ 13.1) without a live Jira call on the request path.

| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| base_url | VARCHAR(500) NOT NULL | Jira site this user belongs to (not tied to a specific local `app_users` row) |
| account_id | VARCHAR(255) NOT NULL | Jira Cloud `accountId` |
| display_name | VARCHAR(255) NOT NULL | |
| synced_at | TIMESTAMP NOT NULL | Last successful sync time |
| *(unique)* | `(base_url, account_id)` | One row per user per site |

### `confluence_features`
One synced Confluence page ("Feature", § 10b) per row. Scoped by `base_url`, not by local user — a site's Confluence content is shared across every local account connected to it.

| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| base_url | VARCHAR(500) NOT NULL | |
| space_key | VARCHAR(100) NOT NULL | |
| page_id | VARCHAR(255) NOT NULL | Confluence page ID |
| title | VARCHAR(500) NOT NULL | "folder name" in the tree grid |
| parent_page_id | VARCHAR(255) | null = space-root page |
| version | INT NOT NULL | Confluence version number as of last sync |
| confluence_updated_at | TIMESTAMP NOT NULL | Confluence's own "updated" time, captured at sync time |
| last_synced_at | TIMESTAMP NOT NULL | |
| url | VARCHAR(1000) | |
| *(unique)* | `(base_url, page_id)` | |

### `confluence_feature_update_history`
Append-only log of every version bump `KnowledgeBaseSyncRunner` detected on a Feature — backs the Spaces detail panel's "Update History" timeline. Never written on first discovery of a page.

| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| feature_id | FK → confluence_features | |
| old_version | INT (nullable) | |
| new_version | INT NOT NULL | |
| new_updated_at | TIMESTAMP NOT NULL | |
| detected_at | TIMESTAMP NOT NULL | |

### `feature_read_status`
Per-user "mark as read" marker. A Feature is unread for a user whenever `feature.version > lastReadVersion` (no row = `0`).

| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| feature_id | FK → confluence_features | |
| user_id | FK → app_users | |
| last_read_version | INT NOT NULL | |
| read_at | TIMESTAMP NOT NULL | |
| *(unique)* | `(feature_id, user_id)` | |

### `ticket_docs`
The generated `tickets.md` for one Feature (§ 10b.6). One doc per Feature.

| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| feature_id | FK → confluence_features UNIQUE | |
| file_path | VARCHAR(1000) | e.g. `./data/feature-tickets/{id}/tickets.md` |
| generated_at | TIMESTAMP (nullable) | null until first generated |
| generated_by_user_id | FK → app_users (nullable) | |

### `ticket_doc_items`
One attached Jira ticket per row.

| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| ticket_doc_id | FK → ticket_docs | |
| ticket_key | VARCHAR(50) NOT NULL | |
| last_known_updated | TIMESTAMP (nullable) | Jira `updated` timestamp as of last generate |
| needs_regenerate | BOOLEAN NOT NULL DEFAULT false | set by background sync when Jira's `updated` moves past `last_known_updated` |
| notified_at | TIMESTAMP (nullable) | set once the "needs regenerate" toast has been shown, to avoid repeats |
| added_at | TIMESTAMP NOT NULL | |
| added_by_user_id | FK → app_users | |
| *(unique)* | `(ticket_doc_id, ticket_key)` | |

### `manual_sync_targets`
Manually-tracked Confluence space/page (§ 10b.5) — the fallback for when a Jira project key doesn't match its Confluence space key.

| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| base_url | VARCHAR(500) NOT NULL | |
| space_key | VARCHAR(100) NOT NULL | |
| page_id | VARCHAR(255) (nullable) | null = track the whole space; set = track only this one page |
| source_url | VARCHAR(1000) | the pasted URL, kept for reference |
| added_by_user_id | FK → app_users | |
| added_at | TIMESTAMP NOT NULL | |

### `feature_design_docs`
Uploaded HTML review/plan doc versions for a Feature (§ 10b.5a), produced externally, not by this app. One row per upload — every version is kept, never overwritten.

| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | |
| feature_id | FK → confluence_features | many rows per Feature (one per version) |
| version | INT NOT NULL | 1-based, increases per upload; composite unique with `feature_id` |
| file_name | VARCHAR(500) | original uploaded filename, display only |
| file_path | VARCHAR(1000) | e.g. `./data/feature-design-docs/{featureId}/v{version}/{sanitizedFileName}` |
| uploaded_at | TIMESTAMP NOT NULL | |
| uploaded_by_user_id | FK → app_users (nullable) | |

Unique constraint: `(feature_id, version)`.

---

## 13. JiraService Architecture

- `@Service` with `@Autowired` constructor (two constructors: production + package-private test constructor).
- `ConfigContext` record `(WebClient, String baseUrl)` — built per-call from a `JiraConfig` via `buildContext(cfg)`.
- `resolveContext()` — checks `testContext` first (unit tests), else loads the current session user's `JiraConfig` from DB and calls `buildContext(cfg)`.
- `buildContext(JiraConfig cfg)` — package-private, session-independent; builds a `WebClient` directly from any given config. Lets the background sync job (§ 13.1) target an arbitrary user's Jira site outside of any HTTP session.
- `isConfigured()` — safe check, never throws; used by nav guards and sidebar.
- `JiraNotConfiguredException` — thrown when config is missing or incomplete.
- `formatDuration(int minutes)` — static utility, e.g. "1h 30m", "45m", "2h".
- `getMyTickets()` requests the `timetracking` field; `parseTicket()` maps `originalEstimate`, `timeSpent`, `remainingEstimate` (string + seconds) onto `JiraTicket`.
- `getTicketByKey(String key)` — single-issue fetch reusing `parseTicket()`; always hits Jira fresh (no caching), used to force real-time time-tracking figures.
- `getTicketByKey(JiraConfig cfg, String key)` / `getMyTickets(JiraConfig cfg)` — session-independent variants of the above, for background jobs acting on an arbitrary user's config (mirrors `fetchAssignableUsersFromJira`).
- `getTicketsByKeys(JiraConfig cfg, List<String> keys)` — bulk JQL `key in (...)`, batched at 50 keys/call; used by the Ticket Docs change-detection pass instead of one call per ticket.
- `getWorklogsForDate(LocalDate)` now delegates to `getWorklogsForDate(LocalDate, String accountId)`, resolving the current user's `accountId` first.
- **Every Jira/Confluence call in this service is read-only** — `GET`, or the search endpoints' `POST` (a JQL query, not a mutation). Nothing in this codebase writes back to Jira or Confluence.

### 13.1 Jira user cache & background sync
- `getCurrentJiraUser()` — live `GET /rest/api/3/myself`; cheap single-object call, used only to resolve "who am I" for the member filter's default selection.
- `searchAssignableUsers()` — **reads from the `jira_cached_users` DB table** (never calls Jira on the request path). Scoped to the current user's `JiraConfig.baseUrl`; always merges in the live current user via `getCurrentJiraUser()` in case the cache is stale or doesn't cover them yet.
- `fetchAssignableUsersFromJira(JiraConfig cfg)` — the actual live `GET /rest/api/3/users/search` call (filtered to active `accountType=atlassian` users), built from an explicit `JiraConfig` via `buildContext()`. Only ever invoked by the sync job below, never by a view.
- **`JiraUserSyncRunner`** (`ApplicationRunner`):
  - On app startup, hands off to a virtual thread (`Thread.ofVirtual().start(...)`) so the sync never blocks boot or the first request.
  - Groups all `JiraConfig` rows by `baseUrl` (one representative config per distinct Jira site, since multiple local accounts may share a site) and calls `fetchAssignableUsersFromJira()` + upserts into `jira_cached_users` for each.
  - `syncOne(JiraConfig cfg)` is also called — again on a virtual thread — from `SettingsView` right after a user saves their Jira connection, so a newly-configured site's member list populates immediately instead of waiting for the next restart.
  - Per-site failures are logged and skipped; they don't affect other sites or the app itself.

### 13.2 Knowledge Base sync (Spaces + Ticket Docs)
- `getConfluenceSpace(JiraConfig cfg, String spaceKey)` — `GET /wiki/rest/api/space/{key}`; returns `null` on 404 (no matching space — expected, not an error).
- `listSpacePages(JiraConfig cfg, String spaceKey)` — `GET /wiki/rest/api/content?spaceKey=...&type=page&status=current&expand=version,ancestors&limit=100`, following `_links.next` until exhausted. Parent page = last element of `ancestors[]` (null = space root).
- `getConfluencePageDetail(JiraConfig cfg, String pageId)` — `GET /wiki/rest/api/content/{id}?expand=version,ancestors,space`; fetches one page directly by ID (including its space key), used for manually-tracked pages (§ 10b.5) which bypass the space-wide crawl.
- `ConfluenceLinkParser` (`service` package, pure/no Spring) — parses a pasted Confluence URL into `SPACE` / `PAGE` / `UNSUPPORTED_FOLDER` / `INVALID`, feeding the "Add space/page" dialog (§ 10b.5).
- **`KnowledgeBaseSyncRunner`** (`ApplicationRunner` + `@Scheduled`), the same shape as `JiraUserSyncRunner`:
  - `run()` hands off to a virtual thread at startup; `@Scheduled(initialDelay = 4h, fixedRate = 4h)` does the same on a recurring basis (`initialDelay`, not `0`, avoids double-firing alongside the startup run). Requires `@EnableScheduling` on `Application`.
  - **Feature sync**: for each distinct `baseUrl`, unions the Jira project keys across every local user on that site (`getMyTickets(cfg).projectKey`) with any manually-tracked space keys (`ManualSyncTarget`, § 10b.5), checks each against Confluence (`getConfluenceSpace`), and crawls matches (`listSpacePages`) — upserting `ConfluenceFeature` rows and logging `ConfluenceFeatureUpdateHistory` on real version bumps. Manually-tracked individual pages are fetched separately via `getConfluencePageDetail`, regardless of whether their space was matched.
  - **Ticket doc change detection**: for each site with existing `TicketDocItem`s (`TicketDocItemRepository.findByFeatureBaseUrl(baseUrl)` — a `JOIN FETCH` through `ticketDoc.feature`, required because this runs on a background virtual thread with no HTTP request/session; naively filtering `findAll()` results in Java after the query returns throws `LazyInitializationException` on the lazy `ticketDoc`/`feature` associations), bulk-refetches them (`getTicketsByKeys`) and flags `needsRegenerate` where Jira's `updated` has moved past `lastKnownUpdated`.
  - `syncOne(JiraConfig cfg)` — targeted resync of a single site (both steps); used by the "Refresh" button on Spaces (which covers both the Feature tree and ticket-doc change detection, since Ticket Docs is embedded there — § 10b.6), the auto-sync-if-empty on first Spaces visit, and the post-Settings-save hook.
  - One representative `JiraConfig` per site does the actual Confluence crawl and ticket-doc detection — same simplification `JiraUserSyncRunner` already accepts (permissions could differ per user on a shared site).

---

## 14. Testing

### `JiraServiceTest` (unit, 18 tests)
- Uses `MockWebServer` (OkHttp) — all HTTP calls go to localhost, **never to real Jira**.
- Covers: success parse, POST JQL endpoint, empty results, null body, 401/410/500 errors, default field values, ADF description, description truncation (500 chars + "…"), date formatting, multiple tickets.
- Plus 6 tests for the Confluence primitives (§ 13.2): space found/404, page-tree parent-from-ancestors, pagination via `_links.next`, single-page-by-ID found/404.

### `ConfluenceLinkParserTest` (unit, 7 tests)
- No Spring context — pure unit tests.
- Covers: page/folder/space URL shapes, space-with-trailing-slash, blank/null input, unrecognized URL.

### `TicketDocMarkdownServiceTest` (unit, 4 tests)
- No Spring context — pure unit tests, writes under a JUnit `@TempDir` (never touches the app's real `./data/` folder).
- Covers: generated content includes ticket fields, overwrite-on-regenerate, missing-description placeholder, `fullDescription` (not the truncated `description`) is what ends up in the file.

### `FeatureDesignDocStorageTest` (unit, 4 tests)
- No Spring context — pure unit tests, writes under a JUnit `@TempDir`.
- Covers: file written under the per-feature-and-version directory (`{featureId}/v{version}/{fileName}`), unsafe filename sanitized (path-traversal attempt reduced to a plain filename), two versions saved for the same Feature both stay on disk and independently readable (nothing is deleted), blank filename falls back to a default name.

### `DesignDocControllerTest` (unit, 5 tests)
- No Spring context — `FeatureDesignDocRepository` mocked with Mockito, controller methods called directly (no MockMvc/HTTP layer).
- Covers `viewLatest`: missing DB row → 404, DB row present but file missing from disk → 404, existing file → 200 with `Content-Disposition: inline` header present and (deliberately) no `Content-Security-Policy` header. Covers `viewVersion`: missing specific version → 404, existing older version → 200 serving that version's file. The authentication boundary itself (`/design-docs/**` requires login) was verified live via `curl` against a running instance rather than in this unit test, since that's `SecurityConfig`/Spring Security's behavior, not the controller's.

### `AuthFlowTest` (integration, 10 tests)
- `@SpringBootTest` + `@Transactional` + `@ActiveProfiles("test")`.
- Covers: USER registration + password encoder + `loadUserByUsername` + ROLE_USER authority + full auth flow.
- Admin tests use `admin@localhost.com` created by `DataInitializer` (no re-register).
- Covers: admin exists after startup, ROLE_ADMIN authority, `authenticateLocal` success, wrong password rejection.
- Covers: `SecurityContextLogoutHandler` invalidates session + clears `SecurityContext`.
- Covers: custom `RequestCache` does not save `/logout` as redirect target.

### `WorklogOverlapDetectorTest` (unit, 19 tests)
- No Spring context — pure unit tests.
- Covers: null/empty list, single entry, touching boundaries (not overlap), sequential with gap, partial overlap, full containment, same time range, all-three-overlap, null worklog IDs (no NPE), null start/end times (no NPE).
- Covers `overlaps()` directly: null entries, touching, partial, commutativity.
- Covers `findOverlapPartners()`: no partners, correct partner subset, target excluded from own partners, null inputs.

---

## 15. Known Limitations / Future Work

| Area | Status | Notes |
|------|--------|-------|
| Reports | Stub | Nav item disabled; not implemented |
| OAuth2 login | Disabled | Code exists; needs client credentials in env |
| Jira API token encryption | Not done | Stored in plain text in DB |
| Pagination (My Tickets) | Not done | Hard-coded `maxResults: 50` |
| Worklog: create/edit/delete | Not done | Read-only view |
| Dark mode | Not done | Uses Lumo default theme |
| Multi-language | Not done | English only |
| Space-key ↔ project-key matching | Heuristic + manual fallback | Auto-match is a heuristic (confirmed real-world mismatch between a Jira project key and its Confluence space key); manual override exists via "Add space/page" (§ 10b.5) |
| Confluence Folder content type | Not supported | Folder links are explicitly rejected in "Add space/page" — Confluence's newer Folder type needs the v2 REST API, not implemented (§ 10b.5) |
| Ticket Docs toast delivery | Not real-time | Shown on next Spaces page visit, not pushed live — no Vaadin `@Push` infrastructure exists in this app (§ 10b.7) |
| Knowledge Base sync permissions | Simplification | One representative `JiraConfig` per site does the actual Confluence crawl / ticket refetch; a shared site's users could have differing Jira/Confluence permissions (same limitation `JiraUserSyncRunner` already accepts) |
| Design Doc XSS protection | **Deferred by explicit decision** | Served with no `Content-Security-Policy` — full script execution on this app's origin. Acceptable only because this app is local-only for one trusted team and the uploader is that same team (§ 1). **Must** add `Content-Security-Policy: sandbox` back in `DesignDocController` before any broader/public deployment (§ 10b.5a) |
