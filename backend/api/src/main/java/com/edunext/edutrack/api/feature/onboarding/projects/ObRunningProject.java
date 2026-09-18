package com.edunext.edutrack.api.feature.onboarding.projects;

import java.time.LocalDate;

/**
 * One running project, as a package outside this one is allowed to see it.
 *
 * <h2>Why this exists rather than {@code ObProjectDtos.ObProjectSummary}</h2>
 *
 * <p>OB-02's project board needs every project's completion date, its delay and
 * its progress — the three figures {@link ObProjectService} already computes
 * through the working calendar for the Projects grid. The dashboard must read
 * <b>those</b> figures rather than compute its own, or the board and the grid
 * will one day disagree about whether a project is late, and both will be able
 * to show their working.
 *
 * <p>The grid's own DTO is not the way to hand them over: {@code ObProjectDtos}
 * is this feature's wire shape, package-private on purpose, and making it
 * public would export the whole {@code /onboarding/projects} contract as a
 * Java API that any other feature could then depend on a field of. This record
 * is the deliberate opposite — a narrow, named, read-only projection whose
 * fields are exactly what one caller was given, so widening it later is a
 * decision somebody has to take rather than a door already open.
 *
 * <p>It is a projection, not the grid row: no stage list, no module services,
 * no {@code statusReason}. A caller wanting those wants
 * {@code GET /onboarding/projects} and should call it.
 *
 * @param currentStage        the stage holding the lowest-sequence task
 *                            actually running, or null — a locked gate, a
 *                            project held behind a sibling, every task blocked.
 *                            {@code gateStatus} is beside it so a reader can
 *                            tell which
 * @param delayedByDays       ceiling working days past the earliest overdue
 *                            <b>task</b>'s due date. <b>Null, not zero</b>, when
 *                            nothing is overdue — a different fact from the
 *                            project's completion date, and the board reports
 *                            both rather than folding one into the other
 * @param tentativeCompletion {@code startDate} plus the project's critical-path
 *                            TAT, walked through the working calendar. Null
 *                            when the project has no instantiated task to
 *                            budget from, which is what makes a completion date
 *                            a guess rather than a commitment
 * @param totalTatDays        that critical path in working days, so a caller can
 *                            express elapsed time as a share of the budget
 */
public record ObRunningProject(long id, String name, LocalDate startDate,
                               long clientId, String clientName, String clientCode, String clientCity,
                               long productId, String productCode, String productName,
                               Long salesPersonId, String salesPersonName,
                               Long implementorId, String implementorName,
                               String gateStatus, String currentStage,
                               Integer delayedByDays, LocalDate tentativeCompletion,
                               int totalTatDays) {
}
