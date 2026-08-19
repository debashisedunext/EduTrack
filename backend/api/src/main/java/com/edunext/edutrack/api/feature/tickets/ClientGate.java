package com.edunext.edutrack.api.feature.tickets;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * B-028 and B-029, enforced where they can only be enforced.
 *
 * <p>Blueprint line 948: a client is not selectable on a ticket until it has at
 * least one primary contact. {@code Client.hasPrimaryContact} reports it on
 * every row {@code GET /clients} returns and the create form refuses the
 * selection on screen — but a form is advice, and this endpoint is the only
 * place the rule is a guarantee. B-029's mirror belongs at the same point: an
 * {@code INACTIVE} client blocks a <b>new</b> ticket and never hides an old one.
 *
 * <p><b>400, not 404</b>, and the contract is explicit about why. The row-scope
 * 404 elsewhere exists so an out-of-scope id leaks nothing; these two clients
 * are legitimately visible to the caller. What is refused is the combination,
 * and a caller who cannot tell "does not exist" from "not yet usable" cannot act
 * on either.
 */
@Component
class ClientGate {

    private final JdbcClient jdbc;

    ClientGate(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void requireSelectable(Long clientId) {
        if (clientId == null) {
            return;
        }

        String status = jdbc.sql("SELECT status FROM clients WHERE id = :id")
                .param("id", clientId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> ClientNotSelectableException.noSuchClient(clientId));

        if (!"ACTIVE".equals(status)) {
            throw ClientNotSelectableException.inactive(clientId, status);
        }

        Integer primaries = jdbc.sql("""
                SELECT COUNT(*) FROM client_contacts
                 WHERE client_id = :id AND is_primary = 1
                """)
                .param("id", clientId)
                .query(Integer.class)
                .single();

        if (primaries == 0) {
            throw ClientNotSelectableException.noPrimaryContact(clientId);
        }
    }
}
