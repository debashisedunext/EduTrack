package com.edunext.edutrack.api.feature.fixtures;

import com.edunext.edutrack.domain.workflow.WorkflowStage;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * B-007 · the reference data {@link ReferenceDataFixture} builds, handed to
 * {@link TicketFixtureGenerator} so it never has to look any of it up twice.
 *
 * <p>Every id here was written by {@link ReferenceDataFixture} in the same
 * fixture run — this is a plan for {@link TicketFixtureGenerator} to walk,
 * not a general-purpose read model.
 *
 * @param projects            the 3 fixture projects (CRM, PAY, WEB), in
 *                             creation order
 * @param usersByRole         role code (ADMIN|PM|SUPPORT|DEVELOPER|QA|DEPLOYMENT)
 *                             to the fixture user ids holding it
 * @param projectMembers      project id to the user ids active on it —
 *                             the pool {@link TicketFixtureGenerator} assigns
 *                             and reports tickets from
 * @param projectManager      project id to its PM's user id
 * @param clientsByProject    project id to the client ids reachable from it
 *                             (mirrors {@code client_projects})
 * @param primaryContactByClient client id to its primary contact's id —
 *                             the one {@code client_contacts} row every
 *                             fixture client is guaranteed to have (B-028)
 * @param stagesByTemplate    workflow template id to its stages, left to
 *                             right — avoids re-querying {@code workflow_stages}
 *                             200 times for 3 templates
 * @param templateIdByTaskType task type code to the workflow template it
 *                             maps onto, per the B-004 seed's own template
 *                             descriptions
 * @param resolutionHoursByLevel priority level to the org-wide
 *                             {@code sla_policies} resolution target, working
 *                             hours — what the breach injector compares the
 *                             walk's elapsed time against
 */
record FixtureContext(
        List<ProjectRef> projects,
        Map<String, List<Long>> usersByRole,
        Map<Long, List<Long>> projectMembers,
        Map<Long, Long> projectManager,
        Map<Long, List<Long>> clientsByProject,
        Map<Long, Long> primaryContactByClient,
        Map<Long, List<WorkflowStage>> stagesByTemplate,
        Map<String, Long> templateIdByTaskType,
        Map<String, BigDecimal> resolutionHoursByLevel) {

    /** One fixture project — just enough to drive ticket creation. */
    record ProjectRef(Long id, String code, String name) {
    }
}
