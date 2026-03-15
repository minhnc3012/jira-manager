package com.jiramanager.service;

import com.jiramanager.model.AppUser;
import com.jiramanager.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Provides current-user information by reading directly from Spring Security's
 * SecurityContext — reliable on all Vaadin UI threads.
 */
@Service
@RequiredArgsConstructor
public class SessionUserService {

    private final UserRepository userRepo;

    public AppUser getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return userRepo.findByEmail(auth.getName()).orElse(null);
    }

    public String getCurrentUserName() {
        AppUser user = getCurrentUser();
        return user != null ? user.getFullName() : "User";
    }

    public String getCurrentUserEmail() {
        AppUser user = getCurrentUser();
        return user != null ? user.getEmail() : "";
    }

    /** Returns "ADMIN" or "USER". */
    public String getCurrentUserRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return "USER";
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .findFirst()
                .orElse("USER");
    }

    public boolean isAdmin() {
        return "ADMIN".equals(getCurrentUserRole());
    }
}
