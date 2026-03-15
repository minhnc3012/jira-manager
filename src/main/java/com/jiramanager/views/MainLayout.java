package com.jiramanager.views;

import com.jiramanager.service.JiraService;
import com.jiramanager.service.SessionUserService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.server.VaadinServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.Layout;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.annotation.security.PermitAll;

@Layout
@PermitAll
public class MainLayout extends AppLayout {

    private final SessionUserService sessionUserService;
    private final JiraService        jiraService;

    public MainLayout(SessionUserService sessionUserService, JiraService jiraService) {
        this.sessionUserService = sessionUserService;
        this.jiraService        = jiraService;
        setPrimarySection(Section.DRAWER);
        addToDrawer(buildDrawer());
        addToNavbar(buildNavbar());
    }

    // ── Drawer (left side menu) ───────────────────────────────────────

    private VerticalLayout buildDrawer() {
        // App logo header inside drawer
        HorizontalLayout logoRow = new HorizontalLayout();
        logoRow.setAlignItems(FlexComponent.Alignment.CENTER);
        logoRow.getStyle()
                .set("padding", "16px 20px")
                .set("border-bottom", "1px solid var(--lumo-contrast-10pct)");

        Span logo = new Span("⚡");
        logo.getStyle().set("font-size", "22px");

        Span appName = new Span("Jira Manager");
        appName.getStyle()
                .set("font-weight", "700")
                .set("font-size", "16px")
                .set("color", "var(--lumo-primary-color)");

        logoRow.add(logo, appName);

        // Navigation
        SideNav nav = buildSideNav();

        // Footer: version or extra links
        Span footer = new Span("v1.0.0");
        footer.getStyle()
                .set("font-size", "11px")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("padding", "12px 20px");

        VerticalLayout drawer = new VerticalLayout(logoRow, new Scroller(nav), footer);
        drawer.setSizeFull();
        drawer.setPadding(false);
        drawer.setSpacing(false);
        drawer.getStyle().set("background", "var(--lumo-base-color)");
        return drawer;
    }

    private SideNav buildSideNav() {
        SideNav nav = new SideNav();
        nav.getStyle().set("padding", "8px");

        boolean admin = sessionUserService.isAdmin();

        if (admin) {
            // ── ADMIN: only User Management + Profile ─────────────────
            nav.addItem(new SideNavItem("User Management", UserManagementView.class, VaadinIcon.USERS.create()));
        } else {
            // ── USER: full app navigation ─────────────────────────────
            boolean jiraOk = jiraService.isConfigured();

            nav.addItem(new SideNavItem("Dashboard", DashboardView.class, VaadinIcon.HOME.create()));

            // My Tickets & Worklog show a warning badge when Jira is not configured
            nav.addItem(jiraNavItem("My Tickets", MainView.class,   VaadinIcon.TICKET, jiraOk));
            nav.addItem(jiraNavItem("Worklog",    WorklogView.class, VaadinIcon.CLOCK,  jiraOk));

            SideNavItem reports = new SideNavItem("Reports");
            reports.setPrefixComponent(VaadinIcon.CHART.create());
            reports.getStyle()
                    .set("color", "var(--lumo-disabled-text-color)")
                    .set("cursor", "default")
                    .set("pointer-events", "none");
            nav.addItem(reports);

            SideNavItem settingsItem = new SideNavItem("Settings", SettingsView.class, VaadinIcon.COG.create());
            if (!jiraOk) {
                // Highlight Settings to draw attention when Jira is unconfigured
                settingsItem.getStyle().set("font-weight", "600").set("color", "var(--lumo-primary-color)");
            }
            nav.addItem(settingsItem);
        }

        // ── Common: Profile ───────────────────────────────────────────
        Hr separator = new Hr();
        separator.getStyle().set("margin", "8px 0");
        nav.getElement().appendChild(separator.getElement());
        nav.addItem(new SideNavItem("My Profile", ProfileView.class, VaadinIcon.USER.create()));

        return nav;
    }

    /** Creates a nav item; when jiraOk=false adds a ⚠ suffix label to signal setup needed. */
    private SideNavItem jiraNavItem(String label,
                                    Class<? extends com.vaadin.flow.component.Component> view,
                                    VaadinIcon icon, boolean jiraOk) {
        SideNavItem item = new SideNavItem(label, view, icon.create());
        if (!jiraOk) {
            Span badge = new Span("⚠");
            badge.getStyle()
                    .set("font-size", "11px")
                    .set("color", "#ff8800")
                    .set("margin-left", "6px")
                    .set("vertical-align", "middle");
            item.setSuffixComponent(badge);
            item.getElement().setAttribute("title", "Jira not configured — go to Settings first");
        }
        return item;
    }

    // ── Navbar (top bar) ──────────────────────────────────────────────

    private HorizontalLayout buildNavbar() {
        DrawerToggle toggle = new DrawerToggle();

        // Spacer pushes user block all the way to the right
        Span spacer = new Span();
        spacer.getStyle().set("flex", "1");

        // User info
        String name  = sessionUserService.getCurrentUserName();
        String email = sessionUserService.getCurrentUserEmail();

        Avatar avatar = new Avatar(name);
        avatar.setTooltipEnabled(true);
        avatar.getStyle().set("cursor", "default").set("flex-shrink", "0");

        Span nameLabel = new Span(name);
        nameLabel.getStyle()
                .set("font-size", "14px")
                .set("font-weight", "500")
                .set("color", "var(--lumo-body-text-color)")
                .set("white-space", "nowrap");

        Span emailLabel = new Span(email);
        emailLabel.getStyle()
                .set("font-size", "12px")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("white-space", "nowrap");

        VerticalLayout userInfo = new VerticalLayout(nameLabel, emailLabel);
        userInfo.setPadding(false);
        userInfo.setSpacing(false);

        // Sign-out: invalidate session server-side then redirect to /login.
        // IMPORTANT: capture the UI reference BEFORE SecurityContextLogoutHandler.logout()
        // because that call invalidates the HTTP session, which also destroys the Vaadin
        // session — after that, UI.getCurrent() returns null.
        Button logoutBtn = new Button(VaadinIcon.SIGN_OUT.create(), e -> {
            UI ui = e.getSource().getUI().orElse(null);          // capture first
            HttpServletRequest request = VaadinServletRequest.getCurrent().getHttpServletRequest();
            new SecurityContextLogoutHandler().logout(
                    request, null, SecurityContextHolder.getContext().getAuthentication());
            if (ui != null) {
                ui.getPage().setLocation("/login");              // redirect after cleanup
            }
        });
        logoutBtn.setTooltipText("Sign out");
        logoutBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        logoutBtn.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("flex-shrink", "0");

        HorizontalLayout userRow = new HorizontalLayout(avatar, userInfo, logoutBtn);
        userRow.setAlignItems(FlexComponent.Alignment.CENTER);
        userRow.setSpacing(true);
        userRow.getStyle()
                .set("padding-right", "16px")
                .set("margin-left", "auto"); // guarantee right-alignment even if flex fails

        HorizontalLayout navbar = new HorizontalLayout(toggle, spacer, userRow);
        navbar.setWidthFull();
        navbar.setAlignItems(FlexComponent.Alignment.CENTER);
        navbar.getStyle()
                .set("border-bottom", "1px solid var(--lumo-contrast-10pct)")
                .set("background", "var(--lumo-base-color)")
                .set("padding-left", "8px");
        return navbar;
    }
}
