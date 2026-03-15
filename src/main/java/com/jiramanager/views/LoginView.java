package com.jiramanager.views;

import com.jiramanager.service.UserService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@Route("login")
@PageTitle("Login – Jira Manager")
@AnonymousAllowed
public class LoginView extends VerticalLayout implements BeforeEnterObserver {

    private final UserService userService;
    private final LoginForm loginForm = new LoginForm();

    public LoginView(UserService userService) {
        this.userService = userService;

        setSizeFull();
        setAlignItems(FlexComponent.Alignment.CENTER);
        setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
        getStyle().set("background", "var(--lumo-contrast-5pct)");

        loginForm.setAction("login");
        loginForm.setI18n(buildI18n());

        Button registerLink = new Button("Don't have an account? Register now");
        registerLink.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        registerLink.getStyle().set("font-size", "14px");
        registerLink.addClickListener(e -> openRegisterDialog());

        add(loginForm, registerLink);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (event.getLocation().getQueryParameters().getParameters().containsKey("error")) {
            loginForm.setError(true);
        }
    }

    // ── LoginForm i18n ────────────────────────────────────────────────

    private LoginI18n buildI18n() {
        LoginI18n i18n = LoginI18n.createDefault();

        LoginI18n.Header header = new LoginI18n.Header();
        header.setTitle("Jira Manager");
        header.setDescription("Manage your Jira tickets");
        i18n.setHeader(header);

        LoginI18n.Form form = i18n.getForm();
        form.setTitle("Sign in");
        form.setUsername("Email");
        form.setPassword("Password");
        form.setSubmit("Sign in");
        i18n.setForm(form);

        LoginI18n.ErrorMessage error = i18n.getErrorMessage();
        error.setTitle("Incorrect email or password");
        error.setMessage("Please check your credentials and try again.");
        i18n.setErrorMessage(error);

        return i18n;
    }

    // ── Register dialog ───────────────────────────────────────────────

    private void openRegisterDialog() {
        Dialog dialog = new Dialog();
        dialog.setWidth("460px");
        dialog.setHeaderTitle("Create new account");

        TextField firstNameField = new TextField("First name *");
        firstNameField.setWidthFull();
        TextField lastNameField = new TextField("Last name");
        lastNameField.setWidthFull();
        EmailField emailField = new EmailField("Email *");
        emailField.setWidthFull();
        emailField.setPlaceholder("name@example.com");
        PasswordField passField = new PasswordField("Password *");
        passField.setWidthFull();
        passField.setHelperText("At least 8 characters");
        PasswordField confirmField = new PasswordField("Confirm password *");
        confirmField.setWidthFull();
        TextField phoneField = new TextField("Phone number");
        phoneField.setWidthFull();
        phoneField.setPlaceholder("+84 xxx xxx xxx");

        FormLayout nameRow = new FormLayout(firstNameField, lastNameField);
        nameRow.setWidthFull();
        nameRow.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 2));

        Span errorMsg = new Span();
        errorMsg.getStyle().set("color", "var(--lumo-error-color)").set("font-size", "13px").set("display", "none");

        VerticalLayout content = new VerticalLayout(nameRow, emailField, passField, confirmField, phoneField, errorMsg);
        content.setPadding(false);
        dialog.add(content);

        Button saveBtn = new Button("Create account");
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveBtn.addClickListener(e -> {
            String firstName = firstNameField.getValue().trim();
            String email     = emailField.getValue().trim();
            String pass      = passField.getValue();
            String confirm   = confirmField.getValue();

            if (firstName.isEmpty() || email.isEmpty() || pass.isEmpty() || confirm.isEmpty()) {
                showError(errorMsg, "Please fill in all required fields (*)");
                return;
            }
            if (pass.length() < 8) {
                showError(errorMsg, "Password must be at least 8 characters");
                return;
            }
            if (!pass.equals(confirm)) {
                showError(errorMsg, "Passwords do not match");
                confirmField.clear();
                return;
            }

            try {
                userService.registerLocal(email, pass, firstName,
                        lastNameField.getValue().trim().isEmpty() ? null : lastNameField.getValue().trim(),
                        phoneField.getValue().trim().isEmpty() ? null : phoneField.getValue().trim());

                dialog.close();
                Notification.show("Account created! You can now sign in.", 4000, Notification.Position.TOP_CENTER)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } catch (UserService.EmailAlreadyExistsException ex) {
                showError(errorMsg, "This email is already registered.");
            }
        });

        Button cancelBtn = new Button("Cancel", e -> dialog.close());
        cancelBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        dialog.getFooter().add(cancelBtn, saveBtn);
        dialog.open();
    }

    private void showError(Span errorSpan, String message) {
        errorSpan.setText(message);
        errorSpan.getStyle().set("display", "block");
    }
}
