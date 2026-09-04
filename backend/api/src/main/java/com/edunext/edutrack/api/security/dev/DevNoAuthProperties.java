package com.edunext.edutrack.api.security.dev;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * A-012. Binds {@code edutrack.dev-noauth.*} — defaults live in the
 * {@code dev-noauth} profile document in application.yml; a developer
 * overrides them in their own gitignored application-local.yml to work as a
 * scoped role (Developer on projects 1 and 2, a PM with reportees, …).
 *
 * <p>The compact constructor defaults every field, so the profile still
 * works if a local override omits some of them.
 */
/*
 * A-110 note: this record has exactly ONE constructor and must keep exactly
 * one. A convenience overload was added here first, to spare four call sites in
 * DevNoAuthFilterTest, and Spring Boot's constructor binding could no longer
 * tell which to bind — every @SpringBootTest in the module failed to start with
 * UnsatisfiedDependencyException, 1141 errors from one extra constructor.
 * CallerIdentity and DevPrincipal can carry overloads because nothing binds
 * them from configuration. This cannot.
 */
@ConfigurationProperties(prefix = "edutrack.dev-noauth")
public record DevNoAuthProperties(
        Long userId,
        String username,
        String fullName,
        String role,
        List<Long> projectIds,
        List<Long> reporteeIds,
        List<String> modules
) {
    public DevNoAuthProperties {
        if (userId == null) userId = 1L;
        if (username == null) username = "dev.admin";
        if (fullName == null) fullName = "Dev Admin (fake principal — A-012)";
        if (role == null) role = "ADMIN";
        if (projectIds == null) projectIds = List.of();
        if (reporteeIds == null) reporteeIds = List.of();
        // A-110 · both modules by default, and the default matters more than
        // the field.
        //
        // `dev-noauth` is what Streams B and C run against until the real
        // chain lands, so a default of "no modules" would 404 every onboarding
        // screen they are building and the first thing anyone would do is turn
        // the guard off. A default of "both" keeps them working and still
        // leaves the refusal reachable: setting
        // `edutrack.dev-noauth.modules: [TICKETING]` in a local override is how
        // you see the 404 path by hand, which is the case worth trying before
        // trusting it.
        if (modules == null) modules = List.of("TICKETING", "ONBOARDING");
    }

}
