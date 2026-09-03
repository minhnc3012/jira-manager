package com.jiramanager.repository;

import com.jiramanager.model.Space;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpaceRepository extends JpaRepository<Space, Long> {
    List<Space> findByBaseUrlOrderByNameAsc(String baseUrl);
}
