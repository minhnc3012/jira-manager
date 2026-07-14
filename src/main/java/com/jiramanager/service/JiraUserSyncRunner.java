package com.jiramanager.service;

import com.jiramanager.model.JiraCachedUser;
import com.jiramanager.model.JiraConfig;
import com.jiramanager.model.JiraUser;
import com.jiramanager.repository.JiraCachedUserRepository;
import com.jiramanager.repository.JiraConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Keeps {@link JiraCachedUser} (the "member" filter dropdown on Worklog / Worklog Calendar)
 * up to date without ever blocking a page load or the app itself.
 *
 * Runs once in the background on every app start ({@link #run}), and again whenever a user
 * saves a new/changed Jira connection in Settings ({@link #syncOne}), so a freshly-configured
 * site doesn't have to wait for the next restart to populate its member list.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JiraUserSyncRunner implements ApplicationRunner {

    private final JiraConfigRepository     jiraConfigRepo;
    private final JiraCachedUserRepository cacheRepo;
    private final JiraService              jiraService;

    @Override
    public void run(ApplicationArguments args) {
        // Never let the sync delay app startup or the first request — hand it to a virtual thread.
        Thread.ofVirtual().start(this::syncAll);
    }

    private void syncAll() {
        List<JiraConfig> configs = jiraConfigRepo.findAll();

        // One representative config per distinct Jira site — avoids redundant /users/search
        // calls when multiple local accounts point at the same Jira Cloud instance.
        Map<String, JiraConfig> byBaseUrl = configs.stream()
                .filter(JiraUserSyncRunner::isUsable)
                .collect(Collectors.toMap(JiraConfig::getBaseUrl, c -> c, (first, dup) -> first));

        log.info("Starting background Jira user sync for {} site(s)", byBaseUrl.size());
        for (JiraConfig cfg : byBaseUrl.values()) {
            syncOne(cfg);
        }
    }

    /** Refreshes the cached user list for a single Jira site. Safe to call from any thread. */
    public void syncOne(JiraConfig cfg) {
        if (!isUsable(cfg)) return;
        String baseUrl = cfg.getBaseUrl();
        try {
            List<JiraUser> users = jiraService.fetchAssignableUsersFromJira(cfg);
            Instant now = Instant.now();
            for (JiraUser u : users) {
                JiraCachedUser row = cacheRepo.findByBaseUrlAndAccountId(baseUrl, u.accountId())
                        .orElseGet(JiraCachedUser::new);
                row.setBaseUrl(baseUrl);
                row.setAccountId(u.accountId());
                row.setDisplayName(u.displayName());
                row.setSyncedAt(now);
                cacheRepo.save(row);
            }
            log.info("Synced {} Jira users for {}", users.size(), baseUrl);
        } catch (Exception ex) {
            log.warn("Jira user sync failed for {}: {}", baseUrl, ex.getMessage());
        }
    }

    private static boolean isUsable(JiraConfig cfg) {
        return cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank()
                && cfg.getEmail() != null && !cfg.getEmail().isBlank()
                && cfg.getApiToken() != null && !cfg.getApiToken().isBlank();
    }
}
