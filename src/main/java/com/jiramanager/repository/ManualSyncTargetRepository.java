package com.jiramanager.repository;

import com.jiramanager.model.ManualSyncTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManualSyncTargetRepository extends JpaRepository<ManualSyncTarget, Long> {
    List<ManualSyncTarget> findByBaseUrl(String baseUrl);
    boolean existsByBaseUrlAndSpaceKeyAndPageId(String baseUrl, String spaceKey, String pageId);
}
