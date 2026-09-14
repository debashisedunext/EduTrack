package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.domain.onboarding.ObClient;
import com.edunext.edutrack.domain.onboarding.ObClientRepository;
import com.edunext.edutrack.domain.onboarding.ObClientStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Adding a client, editing one, and removing one that nothing depends on.
 *
 * <h2>What this class used to be</h2>
 *
 * <p>It committed OB-04's four-step wizard: the client row, its SPOCs, its
 * purchases, its requirements, one locked journey per purchased product, the
 * prerequisite checklist and optionally a portal login — all under one
 * {@code @Transactional}, because the contract called that create "the module's
 * widest side effect" and required it to be atomic.
 *
 * <p>Every one of those except the client row described an <b>engagement</b>
 * rather than a company, and engagements have a table now. The atomicity
 * argument did not disappear, it moved: {@code ObProjectWriteService} holds it,
 * writing the purchase, the project, the checklist and the journeys together so
 * that "a client boarded with no journeys, or journeys with no prerequisites"
 * is still unreachable. What is left here is a company — four fields — which is
 * atomic by being one row.
 *
 * <h2>The similar-name guard stayed, the PAN guard went with the PAN</h2>
 *
 * <p>Plan §1.1 item 6 wants one row per legal entity, and the two guards
 * answered that differently on purpose: the PAN was exact, unscoped and final;
 * name similarity is fuzzy, unscoped and <b>forceable</b>. With the PAN no
 * longer captured, {@code client_code} is the exact half —
 * {@code uq_ob_clients_client_code}, refused outright — and the name guard is
 * still the advisory half, because the many real pairs of clients that share a
 * name stem must not be blocked.
 *
 * <p>Neither guard is scoped, and that is the point: "one client per legal
 * entity" is a fact about the organisation, not about the caller. Scoping them
 * would let one salesperson board a duplicate of a client another salesperson
 * created, because the first is invisible to them.
 */
@Service
class ObClientWriteService {

    /**
     * How many name candidates the guard scores per create.
     *
     * <p>See {@code ObClientReadRepository.namesContaining} for why this is a
     * stop rather than a page.
     */
    private static final int NAME_CANDIDATE_CAP = 300;

    /** How many similar clients are named back before the rest are merely counted. */
    private static final int NAMED_CANDIDATES = 5;

    private final ObClientRepository clients;
    private final ObClientReadRepository reads;
    private final ObClientDeletionGuard deletionGuard;
    private final ObClientChildWriteRepository children;
    private final ObClientService details;

    /**
     * The SPOC the portal login needs, created through B-103's own path rather
     * than inserted here — so that the duplicate-email guard, the primary
     * demotion and the consent default all behave exactly as they do on the
     * panel.
     */
    private final ObContactService contacts;

    /**
     * Supplied by {@code feature.portal}. See
     * {@link ObClientPortalLoginIssuer} for why this is an interface owned by
     * this package rather than a direct call into that one.
     */
    private final ObClientPortalLoginIssuer portalLogins;

    /** One instant per write — the test seam that lets the stamped boarding date be pinned. */
    private final Clock clock;

    /**
     * {@code @Autowired} is not decorative — {@code ObEscalationService}'s own
     * note: two constructors and no annotation is not an ambiguity Spring
     * resolves, it is a context that fails to start.
     */
    @Autowired
    ObClientWriteService(ObClientRepository clients,
                         ObClientReadRepository reads,
                         ObClientDeletionGuard deletionGuard,
                         ObClientChildWriteRepository children,
                         ObClientService details,
                         ObContactService contacts,
                         ObClientPortalLoginIssuer portalLogins) {
        this(clients, reads, deletionGuard, children, details, contacts, portalLogins,
                Clock.systemUTC());
    }

    ObClientWriteService(ObClientRepository clients,
                         ObClientReadRepository reads,
                         ObClientDeletionGuard deletionGuard,
                         ObClientChildWriteRepository children,
                         ObClientService details,
                         ObContactService contacts,
                         ObClientPortalLoginIssuer portalLogins,
                         Clock clock) { // test seam
        this.clients = clients;
        this.reads = reads;
        this.deletionGuard = deletionGuard;
        this.children = children;
        this.details = details;
        this.contacts = contacts;
        this.portalLogins = portalLogins;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Create — the Clients master's add dialog
    // ------------------------------------------------------------------

    /**
     * A company, and nothing else.
     *
     * <p>This method used to commit the four-step wizard — PAN, SPOC contacts,
     * purchases, requirements, journeys, the prerequisite checklist and
     * optionally a portal login, in one transaction. All of that described an
     * <em>engagement</em>, and engagements are {@code ob_projects} now, created
     * from the New Project form. See {@code ObClientDtos.ObClientCreateRequest}
     * for what is no longer captured and what the two knock-on effects are.
     *
     * <p><b>{@code onboardingDate} is stamped, not asked for.</b> The column is
     * still {@code NOT NULL} and nothing reads it as anything other than "when
     * this company was first recorded", which is today by construction. The
     * wizard asked for it separately and routinely got a date a month away from
     * {@code created_at}.
     *
     * <p>The similar-name guard stays, and is the one piece of the wizard worth
     * keeping here. A four-field add dialog is precisely the screen on which
     * somebody boards "Horizon Schools Trust" for the second time, and
     * {@code uq_ob_clients_client_code} catches only the duplicates that also
     * reuse the code.
     *
     * <h2>One optional extra: the portal login</h2>
     *
     * <p>{@code createPortalLogin} brings back the one wizard step that was
     * about the company rather than an engagement — somebody has to be able to
     * log in and look at it. When it is ticked, three rows are written in this
     * one transaction: the client, the primary SPOC the login is named after
     * and addressed at, and the account itself.
     *
     * <p><b>All three, or none.</b> The transaction is what makes that true,
     * and it is the behaviour the flag has to have: a company left behind by a
     * login that failed is a company whose operator was told credentials were
     * on their way. B-102's refusal of the silently-ignored flag is the same
     * argument, and this is where it is finally honoured rather than refused.
     *
     * <p>The order is not arbitrary. The SPOC is written before the login
     * because {@code ClientAccountAdminService} reads the active primary to
     * build the username and to address the mail, and refuses outright when
     * there is none.
     */
    @Transactional
    Created create(ObClientScope scope, long callerId,
                   ObClientDtos.ObClientCreateRequest request) {
        requireWriter(scope);
        validateForCreate(request);
        if (!request.acknowledgedSimilarNames()) {
            guardAgainstSimilarNames(scope, request.name());
        }

        ObClient client = new ObClient(
                request.name().trim(), LocalDate.now(clock.withZone(ZoneOffset.UTC)), callerId);
        client.setClientCode(request.clientCode().trim());
        client.setAddress(trimmedOrNull(request.address()));
        client.setCity(trimmedOrNull(request.city()));

        ObClient saved = clients.saveAndFlush(client);

        ObClientPortalLoginIssuer.IssuedLogin login = null;
        if (request.createsPortalLogin()) {
            addPrimaryContactFor(scope, callerId, saved.getId(), request);
            login = portalLogins.issueFor(scope, saved.getId(), callerId);
        }

        ObClientDtos.ObClientDetail detail = details.findDetail(scope, saved.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "client " + saved.getId() + " was created and is not readable by its own creator — "
                                + "the scope rule and the created_by stamp disagree"));

        return new Created(detail, login);
    }

    /**
     * What the create produced: the client as it now reads, and the login if
     * one was asked for.
     *
     * <p>A pair rather than a field on {@code ObClientDetail} — see
     * {@code ObClientDtos.PortalLoginIssued} for why a credential must not
     * travel on the shape every read returns. {@code login} is null whenever
     * the box was not ticked.
     */
    record Created(ObClientDtos.ObClientDetail detail,
                   ObClientPortalLoginIssuer.IssuedLogin login) {
    }

    /**
     * The SPOC the login is named after, created through B-103's own add.
     *
     * <p>Routed through {@link ObContactService} rather than inserted directly,
     * so that the duplicate-email guard, the primary-demotion step and the
     * consent default are the ones the SPOC panel applies. A contact inserted
     * here by hand would be the second implementation of those rules and the
     * one that stopped matching first.
     *
     * <p>Primary and active are not the caller's to choose. The whole reason
     * this contact is being captured is that a portal login needs an active
     * primary; a dialog that let you create the SPOC non-primary would be
     * offering a combination that fails one line later.
     */
    private void addPrimaryContactFor(ObClientScope scope, long callerId, long obClientId,
                                      ObClientDtos.ObClientCreateRequest request) {
        contacts.add(scope, callerId, obClientId, new ObContactDtos.ObContactUpsertRequest(
                request.contactName().trim(),
                null,
                request.contactEmail().trim(),
                null,
                null,
                null,
                true,
                true));
    }

    // ------------------------------------------------------------------
    // Delete — and the reason it is nearly always refused
    // ------------------------------------------------------------------

    /**
     * Remove a client that nothing depends on.
     *
     * <p><b>This exists for one case: a row typed in wrong, minutes ago.</b>
     * Sixteen tables carry {@code ob_client_id} and several cascade — two of
     * them, {@code ob_step_history} and {@code ob_prereq_history}, are
     * hash-chained and append-only. A delete that reached those would destroy an
     * audit trail the whole module is built to keep, and it would do it
     * silently, because a cascade reports nothing.
     *
     * <p>So {@link ObClientDeletionGuard} asks four questions first and the
     * answer is a sentence naming what is in the way rather than a constraint
     * violation naming an index. {@code fk_ob_projects_client} is
     * {@code RESTRICT} underneath as a second layer, for the hand at a SQL
     * prompt that never passes through this method.
     *
     * <p>The alternative for everything else is already there and is what the
     * screen offers instead: {@code DROPPED} with a reason, which keeps the
     * record and hides the client from nothing.
     *
     * <p>404 before 403, and both before the guard: a caller who cannot see
     * this client must not learn from the refusal that it exists and has
     * projects.
     */
    @Transactional
    void delete(ObClientScope scope, long obClientId) {
        ObClientDtos.ObClientDetail current = details.findDetail(scope, obClientId)
                .orElseThrow(() -> new ObClientNotFoundException(obClientId));
        if (!scope.mayWrite()) {
            throw new ObClientReadOnlyException(current.name());
        }
        List<String> blockers = deletionGuard.blockersFor(obClientId);
        if (!blockers.isEmpty()) {
            throw new ObClientInUseException(obClientId, blockers);
        }
        clients.deleteById(obClientId);
    }


    // ------------------------------------------------------------------
    // Update — OB-05's Client info card
    // ------------------------------------------------------------------

    /**
     * @return empty for a client that is not there or is out of scope, which
     *         the controller turns into 404 — never 403, per CLAUDE.md
     */
    @Transactional
    ObClientDtos.ObClientDetail update(ObClientScope scope, long obClientId,
                                       ObClientUpdateRequest request) {
        // The scoped read comes first, so an out-of-scope id answers 404 before
        // anything about permission is considered. Asking "may you write?"
        // first would answer 403 and confirm the client exists.
        ObClientDtos.ObClientDetail current = details.findDetail(scope, obClientId)
                .orElseThrow(() -> new ObClientNotFoundException(obClientId));

        if (!scope.mayWrite()) {
            // 403, and not the 404 the read rule gives: this caller has already
            // been shown this client by GET, so refusing by status leaks
            // nothing they do not have. Viewer and Step Owner are read-only on
            // a client record — plan §3.
            throw new ObClientReadOnlyException(current.name());
        }

        ObClient client = clients.findById(obClientId)
                .orElseThrow(() -> new ObClientNotFoundException(obClientId));

        Map<String, String> errors = new LinkedHashMap<>();
        if (request.hasName()) {
            if (request.getName() == null || request.getName().isBlank()) {
                errors.put("name", "A client needs a name.");
            } else {
                client.setName(request.getName().trim());
            }
        }
        if (request.hasDescription()) {
            client.setDescription(trimmedOrNull(request.getDescription()));
        }
        if (request.hasAddress()) {
            client.setAddress(trimmedOrNull(request.getAddress()));
        }
        if (request.hasLicenseType()) {
            client.setLicenseType(trimmedOrNull(request.getLicenseType()));
        }
        if (request.hasCity()) {
            client.setCity(trimmedOrNull(request.getCity()));
        }
        if (request.hasClientCode()) {
            String code = trimmedOrNull(request.getClientCode());
            if (code == null) {
                // Not clearable. A client boarded through the retired wizard has
                // no code and that is a gap this screen exists to fill; letting
                // an edit put one back would be a regression somebody performs
                // by clearing a field.
                errors.put("clientCode", "Give the client a code — it is how operations file them.");
            } else if (clients.findByClientCode(code)
                    .filter(holder -> !holder.getId().equals(client.getId())).isPresent()) {
                // Filtered on identity so re-saving the form unchanged is not a
                // conflict with the client's own row. The holder is not named —
                // see ObClientRepository#findByClientCode.
                errors.put("clientCode", "The code " + code + " already belongs to another client.");
            } else {
                client.setClientCode(code);
            }
        }
        if (request.hasSalesPersonId()) {
            Long salesPersonId = request.getSalesPersonId();
            if (salesPersonId != null && !children.isActiveUser(salesPersonId)) {
                errors.put("salesPersonId", "That sales person is not an active user.");
            } else {
                client.setSalesPersonId(salesPersonId);
            }
        }
        if (request.hasStatus()) {
            applyStatus(client, request, errors);
        }

        if (!errors.isEmpty()) {
            throw new ObClientValidationException(errors);
        }

        clients.saveAndFlush(client);
        return details.findDetail(scope, obClientId)
                .orElseThrow(() -> new ObClientNotFoundException(obClientId));
    }

    /**
     * The status rules, all three of them in one place.
     *
     * <p>{@code LIVE} is refused outright; {@code ON_HOLD} and {@code DROPPED}
     * require a reason; anything outside the enum is a 400 rather than a
     * silently ignored field. The reason requirement is checked <b>before</b>
     * the entity is touched, so a rejected status leaves the client exactly as
     * it was rather than half-applied.
     */
    private void applyStatus(ObClient client, ObClientUpdateRequest request,
                             Map<String, String> errors) {
        ObClientStatus status;
        try {
            status = ObClientStatus.valueOf(request.getStatus().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.put("status", "Not a client status. One of ONBOARDING, LIVE, ON_HOLD, DROPPED.");
            return;
        }
        if (!status.settableByHand()) {
            throw new LiveStatusNotEarnedException();
        }
        if (status.requiresReason()
                && (request.getStatusReason() == null || request.getStatusReason().isBlank())) {
            errors.put("statusReason",
                    "Say why. A client put on hold or dropped without a reason is a decision "
                            + "nobody can explain later.");
            return;
        }
        client.recordStatus(status, trimmedOrNull(request.getStatusReason()));
    }

    // ------------------------------------------------------------------
    // The guards
    // ------------------------------------------------------------------


    /** The fuzzy guard: a warning the boarder can overrule, never a refusal they cannot. */
    private void guardAgainstSimilarNames(ObClientScope scope, String name) {
        String probe = SimilarClientNames.probe(name);
        if (probe == null) {
            return;
        }
        List<SimilarClientNameException.Candidate> named = new ArrayList<>();
        int hidden = 0;
        for (ObClientReadRepository.NameRow candidate : reads.namesContaining(probe, NAME_CANDIDATE_CAP)) {
            if (!SimilarClientNames.similar(name, candidate.name())) {
                continue;
            }
            if (scope.seesClientAuthoredBy(candidate.createdBy()) && named.size() < NAMED_CANDIDATES) {
                named.add(new SimilarClientNameException.Candidate(candidate.id(), candidate.name()));
            } else {
                hidden++;
            }
        }
        if (!named.isEmpty() || hidden > 0) {
            throw new SimilarClientNameException(named, hidden);
        }
    }



    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    /**
     * The two things Bean Validation cannot say, collected rather than thrown
     * at the first — see {@link ObClientValidationException} for why.
     */
    private void validateForCreate(ObClientDtos.ObClientCreateRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();

        String code = request.clientCode() == null ? "" : request.clientCode().trim();
        if (code.isEmpty()) {
            errors.put("clientCode", "Give the client a code — it is how operations file them.");
        } else if (clients.findByClientCode(code).isPresent()) {
            // uq_ob_clients_client_code says the same thing and would say it as
            // an index name. The existing holder is deliberately NOT named: the
            // guard runs unscoped, so the client wearing this code may be one
            // this caller has no business knowing exists. The code itself is
            // safe to repeat — they just typed it.
            errors.put("clientCode", "The code " + code + " already belongs to another client.");
        }

        if (request.name() != null && request.name().trim().isEmpty()) {
            errors.put("name", "Give the client a name.");
        }

        // Conditionally required, which is exactly the shape Bean Validation
        // cannot express on a record without a class-level constraint that
        // reports against the whole object rather than the field the operator
        // has to go and fill in. Collected here with the other two so a form
        // with three things wrong is returned once.
        if (request.createsPortalLogin()) {
            if (!hasText(request.contactName())) {
                errors.put("contactName",
                        "A portal login is issued to a person — give the client's main contact.");
            }
            if (!hasText(request.contactEmail())) {
                errors.put("contactEmail",
                        "The login's one-time link is mailed to this address.");
            }
        }

        if (!errors.isEmpty()) {
            throw new ObClientValidationException(errors);
        }
    }


    /**
     * Boarding a client is OB Admin, Onboarding Manager or Sales.
     *
     * <p>404 rather than 403, on {@code ModuleAccessGuard}'s own rule: a caller
     * with no onboarding standing must not be able to tell the module apart
     * from a typo, and none of the six ticketing roles carries any onboarding
     * standing at all. A Viewer or a Step Owner reaching here has standing and
     * gets the same answer, which is a slightly blunter refusal than they
     * deserve and is the same one {@code ObJourneyStepLifecycleService} gives
     * for a non-moderator.
     */
    private static void requireWriter(ObClientScope scope) {
        if (!scope.mayWrite()) {
            throw new NotAnOnboardingClientWriterException();
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
