package com.jiramanager.repository;

import com.jiramanager.model.ConfluenceFeature;
import com.jiramanager.model.TicketDoc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TicketDocRepository extends JpaRepository<TicketDoc, Long> {
    Optional<TicketDoc> findByFeature(ConfluenceFeature feature);
}
