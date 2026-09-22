package com.jiramanager.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * TEMPORARY: login is disabled. This filter auto-authenticates every request that has no
 * authenticated user yet, as a fixed technical account (configured via
 * {@code app.auto-login.email}), so the app is usable without ever showing the login screen.
 *
 * Nothing about the real login feature is removed — LoginView, SecurityConfig#setLoginView,
 * form-login processing and registration all stay intact. To restore the normal login
 * requirement, remove this filter's registration in SecurityConfig.
 */
@Slf4j
@RequiredArgsConstructor
public class AutoLoginFilter extends OncePerRequestFilter {

    private final AppUserDetailsService userDetailsService;
    private final String autoLoginEmail;

    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        boolean alreadyAuthenticated = existing != null && existing.isAuthenticated()
                && !"anonymousUser".equals(existing.getPrincipal());

        if (!alreadyAuthenticated) {
            try {
                UserDetails userDetails = userDetailsService.loadUserByUsername(autoLoginEmail);
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(auth);
                SecurityContextHolder.setContext(context);
                securityContextRepository.saveContext(context, request, response);
            } catch (UsernameNotFoundException e) {
                log.warn("Auto-login account '{}' not found or has no local password — " +
                        "falling back to the normal login screen.", autoLoginEmail);
            }
        }

        chain.doFilter(request, response);
    }
}
