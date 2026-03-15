package com.jiramanager.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Core user account. One user can authenticate via multiple providers
 * (e.g. local email/password AND Google OAuth2 for the same email).
 */
@Entity
@Table(name = "app_users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Canonical email — used as the unique identity key across providers */
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 100)
    private String firstName;

    @Column(length = 100)
    private String lastName;

    @Column(length = 20)
    private String phoneNumber;

    /** BCrypt-hashed password — null if user registered only via OAuth2 */
    @Column(length = 255)
    private String passwordHash;

    /** Application role: "ADMIN" (user management only) or "USER" (full app features). */
    @Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20) DEFAULT 'USER'")
    @Builder.Default
    private String role = "USER";

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** All authentication providers linked to this account */
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private Set<AuthProvider> providers = new HashSet<>();

    public String getFullName() {
        return lastName != null && !lastName.isBlank()
                ? firstName + " " + lastName
                : firstName;
    }

    public boolean hasProvider(String providerName) {
        return providers.stream().anyMatch(p -> p.getProvider().equals(providerName));
    }
}
