-- =====================================================================
-- A-108 · Client Onboarding — the pre-aggregated dashboard tables
--
-- Tables: ob_dashboard_summary, ob_implementor_daily_stats
--
-- Source:  docs/Onboarding-Module-Plan.md §4 ("dashboards keep reading
--          `ob_dashboard_summary`, which gains a product dimension"),
--          §9 OB-02 (the cards and the two grids), §10 (reports)
--          CLAUDE.md: "Never live `COUNT(*)` for dashboards."
--
-- WHY TWO TABLES WHERE PLAN §4 NAMES ONE
--
-- §4 lists `ob_dashboard_summary` alone. OB-02 asks for two things at two
-- different grains: cards and roll-ups that are **journey-counted with a
-- product dimension**, and an **implementor workload & performance grid**
-- with one row per person. Those cannot share a primary key — a row keyed
-- by product cannot express "how many clients is Ravi carrying", and one
-- keyed by person cannot express "how many ERP journeys are at risk".
--
-- Forcing both into one table means a nullable dimension and a
-- `WHERE user_id IS NULL` on every card query, which is how a summary
-- table starts double-counting: the moment someone forgets the predicate,
-- every card silently includes the per-person rows as well.
--
-- The ticketing side already answered this the same way — `daily_ticket_stats`
-- (project), `resource_daily_stats` (person), `client_daily_stats` (client)
-- and `module_daily_stats` (module) are four tables, not one with four
-- nullable columns. **This is that precedent, not a new idea**, and it is
-- flagged here rather than left as a silent expansion of §4's list.
--
-- The alternative was to let B-128 add the second table when it builds the
-- grid. That would mean a Stream B task needing a Stream A migration
-- mid-sprint, on the one screen where CLAUDE.md's no-live-`COUNT(*)` rule
-- is absolute.
--
-- BOTH TABLES ARE STOCK-AND-FLOW, AND THE STOCK COLUMNS CANNOT BE
-- BACKFILLED. RAG, gate status and step status are *current* values with
-- no history behind them, so "how many ERP journeys were amber on 12
-- August" is unrecoverable once that day passes — reconstructing it per
-- day per product is exactly the live computation these tables exist to
-- avoid. So both start empty and fill forward from the day they land, and
-- a chart blank for its first days is correct rather than broken. Stated
-- here rather than discovered, following `module_daily_stats`' own note.
--
-- WHO IS WAITING (docs/DEPENDENCIES.md):
--     B-120 the refresh job · B-121 OB-02 · B-122 OB-10 reports ·
--     B-127 the v1.2 cards and drill slide-over · B-128 the two grids
-- =====================================================================


-- ---------------------------------------------------------------------
-- ob_dashboard_summary — journey-counted, per product, per day.
--
-- The grain is (stat_date, product_id) because §4 says the roll-ups gain a
-- product dimension and §9 groups OB-07 and the funnel by product. A
-- product-less total is `SUM(...) GROUP BY stat_date`, which is cheap; the
-- reverse — recovering a per-product split from a total — is not possible
-- at all, so the finer grain is the one worth storing.
--
-- THE THREE RAG COLUMNS PARTITION THE OPEN POPULATION, and that is a
-- constraint on whoever writes the refresh rather than a hint. §9's cards
-- and the funnel make an arithmetic claim: `rag_green + rag_amber +
-- rag_red` must equal `journeys_open_running`. Three independent counts
-- would double-count a journey that is both overdue and blocked, every
-- product's figures would overstate in proportion to how badly it is
-- running, and each number would stay individually plausible. One `CASE`
-- per journey, worst-wins, exactly as `module_daily_stats` does it.
--
-- `journeys_locked` IS NOT A RAG STATE. A journey whose gate has not
-- cleared has no colour at all (A-104's own note, and the contract's
-- nullable `rag`), so it is counted here and in none of the three colours.
-- OB-03 renders it as "Prerequisites pending". A refresh that folded it
-- into green would report a client who has not started as on track.
--
-- `journeys_held` is the §5.5 service-level hold — past the gate and still
-- waiting on a sibling journey. Also not a colour, and also not locked;
-- it is the third way a journey can be open and not running, and the
-- Delayed Projects grid needs to tell it apart from a genuine delay.
-- ---------------------------------------------------------------------
CREATE TABLE ob_dashboard_summary (
  stat_date               DATE         NOT NULL,
  product_id              BIGINT       NOT NULL,

  -- ── stock: what was true at the end of this day ─────────────────────
  journeys_total          INT          NOT NULL DEFAULT 0,
  -- Gate not cleared. No RAG. See the note above.
  journeys_locked         INT          NOT NULL DEFAULT 0,
  -- Past the gate, held behind a sibling journey (§5.5). Also no RAG.
  journeys_held           INT          NOT NULL DEFAULT 0,
  -- Open and actually running. The three RAG columns partition THIS.
  journeys_open_running   INT          NOT NULL DEFAULT 0,
  journeys_completed      INT          NOT NULL DEFAULT 0,

  -- Disjoint by construction. One CASE per journey, worst-wins.
  rag_green               INT          NOT NULL DEFAULT 0,
  rag_amber               INT          NOT NULL DEFAULT 0,
  rag_red                 INT          NOT NULL DEFAULT 0,

  -- ── the OB-02 cards ─────────────────────────────────────────────────
  -- "Today's Delivery" and "This Week's Deadlines" — services *and*
  -- prerequisite tasks due, per §9's own wording ("all client tasks").
  steps_due_today         INT          NOT NULL DEFAULT 0,
  steps_due_this_week     INT          NOT NULL DEFAULT 0,
  steps_overdue           INT          NOT NULL DEFAULT 0,
  -- Distinct clients with at least one overdue item — the "Overdue
  -- Clients" card counts clients, not items, and the two differ whenever
  -- one client is late on several services.
  clients_overdue         INT          NOT NULL DEFAULT 0,
  clients_live            INT          NOT NULL DEFAULT 0,
  clients_onboarding      INT          NOT NULL DEFAULT 0,
  -- Clients with an open portal escalation. Again clients, not
  -- escalations: one open per service (A-128) means a client with three
  -- unhappy services would otherwise triple its own card.
  clients_escalated       INT          NOT NULL DEFAULT 0,

  -- ── flow: what happened on this day ─────────────────────────────────
  journeys_started        INT          NOT NULL DEFAULT 0,
  journeys_went_live      INT          NOT NULL DEFAULT 0,
  steps_completed         INT          NOT NULL DEFAULT 0,

  computed_at             DATETIME(6)  NOT NULL,

  -- Leading stat_date matches all four ticketing summary tables and
  -- serves the dashboard's access pattern: a contiguous date range across
  -- every product.
  PRIMARY KEY (stat_date, product_id),
  -- §10's time-to-live trend reads one product across a range, which the
  -- PK cannot serve — its leading column is the date.
  KEY ix_ob_summary_product (product_id, stat_date),
  CONSTRAINT fk_ob_summary_product
    FOREIGN KEY (product_id) REFERENCES ob_products (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ob_implementor_daily_stats — one row per Implementor, per day.
--
-- §9 OB-02's second grid. "Implementor" is the v1.2 word for a service's
-- owner; the column is `user_id` because that is what
-- `ob_journey_steps.owner_user_id` holds.
--
-- **A ROW IS WRITTEN FOR AN IMPLEMENTOR WITH ZERO CLIENTS**, and §9 says
-- so explicitly ("one row per implementor *including those with zero
-- clients*"). That is a refresh-job obligation this schema cannot enforce,
-- and it is the requirement most likely to be lost: the natural
-- implementation groups by owner over open steps, which produces no row at
-- all for somebody who has just finished everything — and the grid then
-- shows a fully-delivered implementor as absent rather than as clear. B-128
-- and B-120 own that; it is written here because this is where somebody
-- will come looking for the column list.
--
-- THE SIX WORKLOAD COLUMNS PARTITION THE IMPLEMENTOR'S OPEN CLIENTS, the
-- same arithmetic contract `ob_dashboard_summary`'s RAG columns carry:
-- on_track + not_started + delayed + at_risk + blocked_waiting +
-- ahead_of_schedule = clients_open. §9 lists exactly these six.
--
-- **The performance score is NOT stored, and that is deliberate.** §9
-- describes it as "computed from on-time + early completions weighted
-- against delays and blocks". A weighting is a product decision that will
-- be tuned, and a stored score computed under last month's weights cannot
-- be recomputed under this month's — every historical row would silently
-- mean something different from the ones beside it. The four completion
-- counters below are the inputs; the score is derived on read, so changing
-- the formula re-scores history consistently instead of stratifying it.
-- Same argument A-073's weekly stats made for storing sums rather than
-- pre-divided averages.
-- ---------------------------------------------------------------------
CREATE TABLE ob_implementor_daily_stats (
  stat_date            DATE         NOT NULL,
  user_id              BIGINT       NOT NULL,

  -- ── workload: the six §9 columns, disjoint, summing to clients_open ──
  clients_open         INT          NOT NULL DEFAULT 0,
  on_track             INT          NOT NULL DEFAULT 0,
  not_started          INT          NOT NULL DEFAULT 0,
  -- Backticked because `DELAYED` is a MySQL reserved word — the remains of
  -- `INSERT DELAYED`, removed as a feature but still reserved in 8.4. The
  -- name is kept rather than worked around: §9 lists these six as the
  -- grid's own columns and B-128 maps them straight to its headers, so
  -- renaming one here would put a translation step between the spec and
  -- the screen for the sake of two backticks.
  `delayed`            INT          NOT NULL DEFAULT 0,
  at_risk              INT          NOT NULL DEFAULT 0,
  blocked_waiting      INT          NOT NULL DEFAULT 0,
  ahead_of_schedule    INT          NOT NULL DEFAULT 0,

  -- ── the performance score's inputs. The score itself is derived on
  --    read; see the note above on why it is not a column. ─────────────
  completed_on_time    INT          NOT NULL DEFAULT 0,
  completed_early      INT          NOT NULL DEFAULT 0,
  completed_late       INT          NOT NULL DEFAULT 0,
  -- Working hours this implementor's steps spent blocked or waiting. The
  -- "weighted against delays and blocks" half of §9's formula.
  blocked_hours        INT          NOT NULL DEFAULT 0,

  computed_at          DATETIME(6)  NOT NULL,

  PRIMARY KEY (stat_date, user_id),
  -- The grid reads one implementor across a range for their trend; the PK
  -- leads with the date and cannot serve it.
  KEY ix_ob_implementor_user (user_id, stat_date),
  CONSTRAINT fk_ob_implementor_stats_user
    FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
