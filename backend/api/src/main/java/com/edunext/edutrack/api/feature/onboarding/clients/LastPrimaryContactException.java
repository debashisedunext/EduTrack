package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * B-103 · the client's only primary SPOC may not be demoted or deactivated —
 * 409 {@code ob-contact-primary-required}.
 *
 * <h2>This is where onboarding deliberately parts company with the ticketing
 * master</h2>
 *
 * <p>{@code updateClientContact} (B-027) <b>allows</b> a client to end up with
 * no primary, and says why: "that is a state the client is already in the
 * moment it is created, B-028's gate reports it, and refusing it here while the
 * {@code DELETE} below can produce it anyway would be one rule with two
 * answers." Every clause of that is true over there and none of it is true
 * here.
 *
 * <ul>
 *   <li><b>An onboarding client is never created without one.</b> {@code
 *       ObClientCreateRequest} requires exactly one {@code isPrimary}, so
 *       "already in that state" is a state this module has no way to reach.</li>
 *   <li><b>There is no gate that reports it.</b> B-028 refuses to raise a
 *       ticket against a client with no primary contact, which turns the
 *       omission into a message somebody reads. Onboarding has no equivalent
 *       checkpoint: the kickoff mail, the one-time portal password and every
 *       sign-off request address the primary SPOC, and with none they are
 *       addressed to nobody — silently, because a recipient list that resolves
 *       to zero rows is not an error anywhere in the mail engine.</li>
 *   <li><b>The {@code DELETE} cannot produce it either</b>, so the rule has one
 *       answer rather than two: {@code removeObClientContact} refuses the last
 *       primary on this same exception.</li>
 * </ul>
 *
 * <h2>It is not a dead end, which is the reason it can be this strict</h2>
 *
 * <p>Replacing a SPOC who has left is one request: {@code POST} the new one
 * with {@code isPrimary: true}, which promotes them and demotes the incumbent
 * in the same transaction, then remove the old row. The client is never without
 * a primary for even one statement, which is the outcome a two-step
 * demote-then-promote would open a window on.
 *
 * <p>409 rather than 422: the request is well-formed and would be accepted
 * against a different state of this client, which is what CONVENTIONS.md means
 * by a conflict. The same shape as the module's other 409s, and the response
 * carries {@code forceable: false} — this one is not overridable the way the
 * similar-name guard is.
 */
class LastPrimaryContactException extends RuntimeException {

    LastPrimaryContactException(String contactName) {
        super(contactName + " is this client's only primary SPOC. Add or promote a replacement "
                + "first — the primary receives the kickoff mail, the portal password and every "
                + "sign-off request, and a client with none sends them to nobody.");
    }
}
