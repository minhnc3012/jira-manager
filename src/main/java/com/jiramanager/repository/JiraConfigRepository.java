package com.jiramanager.repository;

import com.jiramanager.model.AppUser;
import com.jiramanager.model.JiraConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JiraConfigRepository extends JpaRepository<JiraConfig, Long> {
    Optional<JiraConfig> findByUser(AppUser user);
}
