package com.edunext.edutrack.api.feature.tickets.comments;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * C-030 · turns {@code @handle} candidates into people who may actually be
 * mentioned on this ticket.
 *
 * <h2>Project membership is the membership check</h2>
 *
 * <p>C-030's own wording is "type-ahead over <b>project members</b>", and this
 * is the query that makes that a rule rather than a UI convenience. D-052 made
 * the equivalent argument for chat and narrowed to thread participants:
 * notifying an outsider deep-links them to a thread that answers 404, and turns
 * {@code @} into a probe for which usernames exist. A ticket's analogue is its
 * project — {@code ScopeResolver} scopes PM and Support by exactly this table,
 * so a project member is someone who can already open the ticket the
 * notification links to.
 *
 * <p>Anything that does not resolve stays plain text, which is also what it
 * looks like to a reader. There is deliberately no way for the caller to learn
 * <em>why</em> a handle did not resolve — "no such user" and "not on this
 * project" are the same answer, for the reason A-035 returns 404 rather than
 * 403.
 *
 * <h2>Why JDBC rather than a repository method</h2>
 *
 * <p>{@code project_members} has no entity in {@code domain} and adding one for
 * a two-column join would be a change to Stream A's package. The join is read-only
 * and touches no ticket table, so it is stated here in the feature that needs
 * it — the same shape {@code ChatRepository.mentionableParticipants} uses.
 */
@Component
class CommentMentions {

    /**
     * {@code pm.is_active} and {@code u.is_active} are both checked, and they
     * mean different things: the first is "taken off this project", the second
     * is "left the company". Either one makes a mention wrong, and a leaver
     * still holding a mailbox is the case that would otherwise send email into
     * the void indefinitely.
     *
     * <p>Ordered by id so a body naming five people notifies in a stable order,
     * which is what makes the tests assertable and the logs readable.
     */
    private static final String RESOLVE_PROJECT_MEMBERS = """
            SELECT u.id       AS id,
                   u.username AS username,
                   u.full_name AS full_name,
                   u.email    AS email
              FROM users u
              JOIN project_members pm ON pm.user_id = u.id
             WHERE pm.project_id = :projectId
               AND pm.is_active = 1
               AND u.is_active = 1
               AND u.username IN (:handles)
             ORDER BY u.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    CommentMentions(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param handles lower-cased usernames from {@link CommentMentionParser}.
     *                The comparison is the column's own collation
     *                ({@code utf8mb4_0900_ai_ci}), which is case-insensitive, so
     *                the lower-casing does not have to be undone here
     * @return the subset that names an active member of {@code projectId},
     *         possibly empty, never null
     */
    List<MentionedUser> resolveProjectMembers(long projectId, List<String> handles) {
        if (handles.isEmpty()) {
            // An empty IN (…) list is a syntax error, and there is nothing to ask.
            return List.of();
        }
        return jdbc.query(RESOLVE_PROJECT_MEMBERS,
                new MapSqlParameterSource()
                        .addValue("projectId", projectId)
                        .addValue("handles", handles),
                (rs, row) -> new MentionedUser(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("full_name"),
                        rs.getString("email")));
    }

    /**
     * @param email may be blank in principle — {@code users.email} is
     *              {@code NOT NULL}, but the notifier treats an unusable address
     *              as "bell only" rather than trusting the constraint, because
     *              the alternative is an {@link IllegalArgumentException} out of
     *              {@code NewMail} on somebody's comment
     */
    record MentionedUser(long id, String username, String fullName, String email) {
    }
}
