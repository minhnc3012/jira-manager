package com.jiramanager.service;

import com.jiramanager.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs once on startup to ensure the default admin account exists.
 * Safe to run multiple times — it's a no-op when the admin already exists.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private static final String ADMIN_EMAIL    = "admin@keytechx.com";
    private static final String ADMIN_PASSWORD = "Admin@123";

    private final UserRepository userRepository;
    private final UserService    userService;

    @Override
    public void run(ApplicationArguments args) {
        if (!userRepository.existsByEmail(ADMIN_EMAIL)) {
            userService.registerLocal(ADMIN_EMAIL, ADMIN_PASSWORD, "Admin", null, null, "ADMIN");
            log.info("Default admin account created: {}", ADMIN_EMAIL);
        }
    }
}
