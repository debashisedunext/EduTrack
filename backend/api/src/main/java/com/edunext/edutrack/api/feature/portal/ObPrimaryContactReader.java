package com.edunext.edutrack.api.feature.portal;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * B-126 · the primary SPOC of an onboarding client, and the client's own name.
 *
 * <h2>The primary, and only the primary</h2>
 *
 * <p>The task line is "one-time credential mail to the primary SPOC", and
 * B-103's own note explains why there is no fallback to "any active contact":
 * "the kickoff mail, the one-time portal password and every sign-off request
 * address the primary SPOC, so with none they go to nobody and nothing says
 * so". A client with no primary is refused here, loudly, rather than quietly
 * mailed to whoever happens to sort first.
 *
 * <p>{@code is_primary_key} is the generated column
 * {@code uq_ob_client_contacts_primary} is built on — {@code 1} when the
 * contact is both primary and active, NULL otherwise. Reading it rather than
 * {@code is_primary = 1 AND is_active = 1} means this query and the unique
 * index agree by construction: a deactivated primary releases the slot, and
 * this finds nobody, which is the correct answer rather than a stale one.
 */
@Component
class ObPrimaryContactReader {

    /**
     * <p>The scope predicate is interpolated rather than parameterised because
     * it is SQL structure and not a value — {@link ObClientScope#predicate}
     * builds it from a closed set of role constants and contributes only the
     * named parameter {@code :scopeUserId} for the id. This is the same call
     * every other reader in the onboarding module makes with it.
     *
     * <p>Scoping is applied here rather than left to the controller because it
     * is the read that decides whether this client exists <em>for this
     * caller</em>. A caller outside scope gets the same empty result as a caller
     * naming an id that was never issued, which is the 404-not-403 rule this
     * repository applies everywhere.
     */
    private static final String PRIMARY_OF = """
            SELECT c.name         AS client_name,
                   ct.id          AS contact_id,
                   ct.name        AS contact_name,
                   ct.email       AS contact_email
              FROM ob_clients          c
              LEFT JOIN ob_client_contacts ct
                     ON ct.ob_client_id = c.id
                    AND ct.is_primary_key = 1
             WHERE c.id = :obClientId
               AND %s
            """;

    private final JdbcClient jdbc;

    ObPrimaryContactReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * <p>{@code LEFT JOIN} so that "no such client" and "client with no active
     * primary" are different answers. An {@code INNER JOIN} would collapse them
     * into one empty result, and the two need different refusals: 404 for the
     * first, and for the second a 422 telling the operator to appoint a primary
     * — which is a thing they can actually go and do.
     */
    Optional<ClientAndPrimary> find(ObClientScope scope, long obClientId) {
        if (scope.deniesEverything()) {
            // Deny by default, without a query. A caller with no onboarding
            // grant has no client list at all, and building a predicate for
            // them would be asking the database a question with one answer.
            return Optional.empty();
        }
        return jdbc.sql(PRIMARY_OF.formatted(scope.predicate("c")))
                .param("obClientId", obClientId)
                .param(ObClientScope.USER_PARAM, scope.userId())
                .query((rs, row) -> {
                    long contactId = rs.getLong("contact_id");
                    return new ClientAndPrimary(
                            rs.getString("client_name"),
                            rs.wasNull() ? null : contactId,
                            rs.getString("contact_name"),
                            rs.getString("contact_email"));
                })
                .optional();
    }

    /** @param contactId null when the client has no active primary SPOC. */
    record ClientAndPrimary(String clientName, Long contactId,
                            String contactName, String contactEmail) {

        boolean hasPrimary() {
            return contactId != null;
        }
    }
}
