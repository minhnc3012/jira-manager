package com.jiramanager.repository;

import com.jiramanager.model.JiraCachedUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JiraCachedUserRepository extends JpaRepository<JiraCachedUser, Long> {
    List<JiraCachedUser> findByBaseUrlOrderByDisplayNameAsc(String baseUrl);
    Optional<JiraCachedUser> findByBaseUrlAndAccountId(String baseUrl, String accountId);
}
