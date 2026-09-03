package com.jiramanager.repository;

import com.jiramanager.model.Space;
import com.jiramanager.model.SpaceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpaceItemRepository extends JpaRepository<SpaceItem, Long> {
    List<SpaceItem> findBySpace(Space space);
    List<SpaceItem> findBySpace_Id(Long spaceId);
    List<SpaceItem> findBySpace_IdAndParentIsNull(Long spaceId);
    List<SpaceItem> findByParent_Id(Long parentId);

    /**
     * Every linked item (has a Confluence page attached) for the given Jira site, with its
     * {@code space} eagerly fetched — needed because this feeds the background sync runner,
     * which runs on a virtual thread with no HTTP session.
     */
    @Query("SELECT i FROM SpaceItem i JOIN FETCH i.space s "
            + "WHERE s.baseUrl = :baseUrl AND i.confluencePageId IS NOT NULL")
    List<SpaceItem> findLinkedBySpaceBaseUrl(@Param("baseUrl") String baseUrl);
}
