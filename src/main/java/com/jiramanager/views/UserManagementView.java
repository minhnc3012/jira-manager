package com.jiramanager.views;

import com.jiramanager.model.AppUser;
import com.jiramanager.service.UserService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Route(value = "users", layout = MainLayout.class)
@PageTitle("User Management – Jira Manager")
@RolesAllowed("ADMIN")
public class UserManagementView extends VerticalLayout {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final UserService userService;
    private final Grid<AppUser> grid = new Grid<>(AppUser.class, false);

    public UserManagementView(UserService userService) {
        this.userService = userService;

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("background", "#f4f5f7");

        add(buildToolbar(), buildGrid());
        refreshGrid();
    }

    // ── Toolbar ───────────────────────────────────────────────────────

    private HorizontalLayout buildToolbar() {
        Button addBtn = new Button("+ Add user", e -> openUserDialog(null));
        addBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout toolbar = new HorizontalLayout(addBtn);
        toolbar.setWidthFull();
        toolbar.setAlignItems(Alignment.CENTER);
        toolbar.getStyle()
                .set("background", "white")
                .set("padding", "12px 24px")
                .set("border-bottom", "1px solid #dfe1e6");
        return toolbar;
    }

    // ── Grid ──────────────────────────────────────────────────────────

    private Grid<AppUser> buildGrid() {
        grid.setSizeFull();
        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        grid.getStyle().set("background", "white").set("flex", "1");

        grid.addColumn(AppUser::getFullName).setHeader("Full name").setFlexGrow(1).setSortable(true);
        grid.addColumn(AppUser::getEmail).setHeader("Email").setFlexGrow(2).setSortable(true);
        grid.addColumn(AppUser::getPhoneNumber).setHeader("Phone").setWidth("160px").setFlexGrow(0);

        grid.addComponentColumn(user -> {
            Span badge = new Span(user.getRole() != null ? user.getRole() : "USER");
            badge.getStyle()
                    .set("padding", "2px 10px").set("border-radius", "12px")
                    .set("font-size", "12px").set("font-weight", "600");
            if ("ADMIN".equals(user.getRole())) {
                badge.getStyle().set("background", "#fff0b3").set("color", "#172b4d");
            } else {
                badge.getStyle().set("background", "#deebff").set("color", "#0052cc");
            }
            return badge;
        }).setHeader("Role").setWidth("90px").setFlexGrow(0);

        grid.addComponentColumn(user -> {
            Span badge = new Span(user.isEnabled() ? "Active" : "Disabled");
            badge.getStyle()
                    .set("padding", "2px 10px").set("border-radius", "12px")
                    .set("font-size", "12px").set("font-weight", "600");
            if (user.isEnabled()) {
                badge.getStyle().set("background", "#e3fcef").set("color", "#006644");
            } else {
                badge.getStyle().set("background", "#ffebe6").set("color", "#bf2600");
            }
            return badge;
        }).setHeader("Status").setWidth("110px").setFlexGrow(0);

        grid.addColumn(u -> u.getCreatedAt() != null ? u.getCreatedAt().format(DATE_FMT) : "—")
                .setHeader("Created at").setWidth("150px").setFlexGrow(0).setSortable(true);

        grid.addComponentColumn(user -> {
            Button editBtn = new Button(VaadinIcon.EDIT.create());
            editBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            editBtn.getElement().setAttribute("title", "Edit");
            editBtn.addClickListener(e -> openUserDialog(user));

            Button resetPassBtn = new Button(VaadinIcon.KEY.create());
            resetPassBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            resetPassBtn.getElement().setAttribute("title", "Reset password");
            resetPassBtn.addClickListener(e -> openResetPasswordDialog(user));

            Button deleteBtn = new Button(VaadinIcon.TRASH.create());
            deleteBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
            deleteBtn.getElement().setAttribute("title", "Delete");
            deleteBtn.addClickListener(e -> confirmDelete(user));

            HorizontalLayout actions = new HorizontalLayout(editBtn, resetPassBtn, deleteBtn);
            actions.setSpacing(false);
            return actions;
        }).setHeader("Actions").setWidth("150px").setFlexGrow(0);

        return grid;
    }

    private void refreshGrid() {
        List<AppUser> users = userService.findAll();
        grid.setItems(users);
    }

    // ── Add / Edit dialog ─────────────────────────────────────────────

    private void openUserDialog(AppUser existing) {
        boolean isEdit = existing != null;
        Dialog dialog = new Dialog();
        dialog.setWidth("480px");
        dialog.setHeaderTitle(isEdit ? "Edit user" : "Add new user");

        TextField firstNameField = new TextField("First name *");
        firstNameField.setWidthFull();
        TextField lastNameField = new TextField("Last name");
        lastNameField.setWidthFull();
        EmailField emailField = new EmailField("Email *");
        emailField.setWidthFull();
        TextField phoneField = new TextField("Phone number");
        phoneField.setWidthFull();
        Checkbox enabledCheck = new Checkbox("Account active");
        enabledCheck.setValue(true);

        ComboBox<String> roleField = new ComboBox<>("Role");
        roleField.setItems("USER", "ADMIN");
        roleField.setValue("USER");
        roleField.setWidthFull();
        roleField.setAllowCustomValue(false);

        PasswordField passField = new PasswordField("Password *");
        passField.setWidthFull();
        passField.setHelperText("At least 8 characters");

        Span errorMsg = new Span();
        errorMsg.getStyle().set("color", "var(--lumo-error-color)").set("font-size", "13px").set("display", "none");

        if (isEdit) {
            firstNameField.setValue(nvl(existing.getFirstName()));
            lastNameField.setValue(nvl(existing.getLastName()));
            emailField.setValue(nvl(existing.getEmail()));
            phoneField.setValue(nvl(existing.getPhoneNumber()));
            enabledCheck.setValue(existing.isEnabled());
            roleField.setValue(existing.getRole() != null ? existing.getRole() : "USER");
        }

        FormLayout nameRow = new FormLayout(firstNameField, lastNameField);
        nameRow.setWidthFull();
        nameRow.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 2));

        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);
        content.setSpacing(true);
        if (isEdit) {
            content.add(nameRow, emailField, phoneField, roleField, enabledCheck, errorMsg);
        } else {
            content.add(nameRow, emailField, passField, phoneField, roleField, enabledCheck, errorMsg);
        }
        dialog.add(content);

        Button saveBtn = new Button(isEdit ? "Save changes" : "Create account");
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveBtn.addClickListener(e -> {
            String firstName = firstNameField.getValue().trim();
            String email     = emailField.getValue().trim();
            String role      = roleField.getValue() != null ? roleField.getValue() : "USER";

            if (firstName.isEmpty() || email.isEmpty()) {
                showError(errorMsg, "Please fill in all required fields (*)");
                return;
            }

            try {
                if (isEdit) {
                    userService.updateUser(
                            existing.getId(), firstName,
                            lastNameField.getValue().trim(),
                            email, phoneField.getValue().trim(),
                            enabledCheck.getValue(), role);
                } else {
                    String pass = passField.getValue();
                    if (pass.length() < 8) {
                        showError(errorMsg, "Password must be at least 8 characters");
                        return;
                    }
                    AppUser created = userService.registerLocal(email, pass, firstName,
                            lastNameField.getValue().trim(),
                            phoneField.getValue().trim(), role);
                    if (!enabledCheck.getValue()) {
                        userService.updateUser(created.getId(), firstName,
                                lastNameField.getValue().trim(), email,
                                phoneField.getValue().trim(), false, role);
                    }
                }
                dialog.close();
                refreshGrid();
                Notification.show(isEdit ? "User updated." : "User created.",
                        2500, Notification.Position.TOP_CENTER)
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

    // ── Reset password dialog ──────────────────────────────────────────

    private void openResetPasswordDialog(AppUser user) {
        Dialog dialog = new Dialog();
        dialog.setWidth("420px");
        dialog.setHeaderTitle("Reset password — " + user.getFullName());

        Paragraph note = new Paragraph("Set a new password for this user. The old password is not required.");
        note.getStyle().set("color", "#6b778c").set("font-size", "13px").set("margin", "0 0 8px 0");

        PasswordField newPassField = new PasswordField("New password *");
        newPassField.setWidthFull();
        newPassField.setHelperText("At least 8 characters");
        PasswordField confirmPassField = new PasswordField("Confirm new password *");
        confirmPassField.setWidthFull();

        Span errorMsg = new Span();
        errorMsg.getStyle().set("color", "var(--lumo-error-color)").set("font-size", "13px").set("display", "none");

        VerticalLayout content = new VerticalLayout(note, newPassField, confirmPassField, errorMsg);
        content.setPadding(false);
        content.setSpacing(true);
        dialog.add(content);

        Button saveBtn = new Button("Reset password");
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveBtn.addClickListener(e -> {
            String newPass = newPassField.getValue();
            String confirm = confirmPassField.getValue();

            if (newPass.length() < 8) {
                showError(errorMsg, "Password must be at least 8 characters");
                return;
            }
            if (!newPass.equals(confirm)) {
                showError(errorMsg, "Passwords do not match");
                return;
            }

            userService.resetPassword(user.getId(), newPass);
            dialog.close();
            Notification.show("Password reset for " + user.getEmail() + ".",
                    2500, Notification.Position.TOP_CENTER)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        });

        Button cancelBtn = new Button("Cancel", e -> dialog.close());
        cancelBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        dialog.getFooter().add(cancelBtn, saveBtn);
        dialog.open();
    }

    // ── Delete confirmation ───────────────────────────────────────────

    private void confirmDelete(AppUser user) {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Delete user");
        confirm.setText("Are you sure you want to delete \"" + user.getFullName()
                + "\" (" + user.getEmail() + ")? This action cannot be undone.");
        confirm.setCancelable(true);
        confirm.setCancelText("Cancel");
        confirm.setConfirmText("Delete");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(e -> {
            userService.deleteUser(user.getId());
            refreshGrid();
            Notification.show("User deleted.", 2500, Notification.Position.TOP_CENTER)
                    .addThemeVariants(NotificationVariant.LUMO_CONTRAST);
        });
        confirm.open();
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private void showError(Span errorSpan, String message) {
        errorSpan.setText(message);
        errorSpan.getStyle().set("display", "block");
    }

    private String nvl(String value) {
        return value != null ? value : "";
    }
}
