package com.jiramanager.views;

import com.jiramanager.model.AppUser;
import com.jiramanager.repository.UserRepository;
import com.jiramanager.service.SessionUserService;
import com.jiramanager.service.UserService;
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
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * My Profile — available to all logged-in users regardless of role.
 * Allows editing first/last name, phone and changing password (local accounts only).
 */
@Route(value = "profile", layout = MainLayout.class)
@PageTitle("My Profile – Jira Manager")
@PermitAll
public class ProfileView extends VerticalLayout {

    private final SessionUserService sessionUserService;
    private final UserService        userService;
    private final UserRepository     userRepository;
    private final PasswordEncoder    passwordEncoder;

    public ProfileView(SessionUserService sessionUserService,
                       UserService userService,
                       UserRepository userRepository,
                       PasswordEncoder passwordEncoder) {
        this.sessionUserService = sessionUserService;
        this.userService        = userService;
        this.userRepository     = userRepository;
        this.passwordEncoder    = passwordEncoder;

        setSizeFull();
        setSpacing(false);
        setPadding(false);
        getStyle().set("background", "#f4f5f7").set("overflow-y", "auto");

        add(buildContent());
    }

    private VerticalLayout buildContent() {
        AppUser user = sessionUserService.getCurrentUser();

        VerticalLayout content = new VerticalLayout();
        content.setWidthFull();
        content.setSpacing(true);
        content.getStyle()
                .set("max-width", "700px")
                .set("margin", "0 auto")
                .set("padding", "32px 24px");

        H2 title = new H2("My Profile");
        title.getStyle().set("margin", "0 0 24px 0").set("color", "#172b4d");

        content.add(title, buildInfoCard(user), buildPasswordCard(user));
        return content;
    }

    // ── Profile info card ─────────────────────────────────────────────

    private VerticalLayout buildInfoCard(AppUser user) {
        VerticalLayout card = card();

        HorizontalLayout header = new HorizontalLayout();
        header.setAlignItems(Alignment.CENTER);
        H3 cardTitle = new H3("Personal Information");
        cardTitle.getStyle().set("margin", "0").set("color", "#172b4d");
        header.add(VaadinIcon.USER.create(), cardTitle);
        header.setSpacing(true);

        TextField firstNameField = new TextField("First name *");
        firstNameField.setWidthFull();
        TextField lastNameField = new TextField("Last name");
        lastNameField.setWidthFull();
        TextField emailField = new TextField("Email");
        emailField.setWidthFull();
        emailField.setReadOnly(true);
        emailField.setHelperText("Email cannot be changed here");
        TextField phoneField = new TextField("Phone number");
        phoneField.setWidthFull();

        // Role badge
        Span roleLabel = new Span("Role: ");
        roleLabel.getStyle().set("font-size", "13px").set("color", "#6b778c");
        String role = user != null ? user.getRole() : "USER";
        Span roleBadge = new Span(role);
        roleBadge.getStyle()
                .set("font-size", "12px").set("font-weight", "600")
                .set("padding", "2px 10px").set("border-radius", "12px")
                .set("background", "ADMIN".equals(role) ? "#fff0b3" : "#deebff")
                .set("color",      "ADMIN".equals(role) ? "#172b4d" : "#0052cc");
        HorizontalLayout roleRow = new HorizontalLayout(roleLabel, roleBadge);
        roleRow.setAlignItems(Alignment.CENTER);
        roleRow.setSpacing(false);

        if (user != null) {
            firstNameField.setValue(nvl(user.getFirstName()));
            lastNameField.setValue(nvl(user.getLastName()));
            emailField.setValue(nvl(user.getEmail()));
            phoneField.setValue(nvl(user.getPhoneNumber()));
        }

        Span errorLabel = new Span();
        errorLabel.getStyle().set("color", "var(--lumo-error-color)").set("font-size", "13px").set("display", "none");

        Button saveBtn = new Button("Save changes", VaadinIcon.CHECK.create());
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveBtn.addClickListener(e -> {
            if (user == null) return;
            String firstName = firstNameField.getValue().trim();
            if (firstName.isEmpty()) {
                errorLabel.setText("First name is required.");
                errorLabel.getStyle().set("display", "block");
                return;
            }
            errorLabel.getStyle().set("display", "none");
            try {
                userService.updateUser(user.getId(), firstName,
                        lastNameField.getValue().trim(),
                        user.getEmail(),
                        phoneField.getValue().trim(),
                        user.isEnabled());
                Notification.show("Profile updated.", 2500, Notification.Position.TOP_CENTER)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } catch (Exception ex) {
                errorLabel.setText("Error: " + ex.getMessage());
                errorLabel.getStyle().set("display", "block");
            }
        });

        card.add(header, firstNameField, lastNameField, emailField, phoneField, roleRow, errorLabel, saveBtn);
        return card;
    }

    // ── Password change card ──────────────────────────────────────────

    private VerticalLayout buildPasswordCard(AppUser user) {
        VerticalLayout card = card();

        HorizontalLayout header = new HorizontalLayout();
        header.setAlignItems(Alignment.CENTER);
        H3 cardTitle = new H3("Change Password");
        cardTitle.getStyle().set("margin", "0").set("color", "#172b4d");
        header.add(VaadinIcon.LOCK.create(), cardTitle);
        header.setSpacing(true);

        boolean hasLocalPassword = user != null && user.getPasswordHash() != null;

        if (!hasLocalPassword) {
            Paragraph note = new Paragraph("Your account uses social login. Password change is not available.");
            note.getStyle().set("color", "#6b778c").set("font-size", "13px");
            card.add(header, note);
            return card;
        }

        PasswordField currentPassField = new PasswordField("Current password *");
        currentPassField.setWidthFull();
        PasswordField newPassField = new PasswordField("New password *");
        newPassField.setWidthFull();
        newPassField.setHelperText("At least 8 characters");
        PasswordField confirmPassField = new PasswordField("Confirm new password *");
        confirmPassField.setWidthFull();

        Span errorLabel = new Span();
        errorLabel.getStyle().set("color", "var(--lumo-error-color)").set("font-size", "13px").set("display", "none");

        Button changeBtn = new Button("Change password", VaadinIcon.KEY.create());
        changeBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        changeBtn.addClickListener(e -> {
            if (user == null) return;
            String current = currentPassField.getValue();
            String newPass  = newPassField.getValue();
            String confirm  = confirmPassField.getValue();

            if (current.isEmpty() || newPass.isEmpty() || confirm.isEmpty()) {
                showError(errorLabel, "Please fill in all password fields.");
                return;
            }
            if (!passwordEncoder.matches(current, user.getPasswordHash())) {
                showError(errorLabel, "Current password is incorrect.");
                return;
            }
            if (newPass.length() < 8) {
                showError(errorLabel, "New password must be at least 8 characters.");
                return;
            }
            if (!newPass.equals(confirm)) {
                showError(errorLabel, "New passwords do not match.");
                return;
            }

            user.setPasswordHash(passwordEncoder.encode(newPass));
            userRepository.save(user);

            currentPassField.clear();
            newPassField.clear();
            confirmPassField.clear();
            errorLabel.getStyle().set("display", "none");

            Notification.show("Password changed successfully.", 2500, Notification.Position.TOP_CENTER)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        });

        card.add(header, currentPassField, newPassField, confirmPassField, errorLabel, changeBtn);
        return card;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private VerticalLayout card() {
        VerticalLayout card = new VerticalLayout();
        card.setWidthFull();
        card.setSpacing(true);
        card.setPadding(true);
        card.getStyle()
                .set("background", "white")
                .set("border-radius", "8px")
                .set("box-shadow", "0 1px 3px rgba(0,0,0,.1)")
                .set("padding", "24px");
        return card;
    }

    private void showError(Span label, String msg) {
        label.setText(msg);
        label.getStyle().set("display", "block");
    }

    private String nvl(String s) { return s != null ? s : ""; }
}
