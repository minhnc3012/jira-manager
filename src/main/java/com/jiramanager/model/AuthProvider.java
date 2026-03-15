package com.jiramanager.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Represents one authentication method linked to an AppUser.
 *
 * A single user can have multiple rows — one per provider — allowing login
 * via any of their linked accounts:
 *
 *   provider = "local"    → email/password
 *   provider = "google"   → Google OAuth2
 *   provider = "github"   → GitHub OAuth2
 *   provider = "facebook" → Facebook OAuth2
 *   ... (add more as needed)
 *
 * To add a new OAuth2 provider:
 *   1. Add a constant below (e.g. public static final String GITHUB = "github")
 *   2. Add the dependency + credentials in pom.xml / application.properties
 *   3. Register the provider in SecurityConfig.configure() via http.oauth2Login()
 *   4. CustomOAuth2UserService.loadUser() reads registrationId automatically —
 *      no code change needed there, it calls handleOAuth2Login(provider, ...) generically
 */
@Entity
@Table(name = "auth_providers",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "provider"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuthProvider {

    // ── Provider name constants ───────────────────────────────────────
    public static final String LOCAL    = "local";
    public static final String GOOGLE   = "google";
    public static final String GITHUB   = "github";
    public static final String FACEBOOK = "facebook";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    /** One of the provider constants above */
    @Column(nullable = false, length = 32)
    private String provider;

    /** Unique user ID from the OAuth2 provider (null for "local") */
    @Column(length = 255)
    private String providerId;

    /** Email reported by this provider */
    @Column(length = 255)
    private String providerEmail;
}
