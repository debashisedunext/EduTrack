package com.edunext.edutrack.api.feature.portal.onboarding;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * C-121 · two small unscoped reads against {@code ob_clients}/{@code
 * ob_client_contacts} for one caller's own, already-verified {@code
 * obClientId}.
 *
 * <h2>The contact-attribution ambiguity, and how it is resolved</h2>
 *
 * <p>{@code ob_prereq_comments.author_contact_id} and {@code
 * ob_prereq_history.actor_contact_id} are foreign keys to {@code
 * ob_client_contacts.id} — a live SPOC row, not a name string. {@code
 * client_accounts} carries no such link: its {@code display_name}/{@code
 * email} are a denormalised snapshot of whoever was primary <em>at account
 * creation</em>, precisely so the login survives that contact being replaced
 * (its own migration's comment). So there is no column anywhere saying which
 * living contact this login now speaks for.
 *
 * <p>{@link #activePrimaryContactId} reads the client's <b>current</b> active
 * primary contact and that is used as the acting SPOC for every write the
 * portal makes. It is the closest living link the schema offers. A client
 * with no active primary at write time is refused (see {@link
 * PortalNoPrimaryContactException}) rather than silently attributed to
 * nobody, because the column is {@code NOT NULL} for a CLIENT actor.
 *
 * <h2>Unscoped, and that is safe here specifically</h2>
 *
 * <p>Unlike the staff-facing {@code ObPrimaryContactReader}, there is no
 * caller-supplied id to scope against: {@code obClientId} always comes from
 * the verified {@code ClientPrincipal} on the caller's own token, never from
 * a path or query parameter. A row-scope predicate exists to stop a caller
 * widening a request past what their token proves; a value already read
 * from the token has nothing left to widen.
 */
@Repository
class PortalPrimaryContactReader {

    private final JdbcClient jdbc;

    PortalPrimaryContactReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Long> activePrimaryContactId(long obClientId) {
        return jdbc.sql("""
                        SELECT id FROM ob_client_contacts
                         WHERE ob_client_id = :obClientId AND is_primary_key = 1
                        """)
                .param("obClientId", obClientId)
                .query(Long.class)
                .optional();
    }

    /** The client's own name, for CP-03's home header. Empty if the id resolves to nobody. */
    Optional<String> clientNameOf(long obClientId) {
        return jdbc.sql("SELECT name FROM ob_clients WHERE id = :id")
                .param("id", obClientId)
                .query(String.class)
                .optional();
    }
}
