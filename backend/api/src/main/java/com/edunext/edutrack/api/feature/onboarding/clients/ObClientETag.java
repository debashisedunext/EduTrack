package com.edunext.edutrack.api.feature.onboarding.clients;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * B-102's {@code ETag} rules, lifted out of {@link ObClientController} by B-103
 * so {@link ObContactController} uses the same ones rather than a second copy.
 *
 * <p>The extraction is the whole reason this class exists. Two controllers
 * writing the same resource have to agree on <em>exactly</em> how its tag is
 * derived and how {@code If-Match} is compared, and a tag computed one way in
 * one file and another way in another is a 412 that only appears when the two
 * screens are used together — the failure mode that is hardest to reproduce and
 * easiest to introduce by copy-paste. Nothing about the behaviour changed in
 * the move; the javadoc below is B-102's, kept with it.
 *
 * <h2>Derived from the content, not from {@code updated_at}</h2>
 *
 * <p>A timestamp tag moves when a save rewrites identical values, failing an
 * edit that conflicts with nothing. Content-derived, two people who saved the
 * same change do not fight.
 *
 * <p><b>The journeys are inside the tag</b>, which is the contract's stated
 * intent — "{@code ETag} covers the whole document, journeys included, so a step
 * transition made elsewhere costs the editor a reload rather than a lost
 * update". That is a deliberate choice to be strict on the one screen where the
 * client record and the work underneath it are shown together.
 *
 * <p><b>And the contacts are inside it too</b>, which is what makes B-103's
 * three operations answer with the whole client document: a SPOC write moves
 * this tag, so a write that returned only the contact would leave the Client
 * info card on the same page holding a tag that is already stale, and its next
 * Save would be a 412 the user cannot account for. {@code updateClientContact}
 * one module over has the same problem and can only warn about it in prose —
 * its contacts hang off a list carrying no tag of its own.
 *
 * <p><b>A 32-bit hash, and two states of one client can collide.</b> B-019
 * found this the honest way on {@code ProjectSettings}, and
 * {@code ClientController}, {@code ProjectController} and
 * {@code SlaPolicyController} all tag the same way. Recorded rather than fixed
 * on one screen: a stronger tag across all of them is a change worth making
 * together.
 */
final class ObClientETag {

    private ObClientETag() {
    }

    static String of(ObClientDtos.ObClientDetail client) {
        return Integer.toHexString(client.hashCode());
    }

    /**
     * {@code If-Match} is required, not optional.
     *
     * <p>A write without one is 428 rather than allowed through: treating a
     * missing precondition as "no conflict" means the guard protects only the
     * callers that already opted in, which is the set that needed it least. The
     * same status and the same reasoning as B-011's resource form, B-016's
     * project form, B-023's working week and B-026's client form.
     *
     * <p>Callers must resolve the client — and answer 404 for one they cannot
     * see — <b>before</b> calling this. Answering 428 for a client that does not
     * exist would send the caller to fetch a tag from a URL that will 404 too.
     */
    static void require(String ifMatch, ObClientDtos.ObClientDetail current) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the client first and send back its ETag.");
        }
        if (!matches(ifMatch, of(current))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This client changed since you read it. Reload and reapply your edit.");
        }
    }

    /** {@code *} matches anything, per RFC 9110. */
    private static boolean matches(String ifMatch, String current) {
        String candidate = ifMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }
}
