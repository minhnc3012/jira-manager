package com.jiramanager.views;

import com.jiramanager.service.SessionUserService;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

/**
 * Root route ("/") — immediately redirects based on the logged-in user's role:
 *   ADMIN → UserManagementView (/users)
 *   USER  → DashboardView      (/dashboard)
 *
 * Uses class-based forwardTo (more reliable than string-path forwardTo in
 * Vaadin Flow because it resolves the route at compile time, not at runtime).
 */
@Route(value = "")
@PageTitle("Jira Manager")
@PermitAll
public class RootView extends VerticalLayout implements BeforeEnterObserver {

    private final SessionUserService sessionUserService;

    public RootView(SessionUserService sessionUserService) {
        this.sessionUserService = sessionUserService;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (sessionUserService.isAdmin()) {
            event.forwardTo(UserManagementView.class);
        } else {
            event.forwardTo(DashboardView.class);
        }
    }
}
