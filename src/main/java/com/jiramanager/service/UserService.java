package com.jiramanager.service;

import java.util.List;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jiramanager.model.AppUser;
import com.jiramanager.model.AuthProvider;
import com.jiramanager.repository.AuthProviderRepository;
import com.jiramanager.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepo;
    private final AuthProviderRepository authProviderRepo;
    private final PasswordEncoder passwordEncoder;

    // ── Local registration ────────────────────────────────────────────

    /**
     * Self-registration: always assigns role "USER".
     */
    @Transactional
    public AppUser registerLocal(String email, String password,
                                 String firstName, String lastName, String phone) {
        return registerLocal(email, password, firstName, lastName, phone, "USER");
    }

    /**
     * Admin-initiated registration: allows specifying an explicit role ("USER" or "ADMIN").
     */
    @Transactional
    public AppUser registerLocal(String email, String password,
                                 String firstName, String lastName, String phone, String role) {
        if (userRepo.existsByEmail(email.toLowerCase().trim())) {
            throw new EmailAlreadyExistsException(email);
        }

        AppUser user = AppUser.builder()
                .email(email.toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(password))
                .firstName(firstName)
                .lastName(lastName)
                .phoneNumber(phone)
                .role(role != null ? role : "USER")
                .build();
        userRepo.save(user);

        AuthProvider localProvider = AuthProvider.builder()
                .user(user)
                .provider(AuthProvider.LOCAL)
                .providerEmail(email.toLowerCase().trim())
                .build();
        authProviderRepo.save(localProvider);
        user.getProviders().add(localProvider); // keep in-memory collection in sync with DB

        log.info("New local user registered: {}", email);
        return user;
    }

    // ── Local login ───────────────────────────────────────────────────

    /**
     * Validate email + password. Returns the user or throws InvalidCredentialsException.
     */
    public AppUser authenticateLocal(String email, String rawPassword) {
        AppUser user = userRepo.findByEmail(email.toLowerCase().trim())
                .orElseThrow(InvalidCredentialsException::new);

        if (!user.hasProvider(AuthProvider.LOCAL) || user.getPasswordHash() == null) {
            throw new InvalidCredentialsException("This account uses social login. Please sign in with the linked provider.");
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return user;
    }

    // ── OAuth2 login (provider-agnostic) ─────────────────────────────

    /**
     * Called after any OAuth2 provider (Google, GitHub, Facebook, …) succeeds.
     *
     * Three scenarios:
     *   1. Provider UID already linked → return existing user (happy path)
     *   2. Email exists (local account) → signal that account linking is needed
     *   3. Brand-new email → auto-create account and link the provider
     *
     * @param provider   e.g. "google", "github", "facebook"
     * @param providerId unique user ID from the provider (e.g. Google "sub")
     * @param email      email reported by the provider
     * @param firstName  first name (may be null for some providers)
     * @param lastName   last name (may be null for some providers)
     */
    @Transactional
    public OAuth2LoginResult handleOAuth2Login(String provider, String providerId,
                                               String email, String firstName, String lastName) {
        // Scenario 1: already linked
        Optional<AuthProvider> existing = authProviderRepo.findByProviderAndProviderId(provider, providerId);
        if (existing.isPresent()) {
            return OAuth2LoginResult.success(existing.get().getUser());
        }

        // Scenario 2: email exists but this provider is not yet linked
        Optional<AppUser> existingUser = userRepo.findByEmail(email.toLowerCase().trim());
        if (existingUser.isPresent()) {
            AppUser user = existingUser.get();
            if (!authProviderRepo.existsByUserAndProvider(user, provider)) {
                return OAuth2LoginResult.needsLinking(user, provider, providerId);
            }
        }

        // Scenario 3: new user — auto-create and link
        AppUser newUser = AppUser.builder()
                .email(email.toLowerCase().trim())
                .firstName(firstName != null ? firstName : "User")
                .lastName(lastName)
                .build();
        userRepo.save(newUser);

        linkOAuth2Provider(newUser, provider, providerId, email);
        log.info("Auto-created account via {} for: {}", provider, email);
        return OAuth2LoginResult.success(newUser);
    }

    /**
     * Link an OAuth2 provider to an existing user account.
     * Call this after the user confirms account linking.
     */
    @Transactional
    public void linkOAuth2Provider(AppUser user, String provider, String providerId, String email) {
        if (!authProviderRepo.existsByUserAndProvider(user, provider)) {
            authProviderRepo.save(AuthProvider.builder()
                    .user(user)
                    .provider(provider)
                    .providerId(providerId)
                    .providerEmail(email)
                    .build());
            log.info("Linked {} account to user: {}", provider, user.getEmail());
        }
    }

    // ── User management ───────────────────────────────────────────────

    public List<AppUser> findAll() {
        return userRepo.findAll();
    }

    public Optional<AppUser> findByEmail(String email) {
        return userRepo.findByEmail(email.toLowerCase().trim());
    }

    @Transactional
    public AppUser updateUser(Long id, String firstName, String lastName,
                              String email, String phone, boolean enabled) {
        return updateUser(id, firstName, lastName, email, phone, enabled, null);
    }

    @Transactional
    public AppUser updateUser(Long id, String firstName, String lastName,
                              String email, String phone, boolean enabled, String role) {
        AppUser user = userRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
        if (!user.getEmail().equals(email.toLowerCase().trim())
                && userRepo.existsByEmail(email.toLowerCase().trim())) {
            throw new EmailAlreadyExistsException(email);
        }
        user.setFirstName(firstName);
        user.setLastName(lastName != null && !lastName.isBlank() ? lastName : null);
        user.setEmail(email.toLowerCase().trim());
        user.setPhoneNumber(phone != null && !phone.isBlank() ? phone : null);
        user.setEnabled(enabled);
        if (role != null && !role.isBlank()) {
            user.setRole(role);
        }
        return userRepo.save(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        userRepo.deleteById(id);
    }

    // ── Result & exception types ──────────────────────────────────────

    /**
     * Result of an OAuth2 login attempt.
     * When needsLinking=true, the UI should ask the user to confirm linking
     * their existing local account with the new provider.
     */
    public record OAuth2LoginResult(
            AppUser user,
            String pendingProvider,
            String pendingProviderId,
            boolean needsLinking) {

        static OAuth2LoginResult success(AppUser user) {
            return new OAuth2LoginResult(user, null, null, false);
        }

        static OAuth2LoginResult needsLinking(AppUser user, String provider, String providerId) {
            return new OAuth2LoginResult(user, provider, providerId, true);
        }
    }

    public static class EmailAlreadyExistsException extends RuntimeException {
        public EmailAlreadyExistsException(String email) {
            super("Email already registered: " + email);
        }
    }

    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() { super("Invalid email or password"); }
        public InvalidCredentialsException(String msg) { super(msg); }
    }
}
