# ⚡ Jira Manager v2

An internal web app that lets each team member connect **their own Jira Cloud account** to see assigned tickets, track daily worklogs on a Gantt chart, and review monthly logged hours on a calendar — without opening Jira itself.

---

## 🧩 Features

### Authentication & roles
- Email/password login (BCrypt). OAuth2 infrastructure (Google…) already exists in the code but is **currently disabled** — see [Re-enabling OAuth2](#re-enabling-oauth2-optional).
- 2 roles: **ADMIN** (sees only User Management + My Profile) and **USER** (Dashboard, My Tickets, Worklog, Settings, My Profile).
- A default admin account is auto-created on first startup: `admin@localhost.com` / `123456`.
- The sidebar shows/hides menu items based on role; Jira-related items show a ⚠ warning badge if the user hasn't configured Jira yet.

### User Management *(ADMIN — `/users`)*
Grid of all users (name, email, phone, role, status), add/edit/delete dialogs, assign USER/ADMIN role.

### My Profile *(`/profile`)*
Edit personal info, change password (local accounts only — hidden for OAuth2 accounts).

### Settings *(`/settings`)*
Each user enters their own **Base URL / Email / API Token** for Jira, stored per-user in the database (no shared, system-wide config). Includes a "Test Connection" button. Any Jira-dependent page redirects here automatically if the user hasn't configured Jira yet.

### Dashboard *(`/dashboard`)*
Quick stats (total / in progress / completed / blocked tickets) + a banner prompting Jira setup if not configured yet.

### My Tickets *(`/tickets`)*
Grid of tickets assigned to the current user, filterable by status / issue type / priority / sprint / project, plus a search box for key or summary. Click a row to open the detail panel (estimate, time logged, % complete).

### Worklog *(`/worklog`)*
- Pure HTML/CSS Gantt chart for a single day (5-minute granularity), frozen ticket column on horizontal scroll, red "now" indicator line.
- Detects **overlapping worklog entries** (logging time on two tickets at once) — flagged with an orange border/diagonal stripe on the bar plus a warning banner above the chart.
- A **"Tickets to Log"** panel below the chart lists tickets assigned to the user that haven't been logged yet or still have remaining estimate — a quick way to see what's still outstanding for the day.
- The right-hand detail panel shows worklog info plus **Confluence References**: it automatically discovers Confluence pages linked to the ticket (via Jira remote links or links in the description) and renders the page content inline.

### Worklog Calendar *(`/worklog-calendar`)*
A monthly calendar view — each day shows total hours logged + ticket count, color-coded by total (amber = under-logged, green = 8–10h, purple = over 10h), with a ⚡ warning if that day has overlapping entries. Click a day to jump straight to its Worklog page. A badge shows the total hours for the whole month.

---

## 🏗️ Tech Stack

| | |
|---|---|
| Framework | Vaadin Flow 24.6.6 + Spring Boot 3.4.3 |
| Language | Java 21 |
| Auth | Spring Security (form login + BCrypt), OAuth2 infrastructure (disabled) |
| Database | H2 file-based (dev) / PostgreSQL (prod) |
| Jira / Confluence | REST API v3 via WebClient, Basic Auth (email + API token) |

---

## 🔑 Getting a Jira API Token

Each user gets their own token from [Atlassian API Tokens](https://id.atlassian.com/manage-profile/security/api-tokens), then enters Base URL + Email + Token on the **Settings** page after logging in. The same token is also used to call Confluence (same Atlassian Cloud site) for the Confluence References feature on the Worklog page.

---

## 💻 Setup & run in an editor (development)

### Requirements
- JDK 21
- No need to install Node.js/npm manually — Vaadin downloads Node automatically when needed. A pre-built frontend dev bundle is already committed at `src/main/bundles/dev.bundle`, so the first run is faster.
- Any Maven-aware IDE (IntelliJ IDEA, VS Code + Extension Pack for Java, Eclipse…).

### Run from the IDE
1. Open the project folder and let the IDE import it as a Maven project (`pom.xml`).
2. Run the `com.jiramanager.Application` class (it has a `main` method).
3. Or use the IDE's terminal:
   ```bash
   ./mvnw spring-boot:run
   ```
   (Windows: `mvnw.cmd spring-boot:run`)
4. Open **http://localhost:8888** (port comes from `server.port` in `application.properties`).
5. Log in with the default admin account `admin@localhost.com` / `123456`, or create a new user via **User Management**.
6. Log in as a regular user → go to **Settings** → enter Jira Base URL / Email / API Token → **Test Connection**.

> The H2 database is a file stored at `./data/jiramanagerdb.*` (relative to wherever the app runs). `data/` is in `.gitignore` — **never commit** this folder, it's runtime data, not source code.

### Re-enabling OAuth2 (optional)
1. In `pom.xml`, uncomment the `spring-boot-starter-oauth2-client` dependency.
2. In `application.properties`, uncomment the 3 `spring.security.oauth2.client.registration.google.*` lines and set the `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` environment variables (get them from [Google Cloud Console](https://console.cloud.google.com/) → APIs & Services → Credentials, redirect URI `http://localhost:8888/login/oauth2/code/google`).

---

## 🐳 Build & run with Docker (no editor needed)

The repo includes a `Dockerfile` (multi-stage: build with Maven/JDK, runtime is JRE-only + the built jar) and a `docker-compose.yml`.

### Build & run with Docker Compose (recommended)
```bash
docker compose up --build -d
```
Open **http://localhost:8888** and log in with the default admin account `admin@localhost.com` / `123456` (auto-created the first time the container starts). H2 data is stored in the `jira-data` Docker volume — **not baked into the image or the source tree** — so the container can be rebuilt/removed without losing data, and `data/` never gets committed to git.

### Or build/run manually with the Docker CLI
```bash
docker build -t jira-manager .
docker run -d --name jira-manager \
  -p 8888:8888 \
  -v jira-data:/app/data \
  jira-manager
```

Notes:
- The first build needs internet access so Maven can download dependencies and Vaadin can download Node.js/pnpm to build the production frontend (minified, no dev tools). Subsequent builds are faster thanks to Docker's layer cache.
- The port can be overridden at runtime via Spring Boot's environment variable binding, e.g. `-e SERVER_PORT=8080` (remember to update `-p` to match).
- The image is built with `mvn package -Pproduction` — Vaadin runs in production mode (minified frontend, no dev tools/live-reload).

---

## 🗄️ Production – switching to PostgreSQL

Add the dependency to `pom.xml`:
```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

Update `application.properties` (or override via environment variables when running in Docker):
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/jiramanager
spring.datasource.username=postgres
spring.datasource.password=yourpassword
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
```

---

## 🧪 Tests

```bash
./mvnw test
```
41 tests: `JiraServiceTest` (12, uses MockWebServer — never calls real Jira), `AuthFlowTest` (10, integration tests for auth/role/logout flows), `WorklogOverlapDetectorTest` (19, unit tests for the overlap-detection logic).

---

## 📁 Project structure

```
src/main/java/com/jiramanager/
├── Application.java
├── AppShell.java               # @Push — enables server push for async data loading
├── model/
│   ├── AppUser.java             # User entity (role, password hash...)
│   ├── AuthProvider.java        # Provider (local/google) for account linking
│   ├── JiraConfig.java          # Per-user Jira config (DB)
│   ├── JiraTicket.java          # Ticket DTO (in-memory, not persisted)
│   ├── WorklogEntry.java        # Worklog DTO (in-memory)
│   └── ConfluencePage.java      # Confluence page DTO (in-memory)
├── repository/
│   ├── UserRepository.java
│   ├── AuthProviderRepository.java
│   └── JiraConfigRepository.java
├── service/
│   ├── UserService.java          # Registration, login, account linking
│   ├── JiraService.java          # Calls Jira REST API v3 + Confluence REST API
│   ├── WorklogOverlapDetector.java # Detects overlapping worklogs (pure logic, no Spring dependency)
│   ├── SessionUserService.java
│   └── DataInitializer.java      # Creates the default admin account on startup
├── security/
│   ├── SecurityConfig.java
│   ├── AppUserDetailsService.java
│   └── CustomOAuth2UserService.java  # Handles the Google callback (currently disabled)
└── views/
    ├── RootView.java             # Post-login routing based on role
    ├── MainLayout.java           # Drawer + navbar, role-based sidebar
    ├── LoginView.java
    ├── DashboardView.java
    ├── UserManagementView.java   # ADMIN
    ├── ProfileView.java
    ├── SettingsView.java         # Per-user Jira configuration
    ├── MainView.java             # My Tickets
    ├── WorklogView.java          # Daily worklog Gantt chart
    └── WorklogCalendarView.java  # Monthly worklog summary (calendar)
```
