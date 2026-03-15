package com.jiramanager.views;

import com.jiramanager.model.AppUser;
import com.jiramanager.model.JiraConfig;
import com.jiramanager.repository.JiraConfigRepository;
import com.jiramanager.service.JiraService;
import com.jiramanager.service.SessionUserService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

import java.time.LocalDateTime;

/**
 * Settings view — per-user Jira connection configuration stored in the database.
 * Replaces the old application.properties Jira properties.
 */
@Route(value = "settings", layout = MainLayout.class)
@PageTitle("Settings – Jira Manager")
@RolesAllowed("USER")
public class SettingsView extends VerticalLayout implements BeforeEnterObserver {

    private static final String PARAM_JIRA_REQUIRED = "jira_required";

    private final JiraConfigRepository jiraConfigRepo;
    private final SessionUserService   sessionUserService;
    private final JiraService          jiraService;

    // Set to true when user is redirected here from a Jira feature
    private boolean redirectedFromJiraFeature = false;

    public SettingsView(JiraConfigRepository jiraConfigRepo,
                        SessionUserService sessionUserService,
                        JiraService jiraService) {
        this.jiraConfigRepo    = jiraConfigRepo;
        this.sessionUserService = sessionUserService;
        this.jiraService        = jiraService;

        setSizeFull();
        setSpacing(false);
        setPadding(false);
        getStyle().set("background", "#f4f5f7").set("overflow-y", "auto");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        // Check if redirected from My Tickets / Worklog via jira_required query param
        redirectedFromJiraFeature = event.getLocation().getQueryParameters()
                .getParameters().containsKey(PARAM_JIRA_REQUIRED);
        add(buildContent());
    }

    private VerticalLayout buildContent() {
        VerticalLayout content = new VerticalLayout();
        content.setWidthFull();
        content.setSpacing(true);
        content.getStyle()
                .set("max-width", "700px")
                .set("margin", "0 auto")
                .set("padding", "32px 24px");

        // Page title
        H2 title = new H2("Settings");
        title.getStyle().set("margin", "0 0 4px 0").set("color", "#172b4d");

        // Show prominent banner when redirected from a Jira feature
        if (redirectedFromJiraFeature) {
            Div banner = new Div();
            banner.getStyle()
                    .set("background", "#deebff")
                    .set("border", "1px solid #4c9aff")
                    .set("border-radius", "6px")
                    .set("padding", "12px 16px")
                    .set("margin-bottom", "8px")
                    .set("font-size", "14px")
                    .set("color", "#0747a6");
            banner.add(new Span(
                    "Jira connection required. Configure your credentials below to use My Tickets, Worklog, and Reports."));
            content.add(banner);
        }

        Paragraph subtitle = new Paragraph("Configure your personal Jira connection. Each user has their own credentials.");
        subtitle.getStyle().set("color", "#6b778c").set("margin", "0 0 24px 0");

        content.add(title, subtitle, buildJiraConfigCard());
        return content;
    }

    private VerticalLayout buildJiraConfigCard() {
        AppUser currentUser = sessionUserService.getCurrentUser();
        JiraConfig existing = currentUser != null
                ? jiraConfigRepo.findByUser(currentUser).orElse(null)
                : null;

        // Card wrapper
        VerticalLayout card = new VerticalLayout();
        card.setWidthFull();
        card.setSpacing(true);
        card.setPadding(true);
        card.getStyle()
                .set("background", "white")
                .set("border-radius", "8px")
                .set("box-shadow", "0 1px 3px rgba(0,0,0,.1)")
                .set("padding", "24px");

        // Section header
        HorizontalLayout header = new HorizontalLayout();
        header.setAlignItems(Alignment.CENTER);
        header.setSpacing(true);

        Span icon = new Span(VaadinIcon.PLUG.create());
        icon.getStyle().set("color", "#0052cc");

        H3 sectionTitle = new H3("Jira Connection");
        sectionTitle.getStyle().set("margin", "0").set("color", "#172b4d");

        header.add(icon, sectionTitle);

        // Fields
        TextField baseUrlField = new TextField("Jira Base URL");
        baseUrlField.setWidthFull();
        baseUrlField.setPlaceholder("https://yourcompany.atlassian.net");
        baseUrlField.setHelperText("Your Jira Cloud domain — no trailing slash");

        TextField emailField = new TextField("Jira Account Email");
        emailField.setWidthFull();
        emailField.setPlaceholder("you@example.com");
        emailField.setHelperText("The email address you use to log into Jira");

        PasswordField tokenField = new PasswordField("API Token");
        tokenField.setWidthFull();
        tokenField.setPlaceholder("ATATT3x...");
        tokenField.setHelperText("Generate at: id.atlassian.com → Security → API tokens");

        // Pre-fill if config exists
        if (existing != null) {
            if (existing.getBaseUrl()  != null) baseUrlField.setValue(existing.getBaseUrl());
            if (existing.getEmail()    != null) emailField.setValue(existing.getEmail());
            if (existing.getApiToken() != null) tokenField.setValue(existing.getApiToken());
        }

        // Status label
        Span statusLabel = new Span();
        statusLabel.getStyle().set("font-size", "13px");

        // Buttons
        Button testBtn = new Button("Test Connection", VaadinIcon.CHECK_CIRCLE.create());
        testBtn.addThemeVariants(ButtonVariant.LUMO_CONTRAST);
        testBtn.addClickListener(e -> {
            String baseUrl = baseUrlField.getValue().trim();
            String email   = emailField.getValue().trim();
            String token   = tokenField.getValue().trim();
            if (baseUrl.isEmpty() || email.isEmpty() || token.isEmpty()) {
                showStatus(statusLabel, "Please fill in all fields before testing.", false);
                return;
            }
            // Save temporarily then test
            saveConfig(currentUser, existing, baseUrl, email, token);
            try {
                jiraService.getMyTickets(); // will use the just-saved config
                showStatus(statusLabel, "Connection successful!", true);
            } catch (JiraService.JiraNotConfiguredException ex) {
                showStatus(statusLabel, "Configuration incomplete: " + ex.getMessage(), false);
            } catch (Exception ex) {
                showStatus(statusLabel, "Connection failed: " + ex.getMessage(), false);
            }
        });

        Button saveBtn = new Button("Save", VaadinIcon.CHECK.create());
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveBtn.addClickListener(e -> {
            String baseUrl = baseUrlField.getValue().trim();
            String email   = emailField.getValue().trim();
            String token   = tokenField.getValue().trim();
            if (baseUrl.isEmpty() || email.isEmpty() || token.isEmpty()) {
                showStatus(statusLabel, "All fields are required.", false);
                return;
            }
            saveConfig(currentUser, existing, baseUrl, email, token);
            Notification.show("Settings saved.", 2500, Notification.Position.TOP_CENTER)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            showStatus(statusLabel, "Settings saved successfully.", true);
        });

        HorizontalLayout buttons = new HorizontalLayout(testBtn, saveBtn);
        buttons.setSpacing(true);

        // API token help link
        Anchor tokenLink = new Anchor(
                "https://id.atlassian.com/manage-profile/security/api-tokens",
                "How to create an API token ↗");
        tokenLink.setTarget("_blank");
        tokenLink.getStyle().set("font-size", "13px").set("color", "#0052cc");

        card.add(header, baseUrlField, emailField, tokenField, tokenLink, buttons, statusLabel);
        return card;
    }

    private void saveConfig(AppUser user, JiraConfig existing, String baseUrl, String email, String token) {
        if (existing != null) {
            existing.setBaseUrl(baseUrl);
            existing.setEmail(email);
            existing.setApiToken(token);
            existing.setUpdatedAt(LocalDateTime.now());
            jiraConfigRepo.save(existing);
        } else {
            jiraConfigRepo.save(JiraConfig.builder()
                    .user(user)
                    .baseUrl(baseUrl)
                    .email(email)
                    .apiToken(token)
                    .build());
        }
    }

    private void showStatus(Span label, String message, boolean success) {
        label.setText(message);
        label.getStyle().set("color", success ? "#006644" : "#bf2600");
    }
}
