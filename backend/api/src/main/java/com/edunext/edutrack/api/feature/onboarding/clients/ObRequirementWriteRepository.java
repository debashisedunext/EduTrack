package com.edunext.edutrack.api.feature.onboarding.clients;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * B-106 · the three writes behind OB-05's requirements list.
 *
 * <h2>SQL and no {@code @Entity}, on the decision B-102 deferred to here</h2>
 *
 * <p>{@code ObClientChildWriteRepository} declined to map
 * {@code ob_client_requirements} and named this task as the one that would own
 * the choice — "B-106 the requirements and their rich text". It comes out the
 * same way {@code ObApplicationWriteRepository} and
 * {@code ObContactWriteRepository} did, for the same first reason and a
 * different second one.
 *
 * <ul>
 *   <li><b>The read side is already SQL.</b> {@code REQUIREMENT_COLUMNS}
 *       projects the row with both of its user names left-joined, because OB-05
 *       prints "met by Priya" inline rather than fetching a user per row. An
 *       entity here would be a second mapping of one table — the failure
 *       {@code ObClient}'s own note names.</li>
 *   <li><b>The interesting behaviour is not persistence.</b> What makes B-106
 *       more than three statements is what happens <em>before</em> the write:
 *       §3.9's allow-list deciding what the body reduces to, and the rule that
 *       an unrelated edit must not re-date the met stamp. Neither is something
 *       a mapping helps with, and a dirty-checked entity would make the second
 *       one harder to see.</li>
 * </ul>
 *
 * <h2>The update is the whole row, and the service is what makes it partial</h2>
 *
 * <p>{@link ObRequirementUpdateRequest} is partial by field, so the obvious
 * implementation is a {@code SET} list assembled from whichever fields arrived.
 * That is not what happens. {@link ObRequirementService} has already read the
 * row — it has to, to answer 404 — and resolves each absent field against what
 * it read, so this class receives five settled values and writes them.
 *
 * <p>The reason is that dynamic SQL is where this kind of statement goes wrong
 * silently: a body naming no editable field assembles {@code SET} with nothing
 * after it, which is a syntax error at runtime rather than a no-op, and every
 * new column is a new branch that has to remember to be added. One shape, always
 * the same five columns, is a statement that can be read against the table.
 */
@Repository
class ObRequirementWriteRepository {

    private final JdbcClient jdbc;

    ObRequirementWriteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record one requirement.
     *
     * <p>The generated key is not read back: the operation answers with the whole
     * client document, which is re-read after the write, so the new row arrives
     * through {@code requirementsOf} like every other.
     *
     * <p>{@code metAt} is bound through {@link Timestamp#from}, which applies the
     * JVM default zone on the way in — this is a {@code DATETIME(6)} carrying a
     * real instant, so that is the correct mapping and the one
     * {@code ObContactWriteRepository.Consent.atTimestamp} documents. It is
     * <em>not</em> the {@code LocalDate} case: a licence date has no instant and
     * must not be given one.
     */
    void insert(long obClientId, int sequence, String title, String bodyHtml, String bodyText,
                boolean isMet, Instant metAt, Long metBy, Long createdBy) {
        jdbc.sql("""
                INSERT INTO ob_client_requirements
                    (ob_client_id, sequence, title, body_html, body_text,
                     is_met, met_at, met_by, created_by, updated_by)
                VALUES (:clientId, :sequence, :title, :bodyHtml, :bodyText,
                        :isMet, :metAt, :metBy, :createdBy, :createdBy)
                """)
                .param("clientId", obClientId)
                .param("sequence", sequence)
                .param("title", title)
                .param("bodyHtml", bodyHtml)
                .param("bodyText", bodyText)
                .param("isMet", isMet)
                .param("metAt", metAt == null ? null : Timestamp.from(metAt))
                .param("metBy", metBy)
                .param("createdBy", createdBy)
                .update();
    }

    /**
     * Rewrite one requirement's five editable columns.
     *
     * <p>{@code sequence} is not among them, and neither is {@code created_by}.
     * Reordering the list is a rewrite of the set rather than an edit of one row
     * — the migration's own note — and authorship of a requirement does not
     * change because somebody else corrected its spelling. {@code updated_by} is
     * what records who touched it last, and {@code updated_at} moves on its own
     * through the column's {@code ON UPDATE CURRENT_TIMESTAMP(6)}.
     *
     * <p>{@code met_at} and {@code met_by} are written together with
     * {@code is_met} because {@code ck_ob_client_requirements_met} refuses them
     * apart. That constraint is the reason this is one statement rather than a
     * flag update followed by a stamp update: the intermediate state between two
     * statements is precisely the one the CHECK exists to reject.
     */
    void update(long requirementId, String title, String bodyHtml, String bodyText,
                boolean isMet, Instant metAt, Long metBy, Long updatedBy) {
        jdbc.sql("""
                UPDATE ob_client_requirements
                   SET title = :title,
                       body_html = :bodyHtml,
                       body_text = :bodyText,
                       is_met = :isMet,
                       met_at = :metAt,
                       met_by = :metBy,
                       updated_by = :updatedBy
                 WHERE id = :id
                """)
                .param("id", requirementId)
                .param("title", title)
                .param("bodyHtml", bodyHtml)
                .param("bodyText", bodyText)
                .param("isMet", isMet)
                .param("metAt", metAt == null ? null : Timestamp.from(metAt))
                .param("metBy", metBy)
                .param("updatedBy", updatedBy)
                .update();
    }

    /**
     * Remove one requirement.
     *
     * <p><b>A hard delete, where the purchases panel has no delete at all</b> —
     * and the difference is a fact about the schema rather than a difference of
     * opinion. {@code fk_ob_journeys_application} points at a purchase and is
     * {@code RESTRICT}; <em>nothing</em> points at
     * {@code ob_client_requirements}. No journey is instantiated from a
     * requirement, no history references one, and no sign-off names one. The row
     * takes nothing with it.
     *
     * <p>The id has already been resolved against its client by
     * {@link ObRequirementService}, so the {@code WHERE} is by primary key
     * alone. A caller who could reach this method with somebody else's id would
     * have had to get past a scoped client read and a 404 to do it.
     */
    void delete(long requirementId) {
        jdbc.sql("DELETE FROM ob_client_requirements WHERE id = :id")
                .param("id", requirementId)
                .update();
    }
}
