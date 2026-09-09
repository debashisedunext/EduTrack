package com.edunext.edutrack.api.feature.onboarding.signoff;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * B-115 · the one contact this surface may name — the SPOC the link was sent
 * to, read back for the acceptance response.
 *
 * <h2>Its own query, on {@link ObSignoffPageReader}'s reasoning</h2>
 *
 * <p>A-121 made the argument one class over and it applies unchanged: a public
 * projection that names its own columns cannot widen by accident. The obvious
 * alternative is {@code ObClientReadRepository.contactsOf}, which serves OB-05
 * and is free to grow a field the day staff need one — and would grow it
 * <em>here</em> too, on an unauthenticated response, without anybody deciding.
 *
 * <p>The consent columns are the concrete case rather than a hypothetical.
 * {@code ob_client_contacts} carries {@code whatsapp_opt_in} and, since B-103,
 * its timestamp and source. Those are facts <b>about</b> a contact recorded by
 * our staff, and the contract puts them on {@code ObContact} for OB-05's panel.
 * They are not selected below, so no mapper can put them on the wire.
 *
 * <h2>Never by client, always by id</h2>
 *
 * <p>There is no "list the contacts of this client" method here and there
 * should not be one. The caller is a customer's SPOC holding a session minted
 * for one sign-off; the only contact they are entitled to see is the one on
 * that row, which the caller already has the id of. A method taking a client id
 * would be one careless call away from returning a colleague's mobile number to
 * whoever holds one link.
 */
@Component
class ObSignoffContactReader {

    private static final String BY_ID = """
            SELECT id,
                   name,
                   designation,
                   email,
                   phone,
                   is_primary,
                   is_active
              FROM ob_client_contacts
             WHERE id = ?
            """;

    private final JdbcClient jdbc;

    ObSignoffContactReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The contact behind {@code ob_signoffs.sent_to_contact_id}.
     *
     * <p>Null rather than an exception when the row is gone. The column is
     * {@code NOT NULL} with a foreign key so this is not reachable today, but
     * the alternative failure mode is the one to avoid: a 500 on a route that
     * has already committed an acceptance, which would tell the client their
     * signature failed when it did not.
     */
    PublicSignoffAcceptDtos.Contact find(Long contactId) {
        if (contactId == null) {
            return null;
        }
        return jdbc.sql(BY_ID)
                .param(contactId)
                .query((rs, row) -> new PublicSignoffAcceptDtos.Contact(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("designation"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getBoolean("is_primary"),
                        rs.getBoolean("is_active")))
                .optional()
                .orElse(null);
    }
}
