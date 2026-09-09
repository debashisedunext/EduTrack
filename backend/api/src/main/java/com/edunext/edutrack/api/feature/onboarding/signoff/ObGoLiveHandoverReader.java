package com.edunext.edutrack.api.feature.onboarding.signoff;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * B-118 · what the support handover note prints, read once the client has
 * just gone live.
 *
 * <p>Its own reads rather than a call into {@code ObClientReadRepository} or
 * {@code ObClientGoLiveService} — {@code ObSignoffPageReader}'s own reasoning
 * one class over: this is a narrow projection for one document, and reusing a
 * staff-facing repository method would let that method's next field addition
 * change what a generated PDF prints without anybody deciding it should.
 */
@Component
class ObGoLiveHandoverReader {

    private final JdbcClient jdbc;

    ObGoLiveHandoverReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param contacts every active contact, primary first — {@code ObClientChildWriteRepository}'s own table. */
    record Data(String clientName, LocalDate onboardingDate, Instant liveAt,
                List<String> productNames, List<Contact> contacts) {
    }

    record Contact(String name, String designation, String email, String phone, boolean primary) {
    }

    Data read(long obClientId) {
        Header header = jdbc.sql("""
                SELECT name, onboarding_date, live_at FROM ob_clients WHERE id = :id
                """)
                .param("id", obClientId)
                .query((rs, row) -> new Header(
                        rs.getString("name"),
                        rs.getObject("onboarding_date", LocalDate.class),
                        rs.getTimestamp("live_at") == null ? null : rs.getTimestamp("live_at").toInstant()))
                .optional()
                .orElseGet(() -> new Header(null, null, null));

        List<String> productNames = jdbc.sql("""
                SELECT p.name FROM ob_journeys j
                  JOIN ob_products p ON p.id = j.product_id
                 WHERE j.ob_client_id = :id AND j.archived_at IS NULL
                 ORDER BY p.name
                """).param("id", obClientId).query(String.class).list();

        List<Contact> contacts = jdbc.sql("""
                SELECT name, designation, email, phone, is_primary_key IS NOT NULL AS is_primary
                  FROM ob_client_contacts
                 WHERE ob_client_id = :id AND is_active = 1
                 ORDER BY is_primary_key IS NULL, name
                """).param("id", obClientId)
                .query((rs, row) -> new Contact(
                        rs.getString("name"),
                        rs.getString("designation"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getBoolean("is_primary")))
                .list();

        return new Data(header.clientName(), header.onboardingDate(), header.liveAt(), productNames, contacts);
    }

    private record Header(String clientName, LocalDate onboardingDate, Instant liveAt) {
    }
}
