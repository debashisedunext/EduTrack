package com.edunext.edutrack.api.feature.clients;

import com.edunext.edutrack.domain.clients.ClientRepository;
import com.edunext.edutrack.domain.validation.EmailFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * B-027 · S-33's Contacts tab — the {@code client_contacts} child grid.
 *
 * <h2>What this task closes</h2>
 *
 * <p>B-026 shipped the tab read-only and said why: the contacts themselves are
 * this task, and {@code POST /clients/{clientId}/contacts} was the <b>seventh</b>
 * "declared, mocked, never mounted" operation this stream has found — after
 * B-023's nine calendar operations, B-014's {@code PATCH /users/{userId}/status},
 * B-018's two SLA operations, B-020's {@code listTaskTypes}, B-021's
 * {@code listPriorities} and B-025's six client operations. The other two verbs
 * were not merely unmounted, they were <b>undeclared</b>: without a {@code PATCH}
 * an edit is remove-and-re-add, which deactivates the row a historical ticket
 * points at and issues a new id — a corrected phone number rendered as a
 * departure and an arrival.
 *
 * <h2>The primary flag is single-writer</h2>
 *
 * <p>"At most one primary" is not expressible in the schema — MySQL has no
 * partial unique index, and a {@code UNIQUE} on {@code (client_id, is_primary)}
 * would forbid a second <em>non</em>-primary contact. {@code ClientContact}'s
 * javadoc has named the service as the enforcer since B-005 and this is it:
 * promoting demotes every other row in the same transaction, which is the shape
 * B-021 gave {@code is_escalation_trigger} on the priority master.
 *
 * <p><b>Demoting or removing the last primary is allowed.</b> B-021 refused the
 * mirror case — clearing the last escalation trigger — and the two are genuinely
 * different. A level with no escalation target silently switches off one of
 * blueprint §1's headline behaviours and nothing on any screen looks wrong. A
 * client with no primary contact is a state <em>every</em> client is created in,
 * B-028's gate reports it on the ticket create path where a caller can act on it,
 * and the person may simply have left. Refusing the demotion while the
 * {@code DELETE} beside it can produce the same state anyway would be one rule
 * with two answers — and the {@code DELETE} cannot be refused, because a contact
 * who cannot be removed until somebody else is promoted reads as a broken button.
 *
 * <h2>Validation</h2>
 *
 * <p>Bean Validation on {@link ClientDtos.ContactWriteRequest} covers lengths and
 * requiredness. <b>B-028 moved the email <em>format</em> here</b>, onto
 * {@link EmailFormat} — {@code @Email} accepted {@code bob@acme}, which
 * {@code notificationOptIn} defaulting to true turns into a subscription that
 * can only ever bounce, and which B-030's importer refused on the same column.
 * The other rule here is the one that needs a query: <b>an email address is
 * unique among a client's live contacts</b>, case-insensitively, and
 * <b>only</b> within the client. Two rows with one address under one client mean
 * D-036 mails the same person twice and C-021's reporter dropdown offers two
 * identical options; the same address under two different clients is a
 * consultant retained by both, which {@code ix_client_contacts_email} is
 * deliberately non-unique to permit and which D-039 resolves by
 * {@code website_domain}.
 */
@Service
public class ClientContactService {

    private final ClientRepository clients;
    private final ClientQueryRepository query;
    private final ClientContactWriteRepository write;

    ClientContactService(ClientRepository clients,
                         ClientQueryRepository query,
                         ClientContactWriteRepository write) {
        this.clients = clients;
        this.query = query;
        this.write = write;
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    /**
     * One client's contacts.
     *
     * <p>404 for a client that is not there rather than an empty list: an empty
     * grid and a mistyped id look identical, and only one of them is worth
     * reporting. B-025's call, unchanged.
     *
     * @param includeInactive the B-027 grid sends true; every picker leaves it
     *                        false, so a removed contact stops being offered on
     *                        the ticket form without becoming unrenderable on the
     *                        tickets they already reported
     */
    @Transactional(readOnly = true)
    public Optional<List<ClientDtos.Contact>> list(long clientId, boolean includeInactive) {
        if (!clients.existsById(clientId)) {
            return Optional.empty();
        }
        return Optional.of(query.contactsOf(clientId, includeInactive));
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Adds a contact.
     *
     * <p><b>Insert first, demote second</b>, and the ordering is deliberate.
     * Demoting before the insert leaves a window — inside one transaction, so
     * invisible to other sessions, but not to a failure — in which the client has
     * no primary at all; if the insert then violates a constraint the rollback
     * saves it, but only because the rollback is doing work the ordering could
     * have avoided. Inserting first also means {@code exceptId} is a real id
     * rather than a sentinel that has to mean "nothing".
     *
     * @return empty for a client that is not there, which the controller turns
     *         into 404 — never 403, per CLAUDE.md
     */
    @Transactional
    public Optional<ClientDtos.Contact> add(long clientId,
                                            ClientDtos.ContactWriteRequest request) {

        if (!clients.existsById(clientId)) {
            return Optional.empty();
        }
        validate(clientId, request, null);

        long contactId = write.insert(clientId, request);
        if (request.primaryOrDefault()) {
            write.demoteOtherPrimaries(clientId, contactId);
        }
        return query.contactOf(clientId, contactId);
    }

    /**
     * Rewrites a contact from the whole representation.
     *
     * <p>Returns empty both for an unknown client and for a contact id that
     * belongs to a <em>different</em> client. Both are 404: the path names a
     * contact under this client, and if there is no such thing the resource does
     * not exist. Not a scope-guard question — clients are not row-scoped — but
     * the same answer for a different reason.
     *
     * <p><b>An inactive contact is editable.</b> Correcting the spelling of a
     * departed contact's name so a historical ticket reads properly is a real
     * thing to want, and the edit cannot bring them back: {@code is_active} is
     * not in {@code update}'s statement, which is pinned by its own test for the
     * reason B-017 had to pin {@code project_members}' two writers apart.
     */
    @Transactional
    public Optional<ClientDtos.Contact> edit(long clientId,
                                             long contactId,
                                             ClientDtos.ContactWriteRequest request) {

        Optional<ClientDtos.Contact> existing = query.contactOf(clientId, contactId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        validate(clientId, request, contactId);

        write.update(clientId, contactId, request);
        if (request.primaryOrDefault()) {
            write.demoteOtherPrimaries(clientId, contactId);
        }
        return query.contactOf(clientId, contactId);
    }

    /**
     * Removes a contact — deactivates it. See
     * {@link ClientContactWriteRepository#deactivate}.
     *
     * @return false only when there is no such contact <em>under this client</em>,
     *         which is 404. Removing one that was already removed is
     *         <b>true</b> — a setter, and the second half of a double-click must
     *         not be an error about something that did happen (B-014's
     *         {@code UNCHANGED} argument, and B-017's on removing a non-member)
     */
    @Transactional
    public boolean remove(long clientId, long contactId) {
        if (query.contactOf(clientId, contactId).isEmpty()) {
            return false;
        }
        write.deactivate(clientId, contactId);
        return true;
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    /**
     * Field-keyed, and collected rather than thrown at the first failure — the
     * shape {@code ClientWriteService} established for this feature, so the row
     * editor marks every bad input at once.
     *
     * <p>Only one rule needs a query today. It is written as a map anyway because
     * the alternative is a bare throw that the next rule has to refactor, and
     * because {@link ClientExceptionHandler} already renders this shape.
     */
    private void validate(long clientId,
                          ClientDtos.ContactWriteRequest request,
                          Long exceptId) {

        Map<String, String> errors = new LinkedHashMap<>();
        boolean duplicate = false;

        String email = request.email() == null ? null : request.email().trim();
        if (email != null && !email.isEmpty()) {
            // B-028 · shape before uniqueness, and only one of the two is
            // reported. Asking `findConflictingEmail` about a string that is not
            // an address spends a query to learn nothing, and a response naming
            // both failures would tell an administrator to fix a duplicate of
            // something that was never valid.
            if (!EmailFormat.isValid(email)) {
                errors.put("email", EmailFormat.message("email"));
            } else {
                Optional<String> holder = write.findConflictingEmail(clientId, email, exceptId);
                // The message names who holds it. "That email is already in use"
                // on a client with nine contacts is a search; naming the row is
                // the fix.
                if (holder.isPresent()) {
                    errors.put("email", holder.get() + " already uses "
                            + email.toLowerCase(Locale.ROOT) + " at this client.");
                    duplicate = true;
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new ContactValidationException(errors, duplicate);
        }
    }

    // ------------------------------------------------------------------
    // Failures
    // ------------------------------------------------------------------

    /**
     * Every field that failed, in one document — rendered by
     * {@link ClientExceptionHandler}.
     *
     * <p>A <b>separate</b> exception from {@code ClientWriteService
     * .ClientValidationException} rather than a reuse, because the two carry
     * different status rules: that one is a 409 when a duplicate client code is
     * the only failure and a 400 otherwise, and this one is a 409 whenever a
     * duplicate email is involved. Sharing the type would mean one handler
     * deciding between two rules by inspecting which keys are present, which is
     * how the wrong status reaches a client that branches on it.
     */
    static final class ContactValidationException extends RuntimeException {

        private final transient Map<String, String> errors;

        /**
         * B-028 · stated by the thrower, <b>not inferred from the key</b>.
         *
         * <p>It used to be {@code errors.containsKey("email")}, which was exact
         * while a duplicate was the only thing that could fail on that field.
         * B-028 adds a second — a malformed address — and it lands on the same
         * key, so the inference would have rendered
         * {@code "bob@acme" is not a well-formed email address} as a <b>409
         * Conflict</b>. CONVENTIONS.md §3 says a client branches on the status,
         * so the editor would have shown a duplicate-contact message about an
         * address no other contact holds. The same shape as
         * {@code ClientValidationException.isDuplicateCodeOnly}, which describes
         * a status rule by looking at the map — and gets away with it only
         * because {@code clientCode} still has exactly one way to fail.
         */
        private final boolean duplicate;

        ContactValidationException(Map<String, String> errors, boolean duplicate) {
            super(String.join(" ", errors.values()));
            this.errors = Map.copyOf(errors);
            this.duplicate = duplicate;
        }

        Map<String, String> errors() {
            return errors;
        }

        /** A duplicate address is a uniqueness conflict — 409, like a client code. */
        boolean isDuplicate() {
            return duplicate;
        }
    }
}
