package com.jiramanager.repository;

import com.jiramanager.model.SpaceItem;
import com.jiramanager.model.SpaceItemUpdateHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpaceItemUpdateHistoryRepository extends JpaRepository<SpaceItemUpdateHistory, Long> {
    List<SpaceItemUpdateHistory> findByItemOrderByDetectedAtDesc(SpaceItem item);
}
