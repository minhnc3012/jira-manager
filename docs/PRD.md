# Product Requirements Document — Jira Manager v2

**Stack:** Vaadin Flow 24.6.6 · Spring Boot 3.4.3 · Spring Security · Spring Data JPA · H2 (file-based)
**Last updated:** 2026-07-14 (My Tickets time-tracking columns; real-time Tickets-to-Log; Worklog/Worklog Calendar member filter)

---

## 1. Overview

Jira Manager v2 is a multi-user web application that lets each user connect their own Jira Cloud account, view their assigned tickets, and inspect daily worklogs on a Gantt-style timeline.
Access is role-gated: **ADMIN** users manage the user base; **USER** accounts use the Jira features.

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
| USER  | Yes     | Registration / Admin | Dashboard, My Tickets, Worklog, Reports (stub), Settings, My Profile |

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

## 11. Navigation Layout (`MainLayout`)

- `AppLayout` with drawer (left) + navbar (top).
- **Drawer**: app logo/name, role-based `SideNav`, version footer.
- **Navbar**: drawer toggle, spacer, user avatar + name + email, sign-out button.

### Role-based sidebar
| Role  | Nav items shown |
|-------|----------------|
| ADMIN | User Management · ─── · My Profile |
| USER  | Dashboard · My Tickets ⚠ · Worklog ⚠ · Reports (disabled) · Settings* · ─── · My Profile |

- My Tickets and Worklog show **⚠ badge** when Jira is not configured.
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
| priority, project, issueType | String | |
| assignee, reporter, description | String | |
| created, updated, dueDate, sprint, url | String | |
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
- `getWorklogsForDate(LocalDate)` now delegates to `getWorklogsForDate(LocalDate, String accountId)`, resolving the current user's `accountId` first.

### 13.1 Jira user cache & background sync
- `getCurrentJiraUser()` — live `GET /rest/api/3/myself`; cheap single-object call, used only to resolve "who am I" for the member filter's default selection.
- `searchAssignableUsers()` — **reads from the `jira_cached_users` DB table** (never calls Jira on the request path). Scoped to the current user's `JiraConfig.baseUrl`; always merges in the live current user via `getCurrentJiraUser()` in case the cache is stale or doesn't cover them yet.
- `fetchAssignableUsersFromJira(JiraConfig cfg)` — the actual live `GET /rest/api/3/users/search` call (filtered to active `accountType=atlassian` users), built from an explicit `JiraConfig` via `buildContext()`. Only ever invoked by the sync job below, never by a view.
- **`JiraUserSyncRunner`** (`ApplicationRunner`):
  - On app startup, hands off to a virtual thread (`Thread.ofVirtual().start(...)`) so the sync never blocks boot or the first request.
  - Groups all `JiraConfig` rows by `baseUrl` (one representative config per distinct Jira site, since multiple local accounts may share a site) and calls `fetchAssignableUsersFromJira()` + upserts into `jira_cached_users` for each.
  - `syncOne(JiraConfig cfg)` is also called — again on a virtual thread — from `SettingsView` right after a user saves their Jira connection, so a newly-configured site's member list populates immediately instead of waiting for the next restart.
  - Per-site failures are logged and skipped; they don't affect other sites or the app itself.

---

## 14. Testing

### `JiraServiceTest` (unit, 12 tests)
- Uses `MockWebServer` (OkHttp) — all HTTP calls go to localhost, **never to real Jira**.
- Covers: success parse, POST JQL endpoint, empty results, null body, 401/410/500 errors, default field values, ADF description, description truncation (500 chars + "…"), date formatting, multiple tickets.

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
