package com.jiramanager.repository;

import com.jiramanager.model.AppUser;
import com.jiramanager.model.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AuthProviderRepository extends JpaRepository<AuthProvider, Long> {
    Optional<AuthProvider> findByProviderAndProviderId(String provider, String providerId);
    Optional<AuthProvider> findByUserAndProvider(AppUser user, String provider);
    boolean existsByUserAndProvider(AppUser user, String provider);
}
