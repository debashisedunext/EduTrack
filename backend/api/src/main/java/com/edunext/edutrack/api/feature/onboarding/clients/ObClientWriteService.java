package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.feature.onboarding.instances.ObJourneyInstantiationService;
import com.edunext.edutrack.api.security.pan.PanService;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * B-102 · boarding a client (OB-04) and editing one (OB-05), plus the two
 * duplicate guards that make either safe.
 *
 * <h2>One request, one transaction, and the reason it has to be</h2>
 *
 * <p>The contract calls the create "the module's widest side effect" and
 * requires it to be atomic: <em>"a client boarded with no journeys, or journeys
 * with no prerequisites, is a half-state somebody has to notice and repair by
 * hand."</em> So the client row, its SPOCs, its purchases, its requirements and
 * one locked journey per purchased product are written under a single
 * {@code @Transactional}, and any failure among them leaves nothing behind.
 *
 * <h2>What this create does not do yet, and why that is stated rather than
 * silent</h2>
 *
 * <ul>
 *   <li><b>No prerequisites instance.</b> There is no {@code ob_prereq_*} table
 *       on {@code develop} — B-124 brings the master and B-125 the per-client
 *       instances. Every journey is still created {@code LOCKED}, which is the
 *       state the gate exists to hold; what is missing is the checklist that
 *       opens it, not the hold.</li>
 *   <li><b>Portal login: built.</b> {@code createPortalLogin: true} was refused
 *       rather than ignored until B-126; it is now honoured, through
 *       {@code ClientAccountAdminService} rather than a second implementation
 *       here. {@code PortalLoginUnavailableException} and its handler branch are
 *       deleted, as that exception's own javadoc said they would be.</li>
 * </ul>
 *
 * <h2>The two guards are deliberately unlike each other</h2>
 *
 * <p>PAN is exact, unscoped and final. Name similarity is fuzzy, unscoped and
 * <b>forceable</b>. Plan §1.1 item 6 wants one row per legal entity; the PAN is
 * what makes that decidable, and a name is only ever evidence. Making both
 * final would block the many real pairs of clients that share a name stem;
 * making both advisory would let a mistyped second row through on a PAN the
 * database could have refused outright.
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
    private final ObClientChildWriteRepository children;
    private final ObRequirementWriteRepository requirements;
    private final ObRequirementBody requirementBodies;
    private final ObClientService details;
    private final ObJourneyInstantiationService journeys;
    private final PanService pan;

    /**
     * B-126 · the client-account panel's service, called for the OB-04 wizard's
     * "create client login" checkbox.
     *
     * <p>Reached across features rather than reimplemented here: username
     * generation, the placeholder password, the single-use link and the
     * credential mail are one path, and a second copy of it in the wizard is a
     * second set of rules to keep in step. The class is {@code public} for
     * exactly this call.
     */
    private final com.edunext.edutrack.api.feature.portal.ClientAccountAdminService portalAccounts;

    /** B-103 · one instant per create, stamped onto every contact's consent. */
    private final Clock clock;

    /**
     * {@code @Autowired} is not decorative — {@code ObEscalationService}'s own
     * note: two constructors and no annotation is not an ambiguity Spring
     * resolves, it is a context that fails to start.
     */
    @Autowired
    ObClientWriteService(ObClientRepository clients,
                         ObClientReadRepository reads,
                         ObClientChildWriteRepository children,
                         ObRequirementWriteRepository requirements,
                         ObRequirementBody requirementBodies,
                         ObClientService details,
                         ObJourneyInstantiationService journeys,
                         PanService pan,
                         com.edunext.edutrack.api.feature.portal.ClientAccountAdminService portalAccounts) {
        this(clients, reads, children, requirements, requirementBodies, details, journeys, pan,
                portalAccounts, Clock.systemUTC());
    }

    ObClientWriteService(ObClientRepository clients,
                         ObClientReadRepository reads,
                         ObClientChildWriteRepository children,
                         ObRequirementWriteRepository requirements,
                         ObRequirementBody requirementBodies,
                         ObClientService details,
                         ObJourneyInstantiationService journeys,
                         PanService pan,
                         com.edunext.edutrack.api.feature.portal.ClientAccountAdminService portalAccounts,
                         Clock clock) { // test seam
        this.clients = clients;
        this.reads = reads;
        this.children = children;
        this.requirements = requirements;
        this.requirementBodies = requirementBodies;
        this.details = details;
        this.journeys = journeys;
        this.pan = pan;
        this.portalAccounts = portalAccounts;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Create — OB-04
    // ------------------------------------------------------------------

    @Transactional
    ObClientDtos.ObClientDetail create(ObClientScope scope, long callerId,
                                       ObClientDtos.ObClientCreateRequest request) {
        requireWriter(scope);

        validateForCreate(request);
        // Sealed once. seal() produces the ciphertext and the blind index
        // together because writing one without the other is always a bug, and
        // the index it produces is the same value the guard below matches on —
        // computing it twice would be two chances to normalise differently.
        PanService.SealedPan sealed = hasText(request.pan()) ? pan.seal(request.pan()) : null;
        if (sealed != null) {
            guardAgainstDuplicatePan(scope, sealed.blindIndex());
        }
        if (!request.acknowledgedSimilarNames()) {
            guardAgainstSimilarNames(scope, request.name());
        }
        requirePublishedTemplates(request);

        ObClient client = new ObClient(request.name().trim(), request.onboardingDate(), callerId);
        client.setDescription(trimmedOrNull(request.description()));
        client.setAddress(trimmedOrNull(request.address()));
        client.setSalesPersonId(request.salesPersonId());
        client.setLicenseType(trimmedOrNull(request.licenseType()));
        if (sealed != null) {
            client.sealPan(sealed.ciphertext(), sealed.blindIndex());
        }

        // Flushed, not merely saved: everything below reads and writes through
        // JdbcClient, which issues SQL outside the EntityManager. Hibernate's
        // AUTO flush only fires ahead of queries it can see overlap the dirty
        // entities, and raw JDBC is not one — the insert would land after the
        // child rows that reference it. ClientWriteService documents the same
        // trap for the same reason.
        ObClient saved = clients.saveAndFlush(client);
        long clientId = saved.getId();

        Instant at = clock.instant();
        children.insertContacts(clientId, request.contacts(), callerId, at);
        children.insertApplications(clientId, request.applications());
        insertRequirements(clientId, request.requirementsOrEmpty(), callerId, at);

        // One locked journey per purchased product, from C-103's service. It
        // reads ob_client_applications to check the product was bought, which
        // is why the purchases are written first.
        for (ObClientDtos.ObApplicationWriteRequest application : request.applications()) {
            journeys.instantiate(clientId, application.productId());
        }

        // B-126 · the wizard's "create client login" checkbox, honoured rather
        // than refused. Last, and deliberately so: the account is minted from
        // the client's name and its primary SPOC, so both rows have to exist,
        // and the credential mail is queued through B-110's outbox inside this
        // same transaction — a login created against a client that then rolls
        // back would be a credential for nothing.
        if (request.wantsPortalLogin()) {
            portalAccounts.create(scope, clientId, callerId);
        }

        return details.findDetail(scope, clientId)
                .orElseThrow(() -> new IllegalStateException(
                        "client " + clientId + " was created and is not readable by its own creator — "
                                + "the scope rule and the created_by stamp disagree"));
    }

    /**
     * B-106 · the wizard's requirements step, written through the same
     * repository and the same sanitiser OB-05's panel uses.
     *
     * <p>Not {@code ObClientChildWriteRepository} any more, and not a private
     * copy of the statement. A requirement body goes through PLAN.md §3.9's
     * allow-list before it is stored, and a wizard path that wrote the raw
     * string while the panel sanitised would be a security boundary with a hole
     * in the older half of it — the hole that is hardest to notice, because the
     * screen renders both rows the same way.
     *
     * <p><b>Blank entries are dropped rather than refused</b>, which is B-102's
     * behaviour kept deliberately. A wizard textarea produces them by accident
     * and an empty requirement is a line on a checklist that says nothing; a
     * boarder should not have to hunt an invisible row to submit their form. A
     * body that is <em>not</em> blank and reduces to nothing under the allow-list
     * is a different matter — that one is a 400, keyed to its own index, because
     * something was typed and nothing survived, and silently dropping it would
     * lose a requirement somebody believed they had recorded.
     *
     * <p>The index in the key is what lets a four-step wizard reopen the right
     * row: {@code requirements[2].bodyHtml} on a list of six is a message about
     * one of them, where {@code requirements} alone is a message about none.
     */
    private void insertRequirements(long clientId,
                                    List<ObClientDtos.ObRequirementWriteRequest> rows,
                                    Long createdBy, Instant at) {
        int sequence = 0;
        for (int index = 0; index < rows.size(); index++) {
            ObClientDtos.ObRequirementWriteRequest row = rows.get(index);
            if (row == null || row.bodyHtml() == null || row.bodyHtml().isBlank()) {
                continue;
            }
            ObRequirementBody.Stored body =
                    requirementBodies.of(row.bodyHtml(), "requirements[" + index + "].bodyHtml");
            boolean met = row.met();
            requirements.insert(clientId, sequence++, trimmedOrNull(row.title()),
                    body.html(), body.text(),
                    met, met ? at : null, met ? createdBy : null, createdBy);
        }
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

    /**
     * The exact guard: one PAN, one client, and no way to force a second.
     *
     * <p>The lookup is by blind index, so <b>no PAN is decrypted to answer
     * it</b> — which is what keeps the guard out of §11's audit log. Routing it
     * through decryption would produce either an audit row per wizard
     * submission or an exemption that hollows out the rule; {@code
     * PanBlindIndex}'s own javadoc makes that argument at length.
     *
     * @param blindIndex the 32 bytes {@code seal} already produced for this PAN
     */
    private void guardAgainstDuplicatePan(ObClientScope scope, byte[] blindIndex) {
        clients.findByPanBlindIndex(blindIndex).ifPresent(existing -> {
            // Named only where this caller could have found it themselves. A
            // 409 that names a client outside the caller's scope would make the
            // duplicate guard a way to read somebody else's client list, one
            // PAN at a time — and whoever holds a PAN to try usually holds the
            // name already, so nothing useful is withheld.
            throw new DuplicateClientPanException(
                    scope.seesClientAuthoredBy(existing.getCreatedBy()) ? existing.getName() : null);
        });
    }

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

    /**
     * Every purchased product must have a template to instantiate from, and
     * every one that does not is named at once.
     */
    private void requirePublishedTemplates(ObClientDtos.ObClientCreateRequest request) {
        Set<Long> productIds = productIdsOf(request);
        Set<Long> withTemplate = children.productIdsWithActiveTemplate(productIds);
        List<Long> without = productIds.stream().filter(id -> !withTemplate.contains(id)).toList();
        if (!without.isEmpty()) {
            throw new ProductWithoutTemplateException(without);
        }
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    /**
     * Everything Bean Validation cannot say, collected rather than thrown at
     * the first — see {@link ObClientValidationException} for why.
     */
    private void validateForCreate(ObClientDtos.ObClientCreateRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();

        long primaries = request.contacts().stream()
                .filter(ObClientDtos.ObContactWriteRequest::primary).count();
        if (primaries == 0) {
            errors.put("contacts", "Mark one contact as the primary SPOC. They receive the kickoff "
                    + "mail, the portal password and every sign-off request.");
        } else if (primaries > 1) {
            errors.put("contacts", "Only one contact can be the primary SPOC — "
                    + primaries + " are marked.");
        }

        // B-103 · consent needs a basis, and the wizard is where the
        // conversation that produced it happened. Storing a bare `true` here
        // would create exactly the row PHASE-2-BUILD-PLAN.md §6.1 calls "the
        // one item that is genuinely irreversible" — a SPOC who has to be
        // re-approached before a single message can go out, and no way to tell
        // from the data that they do. Keyed to `contacts` so the wizard reopens
        // its SPOC step.
        for (ObClientDtos.ObContactWriteRequest contact : request.contacts()) {
            String basis = contact.whatsappOptInSource();
            if (contact.optedIn() && ObConsentSource.parse(basis).isEmpty()) {
                errors.put("contacts", basis == null || basis.isBlank()
                        ? "Say how " + contact.email().trim() + " gave WhatsApp consent — one of "
                                + ObConsentSource.settableNames() + ". It cannot be established later."
                        : "Not a consent basis for " + contact.email().trim() + ". One of "
                                + ObConsentSource.settableNames());
                break;
            }
            if (!contact.optedIn() && basis != null && !basis.isBlank()) {
                errors.put("contacts", "There is no consent to attribute for "
                        + contact.email().trim() + " — whatsappOptIn is false.");
                break;
            }
        }

        Set<String> emails = new LinkedHashSet<>();
        for (ObClientDtos.ObContactWriteRequest contact : request.contacts()) {
            if (!emails.add(contact.email().trim().toLowerCase(Locale.ROOT))) {
                // uq_ob_client_contacts_email says the same thing, and would say
                // it as a constraint name. The same email at two clients is
                // fine: one consultant can be the SPOC at both.
                errors.put("contacts", "Two contacts share the email " + contact.email().trim()
                        + ". One person, one row.");
                break;
            }
        }

        Set<Long> productIds = new LinkedHashSet<>();
        for (ObClientDtos.ObApplicationWriteRequest application : request.applications()) {
            if (application.productId() != null && !productIds.add(application.productId())) {
                errors.put("applications", "Product " + application.productId()
                        + " is selected twice. Buying more seats of something already bought is an "
                        + "edit to one purchase, not a second one — a second row would mean a "
                        + "second journey for one product.");
                break;
            }
        }
        for (ObClientDtos.ObApplicationWriteRequest application : request.applications()) {
            if (application.licenseStart() != null && application.licenseEnd() != null
                    && application.licenseEnd().isBefore(application.licenseStart())) {
                errors.put("applications", "A licence cannot end before it starts.");
                break;
            }
        }
        Set<Long> sellable = children.sellableProductIds(productIds);
        List<Long> unsellable = productIds.stream().filter(id -> !sellable.contains(id)).toList();
        if (!unsellable.isEmpty()) {
            errors.put("applications", "No product on sale with id "
                    + unsellable.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("")
                    + ". A retired product is out of the picker by definition.");
        }

        if (request.salesPersonId() != null && !children.isActiveUser(request.salesPersonId())) {
            // The role is deliberately not checked — B-015 removed exactly this
            // kind of hardcoded role set from the resource form, and B-016 made
            // the same call for a project manager.
            errors.put("salesPersonId", "That sales person is not an active user.");
        }

        if (!errors.isEmpty()) {
            throw new ObClientValidationException(errors);
        }
    }

    private static Set<Long> productIdsOf(ObClientDtos.ObClientCreateRequest request) {
        Set<Long> ids = new LinkedHashSet<>();
        request.applications().forEach(application -> ids.add(application.productId()));
        return ids;
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
