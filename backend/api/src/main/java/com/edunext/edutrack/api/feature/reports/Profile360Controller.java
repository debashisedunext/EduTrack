package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

/**
 * A-069 · {@code GET /users/{userId}/profile-360} — S-28.
 *
 * <p>Mapped under {@code /users} and living in {@code feature/reports}: the path
 * is the contract's, the package is where the arithmetic already is. See
 * {@link Profile360Service} for why that is not an accident.
 *
 * <p>Reachable by every authenticated role, because §2 grants a Developer their
 * own profile even though it denies them everybody else's. <b>Who they may look
 * at is a row question</b>, answered by {@code Profile360Repository.isVisibleTo}
 * and reported as a 404 — never a 403, which would confirm that a user id exists
 * and let anyone enumerate the staff list.
 */
@RestController
@Tag(name = "users")
class Profile360Controller {

    private final Profile360Service profiles;

    Profile360Controller(Profile360Service profiles) {
        this.profiles = profiles;
    }

    @GetMapping(path = "/api/v1/users/{userId}/profile-360", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getUserProfile360", summary = "Resource 360° profile (S-28)")
    Profile360Dtos.Profile360Response profile(
            Authentication caller,
            @PathVariable long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        CallerIdentity identity = CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "an authenticated request reached the profile with no CallerIdentity"));

        return profiles.profile(identity, userId, from, to)
                .map(Profile360Dtos.Profile360Response::new)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No profile is available for user " + userId + "."));
    }
}
