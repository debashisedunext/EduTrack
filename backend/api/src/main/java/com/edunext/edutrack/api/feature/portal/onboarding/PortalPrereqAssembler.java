package com.edunext.edutrack.api.feature.portal.onboarding;

import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqTask;
import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * C-121 · {@code ObClientPrereqTask} rows into {@link
 * PortalOnboardingDtos.PortalPrereqTask} — the portal's own mapping, on
 * {@code ObClientPrereqAssembler}'s shape but reading the public domain
 * entity directly rather than that package-private class's DTOs.
 */
@Component
class PortalPrereqAssembler {

    private final JdbcClient jdbc;
    private final UserRepository users;

    PortalPrereqAssembler(JdbcClient jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    PortalOnboardingDtos.PortalPrereqTask task(ObClientPrereqTask task) {
        return task(task, countsFor(List.of(task.getId())), Instant.now());
    }

    List<PortalOnboardingDtos.PortalPrereqTask> tasks(List<ObClientPrereqTask> rows) {
        List<Long> ids = rows.stream().map(ObClientPrereqTask::getId).toList();
        Map<Long, Counts> counts = countsFor(ids);
        Instant now = Instant.now();
        List<PortalOnboardingDtos.PortalPrereqTask> out = new ArrayList<>();
        for (ObClientPrereqTask row : rows) {
            out.add(task(row, counts, now));
        }
        return out;
    }

    /**
     * The gate every journey on this client shares — {@code
     * ObJourneyGateReader}'s own rule, one read over: any {@code LOCKED}
     * journey (or no journeys at all) means the gate has not opened.
     */
    ObGateStatus gateStatusOf(List<PortalOnboardingDtos.PortalJourneyStrip> journeys) {
        if (journeys.isEmpty()) {
            return ObGateStatus.LOCKED;
        }
        boolean anyLocked = journeys.stream().anyMatch(j -> "LOCKED".equals(j.gateStatus()));
        return anyLocked ? ObGateStatus.LOCKED : ObGateStatus.OPEN;
    }

    private PortalOnboardingDtos.PortalPrereqTask task(
            ObClientPrereqTask row, Map<Long, Counts> counts, Instant now) {

        Counts c = counts.getOrDefault(row.getId(), Counts.NONE);
        return new PortalOnboardingDtos.PortalPrereqTask(
                row.getId(), row.getObClientId(), row.getTemplateTaskId(), row.getSequence(),
                row.getTitle(), row.getDescription(),
                row.isMandatory(), row.isAdHoc(),
                row.getStatus(), row.getDueAt(), row.isOverdue(now),
                row.getSubmittedAt(), row.getSubmittedVia(),
                row.getVerifiedAt(), userRef(row.getVerifiedBy()),
                row.getSkippedAt(), userRef(row.getSkippedBy()), row.getSkipReason(),
                c.comments(), c.attachments());
    }

    private PortalOnboardingDtos.PortalUserRef userRef(Long userId) {
        if (userId == null) {
            return null;
        }
        return users.findById(userId)
                .map(u -> new PortalOnboardingDtos.PortalUserRef(u.getId(), u.getFullName()))
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
            byTask.put(id, new Counts(comments.getOrDefault(id, 0), attachments.getOrDefault(id, 0)));
        }
        return byTask;
    }
}
