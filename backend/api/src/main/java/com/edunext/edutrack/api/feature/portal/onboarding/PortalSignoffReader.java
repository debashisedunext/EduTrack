package com.edunext.edutrack.api.feature.portal.onboarding;

import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * C-122 · CP-05 — every sign-off this client's onboarding has ever raised,
 * pending and past alike.
 *
 * <h2>Its own query, on {@code ObSignoffPageReader}'s precedent</h2>
 *
 * <p>{@code ObSignoffPageReader} (A-121) already reads {@code ob_signoffs}
 * for a public, per-row projection and gives the reason for not reusing a
 * staff repository one package over: "a public projection that names its
 * own columns cannot widen by accident". The same argument applies here one
 * principal over — this is a portal list rather than the public OB-09 page,
 * but the caller is still an external account rather than a colleague, and a
 * shared reader free to grow a column for a staff screen would grow it here
 * too, without anybody deciding. So this is a second, narrower query against
 * the same table rather than a call into that package (which this stream may
 * not edit — {@code feature/onboarding/signoff/} is Stream A/B's) or into
 * {@code ObSignoffRepository} (the domain module).
 *
 * <h2>What is never selected</h2>
 *
 * <p>{@code token_hash}, {@code otp_hash}, {@code otp_attempts},
 * {@code requested_by}, {@code signed_ip} and {@code signed_user_agent} all
 * live on the row and none is read here. The first two are secrets the
 * database itself must never be able to turn back into a working link
 * ({@code ObSignoffTokens}'s own reasoning); the rest are staff-attribution
 * and forensic detail that mean nothing to the client who signed and would
 * be exactly the kind of field a shared serializer leaks by accident.
 */
@Component
class PortalSignoffReader {

    /**
     * One client's sign-offs, newest request first.
     *
     * <p>{@code LEFT JOIN} throughout, on {@code ObSignoffPageReader}'s own
     * choice: a sign-off is worth listing even if the journey or product
     * behind it has since gone, and an {@code INNER JOIN} would silently drop
     * the row instead of showing "no name yet" against it.
     */
    private static final String SIGNOFFS = """
            SELECT sg.id, sg.kind, sg.status,
                   sg.requested_at, sg.token_expires_at,
                   sg.signed_at, sg.signed_name, sg.acceptance_note,
                   sg.objected_at, sg.objection_note,
                   sg.pdf_storage_key,
                   p.name AS product_name,
                   s.name AS step_name,
                   c.email AS sent_to_email
              FROM ob_signoffs sg
              LEFT JOIN ob_journeys j ON j.id = sg.journey_id
              LEFT JOIN ob_products p ON p.id = j.product_id
              LEFT JOIN ob_journey_steps s ON s.id = sg.step_id
              LEFT JOIN ob_client_contacts c ON c.id = sg.sent_to_contact_id
             WHERE sg.ob_client_id = ?
             ORDER BY sg.requested_at DESC, sg.id DESC
            """;

    private final JdbcClient jdbc;

    PortalSignoffReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every sign-off this {@code ob_client_id} has ever had raised, pending and past together — CP-05 sorts them client-side. */
    List<PortalOnboardingDtos.PortalSignoff> listFor(long obClientId) {
        return jdbc.sql(SIGNOFFS)
                .param(obClientId)
                .query((ResultSet rs, int row) -> new PortalOnboardingDtos.PortalSignoff(
                        rs.getLong("id"),
                        ObSignoffKind.valueOf(rs.getString("kind")),
                        ObSignoffStatus.valueOf(rs.getString("status")),
                        rs.getString("product_name"),
                        rs.getString("step_name"),
                        instant(rs, "requested_at"),
                        instant(rs, "token_expires_at"),
                        rs.getString("sent_to_email"),
                        instant(rs, "signed_at"),
                        rs.getString("signed_name"),
                        rs.getString("acceptance_note"),
                        instant(rs, "objected_at"),
                        rs.getString("objection_note"),
                        rs.getString("pdf_storage_key") != null))
                .list();
    }

    private static Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
