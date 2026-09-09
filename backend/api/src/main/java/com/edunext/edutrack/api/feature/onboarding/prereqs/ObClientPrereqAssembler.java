package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqTask;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B-125 · builds the instance responses from the task rows plus the three
 * tables they borrow from — {@code users} for the staff refs,
 * {@code ob_client_contacts} for the client ones, and {@code ob_attachments}
 * for the submission list and the per-task counts.
 *
 * <p>A class of its own for {@link ObPrereqTemplateAssembler}'s reason: two
 * controllers compose the same shapes, and the alternative was each holding
 * four repositories.
 *
 * <p><b>Counts come from one grouped query, not one per task.</b> The
 * checklist is not paginated — the contract's own decision, because the gate
 * arithmetic needs the complete set — so a per-task count would be the N+1
 * this read is most likely to become, on a screen that is the module's most
 * opened.
 */
@Component
class ObClientPrereqAssembler {

    private final JdbcClient jdbc;
    private final UserRepository users;

    ObClientPrereqAssembler(JdbcClient jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    /** One task, with its counts resolved individually. */
    ObClientPrereqDtos.ObClientPrereqTaskDto task(ObClientPrereqTask task) {
        return task(task, countsFor(List.of(task.getId())),
                referenceDocsFor(templateTaskIds(List.of(task))), Instant.now());
    }

    List<ObClientPrereqDtos.ObClientPrereqTaskDto> tasks(List<ObClientPrereqTask> rows) {
        List<Long> ids = rows.stream().map(ObClientPrereqTask::getId).toList();
        Map<Long, Counts> counts = countsFor(ids);
        // One statement for the whole checklist, not one per row: five tasks
        // asking for their own documents is five round trips for a panel that
        // draws in one paint. `countsFor` above batches for the same reason.
        Map<Long, List<ObPrereqTemplateDtos.ObPrereqTemplateTaskDoc>> docs =
                referenceDocsFor(templateTaskIds(rows));
        Instant now = Instant.now();

        List<ObClientPrereqDtos.ObClientPrereqTaskDto> out = new ArrayList<>();
        for (ObClientPrereqTask row : rows) {
            out.add(task(row, counts, docs, now));
        }
        return out;
    }

    private static List<Long> templateTaskIds(List<ObClientPrereqTask> rows) {
        // An ad-hoc task has no master row to read through, so it contributes
        // no id and answers with an empty list below.
        return rows.stream()
                .map(ObClientPrereqTask::getTemplateTaskId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private ObClientPrereqDtos.ObClientPrereqTaskDto task(
            ObClientPrereqTask row, Map<Long, Counts> counts,
            Map<Long, List<ObPrereqTemplateDtos.ObPrereqTemplateTaskDoc>> docs, Instant now) {

        Counts c = counts.getOrDefault(row.getId(), Counts.NONE);
        List<ObPrereqTemplateDtos.ObPrereqTemplateTaskDoc> refs = row.getTemplateTaskId() == null
                ? List.of()
                : docs.getOrDefault(row.getTemplateTaskId(), List.of());
        return ObClientPrereqDtos.ObClientPrereqTaskDto.of(row, now,
                userRef(row.getVerifiedBy()), userRef(row.getSkippedBy()),
                c.comments(), c.attachments(), refs);
    }

    /**
     * Every reference document for a set of master tasks, keyed by the task
     * they hang off — {@link #referenceDocsOf}'s query widened to an
     * {@code IN}, and its javadoc's reasoning applies here unchanged.
     */
    private Map<Long, List<ObPrereqTemplateDtos.ObPrereqTemplateTaskDoc>> referenceDocsFor(
            List<Long> templateTaskIds) {

        if (templateTaskIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ObPrereqTemplateDtos.ObPrereqTemplateTaskDoc>> byTask = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT d.id, d.template_task_id, d.label, d.attachment_id,
                               a.file_name, a.size_bytes
                          FROM ob_prereq_template_task_docs d
                          LEFT JOIN ob_attachments a ON a.id = d.attachment_id
                         WHERE d.template_task_id IN (:taskIds)
                         ORDER BY d.template_task_id ASC, d.sequence ASC, d.id ASC
                        """)
                .param("taskIds", templateTaskIds)
                .query((rs, n) -> new ObPrereqTemplateDtos.ObPrereqTemplateTaskDoc(
                        rs.getLong("id"),
                        rs.getLong("template_task_id"),
                        rs.getString("label"),
                        rs.getLong("attachment_id"),
                        rs.getString("file_name"),
                        rs.getObject("size_bytes") == null ? null : rs.getLong("size_bytes")))
                .list()
                .forEach(doc -> byTask
                        .computeIfAbsent(doc.templateTaskId(), key -> new ArrayList<>())
                        .add(doc));
        return byTask;
    }

    /**
     * The client's submissions on one task — {@code ob_attachments} with
     * {@code kind = SUBMISSION}, tombstoned rows excluded.
     *
     * <p><b>{@code scan_status} is not filtered here and that is deliberate.</b>
     * A-102's rule is that nothing may be *served* while a file is PENDING or
     * INFECTED, which is about the download route rather than the listing: a
     * client who uploaded a file and cannot see it in the list will upload it
     * again. The list says the file exists; the download decides whether its
     * bytes may leave.
     */
    List<ObClientPrereqDtos.ObPrereqSubmissionFile> submissionsOf(long prereqTaskId) {
        return jdbc.sql("""
                        SELECT id, file_name, size_bytes, uploaded_by_type, created_at
                          FROM ob_attachments
                         WHERE prereq_task_id = :taskId
                           AND kind = 'SUBMISSION'
                           AND deleted_at IS NULL
                         ORDER BY id ASC
                        """)
                .param("taskId", prereqTaskId)
                .query((rs, n) -> new ObClientPrereqDtos.ObPrereqSubmissionFile(
                        rs.getLong("id"),
                        rs.getString("file_name"),
                        rs.getLong("size_bytes"),
                        com.edunext.edutrack.domain.onboarding.ObPrereqActorType
                                .valueOf(rs.getString("uploaded_by_type")),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /**
     * The admin's reference documents, read through the task's
     * {@code templateTaskId}.
     *
     * <p><b>Read through, where every other field on the row was copied.</b>
     * The distinction is what each is for: the wording is what the client
     * agreed to and must be frozen, while a reference document is a
     * convenience — a specimen form the admin may replace with a clearer one,
     * and the client benefits from the newer file. Freezing these would mean
     * duplicating every file per client at boarding.
     *
     * <p>Empty on an ad-hoc task, which has no master row to read through.
     */
    List<ObPrereqTemplateDtos.ObPrereqTemplateTaskDoc> referenceDocsOf(Long templateTaskId) {
        if (templateTaskId == null) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT d.id, d.template_task_id, d.label, d.attachment_id,
                               a.file_name, a.size_bytes
                          FROM ob_prereq_template_task_docs d
                          LEFT JOIN ob_attachments a ON a.id = d.attachment_id
                         WHERE d.template_task_id = :taskId
                         ORDER BY d.sequence ASC, d.id ASC
                        """)
                .param("taskId", templateTaskId)
                .query((rs, n) -> new ObPrereqTemplateDtos.ObPrereqTemplateTaskDoc(
                        rs.getLong("id"),
                        rs.getLong("template_task_id"),
                        rs.getString("label"),
                        rs.getLong("attachment_id"),
                        rs.getString("file_name"),
                        rs.getObject("size_bytes") == null ? null : rs.getLong("size_bytes")))
                .list();
    }

    ObClientPrereqDtos.UserRef userRef(Long userId) {
        if (userId == null) {
            return null;
        }
        return users.findById(userId)
                .map(u -> ObClientPrereqDtos.UserRef.of(u.getId(), u.getFullName()))
                .orElse(null);
    }

    ObClientPrereqDtos.ObContactRef contactRef(Long contactId) {
        if (contactId == null) {
            return null;
        }
        return jdbc.sql("SELECT id, name, email FROM ob_client_contacts WHERE id = :id")
                .param("id", contactId)
                .query((rs, n) -> new ObClientPrereqDtos.ObContactRef(
                        rs.getLong("id"), rs.getString("name"), rs.getString("email")))
                .optional()
                .orElse(null);
    }

    private record Counts(int comments, int attachments) {
        static final Counts NONE = new Counts(0, 0);
    }

    private Map<Long, Counts> countsFor(List<Long> taskIds) {
        Map<Long, Counts> byTask = new HashMap<>();
        if (taskIds.isEmpty()) {
            return byTask;
        }

        Map<Long, Integer> comments = new HashMap<>();
        jdbc.sql("""
                        SELECT prereq_task_id AS taskId, COUNT(*) AS n
                          FROM ob_prereq_comments
                         WHERE prereq_task_id IN (:ids)
                         GROUP BY prereq_task_id
                        """)
                .param("ids", taskIds)
                .query((rs, n) -> comments.put(rs.getLong("taskId"), rs.getInt("n")))
                .list();

        Map<Long, Integer> attachments = new HashMap<>();
        jdbc.sql("""
                        SELECT prereq_task_id AS taskId, COUNT(*) AS n
                          FROM ob_attachments
                         WHERE prereq_task_id IN (:ids)
                           AND deleted_at IS NULL
                         GROUP BY prereq_task_id
                        """)
                .param("ids", taskIds)
                .query((rs, n) -> attachments.put(rs.getLong("taskId"), rs.getInt("n")))
                .list();

        for (Long id : taskIds) {
            byTask.put(id, new Counts(
                    comments.getOrDefault(id, 0), attachments.getOrDefault(id, 0)));
        }
        return byTask;
    }
}
