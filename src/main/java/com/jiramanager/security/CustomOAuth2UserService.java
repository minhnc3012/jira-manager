package com.jiramanager.security;

// ─────────────────────────────────────────────────────────────────────────────
// TO ENABLE OAUTH2 LOGIN (Google, GitHub, Facebook, …):
//
// 1. Uncomment spring-boot-starter-oauth2-client in pom.xml
// 2. Add provider credentials in application.properties:
//
//      # Google
//      spring.security.oauth2.client.registration.google.client-id=...
//      spring.security.oauth2.client.registration.google.client-secret=...
//      spring.security.oauth2.client.registration.google.scope=openid,profile,email
//
//      # GitHub
//      spring.security.oauth2.client.registration.github.client-id=...
//      spring.security.oauth2.client.registration.github.client-secret=...
//      spring.security.oauth2.client.registration.github.scope=read:user,user:email
//
//      # Facebook
//      spring.security.oauth2.client.registration.facebook.client-id=...
//      spring.security.oauth2.client.registration.facebook.client-secret=...
//      spring.security.oauth2.client.registration.facebook.scope=email,public_profile
//
// 3. Uncomment the class body below
// 4. In SecurityConfig.configure(), add:
//      http.oauth2Login(oauth2 -> oauth2
//              .userInfoEndpoint(ui -> ui.userService(customOAuth2UserService))
//              .defaultSuccessUrl("/", true));
// ─────────────────────────────────────────────────────────────────────────────

/*
import com.jiramanager.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserService userService;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        // registrationId = "google" | "github" | "facebook" | ...
        // No code change needed here when adding a new provider — just add credentials in application.properties
        String provider = userRequest.getClientRegistration().getRegistrationId();

        String providerId = resolveProviderId(provider, oAuth2User);
        String email      = resolveEmail(provider, oAuth2User);
        String firstName  = oAuth2User.getAttribute("given_name");   // Google
        String lastName   = oAuth2User.getAttribute("family_name");  // Google

        // GitHub / Facebook use different attribute keys
        if (firstName == null) {
            String fullName = oAuth2User.getAttribute("name");
            if (fullName != null) {
                String[] parts = fullName.split(" ", 2);
                firstName = parts[0];
                lastName  = parts.length > 1 ? parts[1] : null;
            }
        }

        UserService.OAuth2LoginResult result =
                userService.handleOAuth2Login(provider, providerId, email, firstName, lastName);

        // Pass linking state through attributes so Vaadin view can show the linking dialog
        Map<String, Object> attrs = new HashMap<>(oAuth2User.getAttributes());
        attrs.put("appUserId",        result.user().getId());
        attrs.put("needsLinking",     result.needsLinking());
        attrs.put("pendingProvider",  result.pendingProvider()   != null ? result.pendingProvider()   : "");
        attrs.put("pendingProviderId",result.pendingProviderId() != null ? result.pendingProviderId() : "");

        return new DefaultOAuth2User(List.of(() -> "ROLE_USER"), attrs, resolveNameAttributeKey(provider));
    }

    // ── Attribute mapping per provider ───────────────────────────────

    private String resolveProviderId(String provider, OAuth2User user) {
        return switch (provider) {
            case "github"   -> String.valueOf(user.getAttribute("id")); // GitHub uses integer id
            default         -> user.getAttribute("sub");                // Google, Facebook
        };
    }

    private String resolveEmail(String provider, OAuth2User user) {
        String email = user.getAttribute("email");
        if (email == null) {
            // GitHub with private email: requires user:email scope and a separate API call
            log.warn("Provider '{}' did not return an email address", provider);
            email = provider + "_" + resolveProviderId(provider, user) + "@noemail.local";
        }
        return email;
    }

    private String resolveNameAttributeKey(String provider) {
        return switch (provider) {
            case "github" -> "login"; // GitHub uses "login" as the name key
            default       -> "email";
        };
    }
}
*/
