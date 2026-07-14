package com.jiramanager.repository;

import com.jiramanager.model.ConfluenceFeature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConfluenceFeatureRepository extends JpaRepository<ConfluenceFeature, Long> {
    List<ConfluenceFeature> findByBaseUrl(String baseUrl);
    Optional<ConfluenceFeature> findByBaseUrlAndPageId(String baseUrl, String pageId);
}
