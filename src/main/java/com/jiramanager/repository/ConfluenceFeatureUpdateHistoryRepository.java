package com.jiramanager.repository;

import com.jiramanager.model.ConfluenceFeature;
import com.jiramanager.model.ConfluenceFeatureUpdateHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConfluenceFeatureUpdateHistoryRepository extends JpaRepository<ConfluenceFeatureUpdateHistory, Long> {
    List<ConfluenceFeatureUpdateHistory> findByFeatureOrderByDetectedAtDesc(ConfluenceFeature feature);
}
