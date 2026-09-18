package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.common.pagination.PageMeta;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * B-121 · the shapes {@code GET /onboarding/dashboard/summary} answers with.
 *
 * <p>Mirrors {@code ObDashboardSummaryResponse} in {@code contracts/openapi.yaml}.
 * Records rather than a builder, and a package-private holder rather than seven
 * top-level files, matching {@code WidgetDtos} one module over.
 *
 * <p>These are deliberately <em>not</em> the ticketing dashboard's types.
 * A-115's ArchUnit rule refuses the import, and A-118 records the three reasons
 * at length above {@code /onboarding/dashboard/summary}: the shapes differ, the
 * vocabularies are not subsets of each other, and the module gate sits on the
 * route tree so a shared route would disclose that onboarding is deployed.
 */
final class ObDashboardDtos {

    private ObDashboardDtos() {
    }

    record ObDashboardSummaryResponse(ObDashboardSummary data) {
    }

    /**
     * @param cards       all seven, always, in {@link ObDashboardCardKey} order.
     *                    A card whose count is zero is drawn as zero rather
     *                    than omitted — an absent card and a card reading
     *                    nought are different claims, and only one of them is
     *                    true when nothing is overdue.
     * @param computedAt  when {@code ob_dashboard_summary} was last refreshed,
     *                    or <b>null when B-120 has never run</b>. A-108 makes
     *                    that a reachable state on purpose ("both start empty
     *                    and fill forward from the day they land"), and it is a
     *                    different claim from a computed board that happens to
     *                    be quiet.
     * @param appliedScope what A-112's rule narrowed the counts to, in a
     *                    sentence. Sent even when nothing was narrowed: it is
     *                    what lets a Step Owner comparing their board against a
     *                    colleague's see why the numbers differ without asking.
     */
    record ObDashboardSummary(List<ObDashboardCard> cards, Instant computedAt, String appliedScope) {
    }

    /**
     * @param key                 the kebab-case token, serialised by
     *                            {@link ObDashboardCardKey#wireName()}.
     * @param count               the figure. 0 and meaningless whenever
     *                            {@code unavailableReason} is set.
     * @param deltaFromYesterday  change against the previous <em>stored</em>
     *                            day, or null when there is no earlier day to
     *                            compare against. Null rather than 0, because 0
     *                            is a claim that nothing moved.
     * @param countIsUpperBound   true when {@code count} may overstate because
     *                            it was summed across the summary table's
     *                            product rows and this card counts clients.
     *                            Always false with a {@code productId}. See
     *                            {@link ObDashboardSummaryRepository} for why
     *                            the exact figure is not recoverable.
     * @param unavailableReason   why this card carries no number, or null when
     *                            it carries one. See {@link ObDashboardScope};
     *                            it is A-056's {@code Widget.unavailableReason}
     *                            transferred whole, including its reason for
     *                            being on the wire rather than re-derived by
     *                            the SPA.
     */
    record ObDashboardCard(ObDashboardCardKey key,
                           long count,
                           Long deltaFromYesterday,
                           boolean countIsUpperBound,
                           String unavailableReason) {

        static ObDashboardCard of(ObDashboardCardKey key, long count, Long delta, boolean upperBound) {
            return new ObDashboardCard(key, count, delta, upperBound, null);
        }

        /**
         * A card with no number at all.
         *
         * <p>{@code countIsUpperBound} is false rather than true: there is no
         * count to bound, and claiming one would have a client render "at most
         * 0" beside a sentence saying the figure is unavailable.
         */
        static ObDashboardCard unavailable(ObDashboardCardKey key, String reason) {
            return new ObDashboardCard(key, 0L, null, false, reason);
        }
    }

    // ── B-127 · the slide-over behind one card ──────────────────────────────

    /** Mirrors {@code ObDashboardItemType} in the contract. */
    enum ObDashboardItemType {
        SERVICE, PREREQUISITE
    }

    /**
     * {@code ObProductRef}, restated locally rather than imported — the same
     * three-field mirror every onboarding package keeps of its own, per
     * {@code ObClientDtos.ObProductRef}'s own precedent, so that a change to
     * one package's response shape is never a silent change to another's.
     */
    record ObProductRef(long id, String code, String name) {
    }

    /** {@code UserRef}, restated locally — see {@link ObProductRef}'s own note. */
    record UserRef(long id, String displayName) {

        /**
         * Null in, null out — an unassigned implementor is an ordinary state
         * and must reach the wire as {@code null} rather than as a user whose
         * id is zero. {@code ObProjectDtos.UserRef} carries the identical
         * factory for the identical reason.
         */
        static UserRef of(Long id, String displayName) {
            return id == null ? null : new UserRef(id, displayName);
        }
    }

    /**
     * One row of the slide-over — mirrors {@code ObDashboardItem}.
     *
     * @param itemId    an {@code ob_journey_steps} id when {@code itemType} is
     *                  {@code SERVICE}, an {@code ob_client_prereq_tasks} id
     *                  when it is {@code PREREQUISITE}. Two id spaces behind
     *                  one field, exactly as the contract states.
     * @param journeyId null on a prerequisite.
     * @param obProjectId the project this row opens onto — {@code jr.project_id}
     *                  on a service row, always present. On a prerequisite it
     *                  is the client's project <em>only when they have exactly
     *                  one</em>, and null otherwise: a prerequisite is the
     *                  client-level gate and names no single project, so a
     *                  client running several cannot be resolved to one here.
     *                  The drill-down opens the project page when this is set
     *                  and the client's project grid when it is not.
     * @param product   null on a prerequisite.
     * @param owner     null on a prerequisite, whose counterparty is the
     *                  client rather than an implementor.
     * @param status    an {@code ObJourneyStepStatus} or an
     *                  {@code ObPrereqTaskStatus} depending on
     *                  {@code itemType} — a plain string, per the contract's
     *                  own reasoning: a display column gains nothing from a
     *                  generated client forced to discriminate two enums to
     *                  render a chip.
     * @param blockedReason the note the owner typed when the service was
     *                  blocked, falling back to the reason code when they
     *                  typed none — {@code ck_ob_journey_steps_blocked_reason}
     *                  makes the code mandatory on a BLOCKED step, so one of
     *                  the two always exists. Null on every other status and
     *                  on every prerequisite: OB-02's "Where it's stuck" table
     *                  renders the fixed "Waiting on client input" for a
     *                  client-attributed pause itself, because that has a
     *                  counterparty rather than a culprit.
     */
    record ObDashboardItem(ObDashboardItemType itemType, long itemId, long obClientId, String obClientName,
                           Long journeyId, Long obProjectId, ObProductRef product, String title, UserRef owner,
                           String status, String blockedReason, Instant dueAt, boolean isOverdue) {

        static ObDashboardItem of(ObDashboardCardItemsRepository.ItemRow row) {
            ObProductRef product = row.productId() == null ? null
                    : new ObProductRef(row.productId(), row.productCode(), row.productName());
            UserRef owner = row.ownerUserId() == null ? null
                    : new UserRef(row.ownerUserId(), row.ownerName());
            return new ObDashboardItem(
                    ObDashboardItemType.valueOf(row.itemType()), row.itemId(), row.obClientId(), row.obClientName(),
                    row.journeyId(), row.projectId(), product, row.title(), owner, row.status(),
                    row.blockedReason(), row.dueAt(), row.isOverdue());
        }
    }

    /**
     * {@code meta} for {@code ObDashboardItemListResponse} — {@code Meta}
     * (A-053's {@code nextCursor}/{@code hasMore}) plus {@code computedAt},
     * repeating the card's own so a screen can say which number these rows
     * belong to.
     *
     * <p>Not {@link com.edunext.edutrack.common.pagination.PageMeta} alone —
     * that type deliberately carries no third field, and the contract's own
     * {@code allOf} extension is what B-127 added to hold this one; see the
     * commit that fixed it.
     */
    record ObDashboardItemListMeta(String nextCursor, boolean hasMore, Instant computedAt) {

        static ObDashboardItemListMeta of(PageMeta page, Instant computedAt) {
            return new ObDashboardItemListMeta(page.nextCursor(), page.hasMore(), computedAt);
        }
    }

    record ObDashboardItemListResponse(List<ObDashboardItem> data, ObDashboardItemListMeta meta) {
    }

    // ── B-128 · the Delayed Projects grid ───────────────────────────────────

    /**
     * {@code ObStepDot}, restated locally — {@link ObProductRef}'s own note on
     * why a package keeps its own mirror rather than importing another
     * package's DTO. {@code rag} is always {@code null} on this route: see
     * {@code ObDelayedProjectsRepository}'s class note for why this grid does
     * not compute it.
     */
    record ObDashboardStepDot(long id, int sequence, String name, String status,
                              String rag, Long dependsOnStepId) {
    }

    /**
     * One row of plan §9's Delayed Projects grid — mirrors {@code ObDelayedProject}.
     *
     * @param productsBought every product this client has bought, not only
     *                       this journey's — the contract's own note, and a
     *                       fact about the client rather than the journey.
     * @param product        this row's own journey's product.
     * @param currentStep    null on a journey whose every step is blocked or
     *                       not yet activated — see
     *                       {@code ObDelayedProjectsRepository}.
     * @param responsible    the owner (falling back to the backup owner) of
     *                       the step that put this journey on the grid — the
     *                       earliest-due overdue step, which may or may not
     *                       be {@code currentStep}.
     * @param delayedByDays  working days, through {@code WorkingCalendar} —
     *                       never a naive calendar-day subtraction.
     */
    record ObDelayedProject(long journeyId, long obClientId, String obClientName, Instant startedAt,
                            List<ObProductRef> productsBought, ObProductRef product,
                            ObDashboardStepDot currentStep, UserRef responsible,
                            Instant expectedCompletionAt, int delayedByDays) {
    }

    record ObDelayedProjectListResponse(List<ObDelayedProject> data, PageMeta meta) {
    }

    // ── B-128 · the Implementor workload & performance grid ────────────────

    /**
     * One row of plan §9's Implementor workload & performance grid — mirrors
     * {@code ObImplementorWorkload}. Sourced from B-120's
     * {@code ob_implementor_daily_stats}, never a live aggregate.
     *
     * @param isActive         whether this implementor still holds a live
     *                         {@code OB_STEP_OWNER} grant.
     * @param clientsOpen      partitioned exactly by the six counters that
     *                         follow — an arithmetic contract the schema
     *                         states and nothing at runtime enforces; see
     *                         {@code ObImplementorWorkloadServiceTest}'s sum
     *                         assertion.
     * @param performanceScore derived on read, 0–100, or null for an
     *                         implementor with zero completions — see
     *                         {@code ObImplementorWorkloadService#performanceScore}.
     * @param statDate         which day's stats this row is.
     */
    record ObImplementorWorkload(UserRef user, boolean isActive, int clientsOpen,
                                 int onTrack, int notStarted, int delayed, int atRisk,
                                 int blockedWaiting, int aheadOfSchedule,
                                 int completedOnTime, int completedEarly, int completedLate,
                                 int blockedHours, BigDecimal performanceScore, LocalDate statDate) {
    }

    /**
     * C-141 · the caller's own review figures — the manager's queue card and
     * the implementor's three-figure card, in one answer.
     *
     * <p>Both roles in one record because plenty of people are both, and a
     * client cannot know in advance which figures it will need. A zero is a
     * real answer meaning "nothing", so the screen draws a card only where
     * there is something to say.
     *
     * @param reviewsPending  rows waiting on this caller <em>as a manager</em>
     * @param sentForReview   rows this caller has out with their own manager
     * @param reviewsApproved rows of theirs approved on the stat day
     * @param reviewsRejected rows of theirs sent back on the stat day
     * @param computedAt      when the worker last wrote these — <b>null means
     *                        never</b>, and the screen says so rather than
     *                        presenting four zeroes as fact
     */
    record ObReviewSummary(int reviewsPending, int sentForReview,
                           int reviewsApproved, int reviewsRejected,
                           Instant computedAt) {
    }

    record ObReviewSummaryResponse(ObReviewSummary data) {
    }

    // ── OB-02's project board ──────────────────────────────────────────────

    /**
     * {@code ObProjectClientRef} — the client as the board's rows name it.
     *
     * <p>Restated here rather than imported from the projects feature, per
     * {@link ObProductRef}'s own note: a change to that feature's response
     * shape must never be a silent change to this one's.
     */
    record ObProjectBoardClientRef(long id, String name, String clientCode, String city) {
    }

    /**
     * One running project, as OB-02's donuts and lists read it — mirrors
     * {@code ObProjectBoardRow}.
     *
     * @param bucket             one of {@code ON_TIME}, {@code AHEAD},
     *                           {@code DELAYED}, {@code AT_RISK},
     *                           {@code NOT_SCHEDULED}. A plain string on the
     *                           same reasoning {@code ObDashboardItem.status}
     *                           gives: the contract closes the vocabulary and
     *                           the screen draws a slice from it
     * @param daysPastCompletion ceiling working days past the <b>project's</b>
     *                           completion date, or null while it is not past
     *                           — never zero, which would claim the project is
     *                           on time today rather than that the question
     *                           does not apply
     * @param delayedByDays      ceiling working days past the earliest overdue
     *                           <b>task</b>'s due date. A different fact, and
     *                           deliberately not folded into {@code bucket}: a
     *                           project inside its completion date with a late
     *                           task is not a late project
     * @param budgetUsedPercent  working hours since the project opened as a
     *                           share of its TAT budget. Uncapped, so a project
     *                           well past its budget says how far
     */
    record ObProjectBoardRow(long id, String name, ObProjectBoardClientRef client, ObProductRef product,
                             LocalDate startDate, UserRef salesPerson, UserRef implementor,
                             String gateStatus, String currentStage, String bucket,
                             LocalDate tentativeCompletion, Integer daysPastCompletion,
                             Integer delayedByDays, int tasksTotal, int tasksDone,
                             Integer budgetUsedPercent, int openEscalations) {
    }

    /** The six counters on the card band — every one on the project's completion date. */
    record ObProjectBoardCards(int ongoingProjects, int thisWeeksDeadlines, int todaysDelivery,
                               int overdueProjects, int atRiskProjects, int clientEscalations) {
    }

    /**
     * The schedule donut's five slices.
     *
     * <p>They sum to {@code cards.ongoingProjects}, because every row falls in
     * exactly one bucket — an arithmetic contract the schema states and
     * {@code ObProjectBoardServiceTest} asserts, since nothing at runtime
     * enforces it.
     */
    record ObProjectBoardSchedule(int onTime, int ahead, int delayed, int atRisk, int notScheduled) {
    }

    /**
     * OB-02's project board — mirrors {@code ObProjectBoard}.
     *
     * @param today     the date every "today" and "this week" figure was
     *                  measured on, in the <b>working calendar's</b> timezone
     *                  rather than the server's. Echoed so a screen cannot
     *                  mislabel the board it is drawing
     * @param truncated whether the row list hit
     *                  {@code ObRunningProjectReader.CEILING} and the counts
     *                  may therefore be short of the truth. False on every
     *                  deployment that has not outgrown a single-screen board
     */
    record ObProjectBoard(Instant asOf, LocalDate today, LocalDate weekStart, LocalDate weekEnd,
                          String appliedScope, ObProjectBoardCards cards,
                          ObProjectBoardSchedule schedule, List<ObProjectBoardRow> projects,
                          boolean truncated) {
    }

    record ObProjectBoardResponse(ObProjectBoard data) {
    }

    record ObImplementorWorkloadListResponse(List<ObImplementorWorkload> data, PageMeta meta) {
    }
}
