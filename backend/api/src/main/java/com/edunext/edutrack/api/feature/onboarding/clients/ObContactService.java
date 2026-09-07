package com.edunext.edutrack.api.feature.onboarding.clients;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * B-103 · adding, editing, promoting, demoting, deactivating and reactivating a
 * client's SPOCs — and recording consent while it is still recordable.
 *
 * <h2>Every operation answers the whole client document</h2>
 *
 * <p>Not the contact it wrote. {@link ObClientETag} hashes {@code
 * ObClientDetail}, contacts included, so any write here moves the client's tag;
 * a response carrying only the contact would leave OB-05's Client info card
 * holding a tag that is already stale, and its next Save would be a 412 nobody
 * could account for. Handing back the fresh document and its new tag costs one
 * read this transaction was going to do anyway to build the response.
 *
 * <h2>The three rules that are not obvious from the schema</h2>
 *
 * <ol>
 *   <li><b>Consent needs a basis.</b> {@code whatsappOptIn: true} without
 *       {@code whatsappOptInSource} is a 400. The flag alone is what
 *       V20260903_1210 already had and what B-103 exists to replace: it records
 *       that somebody ticked a box and cannot answer when, or on what.</li>
 *   <li><b>Consent is restamped only when it changes.</b> Correcting a phone
 *       number must not re-date the consent to today — that would quietly
 *       destroy the same fact a missing basis destroys loudly. See
 *       {@link #consentFor}.</li>
 *   <li><b>The last primary cannot be demoted or deactivated.</b> See
 *       {@link LastPrimaryContactException} for why this module is stricter
 *       than the ticketing master it otherwise follows.</li>
 * </ol>
 *
 * <h2>Order of refusals, which is a security property rather than a style</h2>
 *
 * <p>Scoped client read (404) → write role (403) → contact resolution (404) →
 * validation (400) → conflicts (409). The scoped read is first so an
 * out-of-scope client id is indistinguishable from a missing one, exactly as
 * {@code ObClientWriteService.update} orders it; the write-role check precedes
 * the contact lookup so a Viewer cannot use the 404/403 difference to probe
 * which contact ids exist under a client they can see.
 */
@Service
class ObContactService {

    private final ObClientService details;
    private final ObClientReadRepository reads;
    private final ObContactWriteRepository contacts;
    private final Clock clock;

    /**
     * {@code @Autowired} is not decorative — {@code ObEscalationService}'s own
     * note: two constructors and no annotation is not an ambiguity Spring
     * resolves, it is a context that fails to start.
     */
    @Autowired
    ObContactService(ObClientService details, ObClientReadRepository reads,
                     ObContactWriteRepository contacts) {
        this(details, reads, contacts, Clock.systemUTC());
    }

    /** Test seam. */
    ObContactService(ObClientService details, ObClientReadRepository reads,
                     ObContactWriteRepository contacts, Clock clock) {
        this.details = details;
        this.reads = reads;
        this.contacts = contacts;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Add
    // ------------------------------------------------------------------

    @Transactional
    ObClientDtos.ObClientDetail add(ObClientScope scope, long callerId, long obClientId,
                                    ObContactDtos.ObContactUpsertRequest request) {

        requireWritableClient(scope, obClientId);

        boolean active = request.activeOr(true);
        validate(request, active);
        rejectDuplicateEmail(obClientId, request.email(), null);

        // Demote first, in this transaction. uq_ob_client_contacts_primary
        // refuses a second active primary, so the incumbent has to be out of
        // the way before the insert rather than after it.
        if (request.primary() && active) {
            contacts.demoteOtherPrimaries(obClientId, null);
        }

        Instant now = clock.instant();
        ObContactWriteRepository.Consent consent = request.optedIn()
                ? ObContactWriteRepository.Consent.given(sourceOf(request), now, callerId)
                : ObContactWriteRepository.Consent.withheld();

        long contactId = contacts.insert(obClientId, request, consent, active);

        // A first event only when there is consent to record. A contact added
        // with the box unticked has given nothing, and a journal row saying
        // "did not consent" for every SPOC ever created would bury the rows
        // that matter under the rows that are merely the default.
        if (consent.optedIn()) {
            contacts.recordConsent(contactId, true, consent.source(), callerId, now);
        }

        return detail(scope, obClientId);
    }

    // ------------------------------------------------------------------
    // Edit, promote, demote, deactivate, reactivate
    // ------------------------------------------------------------------

    @Transactional
    ObClientDtos.ObClientDetail update(ObClientScope scope, long callerId, long obClientId,
                                       long contactId,
                                       ObContactDtos.ObContactUpsertRequest request) {

        requireWritableClient(scope, obClientId);
        ObClientReadRepository.ContactRow current = requireContact(obClientId, contactId);

        boolean active = request.activeOr(current.isActive());
        validate(request, active);
        rejectDuplicateEmail(obClientId, request.email(), contactId);
        refuseStrandingTheClient(current, request.primary() && active);

        if (request.primary() && active) {
            contacts.demoteOtherPrimaries(obClientId, contactId);
        }

        Instant now = clock.instant();
        ObContactWriteRepository.Consent consent = consentFor(current, request, callerId, now);
        contacts.update(contactId, request, consent, active);

        if (consentChanged(current, consent)) {
            contacts.recordConsent(contactId, consent.optedIn(), consent.source(), callerId, now);
        }

        return detail(scope, obClientId);
    }

    // ------------------------------------------------------------------
    // Remove
    // ------------------------------------------------------------------

    /**
     * Deactivates. Never deletes — see the contract's own note on why a real
     * {@code DELETE} would either fail on {@code ob_signoff_requests} or, given
     * a cascade, destroy the record of who signed off on a go-live.
     *
     * <p>Removing an already-removed contact changes nothing and answers 200:
     * it is a setter, and the second half of a double-click must not be an
     * error about something that did happen (B-014's {@code UNCHANGED}
     * argument). The check still runs first, so the last primary is refused
     * whether or not the caller has clicked twice.
     */
    @Transactional
    ObClientDtos.ObClientDetail remove(ObClientScope scope, long obClientId, long contactId) {
        requireWritableClient(scope, obClientId);
        ObClientReadRepository.ContactRow current = requireContact(obClientId, contactId);

        refuseStrandingTheClient(current, false);
        if (current.isActive()) {
            contacts.setActive(contactId, false);
        }
        return detail(scope, obClientId);
    }

    // ------------------------------------------------------------------
    // Consent
    // ------------------------------------------------------------------

    /**
     * What goes in the three consent columns after this save.
     *
     * <p><b>An unchanged consent keeps its original stamp.</b> This is the
     * method the task's whole justification rests on. If every save rewrote
     * {@code whatsapp_opt_in_at}, then correcting a SPOC's phone number in
     * November would re-date consent given in March to November — and the
     * organisation would have destroyed the evidence that consent covered the
     * messages it sent in between, in the exact way that "consent cannot be
     * backfilled" warns about, except silently and by a routine edit rather
     * than by an omission somebody could notice.
     *
     * <p>Unchanged means both the flag and the basis are the same. A SPOC who
     * upgrades a verbal consent to a written one is a change worth a new
     * timestamp and a new journal row: the basis is what a challenge turns on.
     */
    private ObContactWriteRepository.Consent consentFor(
            ObClientReadRepository.ContactRow current,
            ObContactDtos.ObContactUpsertRequest request, long callerId, Instant now) {

        if (!request.optedIn()) {
            return ObContactWriteRepository.Consent.withheld();
        }
        ObConsentSource source = sourceOf(request);
        boolean unchanged = current.whatsappOptIn()
                && source.name().equals(current.whatsappOptInSource());
        if (unchanged) {
            // Keeps the original date AND the original attributor. The row is
            // rewritten wholesale by the UPDATE, so both have to be carried
            // forward explicitly — leaving them out would blank the two columns
            // that say who attested this consent and when, which is the same
            // loss as restamping them, arrived at by omission.
            return new ObContactWriteRepository.Consent(
                    true, source, current.whatsappOptInAt(), current.whatsappOptInBy());
        }
        return ObContactWriteRepository.Consent.given(source, now, callerId);
    }

    /**
     * Whether this save is a consent event.
     *
     * <p>A grant, a withdrawal and a change of basis are; a save that leaves
     * consent where it was is not. Note the asymmetry with {@code UNRECORDED}:
     * a pre-capture row whose consent is now recorded properly reads as a
     * change, because the stored source is {@code UNRECORDED} and no request
     * can carry that value — which is the behaviour wanted, since re-approaching
     * those SPOCs and recording the answer is the entire point of leaving the
     * value visible.
     */
    private static boolean consentChanged(ObClientReadRepository.ContactRow current,
                                          ObContactWriteRepository.Consent consent) {
        if (current.whatsappOptIn() != consent.optedIn()) {
            return true;
        }
        return consent.optedIn() && !consent.source().name().equals(current.whatsappOptInSource());
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    /**
     * The scoped read, then the write role.
     *
     * <p>404 before 403 and never the reverse: an out-of-scope client must be
     * indistinguishable from a missing one, and a caller who can see the client
     * has already been shown it, so 403 concedes nothing. {@code
     * ObClientWriteService.update} states the same ordering at length.
     */
    private ObClientDtos.ObClientDetail requireWritableClient(ObClientScope scope, long obClientId) {
        ObClientDtos.ObClientDetail client = detail(scope, obClientId);
        if (!scope.mayWrite()) {
            throw new ObClientReadOnlyException(client.name());
        }
        return client;
    }

    /**
     * The scoped client read on its own, for the controller's {@code If-Match}
     * check.
     *
     * <p>Exposed rather than letting {@link ObContactController} hold {@code
     * ObClientService} as a second collaborator: the precondition has to be
     * evaluated against exactly the document these operations will answer with,
     * and two paths to it are two chances for them to diverge.
     */
    ObClientDtos.ObClientDetail readable(ObClientScope scope, long obClientId) {
        return detail(scope, obClientId);
    }

    private ObClientDtos.ObClientDetail detail(ObClientScope scope, long obClientId) {
        return details.findDetail(scope, obClientId)
                .orElseThrow(() -> new ObClientNotFoundException(obClientId));
    }

    private ObClientReadRepository.ContactRow requireContact(long obClientId, long contactId) {
        return reads.contactOf(obClientId, contactId)
                .orElseThrow(() -> new ObContactNotFoundException(obClientId, contactId));
    }

    /**
     * Refuse anything that would leave the client with no primary SPOC.
     *
     * <p>The condition is narrow on purpose: it fires only when <em>this</em>
     * contact is the active primary and the save would stop them being it,
     * either by clearing the flag or by deactivating them. Demoting somebody who
     * is not the primary changes nothing, and demoting the primary <em>while
     * promoting</em> is how a replacement is installed.
     */
    private static void refuseStrandingTheClient(ObClientReadRepository.ContactRow current,
                                                 boolean staysPrimary) {
        boolean isTheActivePrimary = current.isPrimary() && current.isActive();
        if (isTheActivePrimary && !staysPrimary) {
            throw new LastPrimaryContactException(current.name());
        }
    }

    private void rejectDuplicateEmail(long obClientId, String email, Long selfContactId) {
        Optional<ObClientReadRepository.ContactRow> holder =
                reads.contactByEmail(obClientId, email);
        holder.filter(row -> selfContactId == null || row.id() != selfContactId)
                .ifPresent(row -> {
                    throw new DuplicateContactEmailException(row.email(), !row.isActive());
                });
    }

    /**
     * Everything Bean Validation cannot say, collected rather than thrown at the
     * first — {@code ObClientValidationException}'s own reason.
     */
    private static void validate(ObContactDtos.ObContactUpsertRequest request, boolean active) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.optedIn()) {
            if (ObConsentSource.parse(request.whatsappOptInSource()).isEmpty()) {
                errors.put("whatsappOptInSource", request.whatsappOptInSource() == null
                        || request.whatsappOptInSource().isBlank()
                        ? "Say how this consent was given. It is the one fact about a SPOC that "
                                + "cannot be established later — one of " + ObConsentSource.settableNames()
                        : "Not a consent basis. One of " + ObConsentSource.settableNames());
            }
        } else if (request.whatsappOptInSource() != null && !request.whatsappOptInSource().isBlank()) {
            // Refused rather than ignored. A form sending a basis beside a
            // false is a form whose checkbox and dropdown have come apart, and
            // storing neither while accepting both is how the next person
            // concludes consent was recorded when it was not.
            errors.put("whatsappOptInSource",
                    "There is no consent to attribute — whatsappOptIn is false.");
        }

        if (request.primary() && !active) {
            // The database would allow it: is_primary_key goes NULL for an
            // inactive row, so the unique index has nothing to say. It is still
            // a contradiction — the primary is who the module sends to, and a
            // deactivated one is who it must not.
            errors.put("isPrimary",
                    "A removed contact cannot be the primary SPOC. Reactivate them, or promote "
                            + "somebody else.");
        }

        if (!errors.isEmpty()) {
            throw new ObClientValidationException(errors);
        }
    }

    /** Non-null by the time this is reached — {@link #validate} has already refused the rest. */
    private static ObConsentSource sourceOf(ObContactDtos.ObContactUpsertRequest request) {
        return ObConsentSource.parse(request.whatsappOptInSource())
                .orElseThrow(() -> new IllegalStateException(
                        "consent source passed validation and did not parse: "
                                + request.whatsappOptInSource()));
    }
}
