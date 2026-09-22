package com.jiramanager.service;

import com.jiramanager.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Runs once on startup to ensure the default admin account exists.
 * Safe to run multiple times — it's a no-op when the admin already exists.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private static final String ADMIN_EMAIL    = "admin@localhost.com";
    private static final String ADMIN_PASSWORD = "123456";

    private final UserRepository userRepository;
    private final UserService    userService;

    // TEMPORARY: login is disabled — every request auto-authenticates as this account (see
    // SecurityConfig / AutoLoginFilter). On a brand-new install its DB has no users yet, so
    // this account must exist from the very first run, or SessionUserService.getCurrentUser()
    // would return null everywhere (breaking Settings, Spaces attribution, etc.) until someone
    // manually registered it. Keep this in sync with app.auto-login.email in
    // application.properties.
    @Value("${app.auto-login.email:minh@keytechx.com}")
    private String autoLoginEmail;

    @Override
    public void run(ApplicationArguments args) {
        if (!userRepository.existsByEmail(ADMIN_EMAIL)) {
            userService.registerLocal(ADMIN_EMAIL, ADMIN_PASSWORD, "Admin", null, null, "ADMIN");
            log.info("Default admin account created: {}", ADMIN_EMAIL);
        }

        if (!userRepository.existsByEmail(autoLoginEmail.toLowerCase().trim())) {
            String randomPassword = generateRandomPassword();
            String displayName = autoLoginEmail.contains("@")
                    ? autoLoginEmail.substring(0, autoLoginEmail.indexOf('@'))
                    : autoLoginEmail;
            userService.registerLocal(autoLoginEmail, randomPassword, displayName, null, null, "USER");
            log.info("Auto-login account created: {} (password: {} — only needed once login is " +
                    "re-enabled; see SecurityConfig)", autoLoginEmail, randomPassword);
        }
    }

    private String generateRandomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
