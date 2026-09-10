-- =====================================================================
-- A-1xx · Client Onboarding — the per-scope card board
--
-- Table: ob_scope_dashboard_summary
--
-- Source:  STREAM-B-MASTERS.md, B-121's follow-up note — "Both of those
--          want the same follow-up, and it is a Stream A migration. A
--          scope dimension on `ob_dashboard_summary` fixes the second."
--          ObDashboardScope's own header ("the summary table has no
--          scope dimension, and two of the five roles therefore cannot
--          be answered from it")
--          CLAUDE.md: "Never live `COUNT(*)` for dashboards."
--
-- WHAT WAS BROKEN
--
-- `ob_dashboard_summary` is keyed (stat_date, product_id). A-112's
-- `OnboardingScopeResolver` narrows OB_SALES to "journeys whose client
-- they created" and OB_STEP_OWNER to "journeys containing their steps".
-- Neither is a predicate a product-keyed pre-aggregate can express —
-- there is no column to intersect against — so B-121 shipped those two
-- roles a board of seven `unavailableReason` sentences rather than break
-- either the contract or the no-live-COUNT rule quietly.
--
-- That was the honest answer available at the time and it is the wrong
-- one to keep: OB_SALES and OB_STEP_OWNER are 21 of the 27 people who
-- open OB-02, and a board that never shows them a number is a board they
-- stop opening. This table is the column to intersect against.
--
-- WHY A SECOND TABLE RATHER THAN A COLUMN ON THE FIRST
--
-- A-108 already argued this and the argument has not changed: forcing
-- two grains into one table means a nullable dimension and a
-- `WHERE scope_user_id IS NULL` on every card query, "which is how a
-- summary table starts double-counting — the moment someone forgets the
-- predicate, every card silently includes the per-person rows as well".
-- The ticketing side is four tables at four grains, not one with four
-- nullable columns, and A-108 made `ob_implementor_daily_stats` a
-- separate table on exactly this reasoning. This is that precedent a
-- third time.
--
-- It also keeps the change additive. Nothing reads this table until
-- B-121's service is pointed at it, so the existing board, B-122's
-- reports and both v1.2 grids are untouched by this migration.
--
-- THE THREE UNRESTRICTED ROLES ARE NOT STORED HERE
--
-- OB_ADMIN, OB_MANAGER and OB_VIEWER see every journey, which is what
-- `ob_dashboard_summary` already counts. Duplicating them into this
-- table would store the same figure twice at two grains and invite the
-- two to disagree after a partial refresh. They keep reading the parent.
--
-- SO ROWS EXIST ONLY FOR A LIVE OB_SALES / OB_STEP_OWNER GRANT, and the
-- population is the refresh job's obligation rather than something this
-- schema can enforce — the same kind of obligation A-108 recorded for
-- "a row is written for an implementor with zero clients". The rule
-- here is the opposite one and is worth stating for the same reason:
-- **a scope with nothing visible gets no row at all.** A zero row would
-- claim "nothing of yours is overdue", and B-121's service needs to be
-- able to tell that apart from "you have not been given any services
-- yet". Absence is what carries the difference.
--
-- STOCK ONLY, AND FOR THE REASON A-108 GIVES
--
-- RAG, gate status and step status are *current* values with no history
-- behind them, so a past day's stock can be recorded but never
-- recomputed. This table therefore starts empty and fills forward from
-- the day it lands, exactly as its parent did.
--
-- The three flow columns (journeys_started, journeys_went_live,
-- steps_completed) are deliberately absent. No OB-02 card reads them —
-- they feed §10's reports, which are org-wide — so storing them per
-- scope would be storing a number with no reader.
--
-- THE CLIENT-COUNTED CARDS OVERSTATE HERE EXACTLY AS THEY DO UPSTAIRS
--
-- clients_overdue, clients_live and clients_escalated are written as
-- COUNT(DISTINCT ob_client_id) *within* a product, so summing the
-- product rows counts a multi-product client once per product. That is
-- the same upper bound `ObDashboardSummaryRepository` documents at
-- length for the parent, and it is deliberately not fixed here: one
-- rule for both tables means `countIsUpperBound` keeps one meaning on
-- the wire, and a board that were exact for a Step Owner and
-- approximate for an Admin would be harder to explain than one that is
-- approximate for both. A client-keyed `ob_client_daily_stats` is still
-- the fix for both, and is still its own task.
--
-- WHO IS WAITING (docs/DEPENDENCIES.md):
--     B-120 the refresh job (a fourth stock pass) ·
--     B-121 OB-02 (routes the two narrowed roles here)
-- =====================================================================


-- ---------------------------------------------------------------------
-- ob_scope_dashboard_summary — `ob_dashboard_summary`'s stock columns,
-- one set per (narrowed caller, product, day).
--
-- Column names match the parent's exactly. That is load-bearing rather
-- than tidy: `ObDashboardSummaryRepository.EXPRESSIONS` holds the seven
-- cards' arithmetic as SQL fragments over those names, and matching them
-- lets the scoped reader reuse the same map instead of keeping a second,
-- subtly different copy of "what does At Risk mean" in step by hand.
--
-- THE THREE RAG COLUMNS PARTITION journeys_open_running, the same
-- arithmetic contract the parent carries. One CASE per journey,
-- worst-wins. journeys_locked and journeys_held are open and colourless
-- and are counted in none of the three.
-- ---------------------------------------------------------------------
CREATE TABLE ob_scope_dashboard_summary (
  stat_date               DATE         NOT NULL,
  -- The caller these figures are narrowed to. Never a caller with an
  -- unrestricted role; see the header.
  scope_user_id           BIGINT       NOT NULL,
  product_id              BIGINT       NOT NULL,

  -- ── stock: what was true for this caller at the end of this day ─────
  journeys_total          INT          NOT NULL DEFAULT 0,
  journeys_locked         INT          NOT NULL DEFAULT 0,
  journeys_held           INT          NOT NULL DEFAULT 0,
  journeys_open_running   INT          NOT NULL DEFAULT 0,
  journeys_completed      INT          NOT NULL DEFAULT 0,

  -- Disjoint by construction, and conditioned on RUNNING.
  rag_green               INT          NOT NULL DEFAULT 0,
  rag_amber               INT          NOT NULL DEFAULT 0,
  rag_red                 INT          NOT NULL DEFAULT 0,

  -- ── the OB-02 cards ─────────────────────────────────────────────────
  steps_due_today         INT          NOT NULL DEFAULT 0,
  steps_due_this_week     INT          NOT NULL DEFAULT 0,
  steps_overdue           INT          NOT NULL DEFAULT 0,
  clients_overdue         INT          NOT NULL DEFAULT 0,
  clients_live            INT          NOT NULL DEFAULT 0,
  clients_onboarding      INT          NOT NULL DEFAULT 0,
  clients_escalated       INT          NOT NULL DEFAULT 0,

  computed_at             DATETIME(6)  NOT NULL,

  -- Leading stat_date matches the parent and every ticketing summary
  -- table. The board's read is "one caller, one day, every product",
  -- which this serves on its first two columns.
  PRIMARY KEY (stat_date, scope_user_id, product_id),
  -- One caller across a range, for a trend the parent serves with
  -- ix_ob_summary_product. The PK leads with the date and cannot.
  KEY ix_ob_scope_summary_user (scope_user_id, stat_date),
  CONSTRAINT fk_ob_scope_summary_user
    FOREIGN KEY (scope_user_id) REFERENCES users (id),
  CONSTRAINT fk_ob_scope_summary_product
    FOREIGN KEY (product_id) REFERENCES ob_products (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
