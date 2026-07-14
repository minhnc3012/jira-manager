package com.jiramanager.repository;

import com.jiramanager.model.TicketDoc;
import com.jiramanager.model.TicketDocItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TicketDocItemRepository extends JpaRepository<TicketDocItem, Long> {
    List<TicketDocItem> findByTicketDoc(TicketDoc ticketDoc);
    Optional<TicketDocItem> findByTicketDocAndTicketKey(TicketDoc ticketDoc, String ticketKey);

    /**
     * Items whose Feature belongs to the given Jira site — joined/fetched in one query so callers
     * never trip a LazyInitializationException navigating {@code item.getTicketDoc().getFeature()}
     * after the session that loaded the item has closed (e.g. from a background sync thread,
     * which isn't covered by Spring's open-in-view since it's not an HTTP request).
     */
    @Query("SELECT i FROM TicketDocItem i JOIN FETCH i.ticketDoc d JOIN FETCH d.feature f WHERE f.baseUrl = :baseUrl")
    List<TicketDocItem> findByFeatureBaseUrl(@Param("baseUrl") String baseUrl);
}
