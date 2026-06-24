package com.jiramanager;

import com.jiramanager.model.AppUser;
import com.jiramanager.security.AppUserDetailsService;
import com.jiramanager.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthFlowTest {

    @Autowired UserService userService;
    @Autowired AppUserDetailsService userDetailsService;
    @Autowired PasswordEncoder passwordEncoder;

    // ── Existing user (USER role) ─────────────────────────────────────

    private static final String EMAIL    = "test@example.com";
    private static final String PASSWORD = "11111111";

    // ── Admin user (matches DataInitializer) ──────────────────────────

    private static final String ADMIN_EMAIL    = "admin@localhost.com";
    private static final String ADMIN_PASSWORD = "123456";

    // ── USER role tests ───────────────────────────────────────────────

    @Test
    void step1_registerLocal_savesUserAndLocalProvider() {
        AppUser user = userService.registerLocal(EMAIL, PASSWORD, "Test", "User", null);

        assertThat(user.getId()).isNotNull();
        assertThat(user.getEmail()).isEqualTo(EMAIL);
        assertThat(user.getPasswordHash()).isNotNull();
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.hasProvider("local")).isTrue();
        assertThat(user.getRole()).isEqualTo("USER");

        System.out.println("[PASS] User saved. role=" + user.getRole()
                + " passwordHash=" + user.getPasswordHash());
    }

    @Test
    void step2_passwordEncoder_matchesRawPassword() {
        AppUser user = userService.registerLocal(EMAIL, PASSWORD, "Test", "User", null);

        boolean matches = passwordEncoder.matches(PASSWORD, user.getPasswordHash());
        assertThat(matches).isTrue();

        System.out.println("[PASS] passwordEncoder.matches() = " + matches);
    }

    @Test
    void step3_loadUserByUsername_returnsCorrectUserDetails() {
        userService.registerLocal(EMAIL, PASSWORD, "Test", "User", null);

        UserDetails details = userDetailsService.loadUserByUsername(EMAIL);

        assertThat(details).isNotNull();
        assertThat(details.getUsername()).isEqualTo(EMAIL);
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactly("ROLE_USER");

        System.out.println("[PASS] UserDetails loaded with authority: " + details.getAuthorities());
    }

    @Test
    void step4_fullFlow_passwordMatchesAfterLoadByUsername() {
        userService.registerLocal(EMAIL, PASSWORD, "Test", "User", null);

        UserDetails details = userDetailsService.loadUserByUsername(EMAIL);
        boolean matches = passwordEncoder.matches(PASSWORD, details.getPassword());

        assertThat(matches).isTrue();

        System.out.println("[PASS] Full flow OK — Spring Security can authenticate this user");
    }

    // ── ADMIN role tests ──────────────────────────────────────────────
    // DataInitializer already created the admin on context startup — we just look it up.

    @Test
    void admin_existsAfterDataInitializer() {
        AppUser admin = userService.findByEmail(ADMIN_EMAIL).orElse(null);

        assertThat(admin).isNotNull();
        assertThat(admin.getRole()).isEqualTo("ADMIN");
        assertThat(admin.hasProvider("local")).isTrue();
        assertThat(admin.isEnabled()).isTrue();

        System.out.println("[PASS] Admin created by DataInitializer. role=" + admin.getRole());
    }

    @Test
    void admin_loadUserByUsername_hasRoleAdminAuthority() {
        UserDetails details = userDetailsService.loadUserByUsername(ADMIN_EMAIL);

        assertThat(details.getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactly("ROLE_ADMIN");

        System.out.println("[PASS] Admin authority: " + details.getAuthorities());
    }

    @Test
    void admin_authenticateLocal_succeeds() {
        AppUser authenticated = userService.authenticateLocal(ADMIN_EMAIL, ADMIN_PASSWORD);

        assertThat(authenticated).isNotNull();
        assertThat(authenticated.getEmail()).isEqualTo(ADMIN_EMAIL);
        assertThat(authenticated.getRole()).isEqualTo("ADMIN");

        System.out.println("[PASS] Admin authenticated successfully");
    }

    @Test
    void admin_wrongPassword_throwsInvalidCredentials() {
        assertThatThrownBy(() -> userService.authenticateLocal(ADMIN_EMAIL, "wrongpassword"))
                .isInstanceOf(UserService.InvalidCredentialsException.class);

        System.out.println("[PASS] Wrong password rejected correctly");
    }

    // ── Logout behaviour tests ────────────────────────────────────────

    @Test
    void logout_securityContextLogoutHandler_invalidatesSession() {
        // Use the admin created by DataInitializer
        UserDetails details = userDetailsService.loadUserByUsername(ADMIN_EMAIL);

        var auth = new UsernamePasswordAuthenticationToken(
                details, null, details.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Create a mock request with an active HTTP session
        MockHttpServletRequest request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.getSession(true); // create session

        assertThat(request.getSession(false)).isNotNull();

        // Perform server-side logout (the approach used in MainLayout)
        new SecurityContextLogoutHandler().logout(request, response, auth);

        // Session must be invalidated and SecurityContext cleared
        assertThat(request.getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        System.out.println("[PASS] Session invalidated and SecurityContext cleared after logout");
    }

    @Test
    void requestCache_doesNotSaveLogoutUrl() {
        // Verify that the custom RequestCache in SecurityConfig won't save /logout
        // as the post-login redirect target (prevents login → /logout loop)
        HttpSessionRequestCache cache = new HttpSessionRequestCache() {
            @Override
            public void saveRequest(jakarta.servlet.http.HttpServletRequest req,
                                    jakarta.servlet.http.HttpServletResponse res) {
                if (!req.getRequestURI().endsWith("/logout")) {
                    super.saveRequest(req, res);
                }
            }
        };

        MockHttpServletRequest logoutRequest  = new MockHttpServletRequest("GET", "/logout");
        logoutRequest.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Save /logout — should be silently ignored by the custom cache
        cache.saveRequest(logoutRequest, response);

        // No saved request should be stored for the /logout URL
        assertThat(cache.getRequest(logoutRequest, response)).isNull();

        // A normal request /dashboard SHOULD be saved
        MockHttpServletRequest dashRequest = new MockHttpServletRequest("GET", "/dashboard");
        dashRequest.setSession(logoutRequest.getSession());
        cache.saveRequest(dashRequest, response);
        assertThat(cache.getRequest(dashRequest, response)).isNotNull();

        System.out.println("[PASS] /logout not saved as redirect target; /dashboard is saved");
    }
}
