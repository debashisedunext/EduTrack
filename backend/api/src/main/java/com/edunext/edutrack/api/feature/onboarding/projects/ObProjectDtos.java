package com.edunext.edutrack.api.feature.onboarding.projects;

import com.edunext.edutrack.common.pagination.PageMeta;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The wire shapes for {@code /onboarding/projects}, matching
 * {@code contracts/openapi.yaml}'s {@code ObProject} family.
 *
 * <p>{@code ObProjectDetail} restates {@code ObProjectSummary}'s fields rather
 * than extending it, on {@code ObClientDtos}'s own precedent: the contract
 * composes the two with {@code allOf}, Java has no such thing, and a supertype
 * would put the list row's fields behind an inheritance relationship that
 * exists for no other reason.
 */
final class ObProjectDtos {

    private ObProjectDtos() {
    }

    /** {@code UserRef} — duplicated per package on {@code ObClientDtos.UserRef}'s own precedent. */
    record UserRef(long id, String displayName) {

        static UserRef of(Long id, String displayName) {
            return id == null ? null : new UserRef(id, displayName);
        }
    }

    /** {@code ObProductRef} — three fields, inlined into every project row. */
    record ObProductRef(long id, String code, String name) {
    }

    /**
     * {@code ObClientRef} — the four fields the Clients master now holds.
     *
     * <p>The whole client, in other words, which is the point of having shrunk
     * it: the Projects grid needs the client's name, and a reader looking at
     * two projects for similarly named trusts needs the code and the city to
     * tell them apart. There is nothing else on the row worth withholding — no
     * PAN, no address history, none of the identity data
     * {@code ObClientDtos.ObClientSummary} keeps off a list.
     */
    record ObClientRef(long id, String name, String clientCode, String city) {
    }

    /**
     * {@code ObProjectStage} — one implementation stage of this project, as the
     * grid's "stages completed out of total" counts it.
     *
     * <p><b>Keyed by the implementation stage, not by the stage group.</b> A
     * project boarded through two Module Services has two "Configuration"
     * groups — one per service — and counting them separately would tell a
     * reader the project has eight stages when the master defines six. The
     * roll-up folds them onto {@code ob_journey_template_stages.implementation_stage_id},
     * so Configuration is one stage that is complete when both services'
     * Configuration tasks are.
     *
     * @param isComplete every task in this stage is {@code DONE} or
     *                   {@code SKIPPED}. A skipped task counts as settled
     *                   rather than outstanding, which is the same reading
     *                   {@code ribbonSteps.ts} gives it on the ribbon
     * @param isCurrent  this stage holds the lowest-sequence task that is
     *                   actually running — at most one stage per project
     */
    record ObProjectStage(long stageKey, String name, int sequence, int taskCount,
                          int tasksOutstanding, boolean isComplete, boolean isCurrent) {
    }

    /**
     * {@code ObProjectSummary} — one row of the Projects grid.
     *
     * @param currentStage           the running stage's name, or null. Null is
     *                               ordinary rather than exceptional: a project
     *                               whose gate is still locked has nothing
     *                               running, and neither has one whose every
     *                               task is blocked. {@code gateStatus} is
     *                               beside it so the grid can say which
     * @param stagesComplete         of {@code stagesTotal}. Both zero for a
     *                               project whose journeys have no steps, which
     *                               is a misconfigured Module Service rather
     *                               than a finished project — the grid renders
     *                               0/0 rather than 100%
     * @param delayedByDays          working days past the earliest overdue
     *                               task's due date, through
     *                               {@code WorkingHoursService}. <b>Null, not
     *                               zero, when the project is not late</b> —
     *                               and null for every project whose status
     *                               does not accrue delay, so a completed
     *                               project does not keep counting
     * @param tentativeCompletion    {@code startDate} plus the project's total
     *                               TAT in working days. Null when the project
     *                               has no instantiated task to budget from
     */
    record ObProjectSummary(long id, String name, ObClientRef client, ObProductRef product,
                            LocalDate startDate, UserRef salesPerson, UserRef implementor,
                            UserRef implementorManager,
                            String status, String gateStatus, String currentStage,
                            int stagesComplete, int stagesTotal, int journeyCount,
                            Integer delayedByDays, LocalDate tentativeCompletion,
                            int totalTatDays) {
    }

    /**
     * {@code ObProjectDetail} — the project header the ribbon page prints above
     * its journeys.
     *
     * <p>It carries the stage roll-up as rows rather than only as a count,
     * because the header is where somebody asks <em>which</em> stage is
     * outstanding. The journeys themselves are not here: they are
     * {@code getObJourney}'s, one ribbon at a time, exactly as the client
     * product page already reads them.
     */
    record ObProjectDetail(long id, String name, ObClientRef client, ObProductRef product,
                           LocalDate startDate, UserRef salesPerson, UserRef implementor,
                           UserRef implementorManager,
                           String status, String statusReason, String gateStatus,
                           String currentStage, int stagesComplete, int stagesTotal,
                           int journeyCount, Integer delayedByDays, LocalDate tentativeCompletion,
                           int totalTatDays, List<ObProjectStage> stages,
                           List<ObProjectServiceRef> moduleServices,
                           UserRef createdBy, Instant createdAt) {
    }

    /**
     * One Module Service this project was boarded through — the journey, named,
     * and its own stage roll-up.
     *
     * <h2>Why {@code stages} is repeated here</h2>
     *
     * <p>{@code ObProjectDetail.stages} is the same roll-up folded across every
     * journey, and that fold is what the header's "Stages 2/7" needs. It is
     * also what makes it useless to the project page's tree: folded, there is
     * no answer to "how far is <em>SIS</em> through Configuration", because
     * both services' Configuration tasks are in one bucket.
     *
     * <p>So the tree reads this list and the header reads the folded one. Both
     * are built from a single {@code STAGE_ROLLUP} at journey grain — the
     * project-level figures are summed from these rows rather than queried
     * again, so the two cannot disagree.
     *
     * <p>A stage with {@code taskCount = 0} here means <b>this service</b>
     * scheduled nothing into it, which is a sharper statement than the folded
     * list can make: Reports can be empty for Attendance and busy for SIS, and
     * only this shape can say so.
     *
     * @param stages every stage this service's template publishes, in sequence,
     *               empty ones included
     */
    record ObProjectServiceRef(long journeyId, long templateId, String serviceName,
                            String gateStatus, boolean isComplete,
                            List<ObProjectStage> stages) {
    }

    record ObProjectDetailResponse(ObProjectDetail data) {
    }

    record ObProjectListResponse(List<ObProjectSummary> data, PageMeta meta) {
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * {@code ObProjectCreateRequest} — the New Project form in one request.
     *
     * <p>{@code moduleServiceIds} is {@code @NotEmpty} and that is a product
     * decision, not a technical one. The form arrives with every service
     * checked and lets the user unpick the ones this client did not buy;
     * unpicking <em>all</em> of them would create a project with no journey,
     * no ribbon and nothing to report — which is a purchase record, and this
     * module already has a table for those.
     *
     * <p>There is no {@code status} field. A project is born
     * {@code RUNNING} and the three other values are recorded later through the
     * {@code PATCH}, where {@code ObProjectStatus.requiresReason} can insist on
     * an explanation. {@code COMPLETED} is never accepted from either.
     */
    /**
     * <h2>The three people are required here and optional on the update</h2>
     *
     * <p>The implementor is what an ownerless task falls to — see
     * {@code ObJourneyInstantiationService#defaultImplementorOf}, which writes
     * them onto every step the module service pinned nobody to, and
     * {@code ObJourneyReadService#inheritedOwner}, which resolves the rows that
     * predate it. A project created without one produces journeys whose
     * unpinned tasks belong to nobody, and the fallback has nothing to fall
     * back to. Requiring it at the one moment somebody is choosing who runs the
     * project is cheaper than discovering it on the Manager's unassigned list a
     * fortnight later.
     *
     * <p>The sales person is required on the same occasion for a different
     * reason: every project has one commercially, and the field was optional
     * only because the form had nowhere to get it from before OB-02.
     *
     * <p>{@code implementorManagerUserId} is required on the sales person's
     * reasoning rather than the implementor's. Nothing falls back to the
     * manager and no task is instantiated onto them — they are recorded
     * because every engagement has somebody accountable above the person
     * running it, and the moment to capture that is while somebody is already
     * choosing the other two. <b>It is not the implementor's reporting
     * manager</b>; see {@code ObProject#getImplementorManagerUserId()} for why
     * deriving it from the org chart would be both a different fact and a
     * retroactive one.
     *
     * <p><b>The update deliberately still accepts null for all three.</b>
     * Clearing an implementor is a real thing to do — somebody leaves, the
     * project is between owners — and {@code ObProjectUpdateRequest}'s own
     * javadoc turns on being able to. The rule is that a project cannot be
     * <em>born</em> without one, not that it can never be without one.
     */
    record ObProjectCreateRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull Long clientId,
            @NotNull Long productId,
            @NotNull LocalDate startDate,
            @NotNull Long salesPersonId,
            @NotNull Long implementorUserId,
            @NotNull Long implementorManagerUserId,
            @NotEmpty List<Long> moduleServiceIds) {
    }

    /**
     * {@code ObProjectUpdateRequest} — partial by field, so an absent key means
     * "leave it alone" and an explicit null means "clear it".
     *
     * <p>Java erases that distinction on a record: both arrive as null. The
     * fields where it would matter are the three people, and the resolution is
     * the one {@code ObContactUpsertRequest} already took — <b>this is the
     * whole representation, not a sparse patch</b>. The form always sends all
     * three, absent means cleared, and unassigning an implementor is therefore
     * possible rather than a gap somebody works around by assigning a
     * placeholder user.
     *
     * <p>{@code clientId} and {@code productId} are absent by design. The pair
     * is the project's identity — {@code uq_ob_projects_client_product} is over
     * it and every journey pins a template belonging to that product — so
     * moving a project to another one is not an edit, it is a different
     * project. {@code ObProject} has no setter for either.
     */
    record ObProjectUpdateRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull LocalDate startDate,
            Long salesPersonId,
            Long implementorUserId,
            Long implementorManagerUserId,
            @Size(max = 20) String status,
            @Size(max = 500) String statusReason) {
    }
}
