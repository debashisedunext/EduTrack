package com.edunext.edutrack.api.feature.clients;

import com.edunext.edutrack.domain.clients.Client;
import com.edunext.edutrack.domain.clients.ClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * B-026 · S-33 — the Client Master create/edit form's writes.
 *
 * <p>Separate from {@link ClientService} the way {@code ResourceWriteService} is
 * separate from {@code ResourceService}: the read side is a keyset page plus four
 * grouped projections, the write side is a validation set. Putting both in one
 * class makes the file the one every client change touches, which is what feature
 * packaging exists to avoid within a feature as much as across one.
 *
 * <h2>Nothing is created here that a ticket can be raised against</h2>
 *
 * <p>Blueprint §4B.2 and B-028: <b>a client is not selectable on a ticket until
 * it has a primary contact</b>, and a create cannot supply one — there is no
 * client id to hang a contact off until this method returns. So a created client
 * is deliberately incomplete, {@code hasPrimaryContact} says so on the response,
 * and the form's Contacts tab says so on screen. Enforcing the gate belongs on
 * the ticket create path (B-028), the same place B-029's "blocks new tickets"
 * belongs, and for the same reason: a refusal is only useful where the caller can
 * act on it.
 *
 * <h2>The validation set, and the order it runs in</h2>
 *
 * <p>Bean Validation on {@link ClientDtos.ClientWriteRequest} covers lengths,
 * email shape and the code's character set. What is here is everything that needs
 * a query or a comparison between two fields:
 *
 * <ol>
 *   <li><b>Duplicate client code</b> — 409, field-keyed, checked in the service
 *       <em>and</em> enforced by {@code uq_clients_code}. The check is the good
 *       error message and the index is the thing that is actually true under a
 *       race; B-013 made the same argument for the resource form and it has not
 *       changed.</li>
 *   <li><b>Status and support plan vocabularies</b> — {@link ClientStatus} and
 *       {@link ClientSupportPlan}, one statement of each.</li>
 *   <li><b>Timezone</b> — an IANA zone id. Unchecked, it is a string that reads
 *       fine on the form and makes every client-facing due date on the ticket
 *       page throw at render.</li>
 *   <li><b>Contract dates</b> — an end before a start. Cheap, and the kind of
 *       typo that surfaces months later as a client that appears to have no live
 *       contract.</li>
 *   <li><b>Account manager</b> — must exist and be <b>active</b>. This is who
 *       B-060's client report attributes to and who is watched onto every ticket
 *       raised for the client (§4B.2's auto-fill); naming somebody who has left
 *       points all of that at a mailbox nobody reads. The <b>role is deliberately
 *       not checked</b> — B-016 made that call for a project manager and B-015
 *       removed exactly this kind of hardcoded role set from
 *       {@code ResourceController}.</li>
 *   <li><b>Projects</b> — every id must exist, and <b>every missing one is
 *       named</b>. A stale multi-select is fixed in one round or in as many
 *       rounds as it has stale entries, and only one of those is a screen
 *       somebody uses.</li>
 *   <li><b>Default project</b> — must be one of the selected projects. A default
 *       the client is not mapped to is a row §4B.2's ticket form can never offer,
 *       so it would be configuration that silently does nothing.</li>
 *   <li><b>SLA policy</b> — must exist, because {@code clients.sla_policy_id} is
 *       a foreign key and the alternative is a constraint violation naming a
 *       MySQL index.</li>
 * </ol>
 *
 * <p><b>Every failure is collected, not thrown at the first.</b> S-33 is a
 * four-tab form and its fields are spread across all four; refusing one at a time
 * means an admin who mistyped a timezone on Identity and picked a departed
 * account manager on Commercial saves twice to learn twice. The problem document
 * is field-keyed so each message lands on its own input, which is also what tells
 * the form which tab to open.
 */
@Service
public class ClientWriteService {

    /** The column default, and not a guess made here. */
    static final String DEFAULT_TIMEZONE = "Asia/Kolkata";

    private final ClientRepository clients;
    private final ClientWriteRepository write;
    private final ClientService reads;

    ClientWriteService(ClientRepository clients, ClientWriteRepository write, ClientService reads) {
        this.clients = clients;
        this.write = write;
        this.reads = reads;
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    @Transactional
    public ClientDtos.ClientDetail create(ClientDtos.ClientWriteRequest request) {
        Validated valid = validate(request, null);

        Client client = new Client();
        apply(client, request, valid);
        // Flushed, not merely saved: the response is assembled by
        // ClientQueryRepository through JdbcClient, which issues SQL outside the
        // EntityManager. Hibernate's AUTO flush only fires ahead of queries it
        // can see overlap the dirty entities, and a raw JDBC read is not one —
        // the same trap B-025's bulk status setter documents, where the response
        // would otherwise have reported the state before the write.
        Client saved = clients.saveAndFlush(client);

        applyProjects(saved.getId(), request, valid);
        return detailOf(saved);
    }

    // ------------------------------------------------------------------
    // Update
    // ------------------------------------------------------------------

    /**
     * @return empty for a client that is not there, which the controller turns
     *         into 404 — never 403, per CLAUDE.md
     */
    @Transactional
    public Optional<ClientDtos.ClientDetail> update(long clientId,
                                                    ClientDtos.ClientWriteRequest request) {

        Optional<Client> found = clients.findById(clientId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Validated valid = validate(request, clientId);

        Client client = found.get();
        apply(client, request, valid);
        Client saved = clients.saveAndFlush(client);

        applyProjects(clientId, request, valid);
        return Optional.of(detailOf(saved));
    }

    // ------------------------------------------------------------------
    // Applying
    // ------------------------------------------------------------------

    /**
     * The body is the whole representation — an absent field is a cleared field.
     *
     * <p>Correct here and wrong for B-017's and B-020's patches, and the
     * difference is the screen rather than a preference: S-33 submits every input
     * on every save, so "the form did not send a short name" and "the admin
     * emptied the short name box" are the same event. A sparse reading would make
     * clearing a field impossible through the only screen that edits it.
     */
    private void apply(Client client,
                       ClientDtos.ClientWriteRequest request,
                       Validated valid) {

        client.setClientCode(valid.clientCode());
        client.setName(request.name().trim());
        client.setShortName(trimToNull(request.shortName()));
        client.setLogoUrl(trimToNull(request.logoUrl()));
        client.setIndustry(trimToNull(request.industry()));
        client.setStatus(valid.status().name());
        client.setWebsiteDomain(valid.domain());
        client.setPrimaryEmail(trimToNull(request.primaryEmail()));
        client.setSupportEmail(trimToNull(request.supportEmail()));
        client.setPhone(trimToNull(request.phone()));

        client.setAddressLine1(trimToNull(request.addressLine1()));
        client.setAddressLine2(trimToNull(request.addressLine2()));
        client.setCity(trimToNull(request.city()));
        client.setState(trimToNull(request.state()));
        client.setCountry(trimToNull(request.country()));
        client.setPostalCode(trimToNull(request.postalCode()));
        client.setTimezone(valid.timezone());

        client.setAccountManagerId(request.accountManagerId());
        client.setContractStart(request.contractStart());
        client.setContractEnd(request.contractEnd());
        client.setSupportPlan(valid.supportPlan());
        client.setBillingReference(trimToNull(request.billingReference()));
        client.setBillingEmail(trimToNull(request.billingEmail()));

        client.setNotes(trimToNull(request.notes()));
        client.setTags(valid.tags());
        client.setSlaPolicyId(request.slaPolicyId());
    }

    /**
     * <b>Absent leaves the mapping alone; empty unmaps everything.</b>
     *
     * <p>The one place in this body where those differ, and they have to: B-035's
     * import writes client rows without ever touching project associations, so a
     * null read as "unmap" would have every import silently detach every client
     * it updated from every project — discovered later as a ticket form whose
     * client dropdown has gone empty. An empty array is a real instruction and
     * S-33 can send one, by clearing the multi-select.
     */
    private void applyProjects(long clientId,
                               ClientDtos.ClientWriteRequest request,
                               Validated valid) {

        if (request.projectIds() == null) {
            return;
        }
        write.replaceProjects(clientId, valid.projectIds(), request.defaultProjectId());
    }

    private ClientDtos.ClientDetail detailOf(Client saved) {
        ClientDtos.Client row = reads.find(saved.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Client " + saved.getId() + " was written and could not be read back."));
        return reads.toDetail(saved, row);
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    /** The normalised values, computed once so nothing re-derives them on the way in. */
    private record Validated(String clientCode,
                             ClientStatus status,
                             String supportPlan,
                             String timezone,
                             String domain,
                             List<String> tags,
                             List<Long> projectIds) {
    }

    private Validated validate(ClientDtos.ClientWriteRequest request, Long exceptId) {
        Map<String, String> errors = new LinkedHashMap<>();

        String clientCode = request.clientCode() == null
                ? null
                : request.clientCode().trim().toUpperCase(Locale.ROOT);
        if (clientCode != null && write.findConflictingCode(clientCode, exceptId).isPresent()) {
            // The message names the code as the admin will see it stored, not as
            // they typed it — `acme` refused for colliding with `ACME` otherwise
            // reads as the same string being rejected for matching itself.
            errors.put("clientCode", "Client code " + clientCode + " is already in use.");
        }

        ClientStatus status = ClientStatus.parse(request.status()).orElse(null);
        if (status == null) {
            if (request.status() == null || request.status().isBlank()) {
                // Absent is ACTIVE, which is the column default and what every
                // create means. Not an error.
                status = ClientStatus.ACTIVE;
            } else {
                errors.put("status", "Status must be one of " + ClientStatus.CODES + ".");
                status = ClientStatus.ACTIVE;
            }
        }

        String supportPlan = null;
        if (request.supportPlan() != null && !request.supportPlan().isBlank()) {
            Optional<ClientSupportPlan> plan = ClientSupportPlan.parse(request.supportPlan());
            if (plan.isEmpty()) {
                errors.put("supportPlan",
                        "Support plan must be one of " + ClientSupportPlan.CODES + ".");
            } else {
                supportPlan = plan.get().name();
            }
        }

        String timezone = trimToNull(request.timezone());
        if (timezone == null) {
            timezone = DEFAULT_TIMEZONE;
        } else if (!isKnownZone(timezone)) {
            errors.put("timezone", "'" + timezone + "' is not a known time zone.");
            timezone = DEFAULT_TIMEZONE;
        }

        if (request.contractStart() != null && request.contractEnd() != null
                && request.contractEnd().isBefore(request.contractStart())) {
            errors.put("contractEnd", "The contract cannot end before it starts.");
        }

        if (request.accountManagerId() != null) {
            Optional<ClientWriteRepository.ManagerCandidate> manager =
                    write.findManager(request.accountManagerId());
            if (manager.isEmpty()) {
                errors.put("accountManagerId", "That resource does not exist.");
            } else if (!manager.get().isActive()) {
                errors.put("accountManagerId", manager.get().displayName()
                        + " is deactivated and cannot be an account manager.");
            }
        }

        List<Long> projectIds = request.projectIds() == null
                ? List.of()
                : request.projectIds().stream().filter(java.util.Objects::nonNull).distinct().toList();

        if (!projectIds.isEmpty()) {
            List<Long> missing = write.missingProjectIds(projectIds);
            if (!missing.isEmpty()) {
                errors.put("projectIds", missing.size() == 1
                        ? "Project " + missing.getFirst() + " does not exist."
                        : "These projects do not exist: " + missing + ".");
            }
        }

        if (request.defaultProjectId() != null && !projectIds.contains(request.defaultProjectId())) {
            errors.put("defaultProjectId",
                    "The default project must be one of the projects this client is mapped to.");
        }

        if (request.slaPolicyId() != null && !write.slaPolicyExists(request.slaPolicyId())) {
            errors.put("slaPolicyId", "That SLA policy does not exist.");
        }

        if (!errors.isEmpty()) {
            throw new ClientValidationException(errors);
        }

        return new Validated(clientCode, status, supportPlan, timezone,
                normaliseDomain(request.domain()), normaliseTags(request.tags()), projectIds);
    }

    /**
     * A domain D-039 can actually match a sender address against.
     *
     * <p>An admin types what is in their browser's address bar —
     * {@code https://acme.example/} — and {@code ClientRepository
     * .findByWebsiteDomain} looks up the bare host taken from an email address.
     * Stored unmodified, the two never meet and inbound mail auto-attribution
     * silently stops working for that client, with nothing on any screen wrong.
     * Cheap to normalise, invisible to get wrong.
     */
    private static String normaliseDomain(String raw) {
        String domain = trimToNull(raw);
        if (domain == null) {
            return null;
        }
        String bare = domain.toLowerCase(Locale.ROOT)
                .replaceFirst("^[a-z][a-z0-9+.-]*://", "")
                .replaceFirst("^www\\.", "");
        int slash = bare.indexOf('/');
        if (slash >= 0) {
            bare = bare.substring(0, slash);
        }
        return bare.isBlank() ? null : bare;
    }

    /**
     * Trimmed, blank-free and de-duplicated, preserving the order they were
     * entered in.
     *
     * <p>Null rather than an empty array when nothing survives: the column is
     * nullable, and {@code NULL} and {@code []} both mean "no tags" but only one
     * of them costs a JSON document on every row.
     */
    private static List<String> normaliseTags(List<String> raw) {
        if (raw == null) {
            return null;
        }
        List<String> tags = new ArrayList<>();
        for (String tag : raw) {
            String trimmed = trimToNull(tag);
            if (trimmed != null && !tags.contains(trimmed)) {
                tags.add(trimmed);
            }
        }
        return tags.isEmpty() ? null : List.copyOf(tags);
    }

    /**
     * {@code ZoneId.of} throws two different exceptions for two shapes of bad
     * input and neither is a validation failure on its own.
     */
    private static boolean isKnownZone(String timezone) {
        try {
            ZoneId.of(timezone);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------------
    // Failures
    // ------------------------------------------------------------------

    /**
     * Every field that failed, in one document.
     *
     * <p>Rendered 409 when the only failure is a duplicate code and 400
     * otherwise — see {@link ClientExceptionHandler}. Carrying the whole map
     * rather than throwing at the first failure is what lets a four-tab form
     * mark every bad input at once.
     */
    static final class ClientValidationException extends RuntimeException {

        private final transient Map<String, String> errors;

        ClientValidationException(Map<String, String> errors) {
            super(String.join(" ", errors.values()));
            this.errors = Map.copyOf(errors);
        }

        Map<String, String> errors() {
            return errors;
        }

        /**
         * True when a duplicate client code is the <em>only</em> thing wrong.
         *
         * <p>A 409 that also carries a bad timezone would be describing two
         * different kinds of failure with the status code for one of them, and a
         * client branching on the status — which CONVENTIONS.md §3 says is the
         * supported thing to do — would handle it as a uniqueness conflict and
         * never show the other message.
         */
        boolean isDuplicateCodeOnly() {
            return errors.size() == 1 && errors.containsKey("clientCode");
        }
    }
}
