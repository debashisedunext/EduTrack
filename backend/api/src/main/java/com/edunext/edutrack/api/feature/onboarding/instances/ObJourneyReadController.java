package com.edunext.edutrack.api.feature.onboarding.instances;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.http.HttpStatus;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.api.security.CallerIdentity;

/**
 * C-110 · {@code GET /onboarding/journeys/{journeyId}} — OB-05's expanded
 * ribbon.
 *
 * <h2>Why this route did not exist until now</h2>
 *
 * <p>It was in the contract, in the MSW mock and in {@code JourneyAccordion}'s
 * {@code useGetObJourney} from the day C-110 landed, and in no Java
 * controller. Against the mock the ribbon drew; against the real server every
 * expand answered 404 and the accordion opened onto nothing — the same shape
 * of gap C-111 found and closed for {@code /journey-steps/{stepId}}, one route
 * along. Nothing failed a build to say so, because the contract check compares
 * the committed client to the spec rather than the spec to the controllers.
 *
 * <h2>The 404 is the scope answer as well as the missing-row one</h2>
 *
 * <p>{@link ObJourneyReadRepository} applies the caller's client scope inside
 * the same statement, so a journey belonging to a client this caller cannot
 * see is indistinguishable from one that does not exist. Blueprint §2's
 * no-existence-leak rule, and A-112's statement of it for this module.
 *
 * <h2>ETag</h2>
 *
 * <p>Content-derived over the assembled document, which is what the contract
 * asks for: "content-derived from the journey and every step on it, so a step
 * edit moves the journey's tag". Broader than any single step write needs, and
 * deliberately so — one tag to reason about rather than a narrower second one
 * per step. The same 32-bit hash and the same acknowledged collision risk as
 * {@code ObClientPrereqController#etagOf}.
 */
@RestController
@RequestMapping("/api/v1/onboarding/journeys")
@Tag(name = "onboarding-journeys")
@PreAuthorize("isAuthenticated()")
class ObJourneyReadController {

    private final ObJourneyReadService service;

    ObJourneyReadController(ObJourneyReadService service) {
        this.service = service;
    }

    @GetMapping(path = "/{journeyId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getObJourney",
            summary = "The expanded ribbon for one journey (OB-05)")
    ResponseEntity<ObJourneyReadDtos.ObJourneyDetailResponse> get(
            @PathVariable long journeyId, Authentication caller) {

        ObJourneyReadDtos.ObJourneyDetail body = service.find(scopeOf(caller), journeyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No journey was found at this path."));

        return ResponseEntity.ok()
                .eTag(Integer.toHexString(body.hashCode()))
                .body(new ObJourneyReadDtos.ObJourneyDetailResponse(body));
    }

    private static ObClientScope scopeOf(Authentication caller) {
        return ObClientScope.of(CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-journeys route reached with no resolvable "
                                + "caller identity")));
    }
}
