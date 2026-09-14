package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo sign-off simulator's one route. See
 * {@link DevSignoffSimulationService} for why it exists and what it refuses to
 * do.
 *
 * <h2>{@code /dev/} is in the path on purpose</h2>
 *
 * <p>Not {@code /onboarding/signoffs/&#123;id&#125;/simulate}, which would read like part
 * of the module. A caller, a log line and a browser network tab all say
 * {@code dev} before they say anything else, and nobody has to know about the
 * {@code @Profile} to understand what they are looking at.
 *
 * <p>It also keeps the real module's route inventory honest.
 * {@code ObPermissionMatrixTest} enumerates every mapped handler under
 * {@code /api/v1/onboarding/} and asserts both directions against
 * {@code ObPermissionMatrix} — every route has an entry, and every entry names
 * a route that exists. A demo route living under that prefix would have to be
 * added to the matrix to satisfy the first assertion, and would then break the
 * second on every profile where this controller is absent, which is all of the
 * real ones.
 *
 * <h2>Not in {@code contracts/openapi.yaml}, and not generated into the client</h2>
 *
 * <p>The contract describes the application we ship. This route is not part of
 * it, is absent from four profiles out of five, and adding it would put a
 * "simulate the client" operation into the generated TypeScript client that
 * every screen imports. {@code SignoffPanel} calls it through the raw
 * {@code http} fetcher instead — one call site, plainly marked.
 * {@link Hidden} keeps it off the springdoc surface for the same reason.
 */
@RestController
@RequestMapping("/api/v1/dev/onboarding")
@Profile({"dev-noauth", "fixtures"})
@PreAuthorize("isAuthenticated()")
@Hidden
class DevSignoffSimulationController {

    private final DevSignoffSimulationService service;

    DevSignoffSimulationController(DevSignoffSimulationService service) {
        this.service = service;
    }

    /**
     * <p><b>200 with a body that can say the step did not complete</b> — the
     * public accept route's own contract, kept deliberately. A gate failure is
     * not a failed simulation: the acceptance was recorded, and
     * {@code gateFailures} names what our side still owes. See
     * {@link DevSignoffSimulationService#simulate}.
     */
    @PostMapping(value = "/journeys/{journeyId}/simulate-signoff",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PublicSignoffAcceptDtos.AcceptResultResponse> simulate(
            @PathVariable long journeyId,
            @Valid @RequestBody SimulateRequest request) {

        PublicSignoffAcceptDtos.AcceptResult result = service.simulate(
                journeyId, request.kind(), request.stepId(), request.signedName(), request.note());

        return ResponseEntity.ok(new PublicSignoffAcceptDtos.AcceptResultResponse(result));
    }

    /**
     * @param stepId     required for {@code STEP}, ignored for {@code GO_LIVE}
     * @param signedName optional; defaults to
     *                   {@link DevSignoffSimulationService#DEFAULT_SIGNED_NAME},
     *                   which is worded so the row cannot be mistaken for a
     *                   real signature
     */
    record SimulateRequest(
            @NotNull ObSignoffKind kind,
            Long stepId,
            @Size(max = 160) String signedName,
            @Size(max = 2000) String note
    ) {
    }
}
