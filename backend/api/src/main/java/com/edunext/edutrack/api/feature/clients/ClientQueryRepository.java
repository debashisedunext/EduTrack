package com.edunext.edutrack.api.feature.clients;

import com.edunext.edutrack.common.pagination.Cursor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * B-025 · the S-32 grid's reads.
 *
 * <p>Plain SQL through {@link JdbcClient} rather than {@code ClientRepository},
 * for the two reasons {@code TaskTypeUsageRepository} and
 * {@code SlaMatrixRepository} already give. The list is a keyset page under five
 * optional filters, which is a dynamic predicate a derived query name cannot
 * express and Criteria expresses unreadably; and the four columns S-32 adds on
 * top of the row — open tickets, last ticket date, projects, primary contact —
 * are grouped projections, which is not what a {@code JpaRepository} is for.
 *
 * <h2>Four queries per page, never four per row</h2>
 *
 * <p>The page is read first, and the aggregates are then fetched for exactly the
 * ids it returned. That is the whole reason they are separate statements: a
 * client with tickets, projects and contacts joined in one query multiplies rows
 * by three collections and the grid would have to de-duplicate what the database
 * just fanned out. Five queries at fixed cost beat one that grows with the
 * product of three children.
 *
 * <p><b>This is not the {@code COUNT(*)} CLAUDE.md forbids.</b> That rule is
 * about dashboards, which are read constantly and have pre-aggregated summary
 * tables built for them. This is an Admin grid of at most 200 rows a page, every
 * statement is keyed on {@code ix_tickets_client (client_id, status)} or on a
 * primary key, and a summary table for it would be a second source of truth
 * maintained for a screen somebody opens weekly.
 */
@Repository
class ClientQueryRepository {

    private final JdbcClient jdbc;

    ClientQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A client row before its aggregates are attached. */
    record Row(
            long id,
            String clientCode,
            String name,
            String domain,
            Long accountManagerId,
            String accountManagerName,
            String accountManagerRole,
            String supportPlan,
            Long slaPolicyId,
            String timezone,
            String status) {
    }

    /** The five S-32 filters, all optional. */
    record Filter(
            String q,
            Boolean isActive,
            Long projectId,
            String supportPlan,
            Long accountManagerId) {
    }

    /**
     * One keyset page, ordered by {@code name} then {@code id}.
     *
     * <p><b>The tiebreak is not decoration.</b> A keyset over {@code name} alone
     * skips or repeats rows wherever two clients share one, and after a bulk
     * import they do — {@code client_code} is unique, the name is not.
     *
     * <p>Returns up to {@code fetchSize} rows, which the caller is expected to be
     * one more than the page. The extra row is evidence for
     * {@code CursorPage.of}; trimming it here would move the off-by-one A-053
     * exists to hold in one place.
     *
     * <p>Row-value comparison rather than the expanded
     * {@code name > ? OR (name = ? AND id > ?)}: MySQL 8.4 optimises
     * {@code (a, b) > (?, ?)} against a composite ordering, and the expanded form
     * is where a stray {@code >=} silently repeats one row per page.
     */
    List<Row> page(Filter filter, Cursor cursor, int fetchSize) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.client_code, c.name, c.website_domain,
                       c.account_manager_id, u.full_name AS manager_name,
                       r.code AS manager_role, c.support_plan, c.sla_policy_id,
                       c.timezone, c.status
                FROM clients c
                LEFT JOIN users u ON u.id = c.account_manager_id
                LEFT JOIN roles r ON r.id = u.role_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();

        if (filter.q() != null && !filter.q().isBlank()) {
            // Name, code or domain — the three things somebody types into the
            // S-32 search box, and the same three the contract documents.
            sql.append(" AND (c.name LIKE ? OR c.client_code LIKE ? OR c.website_domain LIKE ?)");
            String like = "%" + filter.q().trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (filter.isActive() != null) {
            sql.append(" AND c.status = ?");
            params.add(filter.isActive() ? ClientService.ACTIVE : ClientService.INACTIVE);
        }
        if (filter.projectId() != null) {
            // EXISTS rather than a join: a client on three projects must appear
            // once, and a join would return it three times.
            sql.append(" AND EXISTS (SELECT 1 FROM client_projects cp"
                    + " WHERE cp.client_id = c.id AND cp.project_id = ?)");
            params.add(filter.projectId());
        }
        if (filter.supportPlan() != null && !filter.supportPlan().isBlank()) {
            // utf8mb4_0900_ai_ci already matches case-insensitively; no lower()
            // on the column, which would prevent the index being used.
            sql.append(" AND c.support_plan = ?");
            params.add(filter.supportPlan().trim());
        }
        if (filter.accountManagerId() != null) {
            sql.append(" AND c.account_manager_id = ?");
            params.add(filter.accountManagerId());
        }
        if (cursor != null) {
            sql.append(" AND (c.name, c.id) > (?, ?)");
            params.add(cursor.sortKey());
            params.add(cursor.id());
        }
        sql.append(" ORDER BY c.name, c.id LIMIT ?");
        params.add(fetchSize);

        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new Row(
                        rs.getLong("id"),
                        rs.getString("client_code"),
                        rs.getString("name"),
                        rs.getString("website_domain"),
                        (Long) rs.getObject("account_manager_id"),
                        rs.getString("manager_name"),
                        rs.getString("manager_role"),
                        rs.getString("support_plan"),
                        (Long) rs.getObject("sla_policy_id"),
                        rs.getString("timezone"),
                        rs.getString("status")))
                .list();
    }

    /** One client by id, for the single-client status setter's response. */
    List<Row> byIds(Collection<Long> clientIds) {
        if (clientIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT c.id, c.client_code, c.name, c.website_domain,
                               c.account_manager_id, u.full_name AS manager_name,
                               r.code AS manager_role, c.support_plan, c.sla_policy_id,
                               c.timezone, c.status
                        FROM clients c
                        LEFT JOIN users u ON u.id = c.account_manager_id
                        LEFT JOIN roles r ON r.id = u.role_id
                        WHERE c.id IN (:ids)
                        ORDER BY c.name, c.id
                        """)
                .param("ids", clientIds)
                .query((rs, n) -> new Row(
                        rs.getLong("id"),
                        rs.getString("client_code"),
                        rs.getString("name"),
                        rs.getString("website_domain"),
                        (Long) rs.getObject("account_manager_id"),
                        rs.getString("manager_name"),
                        rs.getString("manager_role"),
                        rs.getString("support_plan"),
                        (Long) rs.getObject("sla_policy_id"),
                        rs.getString("timezone"),
                        rs.getString("status")))
                .list();
    }

    /**
     * Open tickets per client — S-32's Open Tickets column.
     *
     * <p>"Open" is {@code statuses.is_terminal = 0}, joined rather than compared
     * against a hardcoded {@code 'CLOSED'}. B-003 seeds the vocabulary and S-13
     * lets an Admin add to it, so a literal here would silently stop counting the
     * day somebody adds a second terminal status.
     *
     * <p>Clients with none are absent from the map rather than present as zero;
     * the caller defaults them.
     */
    Map<Long, Long> openTicketCounts(Collection<Long> clientIds) {
        if (clientIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        jdbc.sql("""
                        SELECT t.client_id, COUNT(*) AS open_count
                        FROM tickets t
                        JOIN statuses s ON s.code = t.status
                        WHERE t.client_id IN (:ids) AND s.is_terminal = 0
                        GROUP BY t.client_id
                        """)
                .param("ids", clientIds)
                .query((rs, n) -> counts.put(rs.getLong("client_id"), rs.getLong("open_count")))
                .list();
        return counts;
    }

    /**
     * When each client last had a ticket raised — S-32's Last Ticket Date.
     *
     * <p>{@code date_reported}, not {@code created_at}: an imported or
     * email-raised ticket carries the date it was actually reported, and the
     * column S-32 names is the reporting date.
     */
    Map<Long, Instant> lastTicketDates(Collection<Long> clientIds) {
        if (clientIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Instant> dates = new HashMap<>();
        jdbc.sql("""
                        SELECT t.client_id, MAX(t.date_reported) AS last_reported
                        FROM tickets t
                        WHERE t.client_id IN (:ids)
                        GROUP BY t.client_id
                        """)
                .param("ids", clientIds)
                .query((rs, n) -> {
                    // Read as a LocalDateTime and stamp UTC explicitly, rather
                    // than rs.getTimestamp().toInstant().
                    //
                    // getTimestamp() with no Calendar resolves against the JVM
                    // default zone, and lands on the right instant here only
                    // because the datasource URL happens to carry
                    // connectionTimeZone=UTC. That is correctness resting on a
                    // query parameter somebody could drop while tidying a
                    // connection string, and the failure would be silent: every
                    // Last Ticket date off by the deployment's offset, on a
                    // column nobody reconciles.
                    //
                    // PLAN.md §3.1 says storage is UTC everywhere, so saying so
                    // here is both true and independent of the driver. B-023
                    // already lost a day to the mirror of this — 09:30 written,
                    // 15:00 read.
                    LocalDateTime last = rs.getObject("last_reported", LocalDateTime.class);
                    return last == null
                            ? null
                            : dates.put(rs.getLong("client_id"), last.toInstant(ZoneOffset.UTC));
                })
                .list();
        return dates;
    }

    /** The projects each client is mapped to — S-32's Projects column. */
    Map<Long, List<ClientDtos.ProjectRef>> projectsByClient(Collection<Long> clientIds) {
        if (clientIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ClientDtos.ProjectRef>> byClient = new HashMap<>();
        jdbc.sql("""
                        SELECT cp.client_id, p.id, p.project_code, p.name
                        FROM client_projects cp
                        JOIN projects p ON p.id = cp.project_id
                        WHERE cp.client_id IN (:ids)
                        ORDER BY p.name, p.id
                        """)
                .param("ids", clientIds)
                .query((rs, n) -> byClient
                        .computeIfAbsent(rs.getLong("client_id"), k -> new ArrayList<>())
                        .add(new ClientDtos.ProjectRef(
                                rs.getLong("id"),
                                rs.getString("project_code"),
                                rs.getString("name"))))
                .list();
        return byClient;
    }

    /**
     * Each client's live primary contact, for the grid's contact column and the
     * B-028 gate the ticket form will read.
     *
     * <p>Takes the lowest id where a client somehow has two. "At most one
     * primary" is a service-layer rule the schema cannot assert — see
     * {@code ClientContactRepository} — so a read that assumed uniqueness would
     * throw on a state the database permits.
     */
    Map<Long, ClientDtos.Contact> primaryContacts(Collection<Long> clientIds) {
        if (clientIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ClientDtos.Contact> byClient = new HashMap<>();
        jdbc.sql("""
                        SELECT cc.client_id, cc.id, cc.name, cc.email, cc.phone,
                               cc.is_primary, cc.receives_mail, cc.portal_access
                        FROM client_contacts cc
                        WHERE cc.client_id IN (:ids)
                          AND cc.is_primary = 1 AND cc.is_active = 1
                        ORDER BY cc.client_id, cc.id
                        """)
                .param("ids", clientIds)
                .query((rs, n) -> byClient.putIfAbsent(
                        rs.getLong("client_id"), readContact(rs)))
                .list();
        return byClient;
    }

    /**
     * One client's contacts, for the S-32 row-expand.
     *
     * <p>Active ones only, and CONVENTIONS.md §6 exempts this list from paging:
     * a client has a handful of contacts, and the expand renders all of them.
     */
    List<ClientDtos.Contact> contactsOf(long clientId) {
        return jdbc.sql("""
                        SELECT cc.id, cc.name, cc.email, cc.phone,
                               cc.is_primary, cc.receives_mail, cc.portal_access
                        FROM client_contacts cc
                        WHERE cc.client_id = ? AND cc.is_active = 1
                        ORDER BY cc.is_primary DESC, cc.name, cc.id
                        """)
                .param(clientId)
                .query((rs, n) -> readContact(rs))
                .list();
    }

    /**
     * B-026 · the project this client defaults to on the ticket form —
     * {@code client_projects.is_default}.
     *
     * <p>Null where the client is mapped to projects but none is flagged, which
     * is the state every imported client starts in and is <b>not</b> the same as
     * "mapped to nothing". The form renders the two differently, so collapsing
     * them into a zero here would be losing the distinction one layer too early.
     *
     * <p>Takes the lowest project id where two rows are somehow flagged. The
     * composite primary key makes the pair unique and nothing makes the flag so —
     * the same reason {@link #primaryContacts} takes the lowest contact id rather
     * than assuming a uniqueness the schema does not assert.
     */
    Long defaultProjectId(long clientId) {
        return jdbc.sql("""
                        SELECT cp.project_id
                        FROM client_projects cp
                        WHERE cp.client_id = ? AND cp.is_default = 1
                        ORDER BY cp.project_id
                        LIMIT 1
                        """)
                .param(clientId)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    /**
     * B-026 · how many live contacts a client has, and whether one of them is
     * primary.
     *
     * <p>One statement for both, because they are read together on every detail
     * and the second question is a filter on the first. {@code hasPrimary} is
     * B-028's gate — a client without one is not selectable on a ticket — and it
     * is <b>reported here, never enforced here</b>: enforcement belongs on the
     * ticket create path, which is where a caller can be told what to do about
     * it.
     *
     * <p>Active contacts only, agreeing with {@link #contactsOf} and
     * {@link #primaryContacts}. A client whose only primary contact has left
     * should read as having none, which is the true and useful answer.
     */
    record ContactSummary(int count, boolean hasPrimary) {
    }

    /**
     * B-026 · the two contract dates, read as {@link LocalDate} rather than
     * through the entity — <b>and this is a correctness fix, not a preference.</b>
     *
     * <h2>What was wrong</h2>
     *
     * <p>{@code contract_start = '2025-04-01'} written through JPA and read back
     * through JPA came out as <b>2025-03-31</b>. Not a time-zone misconfiguration
     * to be argued about: {@code ClientMasterIT} measured it with the JVM at UTC,
     * the connection at UTC and the MySQL session at {@code +00:00}, and the raw
     * column holding {@code 2025-04-01} the whole time. Against that same row and
     * that same connection:
     *
     * <pre>
     *   SELECT contract_start → getObject(LocalDate.class)  → 2025-04-01  ✅
     *   SELECT contract_start → getDate(...)                → 2025-03-31  ❌
     * </pre>
     *
     * <p>{@code spring.jpa.properties.hibernate.jdbc.time_zone: UTC} makes
     * Hibernate bind and read temporal columns through the
     * {@code java.sql.Date}/{@code Calendar} path, so every {@code LocalDate}
     * mapped through JPA loses the day on the way out. The write is unaffected —
     * the stored value is right — which is what makes it invisible: nothing looks
     * wrong until somebody compares a contract end date on screen with the
     * contract.
     *
     * <p>This is the same shape as the defect B-023 hit with {@code TIME} — 09:30
     * written, 15:00 read — and its resolution was the same one: take the
     * conversion out rather than configure around it.
     *
     * <h2>What is fixed here, and what is flagged</h2>
     *
     * <p>B-026 fixes it for the two columns it owns, by reading them here.
     * <b>{@code Holiday.holidayDate} has the same bug</b> — a {@code LocalDate}
     * mapped through JPA on a table the working calendar reads, which means every
     * org holiday can be a day out, which means {@code WorkingHoursService} can
     * treat the wrong day as non-working and every SLA crossing it is wrong.
     * {@code resource_leaves} is the same. **Flagged for Stream B (B-023's
     * follow-up) and for Stream A**, whose {@code application.yml} carries the
     * setting; fixing it there is one property and a re-run of every temporal
     * test, which is not a change to make on a masters branch on the way past.
     *
     * <p>{@code Project.startDate}/{@code endDate} are unaffected only because
     * B-016 happened to write and read them through {@link JdbcClient} already.
     */
    record ContractDates(LocalDate start, LocalDate end) {
    }

    ContractDates contractDates(long clientId) {
        return jdbc.sql("""
                        SELECT c.contract_start, c.contract_end
                        FROM clients c
                        WHERE c.id = ?
                        """)
                .param(clientId)
                // getObject(LocalDate.class), never getDate — see the javadoc.
                .query((rs, n) -> new ContractDates(
                        rs.getObject("contract_start", LocalDate.class),
                        rs.getObject("contract_end", LocalDate.class)))
                .single();
    }

    ContactSummary contactSummary(long clientId) {
        return jdbc.sql("""
                        SELECT COUNT(*)                                     AS contact_count,
                               COALESCE(MAX(cc.is_primary), 0)              AS has_primary
                        FROM client_contacts cc
                        WHERE cc.client_id = ? AND cc.is_active = 1
                        """)
                .param(clientId)
                .query((rs, n) -> new ContactSummary(
                        rs.getInt("contact_count"),
                        rs.getBoolean("has_primary")))
                .single();
    }

    private static ClientDtos.Contact readContact(java.sql.ResultSet rs)
            throws java.sql.SQLException {

        return new ClientDtos.Contact(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getBoolean("is_primary"),
                // The column is `receives_mail`; the contract calls it
                // `notificationOptIn`. Renamed here rather than in the contract,
                // which D's mail engine already reads.
                rs.getBoolean("receives_mail"),
                rs.getBoolean("portal_access"));
    }
}
