-- =====================================================================
-- B-120 fix · ob_client_daily_stats — the prerequisite half of OB-02's
-- two deadline cards, at the grain that can actually hold it.
--
-- Source:  docs/Onboarding-Module-Plan.md §9 OB-02 — "This Week's
--          Deadlines (all client tasks — services **and prerequisites** —
--          due Mon–Sun)" and "Today's Delivery"
--          V20260903_1900__ob_dashboard_summary.sql, which already says
--          those two columns mean "services *and* prerequisite tasks"
--          CLAUDE.md: "Never live `COUNT(*)` for dashboards."
--
-- WHAT WAS WRONG
--
-- The board read 0 on This Week's Deadlines while its own drill-over
-- listed seventeen rows. Both are right about what they count:
-- `ObDashboardCardItemsRepository` unions services with
-- `ob_client_prereq_tasks`, exactly as §9 asks, and B-120's refresh counts
-- `ob_journey_steps` alone — so `steps_due_this_week` has never included a
-- prerequisite task since the day it landed. A-108's column comment states
-- the intent; nothing implemented it.
--
-- WHY A NEW TABLE RATHER THAN FIXING THE REFRESH IN PLACE
--
-- `ob_dashboard_summary` is keyed (stat_date, product_id) and a
-- prerequisite task has no product. It hangs off the *client*: one
-- checklist per client, snapshotted at intake (B-125), gating every
-- journey the client bought rather than any one of them. Two ways to force
-- it into the product grain and both are wrong:
--
--   * attribute each task to every product the client bought — the
--     per-product figure is then right and the all-products SUM counts a
--     multi-product client's checklist once per product, which is the
--     `clients_overdue` over-count B-121 already had to flag, spread onto
--     two more cards. Plan §4 makes multi-product the normal case.
--   * attribute each task to one chosen product — the SUM is then exact
--     and a product-filtered board hides a checklist that is blocking that
--     very product.
--
-- Neither survives the case this deployment is actually in: **five of its
-- nine clients hold open prerequisite tasks and no journey at all**
-- (intake done, products not yet instantiated). A product-keyed row cannot
-- represent those tasks under any attribution, and they are thirteen of
-- the seventeen the drill-over was listing. So the grain has to be the
-- client.
--
-- This is the table B-121's own backlog note asked for, in its words: "a
-- client-keyed `ob_client_daily_stats` beside A-108's two — the shape
-- ticketing already landed on with `client_daily_stats`". An org-wide
-- sentinel row in `ob_dashboard_summary` is blocked by `product_id`'s
-- foreign key to `ob_products`, which is why that was never the answer.
--
-- Not a protected table (CLAUDE.md lists `tickets`, `ticket_history`,
-- `ticket_effort_logs`, `ticket_stage_transitions`), so no Stream A review
-- gate — same footing as B-119's `V20260909_1830__ob_signoff_csat.sql`.
--
-- STOCK ONLY, TODAY ONLY, LIKE ITS TWO SIBLINGS. `status` is a current
-- value with no history behind it, so "how many checklists were overdue on
-- 12 August" is unrecoverable once that day passes; recomputing a past day
-- would overwrite it with today's answer and flatten the trend into a line
-- while looking exactly as it always has. The refresh deletes and rewrites
-- the current day and never touches an earlier one, so the table starts
-- empty and fills forward — a board blank for its first days is correct.
--
-- SUMMING THESE ROWS IS EXACT, unlike three of `ob_dashboard_summary`'s
-- seven cards. A prerequisite task belongs to exactly one client, so the
-- rows partition the population and the all-products total is a plain
-- SUM; filtering to a product is an EXISTS against the client's journeys,
-- which is the same predicate the drill-over applies to its PREREQUISITE
-- branch. Card and list therefore agree filtered *and* unfiltered, which
-- is the whole point of the fix.
-- =====================================================================

CREATE TABLE ob_client_daily_stats (
  stat_date                   DATE         NOT NULL,
  ob_client_id                BIGINT       NOT NULL,

  -- ── stock: the client's open checklist as at this day ───────────────
  -- Open is PENDING + SUBMITTED (ObPrereqTaskStatus.isSettled is the
  -- other two). SUBMITTED counts: the client has answered but staff have
  -- not verified, so the task is still somebody's to do and still on the
  -- drill-over, whose predicate is `NOT IN ('VERIFIED','SKIPPED')`.
  prereq_tasks_open           INT          NOT NULL DEFAULT 0,

  -- ── the two OB-02 cards this table exists for ───────────────────────
  -- Both are counts of *tasks*, per §9's "all client tasks", and both are
  -- added to `ob_dashboard_summary`'s `steps_due_*` on read rather than
  -- written into it — the two halves live at different grains and a
  -- pre-added total would have to pick one of them to be wrong at.
  prereq_tasks_due_today      INT          NOT NULL DEFAULT 0,
  prereq_tasks_due_this_week  INT          NOT NULL DEFAULT 0,

  -- Open and past due. Nothing reads this yet: the Overdue Clients card
  -- counts clients with an overdue *service* and its drill-over excludes
  -- prerequisites, so the two agree today and this column is not what
  -- would change that. Written anyway because it falls out of the same
  -- pass for free, and because the alternative when that card does grow a
  -- prerequisite arm is a second migration to add one integer.
  prereq_tasks_overdue        INT          NOT NULL DEFAULT 0,

  computed_at                 DATETIME(6)  NOT NULL,

  -- Leading stat_date, matching ob_dashboard_summary, ob_implementor_daily_stats
  -- and all four ticketing summary tables: the board reads one day across
  -- every client.
  PRIMARY KEY (stat_date, ob_client_id),
  -- One client across a range — OB-03's row and any per-client trend,
  -- which the PK's leading date cannot serve.
  KEY ix_ob_client_stats_client (ob_client_id, stat_date),
  CONSTRAINT fk_ob_client_stats_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
