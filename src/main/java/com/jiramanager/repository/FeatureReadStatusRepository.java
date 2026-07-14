package com.jiramanager.repository;

import com.jiramanager.model.AppUser;
import com.jiramanager.model.ConfluenceFeature;
import com.jiramanager.model.FeatureReadStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeatureReadStatusRepository extends JpaRepository<FeatureReadStatus, Long> {
    Optional<FeatureReadStatus> findByFeatureAndUser(ConfluenceFeature feature, AppUser user);
    List<FeatureReadStatus> findByUser(AppUser user);
}
