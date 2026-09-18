package com.edunext.edutrack.api.feature.onboarding.projects;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.api.feature.onboarding.journeys.JourneyTatCalculator;
import com.edunext.edutrack.common.pagination.Cursor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The read side of {@code /onboarding/projects} — the grid and the header.
 *
 * <h2>Every statement carries {@link ObClientScope}'s predicate, over the
 * client</h2>
 *
 * <p>A project has no scope rule of its own and must not grow one. §3's rule is
 * about clients — Sales sees the clients they created, a step owner sees the
 * clients whose journeys hold a step of theirs — and a project is visible
 * exactly when its client is. So the predicate is applied to the joined
 * {@code ob_clients} alias rather than re-expressed here, which is the same
 * decision {@code ObReportScope} and {@code ObEscalationScope} document: a
 * second spelling of one security rule is the risk, not the convenience.
 *
 * <p>That is also what makes an out-of-scope project id answer {@code 404}
 * rather than {@code 403} — {@link #findDetail} simply returns nothing, and the
 * service cannot tell "no such project" from "not yours" because this class
 * does not tell it.
 *
 * <h2>Three queries for a page, not one</h2>
 *
 * <p>The page, then the stage roll-up, then the module services — each keyed by
 * the page's own ids, on {@code ObClientReadRepository}'s precedent. Folding the
 * stage roll-up into the page query would multiply rows by stages and make
 * {@code LIMIT} mean something other than "fifty projects"; it would also need
 * a correlated derived table, which MySQL only admits with {@code LATERAL}.
 *
 * <h2>What this class does not compute</h2>
 *
 * <p>{@code delayedByDays} and {@code tentativeCompletion}. Both are working-
 * calendar arithmetic and SQL cannot consult {@code WorkingHoursService}, so
 * this class hands back the two raw inputs — {@code earliestOverdueAt} and
 * {@code totalTatDays} — and {@link ObProjectService} turns them into figures.
 * {@code ObDelayedProjectsRepository} makes the identical split, and CLAUDE.md
 * is why: every duration in the system routes through the working calendar.
 */
@Repository
class ObProjectReadRepository {

    /**
     * The earliest due date this project is already past.
     *
     * <p>{@code ObDashboardCardItemsRepository.OVERDUE_SERVICE}'s status set,
     * restated over this query's own aliases — {@code PENDING},
     * {@code IN_PROGRESS} and {@code BLOCKED} are the three that are still owed.
     * {@code WAITING_ON_CLIENT} is deliberately absent from it: plan §5.7 pauses
     * that clock and charges the wait to the client, so a project waiting on a
     * document is not accruing delay against us.
     *
     * <p>Bounded to journeys that are actually running — past the gate, not
     * held behind a sibling service, not finished. A locked journey's steps
     * carry due dates that were computed at instantiation and mean nothing
     * until the gate opens; counting them would report every newly created
     * project as weeks late on the day it was made.
     */
    private static final String EARLIEST_OVERDUE_AT = """
            (SELECT MIN(os.due_at)
               FROM ob_journeys oj
               JOIN ob_journey_steps os ON os.journey_id = oj.id
              WHERE oj.project_id = p.id
                AND oj.archived_at IS NULL
                AND oj.completed_at IS NULL
                AND oj.gate_status <> 'LOCKED'
                AND NOT (oj.held_by_journey_id IS NOT NULL AND oj.released_at IS NULL)
                AND os.status IN ('PENDING', 'IN_PROGRESS', 'BLOCKED')
                AND os.due_at IS NOT NULL
                AND os.due_at < :now)
            """;

    /**
     * The project's TAT budget in working days — summed over the tasks actually
     * instantiated, not over the catalogue.
     *
     * <p>{@code ob_journey_steps.tat_days} is the <b>pinned</b> figure, copied
     * from the template version this project was boarded on. Reading the
     * template live instead would move a running project's tentative completion
     * date every time an admin edited the Module Service, which is the exact
     * thing version pinning exists to prevent.
     *
     * <p><b>This used to be a flat sum across every journey of the project</b>,
     * and said so: it overstated elapsed time wherever two services ran side by
     * side, and left the critical path as "a decision for the plan rather than
     * for this query". The decision was taken — see {@link ObProjectTatPath} —
     * and the figure is now the heaviest chain through
     * {@code ob_journey_template_dependencies} rather than the total of
     * everything. A project boarded through two independent services takes as
     * long as the longer of them.
     *
     * <p>The fold is in Java because a longest path is a graph walk, and a
     * recursive CTE that has to defend itself against a cycle is a worse place
     * to keep one than a tested function.
     */
    private static final String SERVICE_TASK_TATS = """
            SELECT j.project_id        AS projectId,
                   j.id                AS journeyId,
                   s.id                AS stepId,
                   COALESCE(s.tat_days, 0) AS tatDays,
                   s.depends_on_step_id AS dependsOnStepId
              FROM ob_journeys j
         LEFT JOIN ob_journey_steps s ON s.journey_id = j.id
             WHERE j.project_id IN (:projectIds)
               AND j.archived_at IS NULL
            """;

    /**
     * Which of this project's services wait on which.
     *
     * <p>The edge is on the <b>template</b> — a service's template declares
     * what it depends on — so it is resolved back to this project's own
     * journeys. A template dependency on a service the client did not buy
     * produces a null and holds nothing up.
     *
     * <p>One row per (journey, dependency): a service waiting on two arrives
     * twice, a service waiting on none arrives once with a null.
     */
    private static final String SERVICE_DEPENDENCIES = """
            SELECT j.project_id AS projectId,
                   j.id         AS journeyId,
                   dep.id       AS dependsOnJourneyId
              FROM ob_journeys j
         LEFT JOIN ob_journey_template_dependencies d
                ON d.template_id = j.template_id
         LEFT JOIN ob_journeys dep
                ON dep.project_id = j.project_id
               AND dep.template_id = d.depends_on_template_id
               AND dep.archived_at IS NULL
               AND dep.id <> j.id
             WHERE j.project_id IN (:projectIds)
               AND j.archived_at IS NULL
            """;

    /**
     * A project's gate is its client's — the gate clears for every journey at
     * once and never re-locks (C-118).
     *
     * <p>Read off this project's own journeys rather than the client's, so a
     * project whose journeys are all still locked reads LOCKED even where a
     * sibling project of the same client is running. A project with no journey
     * at all reads LOCKED, which is what an unstarted project is.
     */
    private static final String GATE_STATUS = """
            (SELECT IF(SUM(gj.gate_status = 'OPEN') > 0, 'OPEN', 'LOCKED')
               FROM ob_journeys gj
              WHERE gj.project_id = p.id
                AND gj.archived_at IS NULL)
            """;

    private static final String JOURNEY_COUNT = """
            (SELECT COUNT(*)
               FROM ob_journeys cj
              WHERE cj.project_id = p.id
                AND cj.archived_at IS NULL)
            """;

    private static final String COLUMNS = """
            SELECT p.id                  AS id,
                   p.name                AS name,
                   p.start_date          AS startDate,
                   p.status              AS status,
                   p.status_reason       AS statusReason,
                   p.created_at          AS createdAt,
                   p.created_by          AS createdBy,
                   cb.full_name          AS createdByName,
                   cl.id                 AS clientId,
                   cl.name               AS clientName,
                   cl.client_code        AS clientCode,
                   cl.city               AS clientCity,
                   pr.id                 AS productId,
                   pr.code               AS productCode,
                   pr.name               AS productName,
                   p.sales_person_id     AS salesPersonId,
                   sp.full_name          AS salesPersonName,
                   p.implementor_user_id AS implementorId,
                   im.full_name          AS implementorName,
                   p.implementor_manager_user_id AS implementorManagerId,
                   imm.full_name                 AS implementorManagerName,
                   %s                    AS gateStatus,
                   %s                    AS journeyCount,
                   %s                    AS earliestOverdueAt
              FROM ob_projects p
              JOIN ob_clients  cl ON cl.id = p.ob_client_id
              JOIN ob_products pr ON pr.id = p.product_id
         LEFT JOIN users sp ON sp.id = p.sales_person_id
         LEFT JOIN users im ON im.id = p.implementor_user_id
         LEFT JOIN users imm ON imm.id = p.implementor_manager_user_id
         LEFT JOIN users cb ON cb.id = p.created_by
            """.formatted(GATE_STATUS, JOURNEY_COUNT, EARLIEST_OVERDUE_AT);

    /**
     * The grid's filters. Every one is null-tolerant in the same shape, so an
     * absent parameter is a no-op rather than a second SQL string.
     *
     * <p>{@code q} matches the project name or the client name, because those
     * are the two things somebody types into the box above this grid. Not the
     * client code: it is short, it is exact, and a {@code LIKE '%…%'} over it
     * would make "ERP" match a code rather than a product.
     */
    private static final String FILTERS = """
             WHERE %s
               AND (:q IS NULL OR p.name LIKE :q OR cl.name LIKE :q)
               AND (:clientId IS NULL OR p.ob_client_id = :clientId)
               AND (:productId IS NULL OR p.product_id = :productId)
               AND (:status IS NULL OR p.status = :status)
               AND (:implementorId IS NULL OR p.implementor_user_id = :implementorId)
               AND (:salesPersonId IS NULL OR p.sales_person_id = :salesPersonId)
               AND (:cursorId IS NULL OR p.id < :cursorId)
            """;

    /**
     * Descending id, and the cursor is that id alone.
     *
     * <p>{@code start_date} is what the grid <em>shows</em> and is not what it
     * is ordered by, on {@code ObClientReadRepository}'s own reasoning: the id
     * is {@code AUTO_INCREMENT}, so descending id is creation order and is the
     * one value guaranteed unique — which makes the keyset a single column with
     * no ties to break and no same-date rows to skip across a page boundary.
     */
    private static final String ORDER_AND_LIMIT = """
             ORDER BY p.id DESC
             LIMIT :limit
            """;

    /**
     * The stage roll-up, folded onto the <b>implementation stage</b> rather
     * than onto the stage group.
     *
     * <p>A project boarded through two Module Services has two Configuration
     * groups, one per service. Counted separately, a six-stage master would
     * report twelve stages for that project and "3 of 12" where a person sees
     * three of six. {@code COALESCE(g.implementation_stage_id, …)} is the fold,
     * and the two fallbacks below it keep every task counted:
     *
     * <ul>
     *   <li>{@code -g.id} for a group with no implementation stage — the
     *       "Ungrouped" bucket {@code V20260911_1630} created for tasks that
     *       predate the stage master. Negated so it can never collide with a
     *       real stage id.</li>
     *   <li>{@code 0} for a task whose {@code template_step_id} is null or whose
     *       template row has since gone. {@code ob_journey_steps.template_step_id}
     *       is nullable and documented as "provenance only", so an inner join
     *       here would silently drop those tasks out of both the numerator and
     *       the denominator — the worst kind of wrong, because the percentage
     *       would still look plausible.</li>
     * </ul>
     *
     * <p>{@code SKIPPED} counts as settled beside {@code DONE}. A waived task is
     * not outstanding work, and {@code ribbonSteps.ts} reads it the same way on
     * the ribbon this grid summarises.
     *
     * <h2>Driven from the template's stages, not from the tasks</h2>
     *
     * <p>This used to group over {@code ob_journey_steps}, which meant a stage
     * the template publishes but schedules no task into produced <b>no row at
     * all</b>. The ribbon was then a different length for every module service,
     * and — worse — a stage nobody had configured was indistinguishable from a
     * stage that does not exist. Five of the seven stages on the seeded corpus
     * are in exactly that state.
     *
     * <p>So the first arm drives from {@code ob_journey_template_stages} and
     * left-joins the tasks onto it: every published stage answers, with
     * {@code taskCount = 0} where nothing was scheduled. An empty stage is a
     * misconfigured Module Service somebody should see, not a row to suppress.
     *
     * <p><b>An empty stage is not a complete one.</b> {@code tasksOutstanding}
     * is 0 for both, so every caller must test {@code taskCount > 0} as well —
     * {@code ObProjectService} does, in the two places that count. Reading "no
     * outstanding work" as "finished" would report six of seven stages complete
     * on a project where one stage has been done and five were never set up.
     *
     * <h2>The second arm is the orphan bucket, and it is why this is a union</h2>
     *
     * <p>Driving from the stage groups cannot see a task whose
     * {@code template_step_id} is null or whose template row has gone — the
     * {@code 0} bucket the list above describes. Those tasks are real and
     * running, so they are counted separately and unioned back in rather than
     * dropped, which is the behaviour the previous query had and the reason it
     * used outer joins. The two arms cannot collide: the first only ever emits
     * a positive implementation-stage id or a negative group id, the second
     * only ever emits {@code 0}.
     *
     * <h2>Grouped by journey, then folded</h2>
     *
     * <p>The grain is one row per <em>journey</em> and stage, not per project
     * and stage. The project page is a tree — Module Service, then Stage, then
     * Task, then Checklist — so each service needs its own stage roll-up, and
     * a result already folded across journeys cannot say "SIS is 1/1 through
     * Configuration" however it is sliced afterwards.
     *
     * <p>The project-level figures the header and the grid print are summed
     * back up in Java rather than fetched a second time. Two queries counting
     * the same tasks is how a header comes to disagree with the tree beneath
     * it; one query at the finer grain cannot.
     *
     * <p>The first arm joins the template's stages per journey, so a project
     * boarded through two services sharing a template contributes two rows per
     * stage. That is the point — each names its own service — and summing them
     * reproduces exactly what the project-grain grouping used to return.
     */
    private static final String STAGE_ROLLUP = """
            SELECT * FROM (
            SELECT j.project_id                                     AS projectId,
                   j.id                                             AS journeyId,
                   COALESCE(g.implementation_stage_id, -g.id)       AS stageKey,
                   MIN(g.name)                                      AS stageName,
                   MIN(g.sequence)                                  AS stageSequence,
                   COUNT(js.id)                                     AS taskCount,
                   COALESCE(SUM(js.status NOT IN ('DONE', 'SKIPPED')), 0) AS tasksOutstanding,
                   MIN(CASE WHEN js.status IN ('IN_PROGRESS', 'WAITING_ON_CLIENT')
                              OR (js.status = 'PENDING' AND EXISTS (
                                    SELECT 1 FROM ob_journey_step_items i
                                     WHERE i.step_id = js.id AND i.answer IS NOT NULL))
                            THEN js.sequence END)                   AS minActiveSequence
              FROM ob_journeys j
              JOIN ob_journey_template_stages g ON g.template_id = j.template_id
         LEFT JOIN ob_journey_template_steps  ts ON ts.template_stage_id = g.id
         LEFT JOIN ob_journey_steps js ON js.template_step_id = ts.id
                                      AND js.journey_id = j.id
             WHERE j.project_id IN (:projectIds)
               AND j.archived_at IS NULL
             GROUP BY j.project_id, j.id, COALESCE(g.implementation_stage_id, -g.id)
            UNION ALL
            SELECT j.project_id                                     AS projectId,
                   j.id                                             AS journeyId,
                   0                                                AS stageKey,
                   'Ungrouped'                                      AS stageName,
                   9999                                             AS stageSequence,
                   COUNT(*)                                         AS taskCount,
                   SUM(js.status NOT IN ('DONE', 'SKIPPED'))        AS tasksOutstanding,
                   MIN(CASE WHEN js.status IN ('IN_PROGRESS', 'WAITING_ON_CLIENT')
                              OR (js.status = 'PENDING' AND EXISTS (
                                    SELECT 1 FROM ob_journey_step_items i
                                     WHERE i.step_id = js.id AND i.answer IS NOT NULL))
                            THEN js.sequence END)                   AS minActiveSequence
              FROM ob_journeys j
              JOIN ob_journey_steps js ON js.journey_id = j.id
         LEFT JOIN ob_journey_template_steps ts ON ts.id = js.template_step_id
             WHERE j.project_id IN (:projectIds)
               AND j.archived_at IS NULL
               AND (ts.id IS NULL OR ts.template_stage_id IS NULL)
             GROUP BY j.project_id, j.id
            ) r
             ORDER BY r.projectId, r.journeyId, r.stageSequence, r.stageKey
            """;

    private static final String MODULE_SERVICES = """
            SELECT j.project_id  AS projectId,
                   j.id          AS journeyId,
                   j.template_id AS templateId,
                   j.service_name AS serviceName,
                   j.gate_status AS gateStatus,
                   j.completed_at AS completedAt
              FROM ob_journeys j
             WHERE j.project_id IN (:projectIds)
               AND j.archived_at IS NULL
             ORDER BY j.project_id, j.sequence, j.id
            """;

    private final JdbcClient jdbc;

    ObProjectReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // The page
    // ------------------------------------------------------------------

    List<Row> list(ObClientScope scope, String q, Long clientId, Long productId, String status,
                   Long implementorId, Long salesPersonId, String cursor, int fetchSize, Instant now) {

        Cursor decoded = cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor);
        var spec = jdbc.sql(COLUMNS + FILTERS.formatted(scope.predicate("cl")) + ORDER_AND_LIMIT)
                .param("now", Timestamp.from(now))
                .param("q", q == null || q.isBlank() ? null : "%" + q.trim() + "%")
                .param("clientId", clientId)
                .param("productId", productId)
                .param("status", status == null || status.isBlank() ? null : status.trim())
                .param("implementorId", implementorId)
                .param("salesPersonId", salesPersonId)
                .param("cursorId", decoded == null ? null : decoded.id())
                .param("limit", fetchSize);
        if (!scope.unrestricted()) {
            spec = spec.param(ObClientScope.USER_PARAM, scope.userId());
        }
        return spec.query(ROW_MAPPER).list();
    }

    /** One project, scoped exactly as the list is — empty for "no such row" and "not yours" alike. */
    Optional<Row> findDetail(ObClientScope scope, long projectId, Instant now) {
        var spec = jdbc.sql(COLUMNS + " WHERE %s AND p.id = :id".formatted(scope.predicate("cl")))
                .param("now", Timestamp.from(now))
                .param("id", projectId);
        if (!scope.unrestricted()) {
            spec = spec.param(ObClientScope.USER_PARAM, scope.userId());
        }
        return spec.query(ROW_MAPPER).optional();
    }

    // ------------------------------------------------------------------
    // The two reads keyed by the page's ids
    // ------------------------------------------------------------------

    /** Stage rows per project, already in display order. Empty map for an empty page. */
    Map<Long, List<StageRow>> stagesByProject(Collection<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<StageRow>> byProject = new LinkedHashMap<>();
        jdbc.sql(STAGE_ROLLUP).param("projectIds", projectIds).query(STAGE_MAPPER).list()
                .forEach(row -> byProject.computeIfAbsent(row.projectId(), k -> new ArrayList<>()).add(row));
        return byProject;
    }

    /** The journeys of each project, in pinned catalogue order. */
    /**
     * Each project's TAT along its heaviest chain of Module Services.
     *
     * <p>Batched by project id like every other fold on this page, so the grid
     * costs one query for the page rather than one per row.
     *
     * <p>A project with no journeys is absent from the map rather than present
     * with a zero; callers default it, which is the same answer the old
     * {@code COALESCE(SUM(...), 0)} gave an unstarted project.
     */
    Map<Long, Integer> criticalPathTatByProject(Collection<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }

        record TaskRow(long projectId, long journeyId, Long stepId, int tatDays, Long dependsOnStepId) {
        }
        record DepRow(long projectId, long journeyId, Long dependsOnJourneyId) {
        }

        /*
          Level one: how long each service takes on its own.

          `JourneyTatCalculator` rather than a second walk written here — it is
          what the product catalogue's own figure uses, and two arithmetics for
          "how long does this service take" is how a project comes to disagree
          with the product it was boarded from. Its convention is the one that
          matters: a null `depends_on_step_id` means parallel, not first.
        */
        Map<Long, Map<Long, List<JourneyTatCalculator.Task>>> tasks = new LinkedHashMap<>();
        jdbc.sql(SERVICE_TASK_TATS).param("projectIds", projectIds)
                .query((rs, n) -> new TaskRow(
                        rs.getLong("projectId"),
                        rs.getLong("journeyId"),
                        nullableLong(rs, "stepId"),
                        rs.getInt("tatDays"),
                        nullableLong(rs, "dependsOnStepId")))
                .list()
                .forEach(row -> {
                    List<JourneyTatCalculator.Task> bucket = tasks
                            .computeIfAbsent(row.projectId(), k -> new LinkedHashMap<>())
                            .computeIfAbsent(row.journeyId(), k -> new ArrayList<>());
                    // A journey with no tasks arrives as one row with a null
                    // step, so the service exists in the fold with zero days
                    // rather than vanishing from its project.
                    if (row.stepId() != null) {
                        bucket.add(new JourneyTatCalculator.Task(
                                row.stepId(), row.tatDays(), row.dependsOnStepId()));
                    }
                });

        // Level two: which services wait on which.
        Map<Long, Map<Long, Set<Long>>> waitsOn = new LinkedHashMap<>();
        jdbc.sql(SERVICE_DEPENDENCIES).param("projectIds", projectIds)
                .query((rs, n) -> new DepRow(
                        rs.getLong("projectId"),
                        rs.getLong("journeyId"),
                        nullableLong(rs, "dependsOnJourneyId")))
                .list()
                .forEach(row -> {
                    Set<Long> deps = waitsOn
                            .computeIfAbsent(row.projectId(), k -> new LinkedHashMap<>())
                            .computeIfAbsent(row.journeyId(), k -> new LinkedHashSet<>());
                    if (row.dependsOnJourneyId() != null) {
                        deps.add(row.dependsOnJourneyId());
                    }
                });

        Map<Long, Integer> byProject = new LinkedHashMap<>();
        tasks.forEach((projectId, journeys) -> {
            Map<Long, Set<Long>> edges = waitsOn.getOrDefault(projectId, Map.of());
            List<ObProjectTatPath.Node> nodes = journeys.entrySet().stream()
                    .map(e -> new ObProjectTatPath.Node(
                            e.getKey(),
                            JourneyTatCalculator.criticalPathDays(e.getValue()),
                            edges.getOrDefault(e.getKey(), Set.of())))
                    .toList();
            byProject.put(projectId, ObProjectTatPath.longestPath(nodes));
        });
        return byProject;
    }

    Map<Long, List<ServiceRow>> servicesByProject(Collection<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ServiceRow>> byProject = new LinkedHashMap<>();
        jdbc.sql(MODULE_SERVICES).param("projectIds", projectIds).query(SERVICE_MAPPER).list()
                .forEach(row -> byProject.computeIfAbsent(row.projectId(), k -> new ArrayList<>()).add(row));
        return byProject;
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    /** One {@code ob_projects} row with its joins and its two un-computed inputs. */
    record Row(long id, String name, LocalDate startDate, String status, String statusReason,
               Instant createdAt, Long createdBy, String createdByName,
               long clientId, String clientName, String clientCode, String clientCity,
               long productId, String productCode, String productName,
               Long salesPersonId, String salesPersonName,
               Long implementorId, String implementorName,
               Long implementorManagerId, String implementorManagerName,
               String gateStatus, int journeyCount,
               Instant earliestOverdueAt) {
    }

    /**
     * One implementation stage of <b>one journey</b> — the grain
     * {@code STAGE_ROLLUP} returns. {@code minActiveSequence} is null where
     * nothing runs — a task counts as running on {@code IN_PROGRESS} /
     * {@code WAITING_ON_CLIENT}, or on {@code PENDING} with any answered check
     * list item, the same "partial" reading {@code moduleStripStats.ts}'
     * {@code taskProgress} takes: a task somebody has been ticking through all
     * morning without moving off {@code PENDING} is running, not untouched.
     *
     * <p>Project-level figures come from folding these, never from a second
     * query — see {@code ObProjectService.foldToProject}.
     */
    record StageRow(long projectId, long journeyId, long stageKey, String stageName,
                    int stageSequence, int taskCount, int tasksOutstanding,
                    Integer minActiveSequence) {
    }

    record ServiceRow(long projectId, long journeyId, long templateId, String serviceName,
                      String gateStatus, Instant completedAt) {
    }

    private static final RowMapper<Row> ROW_MAPPER = (rs, n) -> new Row(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getObject("startDate", LocalDate.class),
            rs.getString("status"),
            rs.getString("statusReason"),
            instant(rs, "createdAt"),
            nullableLong(rs, "createdBy"),
            rs.getString("createdByName"),
            rs.getLong("clientId"),
            rs.getString("clientName"),
            rs.getString("clientCode"),
            rs.getString("clientCity"),
            rs.getLong("productId"),
            rs.getString("productCode"),
            rs.getString("productName"),
            nullableLong(rs, "salesPersonId"),
            rs.getString("salesPersonName"),
            nullableLong(rs, "implementorId"),
            rs.getString("implementorName"),
            nullableLong(rs, "implementorManagerId"),
            rs.getString("implementorManagerName"),
            rs.getString("gateStatus"),
            rs.getInt("journeyCount"),
            instant(rs, "earliestOverdueAt"));

    private static final RowMapper<StageRow> STAGE_MAPPER = (rs, n) -> new StageRow(
            rs.getLong("projectId"),
            rs.getLong("journeyId"),
            rs.getLong("stageKey"),
            rs.getString("stageName"),
            rs.getInt("stageSequence"),
            rs.getInt("taskCount"),
            rs.getInt("tasksOutstanding"),
            nullableInt(rs, "minActiveSequence"));

    private static final RowMapper<ServiceRow> SERVICE_MAPPER = (rs, n) -> new ServiceRow(
            rs.getLong("projectId"),
            rs.getLong("journeyId"),
            rs.getLong("templateId"),
            rs.getString("serviceName"),
            rs.getString("gateStatus"),
            instant(rs, "completedAt"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
