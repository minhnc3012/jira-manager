package com.jiramanager.repository;

import com.jiramanager.model.AppUser;
import com.jiramanager.model.JiraConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JiraConfigRepository extends JpaRepository<JiraConfig, Long> {
    Optional<JiraConfig> findByUser(AppUser user);

    /**
     * Returns the single shared Jira config, independent of any particular user.
     * Used while login is disabled (see SecurityConfig / AutoLoginFilter) so that
     * Settings / Jira features keep working without depending on "the current user".
     */
    Optional<JiraConfig> findFirstByOrderByIdAsc();
}
