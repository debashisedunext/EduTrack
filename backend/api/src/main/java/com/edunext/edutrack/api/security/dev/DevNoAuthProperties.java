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
@ConfigurationProperties(prefix = "edutrack.dev-noauth")
public record DevNoAuthProperties(
        Long userId,
        String username,
        String fullName,
        String role,
        List<Long> projectIds,
        List<Long> reporteeIds
) {
    public DevNoAuthProperties {
        if (userId == null) userId = 1L;
        if (username == null) username = "dev.admin";
        if (fullName == null) fullName = "Dev Admin (fake principal — A-012)";
        if (role == null) role = "ADMIN";
        if (projectIds == null) projectIds = List.of();
        if (reporteeIds == null) reporteeIds = List.of();
    }
}
