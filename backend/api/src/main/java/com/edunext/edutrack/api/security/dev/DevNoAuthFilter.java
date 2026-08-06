package com.edunext.edutrack.api.security.dev;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * A-012. Stands in for real authentication (A-020…A-025) so Streams B, C and
 * D can develop against a realistic
 * {@link org.springframework.security.core.Authentication} before a login
 * endpoint exists: every request is treated as the single configurable fake
 * user from {@link DevNoAuthProperties}.
 *
 * <p>There is no per-request identity switching — there is no login screen
 * yet to switch from. To develop as a different role, change the properties
 * in your gitignored application-local.yml and restart.
 *
 * <p>Never registered directly: only {@link DevNoAuthConfig} creates this
 * filter, and that config refuses to exist outside {@code local}.
 */
public class DevNoAuthFilter extends OncePerRequestFilter {

    private final DevNoAuthProperties properties;

    public DevNoAuthFilter(DevNoAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        DevPrincipal principal = new DevPrincipal(
                properties.userId(),
                properties.username(),
                properties.fullName(),
                properties.role(),
                properties.projectIds(),
                properties.reporteeIds());

        // Same authority convention the real chain will use (A-032/A-033):
        // hasRole("ADMIN") == authority "ROLE_ADMIN".
        List<GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().toUpperCase(Locale.ROOT)));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));

        filterChain.doFilter(request, response);
    }
}
