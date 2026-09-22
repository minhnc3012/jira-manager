package com.jiramanager.security;

import com.jiramanager.views.LoginView;
import com.vaadin.flow.spring.security.VaadinWebSecurity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

// To re-enable Google login:
// 1. Uncomment spring-boot-starter-oauth2-client in pom.xml
// 2. Uncomment Google credentials in application.properties
// 3. Uncomment CustomOAuth2UserService.java class body
// 4. Add oauth2Login config in configure() below
@EnableWebSecurity
@Configuration
@RequiredArgsConstructor
public class SecurityConfig extends VaadinWebSecurity {

    private final AppUserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    // TEMPORARY: login is disabled — see AutoLoginFilter below. To require login again,
    // delete the http.addFilterBefore(...) line in configure() (everything else — LoginView,
    // registration, this account lookup — is untouched and ready to use as-is).
    @Value("${app.auto-login.email:minh@keytechx.com}")
    private String autoLoginEmail;

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AutoLoginFilter autoLoginFilter() {
        return new AutoLoginFilter(userDetailsService, autoLoginEmail);
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.authenticationProvider(authenticationProvider());

        // TEMPORARY: auto-authenticate every request so the app never forces a login
        // screen. Remove this line to restore normal login enforcement.
        http.addFilterBefore(autoLoginFilter(), UsernamePasswordAuthenticationFilter.class);

        // Prevent /logout from being saved as the post-login redirect target.
        // Without this, if a browser lands on /logout (e.g. from a stale tab),
        // Spring Security saves it and redirects back there after the next login.
        http.requestCache(cache -> cache.requestCache(new HttpSessionRequestCache() {
            @Override
            public void saveRequest(HttpServletRequest request, HttpServletResponse response) {
                if (!request.getRequestURI().endsWith("/logout")) {
                    super.saveRequest(request, response);
                }
            }
        }));

        super.configure(http);
        setLoginView(http, LoginView.class);
    }
}
