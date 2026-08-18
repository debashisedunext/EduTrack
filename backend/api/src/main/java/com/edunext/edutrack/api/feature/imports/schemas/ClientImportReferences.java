package com.edunext.edutrack.api.feature.imports.schemas;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * B-037 · which of these clients a reversal must keep, and why.
 *
 * <p>{@code ClientImportSchema.reverse} deletes the clients one import run
 * created. A client that has since been named on a ticket cannot be deleted —
 * {@code fk_tickets_client} and {@code fk_tickets_client_contact} are both
 * RESTRICT — and should not be: the ticket is independent work that happened
 * after the import, and the two ways to get the delete to succeed are destroying
 * it or destroying its client.
 *
 * <h2>Why this is asked rather than discovered</h2>
 *
 * <p>The registration also catches the integrity violation, because a pre-check
 * cannot see a foreign key some future stream adds to {@code clients} — and it
 * must not be the <em>only</em> mechanism, because a caught constraint violation
 * can say nothing useful. "Something still references this client" is not a
 * sentence anybody can act on; "named on 3 tickets" is, and it is the reason in
 * all but a vanishing minority of cases.
 *
 * <p>So the two are layered on purpose: this answers the case that actually
 * happens, in one query for the whole batch, and the catch is the backstop that
 * keeps a surprise reference costing one retained row rather than the set.
 *
 * <h2>Reading {@code tickets} from Stream B code</h2>
 *
 * <p>Read-only and by foreign key, which is the precedent
 * {@code ClientQueryRepository.openTicketCounts} set for S-32's Open Tickets
 * column: the client master's screens cannot describe a client without counting
 * the work raised against it. Nothing here writes, and no ticket is loaded — the
 * query returns client ids and a count.
 *
 * <p><b>Every ticket, not only the open ones.</b> S-32's column counts
 * {@code statuses.is_terminal = 0} because a closed ticket is not a reason to
 * warn an Admin off deactivating a client. It is absolutely a reason not to
 * <em>delete</em> one: a closed ticket is history, its client is part of that
 * history, and the foreign key does not care about the status either.
 */
@Repository
class ClientImportReferences {

    private final JdbcClient jdbc;

    ClientImportReferences(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * How many tickets name each of these clients, directly or through one of
     * their contacts.
     *
     * <p>One query for the whole batch, keyed on {@code ix_tickets_client} and
     * {@code ix_tickets_client_contact}. The alternative — asked per client — is
     * 5,000 round trips on the reversal of a full-size import, which is the same
     * trap {@code ImportSchemaDefinition.findExisting} exists to avoid on the way
     * in.
     *
     * <p>The union covers both columns because both are RESTRICT: a ticket that
     * names a contact of this client pins the client too, through
     * {@code client_contacts}, which the delete has to remove first.
     *
     * @param clientIds the ids one import run created
     * @return client id → ticket count, for the subset with at least one.
     *         Clients with none are absent rather than present as zero — the
     *         caller is asking "which must I keep", and an empty map is the
     *         ordinary answer for a batch nobody has used yet
     */
    Map<Long, Long> ticketCounts(Collection<Long> clientIds) {
        if (clientIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        jdbc.sql("""
                        SELECT client_id, COUNT(*) AS ticket_count
                        FROM (
                            SELECT t.id, t.client_id
                            FROM tickets t
                            WHERE t.client_id IN (:ids)
                            UNION
                            SELECT t.id, c.client_id
                            FROM tickets t
                            JOIN client_contacts c ON c.id = t.client_contact_id
                            WHERE c.client_id IN (:ids)
                        ) referencing
                        GROUP BY client_id
                        """)
                .param("ids", clientIds)
                .query((rs, n) -> counts.put(rs.getLong("client_id"), rs.getLong("ticket_count")))
                .list();
        return counts;
    }

    /**
     * <b>One client and its contacts, removed together, in one transaction.</b>
     *
     * <p>The transaction boundary is here rather than on the registration for a
     * mundane reason with a real consequence: {@code @Transactional} is applied
     * by a Spring proxy, and a registration calling its own annotated method
     * through {@code this} would bypass it — running with no transaction at all,
     * so the two statements below would commit separately and a client whose
     * contacts were removed and whose own delete then failed would have silently
     * lost data with nothing recording it.
     *
     * <p>The alternative was to inject the registration into itself with
     * {@code @Lazy}. That works and it is worse: it turns the one component the
     * SPI is documented as a short, ordinary file into a three-argument
     * constructor whose third argument is itself.
     *
     * <p><b>One client per call, by design.</b> The reversal walks its set
     * calling this once per row, so a client that cannot be removed costs that
     * client and not the set — the same shape {@code ImportCommitRunner} uses on
     * the way in, and for the same reason.
     *
     * <p>{@code client_projects} needs no statement: A-006 gave it
     * {@code ON DELETE CASCADE} with the note that it is "safe here and only
     * here", because it holds nothing but the association.
     */
    @Transactional
    void deleteClientAndContacts(long clientId) {
        deleteContacts(clientId);
        jdbc.sql("DELETE FROM clients WHERE id = :clientId")
                .param("clientId", clientId)
                .update();
    }

    /**
     * Removes one client's contacts, so the delete of the client can proceed.
     *
     * <p>{@code fk_client_contacts_client} is RESTRICT — deliberately, and
     * unlike {@code client_projects}, which A-006 gave ON DELETE CASCADE with the
     * note that it is "safe here and only here" because it holds no fact of its
     * own. A contact does hold facts, so nothing cascades and a caller that wants
     * the client gone has to say what happens to them.
     *
     * <p><b>A reversal says they go with it, and that is the line this feature
     * draws.</b> A contact is wholly owned by its client and means nothing
     * without one; a ticket is independent work that merely names a client.
     * Deleting the contacts of a client that is being removed destroys nothing
     * that outlives it — which is precisely why there is no equivalent method
     * for tickets, and why a ticket retains the client instead.
     *
     * <p>Called only from {@link #deleteClientAndContacts}, inside its
     * transaction, and only for a client {@link #ticketCounts} has already
     * cleared.
     */
    private void deleteContacts(long clientId) {
        jdbc.sql("DELETE FROM client_contacts WHERE client_id = :clientId")
                .param("clientId", clientId)
                .update();
    }
}
