package com.edunext.edutrack.api.security.dev;

import com.edunext.edutrack.api.security.permission.RolePermissions;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
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

    /** Matches the first delegate in HttpSecurity's default repository. */
    private static final SecurityContextRepository CONTEXT_REPOSITORY =
            new RequestAttributeSecurityContextRepository();

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
                properties.reporteeIds(),
                // A-110. Both modules by default (see DevNoAuthProperties), so
                // the onboarding screens B and C are building are reachable in
                // dev — and overridable to a narrower list, which is how the
                // 404 path gets tried by hand before anyone trusts it.
                properties.modules());

        // Same authority convention the real chain uses (A-032/A-033):
        // hasRole("ADMIN") == authority "ROLE_ADMIN", and the dotted capability
        // codes verbatim.
        //
        // A-033 added the permissions, and they are not a nicety. Until then this
        // granted the role authority alone, which cost nothing because nothing
        // asserted a permission — the first @PreAuthorize would have turned every
        // dev-noauth request into a 403 for Streams B, C and D, on a profile whose
        // entire purpose is to unblock them. The role is a property, not a user
        // row, so there is usually nothing in role_permissions to read for it;
        // RolePermissions is the static mirror, pinned to the seed migration by
        // RolePermissionsTest so this profile cannot quietly grant more than
        // production does.
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(
                RolePermissions.ROLE_AUTHORITY_PREFIX + principal.role().toUpperCase(Locale.ROOT)));
        RolePermissions.of(principal.role()).stream()
                .map(SimpleGrantedAuthority::new)
                .forEach(authorities::add);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
        SecurityContextHolder.setContext(context);

        // Setting the holder is not enough, and that is the whole reason this
        // line exists. This filter runs ahead of springSecurityFilterChain, and
        // SecurityContextHolderFilter *replaces* the held context with a
        // deferred one loaded from its repository — so the principal set above
        // was discarded before any controller saw it, and every /chat and
        // /notifications call answered 500 through CurrentUser.
        //
        // HttpSecurity's default repository is a DelegatingSecurityContextRepository
        // over the request attribute and the session, and it consults the
        // request attribute first. Saving there is what makes the deferred load
        // find this principal instead of an empty context.
        CONTEXT_REPOSITORY.saveContext(context, request, response);

        filterChain.doFilter(request, response);
    }
}
