package com.edunext.edutrack.api.feature.portal.tickets;

import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketDtos.PortalComment;
import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketDtos.PortalTicket;
import com.edunext.edutrack.common.pagination.Cursor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A-127 · every portal ticket read, with the filters in the SQL.
 *
 * <h2>The filters are predicates, not mapping decisions</h2>
 *
 * <p>A row a client may not read is never loaded. That is the whole point of
 * putting {@code is_internal = 0}, {@code is_client_visible = 1} and
 * {@code scan_status = 'CLEAN'} in the {@code WHERE} clause rather than in a
 * serializer: no later change to a DTO, no field added to a record, and no
 * mapper somebody reuses can publish a row that never arrived.
 *
 * <h2>Two different columns, and they are not spelled alike</h2>
 *
 * <p><b>{@code ticket_comments} has no {@code is_client_visible} column.</b> It
 * carries {@code is_internal}, which defaults to 1 and whose own comment in
 * {@code V20260805_1530} reads "0 = client-visible". Only
 * {@code ticket_attachments} has {@code is_client_visible}. The two flags point
 * in opposite directions, which is exactly the kind of thing that gets copied
 * wrong once and then inverts a customer-facing filter — so each predicate is
 * written out here beside the column it applies to, rather than hidden behind a
 * shared helper that would have to know which way round each table is.
 *
 * <h2>The client id is a parameter, never a filter the caller sent</h2>
 *
 * <p>Every statement takes {@code clientId} from {@code ClientScopeResolver},
 * and no method offers an overload without it. {@code tickets.client_id} is
 * nullable — an internally-raised ticket leaves it null — so the equality
 * excludes those without needing a rule of its own.
 */
@Repository
class PortalTicketReadRepository {

    /**
     * Whether this client's portal is open at all.
     *
     * <p>Plan §2.3 names {@code client_contacts.portal_access} as the second
     * dormant hook this task activates. {@code client_accounts} holds no
     * reference to a contact row, so the honest reading of the flag at the
     * client level is "somebody at this client is allowed to use the portal":
     * at least one active contact carrying it.
     *
     * <p><b>The alternative was considered and is named rather than hidden:</b>
     * matching {@code client_accounts.email} to a contact's would tie the login
     * to one person, which is stricter and probably where this ends up — but the
     * schema does not model that link today, and inventing it in a WHERE clause
     * would make a security rule out of a coincidence between two free-text
     * columns. Flagged for the A-127 security review.
     */
    private static final String PORTAL_IS_OPEN = """
            SELECT EXISTS(
                SELECT 1 FROM client_contacts
                 WHERE client_id = ? AND is_active = 1 AND portal_access = 1)
            """;

    /**
     * The portal's ticket projection — eleven columns and no twelfth.
     *
     * <p>{@code assigned_to}, {@code reported_by}, {@code level},
     * {@code current_stage}, {@code is_delayed} and {@code total_effort_hrs} are
     * all on the row and none is selected. Selecting a column the DTO happens
     * not to carry is how a field reaches a customer the first time somebody
     * adds a component to the record "because the query already has it".
     */
    private static final String TICKET_COLUMNS = """
            SELECT t.id, t.ticket_code, t.title, t.description, t.status,
                   p.name AS project_name, tt.name AS task_type_name,
                   t.date_reported, t.planned_close_date, t.actual_close_date,
                   t.updated_at
              FROM tickets t
              JOIN projects p ON p.id = t.project_id
              LEFT JOIN task_types tt ON tt.id = t.task_type_id
            """;

    private final JdbcClient jdbc;

    PortalTicketReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean portalIsOpenFor(long clientId) {
        return Boolean.TRUE.equals(
                jdbc.sql(PORTAL_IS_OPEN).param(clientId).query(Boolean.class).single());
    }

    /**
     * One page of the client's tickets, newest first.
     *
     * <p>Keyset rather than offset, on {@code (date_reported, id)} — the id
     * breaks ties, because two tickets raised in the same microsecond would
     * otherwise make a page boundary non-deterministic and silently skip one.
     *
     * @param fetchSize {@code limit + 1}; {@code CursorPage.of} consumes the
     *                  extra row and is the only thing that decides {@code hasMore}
     */
    List<PortalTicket> tickets(long clientId, String status, boolean excludeClosed,
                               boolean descending, Cursor after, int fetchSize) {

        StringBuilder sql = new StringBuilder(TICKET_COLUMNS).append(" WHERE t.client_id = ?");
        List<Object> params = new java.util.ArrayList<>();
        params.add(clientId);

        if (status != null && !status.isBlank()) {
            sql.append(" AND t.status = ?");
            params.add(status);
        }
        if (excludeClosed) {
            sql.append(" AND t.status <> 'CLOSED'");
        }
        // The keyset comparison flips with the direction: descending resumes
        // with "strictly before the last row", ascending with "strictly after"
        // — the same (sortCol, id) pair either way, on TicketListSpecs'
        // reasoning for why id must break the tie.
        String cmp = descending ? "<" : ">";
        if (after != null) {
            sql.append(" AND (t.date_reported ").append(cmp).append(" ? OR (t.date_reported = ? AND t.id ")
                    .append(cmp).append(" ?))");
            Timestamp key = Timestamp.from(Instant.parse(after.sortKey()));
            params.add(key);
            params.add(key);
            params.add(after.id());
        }
        sql.append(" ORDER BY t.date_reported ").append(descending ? "DESC" : "ASC")
                .append(", t.id ").append(descending ? "DESC" : "ASC")
                .append(" LIMIT ?");
        params.add(fetchSize);

        var call = jdbc.sql(sql.toString());
        for (Object param : params) {
            call = call.param(param);
        }
        return call.query(TICKET).list();
    }

    /** One ticket by its code, scoped. Empty is the caller's 404. */
    Optional<PortalTicket> ticket(long clientId, String ticketCode) {
        return jdbc.sql(TICKET_COLUMNS + " WHERE t.client_id = ? AND t.ticket_code = ?")
                .param(clientId).param(ticketCode)
                .query(TICKET).optional();
    }

    /** The ticket's row id, scoped — what the child reads join on. */
    Optional<Long> ticketRowId(long clientId, String ticketCode) {
        return jdbc.sql("SELECT id FROM tickets WHERE client_id = ? AND ticket_code = ?")
                .param(clientId).param(ticketCode)
                .query(Long.class).optional();
    }

    /**
     * Client-visible comments, oldest first, as a thread reads.
     *
     * <p>{@code body_text} rather than {@code body_html}. The staff surface
     * serves the sanitised HTML because colleagues wrote it for each other with
     * formatting; the portal is the one surface where a sanitiser bug would land
     * in front of somebody outside the organisation, and a thread reads perfectly
     * well as text. Recorded as a deliberate downgrade, and flagged for the
     * security review rather than settled quietly.
     *
     * <p>Tombstones are excluded rather than rendered. "A message was removed" is
     * an internal fact about the record — §4B.5's tombstone exists so colleagues
     * can see what happened, not so a customer can infer that something did.
     */
    List<PortalComment> comments(long ticketRowId, Cursor after, int fetchSize) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.body_text, u.full_name AS author_name, c.created_at
                  FROM ticket_comments c
                  LEFT JOIN users u ON u.id = c.author_id
                 WHERE c.ticket_id = ?
                   AND c.is_internal = 0
                   AND c.is_deleted = 0
                """);
        List<Object> params = new java.util.ArrayList<>();
        params.add(ticketRowId);
        if (after != null) {
            sql.append(" AND (c.created_at > ? OR (c.created_at = ? AND c.id > ?))");
            Timestamp key = Timestamp.from(Instant.parse(after.sortKey()));
            params.add(key);
            params.add(key);
            params.add(after.id());
        }
        sql.append(" ORDER BY c.created_at ASC, c.id ASC LIMIT ?");
        params.add(fetchSize);

        var call = jdbc.sql(sql.toString());
        for (Object param : params) {
            call = call.param(param);
        }
        return call.query(COMMENT).list();
    }

    /**
     * Client-visible attachments that cleared the scanner and are not tombstoned.
     *
     * <p>All three conditions, and the scan one is the easy one to leave out: an
     * INFECTED row carries no readable bytes anyway, so omitting it looks
     * harmless — until a PENDING file that later turns out infected has already
     * been listed to a customer.
     */
    List<AttachmentRow> attachments(long ticketRowId, Cursor after, int fetchSize) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.file_name, a.mime_type, a.size_bytes,
                       a.storage_key, a.thumbnail_key, a.created_at
                  FROM ticket_attachments a
                 WHERE a.ticket_id = ?
                   AND a.is_client_visible = 1
                   AND a.scan_status = 'CLEAN'
                   AND a.is_deleted = 0
                """);
        List<Object> params = new java.util.ArrayList<>();
        params.add(ticketRowId);
        if (after != null) {
            sql.append(" AND (a.created_at > ? OR (a.created_at = ? AND a.id > ?))");
            Timestamp key = Timestamp.from(Instant.parse(after.sortKey()));
            params.add(key);
            params.add(key);
            params.add(after.id());
        }
        sql.append(" ORDER BY a.created_at ASC, a.id ASC LIMIT ?");
        params.add(fetchSize);

        var call = jdbc.sql(sql.toString());
        for (Object param : params) {
            call = call.param(param);
        }
        return call.query(ATTACHMENT).list();
    }

    /**
     * An attachment before its URLs are signed.
     *
     * <p>The storage keys stay inside this package and never reach a DTO: a key
     * is an address in a private bucket, and the client gets a short-lived
     * signature over it or nothing.
     */
    record AttachmentRow(
            long id,
            String fileName,
            String contentType,
            long sizeBytes,
            String storageKey,
            String thumbnailKey,
            Instant createdAt) {
    }

    // ── mappers ─────────────────────────────────────────────────────────────

    private static final RowMapper<PortalTicket> TICKET = (ResultSet rs, int row) -> new PortalTicket(
            rs.getLong("id"),
            rs.getString("ticket_code"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getString("project_name"),
            rs.getString("task_type_name"),
            instant(rs, "date_reported"),
            instant(rs, "planned_close_date"),
            instant(rs, "actual_close_date"),
            instant(rs, "updated_at"));

    private static final RowMapper<PortalComment> COMMENT = (ResultSet rs, int row) -> new PortalComment(
            rs.getLong("id"),
            rs.getString("body_text"),
            // Nothing writes a client-authored comment yet — the portal thread is
            // read-only in phase 1 — so every row here is a colleague's.
            "STAFF",
            rs.getString("author_name"),
            instant(rs, "created_at"));

    private static final RowMapper<AttachmentRow> ATTACHMENT = (ResultSet rs, int row) -> new AttachmentRow(
            rs.getLong("id"),
            rs.getString("file_name"),
            rs.getString("mime_type"),
            rs.getLong("size_bytes"),
            rs.getString("storage_key"),
            rs.getString("thumbnail_key"),
            instant(rs, "created_at"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
