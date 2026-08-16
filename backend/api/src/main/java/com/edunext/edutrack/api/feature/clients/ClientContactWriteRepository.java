package com.edunext.edutrack.api.feature.clients;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * B-027 · the {@code client_contacts} writes behind S-33's child grid.
 *
 * <h2>Why this is not the JPA repository</h2>
 *
 * <p>{@code domain.clients.ClientContactRepository} exists and B-005 mapped the
 * entity, and this deliberately does not go through either. Two of the four
 * statements here are set operations — demote every other primary, deactivate one
 * row — and running them through JPA means loading a collection of entities to
 * write one column on each, inside a transaction that then has to be flushed in
 * the right order relative to the read that assembles the response. The read side
 * of this feature is already {@link JdbcClient} for the same reason
 * ({@code ClientQueryRepository}), and mixing the two is what forces the
 * {@code saveAndFlush} comments B-025 and B-026 both had to write.
 *
 * <p><b>Named {@code ClientContactWriteRepository}, not
 * {@code ClientContactRepository}.</b> Spring derives a bean name from the simple
 * class name, so the short name would collide with the domain repository and take
 * out <em>every</em> {@code @SpringBootTest} in the module with a
 * {@code BeanDefinitionOverrideException} naming neither the feature nor the task
 * — which is exactly what B-016 hit with a second {@code ProjectRepository}.
 *
 * <h2>There is no delete</h2>
 *
 * <p>{@code tickets.client_contact_id} is a foreign key into this table
 * <b>without</b> a cascade. A real {@code DELETE} fails as a constraint violation
 * naming a MySQL index; "fixing" that with a cascade would silently rewrite who a
 * historical ticket says reported it. {@link #deactivate} is the removal, which is
 * the call B-017 made on {@code project_members}, B-018 on cleared SLA overrides
 * and B-020 on a retired task type.
 */
@Repository
class ClientContactWriteRepository {

    private final JdbcClient jdbc;

    ClientContactWriteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a contact and returns its id.
     *
     * <p>{@code is_active} is not named: the column defaults to 1 and this
     * statement has no business restating it. The column defaults are the
     * schema's statement of what a new row is, and repeating them here is how the
     * two versions start to differ.
     */
    long insert(long clientId, ClientDtos.ContactWriteRequest request) {
        jdbc.sql("""
                        INSERT INTO client_contacts
                            (client_id, name, designation, email, phone,
                             is_primary, receives_mail, portal_access)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(clientId)
                .param(request.name().trim())
                .param(trimToNull(request.designation()))
                .param(trimToNull(request.email()))
                .param(trimToNull(request.phone()))
                .param(request.primaryOrDefault())
                .param(request.notificationOptInOrDefault())
                .param(request.portalAccessOrDefault())
                .update();

        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    /**
     * Rewrites one contact from the whole representation.
     *
     * <p>Every editable column is named, because the body <em>is</em> the row —
     * an absent field is a cleared one, which is B-026's reading of
     * {@code ClientWriteRequest} and the reason both take a record.
     *
     * <p><b>{@code is_active} is not in this statement, and that is the guard
     * that keeps the two writers apart.</b> An edit must not be able to resurrect
     * a contact the grid removed — B-017 had to pin exactly this between
     * {@code project_members}' two writers with a named regression test, and the
     * same test exists here. Reactivation is not an S-33 gesture at all: a person
     * who returns to the client is added again.
     */
    int update(long clientId, long contactId, ClientDtos.ContactWriteRequest request) {
        return jdbc.sql("""
                        UPDATE client_contacts
                        SET name          = ?,
                            designation   = ?,
                            email         = ?,
                            phone         = ?,
                            is_primary    = ?,
                            receives_mail = ?,
                            portal_access = ?
                        WHERE client_id = ? AND id = ?
                        """)
                .param(request.name().trim())
                .param(trimToNull(request.designation()))
                .param(trimToNull(request.email()))
                .param(trimToNull(request.phone()))
                .param(request.primaryOrDefault())
                .param(request.notificationOptInOrDefault())
                .param(request.portalAccessOrDefault())
                .param(clientId)
                .param(contactId)
                .update();
    }

    /**
     * Clears {@code is_primary} on every other live contact of this client.
     *
     * <p><b>One statement, and it is what makes "at most one primary" true.</b>
     * The schema cannot assert it: MySQL has no partial unique index, so a
     * {@code UNIQUE} on {@code (client_id, is_primary)} would forbid a second
     * non-primary contact, which is absurd. {@code ClientContact}'s own javadoc
     * has said so since B-005 and named the service as the enforcer; this is that
     * enforcement.
     *
     * <p>{@code exceptId} is the row being promoted. Passing 0 for a create that
     * has not been inserted yet would work too, but the call site inserts first
     * and demotes second — see {@code ClientContactService.add} for why that
     * ordering is the safe one.
     *
     * <p>Inactive rows are demoted as well ({@code is_active} is not in the
     * predicate). A removed contact keeps its flag otherwise, and
     * {@code ?includeInactive=true} would render two rows starred — one of them
     * a person who has left.
     */
    int demoteOtherPrimaries(long clientId, long exceptId) {
        return jdbc.sql("""
                        UPDATE client_contacts
                        SET is_primary = 0
                        WHERE client_id = ? AND id <> ? AND is_primary = 1
                        """)
                .param(clientId)
                .param(exceptId)
                .update();
    }

    /**
     * Removal — {@code is_active = 0}, never a {@code DELETE}. See the class note.
     *
     * <p><b>{@code is_primary} is cleared in the same statement.</b> Leaving it
     * set would mean a client whose only starred contact is somebody who has
     * left, and — worse — {@code primaryContacts} filters on
     * {@code is_active = 1} while {@link #demoteOtherPrimaries} does not, so the
     * grid would show no primary while a promotion still had a row to demote.
     * One of those two readings has to win, and the useful one is that a removed
     * contact is not the primary.
     *
     * @return rows touched — 0 when the contact was already removed, which the
     *         service answers 204 for rather than 404 (B-014's {@code UNCHANGED}
     *         argument: the second half of a double-click is not an error)
     */
    int deactivate(long clientId, long contactId) {
        return jdbc.sql("""
                        UPDATE client_contacts
                        SET is_active = 0, is_primary = 0
                        WHERE client_id = ? AND id = ? AND is_active = 1
                        """)
                .param(clientId)
                .param(contactId)
                .update();
    }

    /**
     * Another live contact of this client already holding this email address.
     *
     * <p><b>No {@code UPPER()} on the column</b>, the call
     * {@code ClientWriteRepository.findConflictingCode} documents:
     * {@code email} collates {@code utf8mb4_0900_ai_ci}, so MySQL already matches
     * {@code Sara@acme.example} against {@code sara@acme.example}, and it does it
     * through {@code ix_client_contacts_email} rather than scanning, which
     * wrapping the column in a function would prevent.
     *
     * <p><b>Scoped to the client, and that scope is the rule.</b> The same address
     * under two different clients is legitimate — a consultant retained by both —
     * and {@code ix_client_contacts_email} is deliberately not unique so that
     * D-039 can resolve inbound mail by taking the set and disambiguating on
     * {@code website_domain}. A global uniqueness check here would break that.
     *
     * <p><b>Live rows only.</b> A contact who left and is being re-added is the
     * ordinary case, and refusing it would mean the address is burned forever by
     * a removal.
     *
     * <p>{@code exceptId} is what stops every ordinary edit reporting the
     * contact's own address as taken — the {@code u.id <> ?} B-013 had to
     * document on the resource form, in the same shape, because this body is the
     * whole representation and carries the email on every save.
     */
    Optional<String> findConflictingEmail(long clientId, String email, Long exceptId) {
        return jdbc.sql("""
                        SELECT cc.name
                        FROM client_contacts cc
                        WHERE cc.client_id = ?
                          AND cc.email = ?
                          AND cc.is_active = 1
                          AND (? IS NULL OR cc.id <> ?)
                        LIMIT 1
                        """)
                .param(clientId)
                .param(email)
                .param(exceptId)
                .param(exceptId)
                .query(String.class)
                .optional();
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
