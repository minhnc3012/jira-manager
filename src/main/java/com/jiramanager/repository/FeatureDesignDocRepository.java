package com.jiramanager.repository;

import com.jiramanager.model.ConfluenceFeature;
import com.jiramanager.model.FeatureDesignDoc;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FeatureDesignDocRepository extends JpaRepository<FeatureDesignDoc, Long> {

    // JOIN FETCH uploadedByUser — the UI reads .getUploadedByUser().getFirstName() while
    // rendering, and that must not be a lazy load left to happen outside this query's session.
    @Query("SELECT d FROM FeatureDesignDoc d LEFT JOIN FETCH d.uploadedByUser "
            + "WHERE d.feature = :feature ORDER BY d.version DESC")
    List<FeatureDesignDoc> findByFeatureOrderByVersionDesc(@Param("feature") ConfluenceFeature feature);

    Optional<FeatureDesignDoc> findTopByFeatureOrderByVersionDesc(ConfluenceFeature feature);
    Optional<FeatureDesignDoc> findTopByFeature_IdOrderByVersionDesc(Long featureId);
    Optional<FeatureDesignDoc> findByFeature_IdAndVersion(Long featureId, int version);
}
