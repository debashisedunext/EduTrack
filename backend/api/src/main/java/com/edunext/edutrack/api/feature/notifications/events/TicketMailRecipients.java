package com.edunext.edutrack.api.feature.notifications.events;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * D-037 · who each §4B.6 event goes to.
 *
 * <p>The recipient lists are the blueprint's, read off its table rather than
 * chosen here — "Ticket created and assigned → Assignee", "Comment added →
 * Assignee, watchers", "Ticket closed → Reporter, client contact, watchers",
 * "Reopened → New assignee, PM". Each method is one of those phrases.
 *
 * <h2>Why this is a query rather than fields on {@code Ticket}</h2>
 *
 * <p>Watchers and project managers are rows in other tables, and the client
 * contact is not a user at all. Loading them through JPA associations would
 * make every ticket write pay for relationships only a notification needs, on
 * the write path the mail must never slow down (§4B.6: "queued, never sent
 * inline — a slow SMTP server must never slow down a handoff").
 *
 * <h2>Inactive people are excluded everywhere, and that is not an optimisation</h2>
 *
 * <h2>A missing address is not a missing recipient</h2>
 *
 * <p>No query here filters on the email column. {@code users.email} is
 * {@code NOT NULL UNIQUE}, so for a user it cannot be absent — but the guard
 * that matters is a different one: the bell entry and the mail are separate
 * channels, and a recipient with no usable address must still get the bell.
 * {@link TicketEventNotifier} checks the address at the point it enqueues,
 * which is where {@code CommentMentionNotifier} already puts it. Filtering here
 * would silently cost somebody a notification to save a mail.
 *
 * <p>The client contact is the exception and filters on the address in SQL,
 * because there is no bell entry to lose — a contact has no account.
 *
 */
@Component
public class TicketMailRecipients {

    /**
     * Someone a mail can be addressed to.
     *
     * @param userId null when the recipient is a client contact — the case
     *               {@link com.edunext.edutrack.domain.outbox.NewMail} documents
     *               as normal, and the reason its {@code toUserId} is nullable
     */
    public record Recipient(Long userId, String displayName, String email) {
    }

    private static final String ACTIVE_USER = """
            SELECT u.id, u.full_name, u.email
              FROM users u
             WHERE u.id = :userId
               AND u.is_active = 1
            """;

    private final JdbcClient db;

    TicketMailRecipients(JdbcClient db) {
        this.db = db;
    }

    /** The current assignee, or empty when the ticket is unassigned or they have left. */
    public Optional<Recipient> assignee(long ticketId) {
        return one("""
                SELECT u.id, u.full_name, u.email
                  FROM tickets t
                  JOIN users u ON u.id = t.assigned_to
                 WHERE t.id = :ticketId
                   AND u.is_active = 1
                """, ticketId);
    }

    /** Who raised it. §4B.6 mails the reporter on close, not the assignee. */
    public Optional<Recipient> reporter(long ticketId) {
        return one("""
                SELECT u.id, u.full_name, u.email
                  FROM tickets t
                  JOIN users u ON u.id = t.reported_by
                 WHERE t.id = :ticketId
                   AND u.is_active = 1
                """, ticketId);
    }

    /** §7.5's watcher list — "multi-select; they get notifications too". */
    public List<Recipient> watchers(long ticketId) {
        return many("""
                SELECT u.id, u.full_name, u.email
                  FROM ticket_watchers w
                  JOIN users u ON u.id = w.user_id
                 WHERE w.ticket_id = :ticketId
                   AND u.is_active = 1
                """, ticketId);
    }

    /**
     * The client contact the ticket names, when they are one this system may
     * write to.
     *
     * <p><strong>{@code receives_mail} is checked and is the whole point of the
     * column.</strong> A contact is on a client's record so the desk knows who
     * to speak to; that is not consent to be mailed automatically every time a
     * ticket closes, and B-027's master has a per-contact flag for exactly this
     * distinction. Ignoring it would make the one recipient who is <em>outside
     * the organisation</em> the one recipient with no way to opt out — D-036's
     * preference matrix covers users only, because a client contact has no
     * account to hold a preference on.
     *
     * <p>Returned with a null {@code userId}, which {@code NewMail} allows and
     * documents. The bell entry is skipped for them: there is nowhere to show it.
     */
    public Optional<Recipient> clientContact(long ticketId) {
        return db.sql("""
                        SELECT c.name, c.email
                          FROM tickets t
                          JOIN client_contacts c ON c.id = t.client_contact_id
                         WHERE t.id = :ticketId
                           AND c.is_active = 1
                           AND c.receives_mail = 1
                           AND c.email IS NOT NULL
                           AND c.email <> ''
                        """)
                .param("ticketId", ticketId)
                .query((rs, rowNum) -> new Recipient(null, rs.getString("name"), rs.getString("email")))
                .optional();
    }

    /**
     * The project's managers — §4B.6's "cc PM" on a reopen.
     *
     * <p>Read from {@code project_members.role_in_project}, not from
     * {@code users.role}: §7.3 lets somebody be a PM on one project and a
     * developer on another, and mailing every global PM about a reopen on a
     * project they have nothing to do with is how a mail rule gets written that
     * hides the ones that matter.
     */
    public List<Recipient> projectManagers(long ticketId) {
        return many("""
                SELECT u.id, u.full_name, u.email
                  FROM tickets t
                  JOIN project_members pm ON pm.project_id = t.project_id
                  JOIN users u            ON u.id = pm.user_id
                 WHERE t.id = :ticketId
                   AND pm.role_in_project = 'PM'
                   AND pm.is_active = 1
                   AND u.is_active = 1
                """, ticketId);
    }

    /** A named user, when a caller already knows the id — the new assignee on a reopen. */
    public Optional<Recipient> user(long userId) {
        return db.sql(ACTIVE_USER)
                .param("userId", userId)
                .query((rs, rowNum) -> new Recipient(
                        rs.getLong("id"), rs.getString("full_name"), rs.getString("email")))
                .optional();
    }

    private Optional<Recipient> one(String sql, long ticketId) {
        return db.sql(sql).param("ticketId", ticketId).query(TicketMailRecipients::map).optional();
    }

    private List<Recipient> many(String sql, long ticketId) {
        return db.sql(sql).param("ticketId", ticketId).query(TicketMailRecipients::map).list();
    }

    private static Recipient map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Recipient(rs.getLong("id"), rs.getString("full_name"), rs.getString("email"));
    }
}
