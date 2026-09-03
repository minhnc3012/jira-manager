package com.jiramanager.repository;

import com.jiramanager.model.AppUser;
import com.jiramanager.model.SpaceItem;
import com.jiramanager.model.SpaceItemReadStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpaceItemReadStatusRepository extends JpaRepository<SpaceItemReadStatus, Long> {
    Optional<SpaceItemReadStatus> findByItemAndUser(SpaceItem item, AppUser user);
    List<SpaceItemReadStatus> findByUser(AppUser user);
    List<SpaceItemReadStatus> findByItem(SpaceItem item);
}
