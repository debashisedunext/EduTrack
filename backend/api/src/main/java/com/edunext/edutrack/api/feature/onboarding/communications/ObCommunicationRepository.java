package com.edunext.edutrack.api.feature.onboarding.communications;

import com.edunext.edutrack.common.pagination.Cursor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * C-112 · {@code ob_step_communications} — the per-step timeline, the
 * client-level stitched view, and the one write that appends to either.
 *
 * <h2>THIS TABLE IS APPEND ONLY. There is no {@code update} and no
 * {@code delete} on this class, and none may be added.</h2>
 *
 * <p>CLAUDE.md's four layers, of which this class is layer 1:
 * <pre>
 *   layer 1  no update()/delete() method on the service   ← this file
 *   layer 2  no PUT/PATCH/DELETE route registered         ObCommunicationController
 *   layer 3  edutrack_app holds INSERT, SELECT only here  A-109
 *   layer 4  the triggers in V20260903_1745               the migration
 * </pre>
 *
 * <p>A correction is a new row carrying {@code is_correction} and {@code
 * corrects_entry_id} — an accounting reversal, not an edit. No route writes
 * one yet: nothing in the product asks a person to correct a communication,
 * and inventing the affordance ahead of the requirement would be inventing
 * the workflow around it too. The columns are read and rendered rather than
 * ignored, so a row some later task writes appears correctly the day it does.
 *
 * <p>Unlike {@code ob_step_history} beside it, this table is <b>not
 * hash-chained</b>, and the migration's own header argues why: the chain
 * costs a pessimistic lock on the parent journey per append, and every
 * communication worth disputing has a history row beside it inside the chain
 * already. So an append here is a plain insert with no journey lock, which is
 * also why the portal can write one without serialising against a handoff.
 *
 * <h2>Two reads, two orders, one index each</h2>
 *
 * <p>The per-step read is <b>oldest first</b> on {@code (occurred_at, id)},
 * served by {@code ix_ob_comms_step (step_id, id)} — a service's timeline is
 * a narrative and reads forwards. The client-level read is <b>newest
 * first</b> on the same pair, served by {@code ix_ob_comms_client
 * (ob_client_id, is_client_visible, occurred_at)} whose column order is
 * exactly the {@code clientVisibleOnly} filter followed by the sort. The
 * index was written for this query before this query existed; the DDL's own
 * comment says so.
 *
 * <p>Both sort on {@code occurred_at} rather than {@code created_at} —
 * <b>when the conversation happened, not when it was typed</b>. The contract
 * field's own description makes the point: people record a Friday call on
 * Monday, and a communication audit ordered by entry time misreports every
 * one of them. It is also why the cursor's sort key is that column: a
 * backdated entry inserted after a page was served appears where it belongs
 * rather than at the end.
 */
@Repository
class ObCommunicationRepository {

    /**
     * Every column both shapes need. The client-level view reads all of it;
     * the per-step view ignores the four context columns rather than running
     * a second statement for the sake of not selecting them — one join plan
     * to reason about, and the joins are all index lookups on primary keys.
     */
    private static final String SELECT_BASE = """
            SELECT k.id                AS id,
                   k.step_id           AS stepId,
                   k.journey_id        AS journeyId,
                   k.ob_client_id      AS obClientId,
                   s.name              AS stepName,
                   s.sequence          AS stepSequence,
                   p.name              AS productName,
                   k.entry_type        AS channel,
                   k.body              AS summary,
                   k.author_type       AS authorType,
                   k.author_user_id    AS authorUserId,
                   au.full_name        AS authorUserName,
                   k.author_contact_id AS authorContactId,
                   ac.name             AS authorContactName,
                   k.is_client_visible AS isClientVisible,
                   k.occurred_at       AS occurredAt,
                   k.is_correction     AS isCorrection,
                   k.corrects_entry_id AS correctsEntryId,
                   k.created_at        AS createdAt
              FROM ob_step_communications k
              JOIN ob_journey_steps s ON s.id = k.step_id
              JOIN ob_journeys      j ON j.id = k.journey_id
              JOIN ob_products      p ON p.id = j.product_id
         LEFT JOIN users            au ON au.id = k.author_user_id
         LEFT JOIN ob_client_contacts ac ON ac.id = k.author_contact_id
            """;

    /** One service's timeline, oldest first — a narrative read forwards. */
    private static final String LIST_FOR_STEP = SELECT_BASE + """
             WHERE k.step_id = :stepId
               AND %s
               AND (:cursorAt IS NULL
                    OR k.occurred_at > :cursorAt
                    OR (k.occurred_at = :cursorAt AND k.id > :cursorId))
             ORDER BY k.occurred_at ASC, k.id ASC
             LIMIT :limit
            """;

    /**
     * The stitched view, newest first — a feed you check, where the entry
     * that matters is the last one. The cursor comparison flips with the
     * sort; keeping {@code >} here would page backwards through the table.
     */
    private static final String LIST_FOR_CLIENT = SELECT_BASE + """
             WHERE k.ob_client_id = :obClientId
               AND %s
               AND (:journeyId IS NULL OR k.journey_id = :journeyId)
               AND (:clientVisibleOnly = FALSE OR k.is_client_visible = 1)
               AND (:cursorAt IS NULL
                    OR k.occurred_at < :cursorAt
                    OR (k.occurred_at = :cursorAt AND k.id < :cursorId))
             ORDER BY k.occurred_at DESC, k.id DESC
             LIMIT :limit
            """;

    /** One entry by id, under the same scope predicate the lists apply. */
    private static final String FIND_BY_ID = SELECT_BASE + """
             WHERE k.id = :id
               AND %s
            """;

    /**
     * {@code journey_id} and {@code ob_client_id} are denormalised onto every
     * communication row, so an append has to resolve them from the step it is
     * being written against rather than trusting a caller to supply them.
     *
     * <p>Scoped identically to the reads: a caller who may not read a step's
     * timeline may not append to it either, and both answer "no such step"
     * rather than "not yours".
     *
     * <p><b>The derived table is what lets one scope predicate serve two
     * different FROM clauses.</b> {@link ObCommunicationScope#predicate}
     * writes {@code <alias>.step_id} and {@code <alias>.ob_client_id}, which
     * are {@code ob_step_communications}' column names — and no communication
     * row is involved here. Projecting the step and its journey into a
     * subquery that spells those two columns lets the predicate apply
     * verbatim against alias {@code ctx}, rather than the rule being written
     * a second time for this statement's own aliases. Two expressions of one
     * security rule is the risk {@code ObReportScope}'s javadoc names, and it
     * is worth a derived table to avoid inside a single package.
     */
    private static final String STEP_CONTEXT = """
            SELECT ctx.step_id AS stepId, ctx.journey_id AS journeyId, ctx.ob_client_id AS obClientId
              FROM (SELECT s.id           AS step_id,
                           s.journey_id   AS journey_id,
                           j.ob_client_id AS ob_client_id
                      FROM ob_journey_steps s
                      JOIN ob_journeys j ON j.id = s.journey_id
                     WHERE s.id = :stepId) ctx
             WHERE %s
            """;

    /**
     * INSERT. The only write in this class, and the only one there will be.
     *
     * <p>{@code is_correction}/{@code corrects_entry_id} are left at their
     * defaults — no route writes a correction yet, and {@code
     * ck_ob_comms_correction} rejects a row that names one without being one.
     */
    private static final String INSERT = """
            INSERT INTO ob_step_communications
                   (step_id, journey_id, ob_client_id, entry_type, body,
                    author_type, author_user_id, is_client_visible, occurred_at)
            VALUES (:stepId, :journeyId, :obClientId, :entryType, :body,
                    :authorType, :authorUserId, :isClientVisible, :occurredAt)
            """;

    /**
     * C-126 · the escalation mirror's own insert. {@code entry_type} is
     * fixed to {@code ESCALATION} and the row is always client-visible —
     * unlike {@link #INSERT} above, whose caller ({@link
     * ObCommunicationService#record}) is staff-only and defaults to
     * internal. The author may be a client contact (a raise) or a staff
     * user (a resolution), so both author columns are bound rather than
     * {@link #INSERT}'s fixed {@code STAFF}.
     */
    private static final String INSERT_ESCALATION = """
            INSERT INTO ob_step_communications
                   (step_id, journey_id, ob_client_id, entry_type, body,
                    author_type, author_user_id, author_contact_id, is_client_visible, occurred_at)
            VALUES (:stepId, :journeyId, :obClientId, 'ESCALATION', :body,
                    :authorType, :authorUserId, :authorContactId, 1, :occurredAt)
            """;

    private final JdbcClient jdbc;

    ObCommunicationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param fetchSize {@link com.edunext.edutrack.common.pagination.PageLimit#fetchSize} — one
     *                  more than the page, so {@code CursorPage.of} can tell {@code hasMore}
     */
    List<Row> listForStep(ObCommunicationScope scope, long stepId, String cursor, int fetchSize) {
        var spec = jdbc.sql(LIST_FOR_STEP.formatted(scope.predicate("k")))
                .param("stepId", stepId)
                .param("limit", fetchSize);
        return withCursor(withScope(spec, scope), cursor).query(MAPPER).list();
    }

    List<Row> listForClient(ObCommunicationScope scope, long obClientId, Long journeyId,
                            boolean clientVisibleOnly, String cursor, int fetchSize) {
        var spec = jdbc.sql(LIST_FOR_CLIENT.formatted(scope.predicate("k")))
                .param("obClientId", obClientId)
                .param("journeyId", journeyId)
                .param("clientVisibleOnly", clientVisibleOnly)
                .param("limit", fetchSize);
        return withCursor(withScope(spec, scope), cursor).query(MAPPER).list();
    }

    /** Empty for "no such entry" and "not yours" alike — A-112's rule. */
    Optional<Row> findById(ObCommunicationScope scope, long id) {
        var spec = jdbc.sql(FIND_BY_ID.formatted(scope.predicate("k"))).param("id", id);
        return withScope(spec, scope).query(MAPPER).optional();
    }

    /**
     * The step's journey and client, or empty when the step does not exist or
     * is out of this caller's scope — see {@link #STEP_CONTEXT} for why the
     * scope predicate applies to a derived table rather than to the step row.
     */
    Optional<StepContext> stepContext(ObCommunicationScope scope, long stepId) {
        var spec = jdbc.sql(STEP_CONTEXT.formatted(scope.predicate("ctx"))).param("stepId", stepId);
        return withScope(spec, scope)
                .query((ResultSet rs, int rowNum) -> new StepContext(
                        rs.getLong("stepId"), rs.getLong("journeyId"), rs.getLong("obClientId")))
                .optional();
    }

    /** Appends one entry and answers its generated id. Insert only — see the class javadoc. */
    long insert(StepContext context, String entryType, String body, long authorUserId,
                boolean isClientVisible, Instant occurredAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql(INSERT)
                .param("stepId", context.stepId())
                .param("journeyId", context.journeyId())
                .param("obClientId", context.obClientId())
                .param("entryType", entryType)
                .param("body", body)
                .param("authorType", ObCommunicationDtos.AUTHOR_STAFF)
                .param("authorUserId", authorUserId)
                .param("isClientVisible", isClientVisible ? 1 : 0)
                .param("occurredAt", Timestamp.from(occurredAt))
                .update(keys);
        Number generated = keys.getKey();
        if (generated == null) {
            throw new IllegalStateException("ob_step_communications insert returned no generated id");
        }
        return generated.longValue();
    }

    /**
     * Appends one {@code ESCALATION}-typed entry — C-126's mirror of a
     * client escalation raise or resolution (plan §4). Insert only, on the
     * same door as {@link #insert} above; see {@link #INSERT_ESCALATION}'s
     * own note on the two differences from that method.
     *
     * @param authorType      {@code CLIENT} for a raise, {@code STAFF} for a resolution
     * @param authorUserId    set for a {@code STAFF} author, {@code null} otherwise
     * @param authorContactId set for a {@code CLIENT} author, {@code null} otherwise
     */
    long insertEscalationEntry(StepContext context, String body, String authorType,
                               Long authorUserId, Long authorContactId, Instant occurredAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql(INSERT_ESCALATION)
                .param("stepId", context.stepId())
                .param("journeyId", context.journeyId())
                .param("obClientId", context.obClientId())
                .param("body", body)
                .param("authorType", authorType)
                .param("authorUserId", authorUserId)
                .param("authorContactId", authorContactId)
                .param("occurredAt", Timestamp.from(occurredAt))
                .update(keys);
        Number generated = keys.getKey();
        if (generated == null) {
            throw new IllegalStateException("ob_step_communications insert returned no generated id");
        }
        return generated.longValue();
    }

    private static JdbcClient.StatementSpec withScope(JdbcClient.StatementSpec spec, ObCommunicationScope scope) {
        return scope.unrestricted() ? spec : spec.param(ObCommunicationScope.USER_PARAM, scope.userId());
    }

    private static JdbcClient.StatementSpec withCursor(JdbcClient.StatementSpec spec, String cursor) {
        Cursor decoded = decodeCursor(cursor);
        return spec
                .param("cursorAt", decoded == null ? null : Timestamp.from(Instant.parse(decoded.sortKey())))
                .param("cursorId", decoded == null ? null : decoded.id());
    }

    /**
     * The cursor names {@code occurred_at|id}. Malformed, absent or an
     * unparseable {@code sortKey} is the first page — {@code Cursor}'s own
     * convention — rather than a 400.
     */
    private static Cursor decodeCursor(String cursor) {
        Cursor decoded = Cursor.decode(cursor);
        if (decoded == null) {
            return null;
        }
        try {
            Instant.parse(decoded.sortKey());
            return decoded;
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }

    private static final RowMapper<Row> MAPPER = (ResultSet rs, int rowNum) -> new Row(
            rs.getLong("id"),
            rs.getLong("stepId"),
            rs.getLong("journeyId"),
            rs.getLong("obClientId"),
            rs.getString("stepName"),
            rs.getInt("stepSequence"),
            rs.getString("productName"),
            rs.getString("channel"),
            rs.getString("summary"),
            rs.getString("authorType"),
            nullableLong(rs, "authorUserId"),
            rs.getString("authorUserName"),
            nullableLong(rs, "authorContactId"),
            rs.getString("authorContactName"),
            rs.getBoolean("isClientVisible"),
            instant(rs, "occurredAt"),
            rs.getBoolean("isCorrection"),
            nullableLong(rs, "correctsEntryId"),
            instant(rs, "createdAt"));

    /** By hand — {@code DATETIME(6)} does not map onto an {@link Instant} unassisted; storage is UTC (CLAUDE.md). */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    record Row(
            long id,
            long stepId,
            long journeyId,
            long obClientId,
            String stepName,
            int stepSequence,
            String productName,
            String channel,
            String summary,
            String authorType,
            Long authorUserId,
            String authorUserName,
            Long authorContactId,
            String authorContactName,
            boolean isClientVisible,
            Instant occurredAt,
            boolean isCorrection,
            Long correctsEntryId,
            Instant createdAt) {
    }

    /** Where an append lands: the step, and the journey and client denormalised onto its rows. */
    record StepContext(long stepId, long journeyId, long obClientId) {
    }
}
