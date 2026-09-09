package com.edunext.edutrack.api.feature.portal.onboarding;

import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.PageMeta;
import com.edunext.edutrack.domain.onboarding.ObPrereqActorType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * C-121 · CP-04's comment thread — {@code ob_prereq_comments}, read here and
 * appended through {@code ObPrereqTaskService#addComment} (see that method's
 * own javadoc: "the author comes from the token").
 *
 * <p>{@code ObPrereqThreadRepository}'s own read SQL, read again here rather
 * than reused: that class and the {@code ObClientPrereqDtos} shapes it
 * returns are both package-private in {@code feature.onboarding.prereqs}, a
 * staff-owned package. <b>The insert is not duplicated here</b> — on the
 * append-only rule's own "one door" principle, a second class with its own
 * {@code INSERT} into {@code ob_prereq_comments} would be a second door into
 * a table that is supposed to have exactly one. This class only reads, plus
 * the one render-back-by-id helper the 201 response needs after that door
 * has already written the row.
 */
@Repository
class PortalPrereqThreadReader {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final JdbcClient jdbc;

    PortalPrereqThreadReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    PortalOnboardingDtos.PortalPrereqCommentListResponse comments(long prereqTaskId, String cursor, Integer limit) {
        int size = pageSize(limit);
        Long after = afterId(cursor);

        List<PortalOnboardingDtos.PortalPrereqComment> rows = jdbc.sql("""
                        SELECT c.id, c.prereq_task_id, c.author_type, c.body, c.is_system, c.created_at,
                               u.id AS staffId, u.full_name AS staffName,
                               k.id AS contactId, k.name AS contactName, k.email AS contactEmail
                          FROM ob_prereq_comments c
                          LEFT JOIN users u ON u.id = c.author_user_id
                          LEFT JOIN ob_client_contacts k ON k.id = c.author_contact_id
                         WHERE c.prereq_task_id = :taskId
                           AND (:afterId IS NULL OR c.id > :afterId)
                         ORDER BY c.id ASC
                         LIMIT :limit
                        """)
                .param("taskId", prereqTaskId)
                .param("afterId", after)
                .param("limit", size + 1)
                .query(this::map)
                .list();

        boolean hasMore = rows.size() > size;
        List<PortalOnboardingDtos.PortalPrereqComment> page = trim(rows, size);
        String next = hasMore && !page.isEmpty()
                ? new Cursor(String.valueOf(page.get(page.size() - 1).id()), page.get(page.size() - 1).id()).encode()
                : null;
        return new PortalOnboardingDtos.PortalPrereqCommentListResponse(page, new PageMeta(next, hasMore));
    }

    /** One comment, for the 201 body after {@code ObPrereqTaskService#addComment} has written it. */
    PortalOnboardingDtos.PortalPrereqComment comment(long id) {
        return jdbc.sql("""
                        SELECT c.id, c.prereq_task_id, c.author_type, c.body, c.is_system, c.created_at,
                               u.id AS staffId, u.full_name AS staffName,
                               k.id AS contactId, k.name AS contactName, k.email AS contactEmail
                          FROM ob_prereq_comments c
                          LEFT JOIN users u ON u.id = c.author_user_id
                          LEFT JOIN ob_client_contacts k ON k.id = c.author_contact_id
                         WHERE c.id = :id
                        """)
                .param("id", id)
                .query(this::map)
                .single();
    }

    private PortalOnboardingDtos.PortalPrereqComment map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new PortalOnboardingDtos.PortalPrereqComment(
                rs.getLong("id"),
                rs.getLong("prereq_task_id"),
                ObPrereqActorType.valueOf(rs.getString("author_type")),
                rs.getObject("staffId") == null ? null
                        : new PortalOnboardingDtos.PortalUserRef(rs.getLong("staffId"), rs.getString("staffName")),
                rs.getObject("contactId") == null ? null
                        : new PortalOnboardingDtos.PortalContactRef(rs.getLong("contactId"),
                                rs.getString("contactName"), rs.getString("contactEmail")),
                rs.getString("body"),
                rs.getBoolean("is_system"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static <T> List<T> trim(List<T> rows, int size) {
        return rows.size() > size ? new ArrayList<>(rows.subList(0, size)) : rows;
    }

    private static int pageSize(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static Long afterId(String cursor) {
        Cursor decoded = Cursor.decode(cursor);
        return decoded == null ? null : decoded.id();
    }
}
