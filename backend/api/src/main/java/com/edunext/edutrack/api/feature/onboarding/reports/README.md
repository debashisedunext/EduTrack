# `feature/onboarding/reports` — OB-10

B-122. Two routes: `GET /onboarding/reports` (the catalogue behind the hub's
card grid) and `GET /onboarding/reports/{reportKey}` (the parameterised runner
behind the viewer, JSON or a file).

## What is here

| Class | Owns |
|---|---|
| `ObReportController` | The two routes, `If-None-Match`, and the `?export=` branch. |
| `ObReportService` | Catalogue, date window, scope resolution, the ETag, dispatch. |
| `ObReportCatalogue` | The twelve descriptors plan §10 specifies — six runnable. |
| `ObReportScope` | A-112's row-scope rule, expressed as SQL predicates. |
| `ObReportRepository` | Every statement, in one auditable file. |
| `ObReportRunner` + six runners | "What are the columns and rows", and nothing else. |
| `ObStepRag` | The health formula, shared by the chip and the `?rag=` filter. |
| `ObReportExportService` | The translation into the shared export engine. |
| `ObReportSensitivity` | B-123 · what a column holds, as a disclosure question. |
| `ObExportRedaction` | B-123 · masking it, once, before anything can read it. |

## Four things to read before changing anything here

### 1. `ObReportScope` is A-112's rule written a second time

`OnboardingScopeResolver` answers with a JPA `Specification<ObJourney>`. Every
report here is an aggregate that Criteria cannot express over a single root, so
the rule reaches SQL through `ObReportScope` instead.

**Two expressions of one security rule is the arrangement that rots silently.**
`ObReportsIT.theSqlAndTheSpecificationAgree` runs both against the same rows for
all five module roles plus the two deny cases and asserts they select the same
journeys. That test is the only thing making the duplication survivable — if it
is ever deleted, `ObReportScope` must be too.

Resolving the scope to a list of journey ids (the way `ReportScope` handles
projects one module over) was considered and does not carry: a salesperson two
years in has thousands of clients, and every one of those ids would land in the
SQL text of every report they open.

### 2. These are live reads, and that does not break CLAUDE.md

"Never live `COUNT(*)` for dashboards. Read the pre-aggregated summary tables."

These are reports, not dashboards — the same distinction the ticketing side
already draws between `ReportRepository` (summaries) and
`TicketReportRepository` (the ticket tables). Every report here is at a grain
`ob_dashboard_summary` does not have: per step, per owner, per sales person, per
sign-off. A table keyed `(stat_date, product_id)` cannot answer any of them.

A report is also opened deliberately by one person rather than polled by every
open browser, which is what made the dashboard rule necessary in the first
place. OB-02's board — B-121 — reads the summary table and must go on doing so.

### 3. Redaction happens in `run()`, and the task is still called export redaction

B-123. `ObExportRedaction.apply` sits immediately after `runner.run(...)` and
before the `Report`, the `ETag` and either serialiser — so **no unredacted
value exists past that line**.

Putting it in `ObReportExportService` would have matched the task's name and
been wrong in a way that is easy to miss: the viewer renders the JSON from the
same call, so the spreadsheet would have been safer than the page it was
downloaded from. Putting it in each runner makes it six rules that drift. It
rides the same seam scope does, for the reason `ObReportExportService` already
gives — there is only one `run()`.

**No built report has a sensitive column today**, and that is the point of the
guard rather than an argument against it. `ObReportDtos.Column`'s constructor
refuses a column whose key or label reads as a PAN or an amount and is declared
`ORDINARY`, so the seventh report cannot add one without deciding. Every runner
here builds its column list under `ObReportRunnersTest`, which is what makes the
refusal land in CI rather than in somebody's download.

**Exports carry the masked PAN for every role, OB_ADMIN included.** The
on-screen rule names OB_ADMIN and OB_MANAGER as the roles that may see one, but
A-113 made the unmasked value a *reveal operation* that writes one audit row per
disclosure. A five-hundred-row export cannot write five hundred of those, so an
unmasked column here would be the only bulk read of PAN in the product with no
trail behind it. `ObExportRedactionTest.ExportPath` pins it for all five module
roles.

### 4. Durations are working hours, computed by `WorkingHoursService`

A-118 puts it on the contract: `duration` on this surface is working hours,
"already through the calendar, so a client formatting it must not re-derive it
from two timestamps". Three runners call `WorkingHoursService`, which is
B-024's and is the product's only definition of working time.

That costs **one calendar call per row**, because the service has no batched
form. It is accepted here — a report is bounded by a date range and is not on a
hot path — and the fix is real and belongs on `WorkingHoursService` rather than
in a report that hand-rolls a cache: a batched `workingHoursBetween` over many
windows sharing one holiday-and-leave load would collapse the round trips to
two.

`AVG(TIMESTAMPDIFF(…))` in MySQL is the tempting alternative and it is
calendar-blind, which for a multi-week boarding is most of the difference.

## Adding a report

1. Write a `ObReportRunner` `@Component` with a `KEY` constant.
2. Add its statement to `ObReportRepository`, carrying
   `scope.journeyPredicate(alias)` or `scope.clientPredicate(alias)`.
3. Flip its descriptor in `ObReportCatalogue` from `held(...)` to `built(...)`.

`ObReportCatalogueTest` refuses a descriptor marked available with no runner,
and refuses an unavailable one with no reason. Nothing else needs touching —
the ETag, the scope, the date window and both export formats come from the
engine above.

## What is deliberately not built

Six of the twelve descriptors are `available: false`, in two groups with
genuinely different reasons, which is why the reasons are on the cards rather
than averaged into one sentence:

- **Five are held as OB4b** pending the product decision in
  PHASE-2-BUILD-PLAN §11.6 — breach log, escalation log, owner workload,
  communication audit, CSAT summary. Nothing technical is missing.
- **Prerequisite aging is waiting on tables.** It reads
  `ob_client_prereq_tasks`, which B-124/B-125 create and no applied migration
  contains. A-118's MSW mock declared it available; B-122 corrected the mock
  rather than leaving two servers disagreeing about one key.

## Known gaps, named rather than discovered

- **`due_at` is C-105's and is not filled yet.** TAT compliance therefore
  reports `completed` and `measured` separately and sends a null percentage
  where nothing could be judged. That is the report saying it cannot answer,
  which is the answer — not a bug to paper over with a zero.
- **The owner filter's list is every user**, not every OB_STEP_OWNER. The right
  source is `listObImplementorWorkload`, which is B-128's and has no
  implementation behind it. Flagged in `ObReportFilterBar`.
- **`If-None-Match` is now the fourth copy** of the same helper, after
  `DashboardController`, `CalendarController` and `ReportController`. Still
  local, because `common/` is Stream A's directory and the extraction wants its
  own review rather than being a side effect of a reports task.
