package com.jiramanager;

import com.jiramanager.model.AppUser;
import com.jiramanager.repository.JiraConfigRepository;
import com.jiramanager.repository.UserRepository;
import com.jiramanager.security.AppUserDetailsService;
import com.jiramanager.service.JiraService;
import com.jiramanager.service.JiraUserSyncRunner;
import com.jiramanager.service.KnowledgeBaseSyncRunner;
import com.jiramanager.service.SessionUserService;
import com.jiramanager.views.DashboardView;
import com.jiramanager.views.SettingsView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Simulates a completely fresh install: an empty DB (the {@code test} profile's H2 is
 * create-drop, so this class starts with none of the file-based dev DB's data — no users, no
 * JiraConfig), then exercises exactly what a real request does with login disabled:
 * DataInitializer creates the auto-login account at startup, AutoLoginFilter authenticates as
 * it on every request, and the main views build their UI from that state. None of this should
 * ever NPE or blow up on a missing/null current user.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FreshInstallNoUserTest {

    @Autowired UserRepository userRepository;
    @Autowired AppUserDetailsService userDetailsService;
    @Autowired SessionUserService sessionUserService;
    @Autowired JiraService jiraService;
    @Autowired JiraConfigRepository jiraConfigRepo;
    @Autowired JiraUserSyncRunner jiraUserSyncRunner;
    @Autowired KnowledgeBaseSyncRunner knowledgeBaseSyncRunner;

    @Value("${app.auto-login.email:minh@keytechx.com}")
    private String autoLoginEmail;

    /** Mirrors AutoLoginFilter's own authentication logic exactly. */
    private void authenticateAsAutoLoginAccount() {
        UserDetails userDetails = userDetailsService.loadUserByUsername(autoLoginEmail);
        var auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void dataInitializer_createsAutoLoginAccount_onFreshDb() {
        // Nothing has run yet in this test except context startup (DataInitializer runs there).
        AppUser user = userRepository.findByEmail(autoLoginEmail.toLowerCase().trim()).orElse(null);

        assertThat(user).as("auto-login account must exist from the very first run").isNotNull();
        assertThat(user.getRole()).isEqualTo("USER");
        assertThat(user.hasProvider("local")).isTrue();
        assertThat(user.isEnabled()).isTrue();

        System.out.println("[PASS] Auto-login account auto-created on fresh DB: " + user.getEmail());
    }

    @Test
    void sessionUserService_resolvesCurrentUser_afterAutoLogin_onFreshDb() {
        authenticateAsAutoLoginAccount();

        AppUser current = sessionUserService.getCurrentUser();
        assertThat(current).as("getCurrentUser() must not be null once auto-login has run").isNotNull();
        assertThat(current.getEmail()).isEqualTo(autoLoginEmail.toLowerCase().trim());

        assertThat(sessionUserService.getCurrentUserName()).isNotBlank();
        assertThat(sessionUserService.getCurrentUserEmail()).isEqualTo(autoLoginEmail.toLowerCase().trim());
        assertThat(sessionUserService.isAdmin()).isFalse();

        System.out.println("[PASS] SessionUserService resolves a real user after auto-login");
    }

    @Test
    void sessionUserService_isNullSafe_whenNoAuthenticationAtAll() {
        // Simulates AutoLoginFilter itself failing to find the account (defensive fallback path)
        SecurityContextHolder.clearContext();

        assertThatCode(() -> {
            assertThat(sessionUserService.getCurrentUser()).isNull();
            assertThat(sessionUserService.getCurrentUserName()).isEqualTo("User");
            assertThat(sessionUserService.getCurrentUserEmail()).isEqualTo("");
            assertThat(sessionUserService.isAdmin()).isFalse();
        }).as("SessionUserService must degrade gracefully, never throw, with no authentication")
          .doesNotThrowAnyException();

        System.out.println("[PASS] SessionUserService is null-safe with no authentication at all");
    }

    @Test
    void jiraService_isConfigured_falseNotException_onFreshDb() {
        authenticateAsAutoLoginAccount();

        // No JiraConfig row exists at all yet (fresh DB) — must report "not configured", not throw.
        assertThat(jiraConfigRepo.findFirstByOrderByIdAsc()).isEmpty();
        assertThatCode(() -> assertThat(jiraService.isConfigured()).isFalse())
                .doesNotThrowAnyException();

        // getMyTickets() etc. should surface the expected "not configured" exception, not a
        // NullPointerException, when nothing has been set up yet.
        assertThatCode(jiraService::getMyTickets)
                .isInstanceOf(JiraService.JiraNotConfiguredException.class);

        System.out.println("[PASS] JiraService reports 'not configured' cleanly on an empty DB");
    }

    // NOTE: MainLayout itself isn't unit-tested here — its side nav builds SideNavItem(...,
    // ViewClass.class) entries, which need a live VaadinService (real request/session) to
    // resolve routes, so constructing it outside a real Vaadin request always NPEs regardless
    // of app correctness. It's covered instead by an end-to-end smoke test: booting the packaged
    // jar against an empty DB and curling "/", "/dashboard", "/settings", etc. — all returned
    // 200 with zero server-side exceptions logged.

    @Test
    void dashboardView_buildsWithoutError_onFreshDb() {
        authenticateAsAutoLoginAccount();

        assertThatCode(() -> new DashboardView(jiraService, sessionUserService))
                .doesNotThrowAnyException();

        System.out.println("[PASS] DashboardView builds cleanly");
    }

    @Test
    void settingsView_jiraConfigCard_buildsWithoutError_onFreshDb() throws Exception {
        authenticateAsAutoLoginAccount();

        SettingsView view = new SettingsView(
                jiraConfigRepo, sessionUserService, jiraService, jiraUserSyncRunner, knowledgeBaseSyncRunner);

        // buildJiraConfigCard() is private — it's exactly the method that used to look up
        // JiraConfig by the current user and now uses the shared-config lookup instead.
        Method m = SettingsView.class.getDeclaredMethod("buildJiraConfigCard");
        m.setAccessible(true);

        assertThatCode(() -> m.invoke(view))
                .as("Settings' Jira config card must build even with zero JiraConfig rows in the DB")
                .doesNotThrowAnyException();

        System.out.println("[PASS] SettingsView's Jira config card builds cleanly with no JiraConfig yet");
    }
}
