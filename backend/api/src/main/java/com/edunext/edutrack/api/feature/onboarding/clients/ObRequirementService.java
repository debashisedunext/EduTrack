package com.edunext.edutrack.api.feature.onboarding.clients;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * B-106 · what a client needs, as rows somebody can work through.
 *
 * <h2>What this task actually closes</h2>
 *
 * <p>Requirements were an {@code array<string>} on the client document,
 * captured once by the OB-04 wizard and untouchable from then on. Three
 * ordinary things had no representation at all:
 *
 * <ul>
 *   <li><b>Correcting one.</b> There was no id, so there was nothing to address.
 *       A requirement typed with a typo, or superseded by what the client
 *       actually asked for on the kickoff call, stayed as first written for the
 *       life of the record.</li>
 *   <li><b>Marking one done.</b> Plan §9 renders OB-05's requirements as a list
 *       somebody works through, and a string has nowhere to record having been
 *       worked through. The team's actual answer to "are we ready for go-live"
 *       lived in somebody's head or in a comment.</li>
 *   <li><b>Writing one properly.</b> A requirement is frequently two clauses, a
 *       list of environments and a link to a document. As a bare string it was
 *       one run-on line, and any attempt at formatting was stored and rendered
 *       as literal angle brackets — or, worse, was not.</li>
 * </ul>
 *
 * <h2>§3.9 runs on the server, on write, always</h2>
 *
 * <p>{@code RichTextSanitizer} is the one copy of PLAN.md §3.9's allow-list, and
 * this is the fourth field to route through it after the ticket description, the
 * steps-to-generate and the comment body. Using it rather than writing a fourth
 * allow-list is the whole point of that class existing: §3.9 ends with the
 * requirement that tightening the list "retroactively protects rows already
 * stored", and two copies of an allow-list cannot do that — the day one gains a
 * tag the other does not, the weaker one is the vulnerability and nothing fails
 * a build to say so.
 *
 * <p>The threat model is the comment box's rather than the description's, which
 * is what makes this worth stating. A requirement is written by the boarding
 * team, frequently pasted out of a client's own email, and rendered on OB-05 to
 * a manager who has every reason to trust the page. That is §3.9's named case.
 *
 * <p>Three consequences, all of them in {@link ObRequirementBody}, which is a
 * component rather than a method here because the OB-04 wizard writes
 * requirements too and the two paths must reduce a body identically:
 *
 * <ul>
 *   <li><b>What is stored is the sanitised result, never what was sent.</b></li>
 *   <li><b>A body that reduces to nothing is a 400.</b>
 *       {@code <script>alert(1)</script>} is a non-blank 27-character string
 *       that passes {@code @NotBlank} and every length check and that means
 *       nothing once the allow-list has run. Storing the empty row would put a
 *       line on somebody's checklist that says nothing.</li>
 *   <li><b>The length bound is checked against the sanitised value too.</b>
 *       Escaping makes strings longer — a bare {@code &} leaves as {@code &amp;}
 *       — so 20 000 legal characters can become five times that, and §3.9's
 *       sentence is about what gets stored. {@code CommentSanitizer}'s class
 *       note records finding this against a {@code TEXT} column that truncated
 *       mid-tag; {@code body_html} is {@code MEDIUMTEXT} precisely because that
 *       lesson was available when this migration was written, so here the check
 *       enforces a rule rather than protecting a column.</li>
 * </ul>
 *
 * <h2>The met flag carries its own evidence, and an edit does not re-date it</h2>
 *
 * <p>{@code is_met} is three columns — the flag, {@code met_at} and
 * {@code met_by} — on the reasoning V20260907_1130 gives about consent: a bare
 * boolean records that somebody at some point decided a requirement was
 * satisfied, and cannot answer <b>when</b> or <b>on whose word</b>. Those are
 * the questions a disputed go-live turns on.
 *
 * <p>So {@link #stampFor} moves the stamp only when the flag moves.
 * {@code ObContactService.consentFor} states the rule for consent and it is the
 * same class of evidence: correcting a requirement's wording in November must
 * not re-date a requirement met in March. That is the same evidence a missing
 * basis destroys, arrived at silently and by a routine edit.
 *
 * <p><b>This is not a sign-off.</b> Journey sign-off is C-112's gate over
 * {@code ob_signoff_requests} and involves the client; a requirements tick is
 * the person doing the work saying they have done it. Naming it "sign-off"
 * anywhere would invite one to be read as the other on a screen that shows both.
 *
 * <h2>Order of refusals, which is the same security property B-103 states</h2>
 *
 * <p>Scoped client read (404) → write role (403) → requirement resolution (404)
 * → sanitisation and validation (400). The scoped read is first so an
 * out-of-scope client id is indistinguishable from a missing one; the write-role
 * check precedes the requirement lookup so a Viewer cannot use the 404/403
 * difference to probe which requirement ids exist under a client they can see.
 */
@Service
class ObRequirementService {

    private final ObClientService details;
    private final ObClientReadRepository reads;
    private final ObRequirementWriteRepository requirements;
    private final ObRequirementBody bodies;
    private final Clock clock;

    /**
     * {@code @Autowired} is not decorative — {@code ObClientWriteService}'s own
     * note, and this class proved it the honest way: two constructors and no
     * annotation is not an ambiguity Spring resolves, it is a context that fails
     * to start with "No default constructor found".
     */
    @Autowired
    ObRequirementService(ObClientService details, ObClientReadRepository reads,
                         ObRequirementWriteRepository requirements, ObRequirementBody bodies) {
        this(details, reads, requirements, bodies, Clock.systemUTC());
    }

    ObRequirementService(ObClientService details, ObClientReadRepository reads,
                         ObRequirementWriteRepository requirements, ObRequirementBody bodies,
                         Clock clock) { // test seam
        this.details = details;
        this.reads = reads;
        this.requirements = requirements;
        this.bodies = bodies;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Add
    // ------------------------------------------------------------------

    /**
     * Raise a requirement.
     *
     * <p>It lands at the end of the list. {@code sequence} is the order things
     * were entered and the order OB-05 prints them, so a requirement raised in
     * month three belongs after the ones raised at boarding rather than
     * interleaved by id — which is what ordering by id alone would produce the
     * moment anything is deleted.
     *
     * <p>{@code isMet: true} on a create is unusual and permitted: a requirement
     * recorded after it was satisfied is an ordinary thing on a client whose
     * onboarding started before anybody was writing them down. The stamp is the
     * server's clock either way, because a body that could supply it could
     * backdate the evidence.
     */
    @Transactional
    ObClientDtos.ObClientDetail add(ObClientScope scope, long obClientId,
                                    ObClientDtos.ObRequirementWriteRequest request) {

        requireWritableClient(scope, obClientId);

        ObRequirementBody.Stored body = bodies.of(request.bodyHtml(), "bodyHtml");
        boolean met = request.met();
        Instant now = clock.instant();

        requirements.insert(obClientId,
                reads.nextRequirementSequence(obClientId),
                trimmedOrNull(request.title()),
                body.html(),
                body.text(),
                met,
                met ? now : null,
                met ? scope.userId() : null,
                scope.userId());

        return detail(scope, obClientId);
    }

    // ------------------------------------------------------------------
    // Edit — the wording, the label, and whether it is satisfied
    // ------------------------------------------------------------------

    /**
     * Change what a requirement says, or record that it has been met.
     *
     * <p><b>Partial by field, resolved here rather than in SQL.</b> The row has
     * already been read to answer 404, so each absent field is resolved against
     * what was read and the repository writes five settled values. That keeps
     * the statement one shape — see {@link ObRequirementWriteRepository}'s note
     * on why an assembled {@code SET} list is the version that fails at runtime.
     *
     * <p><b>A body that arrives is re-sanitised, never merged.</b> There is no
     * sense in which half of a stored document and half of a submitted one is a
     * document; the caller sends the whole body or does not send it.
     */
    @Transactional
    ObClientDtos.ObClientDetail update(ObClientScope scope, long obClientId, long requirementId,
                                       ObRequirementUpdateRequest request) {

        requireWritableClient(scope, obClientId);
        ObClientReadRepository.RequirementRow current = requireRequirement(obClientId, requirementId);

        String title = request.hasTitle() ? trimmedOrNull(request.getTitle()) : current.title();

        ObRequirementBody.Stored body = request.hasBodyHtml()
                ? bodies.of(request.getBodyHtml(), "bodyHtml")
                : new ObRequirementBody.Stored(current.bodyHtml(), current.bodyText());

        boolean met = request.hasIsMet() ? request.met() : current.isMet();
        Stamp stamp = stampFor(current, met, scope.userId());

        requirements.update(requirementId, title, body.html(), body.text(),
                met, stamp.at(), stamp.by(), scope.userId());

        return detail(scope, obClientId);
    }

    // ------------------------------------------------------------------
    // Remove
    // ------------------------------------------------------------------

    /**
     * Remove a requirement entered by mistake.
     *
     * <p><b>A hard delete, where {@code ObApplicationService} offers no delete at
     * all.</b> The two panels sit on one screen, so the difference is worth being
     * explicit about: it is a fact about the schema, not a difference of taste.
     * Every purchase carries a journey from the moment it is made and
     * {@code fk_ob_journeys_application} is {@code RESTRICT}. Nothing at all
     * points at {@code ob_client_requirements} — no journey is instantiated from
     * a requirement, no history references one, no sign-off names one — so this
     * row takes nothing with it.
     *
     * <p><b>And a delete rather than a tombstone.</b> What this removes is a typo
     * or a duplicate from a wizard textarea; {@code insertRequirements} has
     * dropped blank ones since B-102 on the same reasoning. A tombstoned typo is
     * a line every future reader of the list has to decide to ignore. A
     * requirement that was genuinely agreed and later dropped is a different act
     * — {@code isMet: false} with the reason in its body — and stays visible.
     */
    @Transactional
    ObClientDtos.ObClientDetail delete(ObClientScope scope, long obClientId, long requirementId) {
        requireWritableClient(scope, obClientId);
        requireRequirement(obClientId, requirementId);
        requirements.delete(requirementId);
        return detail(scope, obClientId);
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    /**
     * The scoped read, then the write role — 404 before 403 and never the
     * reverse.
     *
     * <p>{@code ObApplicationService.requireWritableClient}'s reasoning verbatim,
     * which is {@code ObContactService}'s before it: an out-of-scope client must
     * be indistinguishable from a missing one, and a caller who can see the
     * client has already been shown it, so 403 concedes nothing.
     */
    private void requireWritableClient(ObClientScope scope, long obClientId) {
        ObClientDtos.ObClientDetail client = detail(scope, obClientId);
        if (!scope.mayWrite()) {
            throw new ObClientReadOnlyException(client.name());
        }
    }

    /**
     * The scoped client read on its own, for the controller's {@code If-Match}.
     *
     * <p>Exposed rather than letting the controller hold {@code ObClientService}
     * as a second collaborator — {@code ObContactService.readable} says why: the
     * precondition has to be evaluated against exactly the document these
     * operations answer with, and two paths to it are two chances to diverge.
     */
    ObClientDtos.ObClientDetail readable(ObClientScope scope, long obClientId) {
        return detail(scope, obClientId);
    }

    private ObClientDtos.ObClientDetail detail(ObClientScope scope, long obClientId) {
        return details.findDetail(scope, obClientId)
                .orElseThrow(() -> new ObClientNotFoundException(obClientId));
    }

    private ObClientReadRepository.RequirementRow requireRequirement(long obClientId,
                                                                     long requirementId) {
        return reads.requirementOf(obClientId, requirementId)
                .orElseThrow(() -> new ObRequirementNotFoundException(obClientId, requirementId));
    }

    // ------------------------------------------------------------------
    // §3.9, and the met stamp
    // ------------------------------------------------------------------

    /** The met evidence: when, and on whose word. */
    private record Stamp(Instant at, Long by) {
    }

    /**
     * The stamp moves only when the flag moves.
     *
     * <p>{@code ObContactService.consentFor}'s rule, applied to the same class of
     * evidence. An edit that leaves {@code isMet} where it was — a wording fix, a
     * label added — keeps the original {@code met_at} and {@code met_by},
     * because re-dating a requirement met in March to the November somebody
     * corrected its spelling destroys the only fact the two columns exist to
     * carry.
     *
     * <p>Un-meeting clears both rather than leaving a stamp beside a {@code 0}.
     * {@code ck_ob_client_requirements_met} refuses the other arrangement at the
     * column, so this is the readable version of a rule the database also holds.
     */
    private Stamp stampFor(ObClientReadRepository.RequirementRow current, boolean met, long callerId) {
        if (met == current.isMet()) {
            return new Stamp(current.metAt(), current.metBy());
        }
        return met ? new Stamp(clock.instant(), callerId) : new Stamp(null, null);
    }

    private static String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
