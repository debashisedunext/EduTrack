package com.edunext.edutrack.api.feature.onboarding.clients;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * B-103 · the writes behind OB-05's SPOC panel, plus the consent journal.
 *
 * <h2>SQL, and no {@code @Entity} — B-102 left the decision here and this is it</h2>
 *
 * <p>{@code ObClientChildWriteRepository} declined to map {@code
 * ob_client_contacts} because "each of these tables is the subject of a later
 * task that owns its behaviour … if B-102 declared entities for them, each of
 * those tasks would arrive to find a mapping written by somebody solving a
 * different problem". B-103 is that task for the SPOCs, so the choice is now a
 * real one rather than a deferral, and it comes out the same way for two
 * reasons that only became visible with the behaviour in hand.
 *
 * <ul>
 *   <li><b>The read side is already SQL.</b> {@code ObClientReadRepository}
 *       projects contacts through {@code CONTACT_COLUMNS} into {@code
 *       ContactRow} — it has to, because OB-03 needs one statement over a page
 *       of clients rather than a lazy collection per row. An entity here would
 *       make two mappings of one table, which is precisely the outcome B-102's
 *       note was avoiding, arrived at from the other direction.</li>
 *   <li><b>{@link #demoteOtherPrimaries} is a set-based write.</b> Under JPA it
 *       is a select of every sibling, a dirty-check and N updates — or a
 *       {@code @Modifying} query, which is this statement with an annotation on
 *       it. The promotion has to beat {@code uq_ob_client_contacts_primary} to
 *       the row, and one {@code UPDATE … WHERE is_primary_key = 1} is the
 *       shortest window there is.</li>
 * </ul>
 *
 * <h2>The consent journal is insert-only and has no other method here</h2>
 *
 * <p>{@link #recordConsent} is the only thing this class does to {@code
 * ob_contact_consent_events}, and V20260907_1130's two triggers refuse an
 * {@code UPDATE} or a {@code DELETE} independently of anything in Java — the
 * shape A-105 and A-106 set for the module's other append-only tables. This is
 * not one of CLAUDE.md's three protected tables and is not hash-chained, so it
 * carries the weaker of the two guarantees the codebase offers; {@code
 * AppendOnlyRulesTest} guards the stronger one and does not apply here.
 *
 * <p><b>Owed to Stream A, named rather than done quietly:</b> {@code
 * apply-app-grants.sql} sweeps every table and would hand this one the default
 * {@code SELECT, INSERT, UPDATE, DELETE}. It wants the {@code SELECT, INSERT}
 * branch that {@code ob_step_communications} and {@code ob_step_clock_events}
 * already have. That file is Stream A's; the triggers hold the guarantee in the
 * meantime, and the privilege is the belt to their braces rather than the only
 * thing standing there.
 */
@Repository
class ObContactWriteRepository {

    private final JdbcClient jdbc;

    ObContactWriteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Add one SPOC and hand back its id.
     *
     * <p>The consent triple is written as one unit or not at all — the CHECK
     * constraint {@code ck_ob_client_contacts_consent} refuses a stamp beside a
     * {@code false} and a {@code true} beside a NULL stamp, so a caller that
     * got the pairing wrong is refused by the database and not only by the
     * service.
     */
    long insert(long obClientId, ObContactDtos.ObContactUpsertRequest contact,
                Consent consent, boolean active) {

        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO ob_client_contacts
                    (ob_client_id, name, designation, email, phone,
                     whatsapp_opt_in, whatsapp_opt_in_at, whatsapp_opt_in_source,
                     whatsapp_opt_in_by, is_primary, is_active)
                VALUES (:clientId, :name, :designation, :email, :phone,
                        :optIn, :optInAt, :optInSource, :optInBy, :primary, :active)
                """)
                .param("clientId", obClientId)
                .param("name", contact.name().trim())
                .param("designation", trimmedOrNull(contact.designation()))
                .param("email", contact.email().trim())
                .param("phone", trimmedOrNull(contact.phone()))
                .param("optIn", consent.optedIn())
                .param("optInAt", consent.atTimestamp())
                .param("optInSource", consent.sourceName())
                .param("optInBy", consent.by())
                .param("primary", contact.primary())
                .param("active", active)
                .update(keys);

        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException(
                    "ob_client_contacts insert returned no generated key for client " + obClientId);
        }
        return id.longValue();
    }

    /**
     * The whole representation, rewritten.
     *
     * <p>Every column moves on every save because {@code ObContactUpsertRequest}
     * is not a sparse patch — an absent designation is a cleared designation.
     * The consent triple is the one part that does <b>not</b> simply follow the
     * request: {@code consent} carries either the caller's new position or the
     * one already on the row, decided by {@code ObContactService} so that an
     * unrelated edit cannot re-date a consent that has not changed.
     */
    void update(long contactId, ObContactDtos.ObContactUpsertRequest contact,
                Consent consent, boolean active) {

        jdbc.sql("""
                UPDATE ob_client_contacts
                   SET name = :name,
                       designation = :designation,
                       email = :email,
                       phone = :phone,
                       whatsapp_opt_in = :optIn,
                       whatsapp_opt_in_at = :optInAt,
                       whatsapp_opt_in_source = :optInSource,
                       whatsapp_opt_in_by = :optInBy,
                       is_primary = :primary,
                       is_active = :active
                 WHERE id = :id
                """)
                .param("id", contactId)
                .param("name", contact.name().trim())
                .param("designation", trimmedOrNull(contact.designation()))
                .param("email", contact.email().trim())
                .param("phone", trimmedOrNull(contact.phone()))
                .param("optIn", consent.optedIn())
                .param("optInAt", consent.atTimestamp())
                .param("optInSource", consent.sourceName())
                .param("optInBy", consent.by())
                .param("primary", contact.primary())
                .param("active", active)
                .update();
    }

    /** {@code removeObClientContact}, and the {@code isActive: false} half of the PATCH. */
    void setActive(long contactId, boolean active) {
        jdbc.sql("UPDATE ob_client_contacts SET is_active = :active WHERE id = :id")
                .param("id", contactId)
                .param("active", active)
                .update();
    }

    /**
     * Clear the primary slot before something else claims it.
     *
     * <p>Matched on {@code is_primary_key}, the generated column {@code
     * uq_ob_client_contacts_primary} is on, rather than on {@code is_primary =
     * 1}: they differ for a deactivated contact whose stored flag is still 1,
     * and the row that has to be moved out of the way is precisely the one the
     * index would collide with. Reading the enforced fact rather than a second
     * one that could drift from it — {@code primaryContactsOf}'s own note.
     *
     * <p>Must run <b>before</b> the insert or update that promotes, in the same
     * transaction. Between the two statements the client has no primary, which
     * is why they are in one: no reader ever observes the gap, and a rollback
     * puts the incumbent back.
     *
     * @param exceptContactId the contact being promoted, so an idempotent
     *                        re-save of the existing primary does not demote and
     *                        then re-promote the same row
     */
    void demoteOtherPrimaries(long obClientId, Long exceptContactId) {
        jdbc.sql("""
                UPDATE ob_client_contacts
                   SET is_primary = 0
                 WHERE ob_client_id = :clientId
                   AND is_primary_key = 1
                   AND (:except IS NULL OR id <> :except)
                """)
                .param("clientId", obClientId)
                .param("except", exceptContactId)
                .update();
    }

    /**
     * One row in the consent journal.
     *
     * <p>Written for a grant and for a withdrawal both, because the second is
     * the one the first has to be defensible against: a flag that flipped to 0
     * with nothing kept cannot show that consent stood when the messages that
     * were sent went out.
     *
     * <p>{@code source} is NULL on a withdrawal by design — "how it was given"
     * has no counterpart when it is being taken away, and {@code
     * ck_ob_contact_consent_source} allows the NULL only in that direction.
     */
    void recordConsent(long contactId, boolean optedIn, ObConsentSource source,
                       Long recordedBy, Instant recordedAt) {

        jdbc.sql("""
                INSERT INTO ob_contact_consent_events
                    (ob_client_contact_id, channel, opted_in, source, recorded_by, recorded_at)
                VALUES (:contactId, 'WHATSAPP', :optedIn, :source, :recordedBy, :recordedAt)
                """)
                .param("contactId", contactId)
                .param("optedIn", optedIn)
                .param("source", optedIn && source != null ? source.name() : null)
                .param("recordedBy", recordedBy)
                .param("recordedAt", Timestamp.from(recordedAt))
                .update();
    }

    /**
     * The consent triple as it goes to the database — present together or NULL
     * together, which is what {@code ck_ob_client_contacts_consent} enforces.
     *
     * @param optedIn the flag
     * @param source  the basis, non-null exactly when {@code optedIn}
     * @param at      when it was recorded, non-null exactly when {@code optedIn}
     * @param by      the staff user who recorded it, or null for a client-portal
     *                action — nullable independently of the other three
     */
    record Consent(boolean optedIn, ObConsentSource source, Instant at, Long by) {

        /** Consent withheld: the flag off and the whole stamp cleared. */
        static Consent withheld() {
            return new Consent(false, null, null, null);
        }

        static Consent given(ObConsentSource source, Instant at, Long by) {
            return new Consent(true, source, at, by);
        }

        String sourceName() {
            return source == null ? null : source.name();
        }

        /**
         * Bound as a {@link Timestamp}, never as the {@link Instant} itself.
         *
         * <p>Connector/J converts a raw {@code java.time.Instant} parameter
         * through the <b>JVM's default zone</b>, not through the connection's
         * {@code connectionTimeZone=UTC}. On a machine set to IST that writes
         * the wall clock rather than the instant, and the round trip then
         * compounds it: the read interprets the stored value as UTC and the next
         * write shifts it again. Found by
         * {@code ObContactsIT.anUnrelatedEditIsNotAConsentEvent}, which failed
         * by exactly one offset — on a consent date, which is the one value in
         * this feature that has to still be right in a year.
         *
         * <p>{@code ObReportRepository} and {@code ObEscalationReadRepository}
         * both bind {@code Timestamp.from(...)} for the same reason; this is the
         * house pattern rather than a local workaround.
         */
        Timestamp atTimestamp() {
            return at == null ? null : Timestamp.from(at);
        }
    }

    private static String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
