# `feature/masters/stages` — S-13 tab 2, the Stage Master (B-040, B-042)

| File | What it is |
|---|---|
| `StageController.java` | Eight operations. Six from B-040, two from B-042. |
| `StageService.java` | Every rule the schema cannot state. |
| `StageDtos.java` | The wire shapes — and **two** stage shapes, which is a decision. |
| `StageUsageRepository.java` | The two counts that decide whether a code may still be renamed. |
| `StageExceptionHandler.java` | Seven refusals, seven remedies. |

## One migration, and it arrived a task late

B-040 needed none: `workflow_stages` has existed since A-005, B-004 seeded three
templates with 8 + 5 + 5 stages, and `WorkflowStage` has been read by Stream C's
reopen and Stream D's stage-SLA scanner since. **Nothing had ever written to the
table** — every ribbon an Admin could see was a migration.

B-042 adds `V20260818_2140__workflow_stage_deprecation.sql`: `is_deprecated` and
`deprecated_at`, with a `CHECK` keeping them in step. `workflow_stages` is A-005's
table and none of the four protected ones, so CLAUDE.md asks for no Stream A
review — the same call B-039 made about `statuses`.

## A stage belongs to a template, and §7.4 reads as though it does not

The blueprint describes tab 2 as one flat list of "the ribbon stages" and tab 3 as
templates "built by picking stages" — which implies a catalogue that templates
draw from. There is no such table. `workflow_stages.template_id` is `NOT NULL`
behind a cascading foreign key, so `DEV` on Standard Dev Flow and `DEV` on Support
Fast-Track are two independent rows that share a code.

CLAUDE.md settles it: PLAN.md wins on implementation and A-005 is what it
produced. The tab carries a template selector and every route is scoped beneath
one. The alternative reading would have meant a new table, a migration touching
the ribbon, and two answers to what a stage is.

## Renaming a code in use is the worst edit here, and it fails silently

`ticket_stage_transitions.to_stage` stores the code as plain text with no foreign
key, and `StageSlaRepository` joins `ws.stage_code = tr.to_stage`. A rename
therefore does two things at once and **neither of them errors**:

- every historical ribbon segment stops resolving to a stage definition, and
- the §4A.7 "stuck in stage" scan stops matching those rows and never alerts on
  them again.

The second is the one that matters. A silent gap in an escalation scanner is not
a bug anybody reports; it is an alert that does not arrive. So the code is frozen
the moment either usage count is above zero, and `isCodeEditable` travels on the
view so the form does not restate the rule.

On a stage nothing has entered, a rename is provably safe — no row anywhere holds
the old code. That is the only case it is allowed in, and it is the case that
matters: a typo caught the same afternoon.

## The reorder is two passes, and that is MySQL's requirement rather than a style

`uq_workflow_stages_seq (template_id, seq)` is a unique key, and InnoDB enforces
it **per row** — MySQL has no deferred constraints. Writing the final values
straight out collides the instant two stages swap: setting the row at 20 to 10
fails while 10 is still occupied, and the transaction rolls back on an operation
the Admin experiences as dragging one row up.

The first pass parks every row above the occupied range, the second writes
10, 20, 30 …, and both are in one transaction so no reader sees the parking
values. The offset is computed from the current maximum rather than being a
constant, so a template already at unusual values cannot collide with it.

**No mock can catch this**, which is why `StageMasterIT` exists and why its swap
and full-reversal cases are the two that justify the container.

## `canReturnTo` is a backward target, and a reorder can invalidate one

§4A.1's loop-back table is entirely backward moves; a forward "return" is an
ordinary advance with a reason attached. So the column is validated on the write
*and* re-checked from the reorder's side against the order being proposed: drag
`DEV` past `QA` while `QA → DEV` exists and the reorder is refused, naming every
pair it would break. The screen renders the same sentence before the request.

The seed obeys this rule on all three templates, and `StageMasterIT` asserts that
rather than trusting it — B-004 wrote those arrays by hand before any code
validated them, and a seed that disagreed would refuse an Admin's very first drag
for a state they did not create.

## Two stage shapes on the wire, and the reason is a screen that already shipped

`Stage` is the row S-13 tab 2 edits: `id`, `templateId`, `seq`, `position`, and
the two usage counts. `WorkflowStage` — the array still inline on
`WorkflowTemplate` — is the ribbon as a *vocabulary*: code, name, sequence, owner
role, no identity.

B-040's drafting removed the inline array as redundant. It is not.
`TicketListPage` has built S-25's stage filter from it since C-013, deduplicating
by `stageCode` across every template, and removing it would have emptied a live
filter to tidy a response. Caught by `tsc`, not by review.

Serving the editing shape there instead would have put four fields with no meaning
to a filter into a response every ticket list reads — and two grouped `COUNT`
queries per template behind them.

## Deprecated, never deleted — B-042

§7.4: *"Stages used by live tickets can only be deprecated, never deleted —
otherwise historical ribbons would break."* B-040 shipped this package with **no
removal at all** rather than a delete this task would have had to take away; the
narrower "delete only where both counts are zero" was drawn and rejected then, on
the ground that it would put a delete route on the ribbon's definition table
before the rule protecting that table existed.

That rule is `setDeprecated` and `delete`, and the ordering was the point: they
land together.

**Deprecating a stage that live tickets are standing in is allowed, and the
obvious guard against it is exactly wrong.** §7.4's clause is about stages *used
by live tickets*, so refusing on `openTicketCount` would refuse the only case the
word "deprecated" is in the blueprint to describe. Those tickets keep rendering
their segment and keep their ordinary way out of it. What deprecation stops is new
*entry*.

Two things are refused, and both are states an Admin could not get back out of:

- **The template's last live stage.** A workflow with nothing live routes no
  ticket at all, and nothing would notice — the create form's picker would go on
  offering it. B-039 refused a status retire on the same ground.
- **A live stage that still returns to it.** `can_return_to` is a whitelist of
  moves the transition service will honour, so an arrow into a retired stage is an
  entry into a stage nothing may enter. Same failure the reorder refuses from the
  other direction, so it reuses that problem type and its `pairs` property — the
  screen highlights both ends of each pair on the ribbon it is already drawing,
  and should not need to know which operation produced the list.

**Delete survives, narrowly.** Both counts zero, nothing live returning to it, and
not the last live stage — the typo caught the same afternoon. Everything else is
409 `stage-in-use` carrying both counts *and* `canDeprecate: true`, because an
Admin told "no" with no alternative concludes the row cannot be got rid of.

**Nothing in the database would have refused any of this**, which is why the rule
is a service and not a constraint: `ticket_stage_transitions.to_stage` and
`tickets.current_stage` hold the code as plain text with no foreign key onto this
table, so a delete cascades nothing, fails nowhere, and takes every historical
ribbon segment's meaning with it in silence.

**Two routes, not a field on the `PATCH`.** The patch's convention is that null
means "leave it alone", so a boolean there would carry three wire states for a
column with two — and the one write in this package with a consequence for live
tickets would arrive indistinguishable from a display-name edit.
`/users/{userId}/status` and `/clients/{clientId}/status` are separate setters for
the same reason, and the deprecation route inherits their `NO_IF_MATCH`
exemption. The `DELETE` does **not**: its whole guard is that both usage counts
are zero, and both are inside the tag `getStage` emits.

## What this package still does not do

**It does not refuse to edit a template with live tickets.** A-005's own header
says a template is "versioned by copy, never edited in place", and that is kept by
**B-043**'s designer cloning — there is no version column to clone into yet, and a
tab that refused every template with an open ticket would refuse all three seeded
ones and be unusable on the day it ships. What the service does instead is put
`openTicketCount` on every stage so the screen states the number before the drag.

## `listWorkflowTemplates` was declared, mocked and never mounted

The sixth case this stream has found — and the first where the declared *shape*
had drifted too. `version`, `projectId` and `taskTypeId` name no column and no
mapping table, so B-040 removed them rather than emit a hard-coded `1` and two
nulls, and the two query parameters that filtered on them went with the fields: a
filter that silently ignores its argument is worse than one that does not exist.
The mapping is **B-041**'s, along with the table to store it in.

## Tests

| Suite | What it holds |
|---|---|
| `StageServiceTest` | 34 — the four refusals, both usage paths, the reorder's completeness and direction rules |
| `StageControllerTest` | 18 — both preconditions, both `ETag` scopes, the 404 before them, and B-042's `DELETE` shape |
| `StageBodyValidationTest` | 15 — the annotations, including the `slaHours` floor and that `seq` is not patchable |
| `StageMasterIT` | 19 against real MySQL — the unique key the reorder has to survive, the counts' columns, their template scope, B-004's seed, and that `ck_workflow_stages_deprecation` is enforced rather than parsed |

B-040's `StageControllerTest.noDeleteExists` and `StagesTab.test.tsx`'s *"there is
no delete"* were both replaced by B-042 rather than deleted. Each named this task
as the one that would change it, and each did real work while it lived.

## For Stream C and Stream D — two consequences in your files

Recorded rather than done, because neither is this stream's code.

**`ReopenService` (C)** validates a restart stage with
`findByTemplateIdAndStageCode(...).isEmpty()`. That now accepts a *deprecated*
stage, so a reopen can restart a ticket into a stage the master says is retired.
Whether that should be refused is C's call — it may well be right to allow it on a
cycle that ran on the old ribbon.

**The stage-SLA scanner (D)** must go on matching deprecated codes for history —
`ticket_stage_transitions` rows naming them are exactly the rows §4A.7's scan is
for. What is worth a look is whether a *new* stuck-in-stage alert should be raised
against a retired stage; nothing here changes the query either way.
