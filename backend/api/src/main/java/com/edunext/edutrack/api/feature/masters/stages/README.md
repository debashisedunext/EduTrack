# `feature/masters/stages` — S-13 tab 2, the Stage Master (B-040)

| File | What it is |
|---|---|
| `StageController.java` | Six operations. Five new, one served for the first time. |
| `StageService.java` | Every rule the schema cannot state. |
| `StageDtos.java` | The wire shapes — and **two** stage shapes, which is a decision. |
| `StageUsageRepository.java` | The two counts that decide whether a code may still be renamed. |
| `StageExceptionHandler.java` | Five refusals, five remedies. |

## No migration

`workflow_stages` has existed since A-005 and B-004 seeded three templates with
8 + 5 + 5 stages. `WorkflowStage` and `WorkflowStageRepository` have been read by
Stream C's reopen and Stream D's stage-SLA scanner since. **Nothing had ever
written to the table** — every ribbon an Admin could see was a migration.

So this task is API and UI only, no schema change, and CLAUDE.md's Stream A review
does not apply.

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

## What this package deliberately does not do

**There is no delete.** §7.4: *"Stages used by live tickets can only be
deprecated, never deleted — otherwise historical ribbons would break."* The
deprecation flag and its guard are **B-042**. The narrower version — delete only
where both counts are zero — was drawn and rejected: it is safe in itself, and it
is also a delete route on the ribbon's definition table existing before the rule
that protects that table does. `StageControllerTest.noDeleteExists` asserts the
absence, and there is no `DELETE` row in `PermissionMatrix` because there is no
route.

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
| `StageControllerTest` | 13 — both preconditions, both `ETag` scopes, the 404 before them, and that no `DELETE` exists |
| `StageBodyValidationTest` | 15 — the annotations, including the `slaHours` floor and that `seq` is not patchable |
| `StageMasterIT` | 15 against real MySQL — the unique key the reorder has to survive, the counts' columns, their template scope, and B-004's seed |
