# `features/masters/stages` — S-13 tab 2, the Stage Master (B-040)

| File | What it is |
|---|---|
| `StagesTab.tsx` | The template selector, the ribbon list with its drag and keyboard reorder, and the one dialog. |
| `stageForm.ts` | Form state, the validation subset, the two mappers and the reorder model. |
| `stageQueries.ts` | The data layer — the hand-written parts orval cannot emit. |

Mounted inside `statuses/StatusMasterPage.tsx`, which is S-13's shell. B-039 built
that shell with tab 2 disabled and named; B-040 turns it on. Tab 3 stays disabled
and still names B-041.

## A template selector, which §7.4 does not describe

The blueprint reads as though tab 2 edits one flat list of stages.
`workflow_stages.template_id` is `NOT NULL`, so there is no stage outside a
template — `DEV` on Standard Dev Flow and `DEV` on Support Fast-Track are two
independent rows. CLAUDE.md settles which side wins: PLAN.md is the authority on
implementation and A-005 is what it produced.

The selector lists **inactive templates too**, unlike every other master's
active-only default. A retired row handed to a *filter* offers a value matching no
ticket anybody can raise; a template is not a filter value, it is what every
historical ticket points at through `tickets.workflow_template_id`. Hiding a
deactivated one would hide the stages of every ticket that ever ran on it.

## The drag is staged, not saved

Dragging changes local state and nothing else. The reorder is a whole-set `PUT`
with an `If-Match`, so a save-per-drag would fire eight requests to move one row
four places and each would move the tag under the next.

That is also what makes the warning possible. With the order held locally,
`forwardReturnPaths` can name the pairs a drag would invert **before** the
request, so an Admin reads "QA → DEV would point forwards" rather than a 409
naming a rule. The server checks it again, because a browser is not a guarantee.

## The keyboard path is not a lesser one

WCAG AA is not optional here, and "drag to reorder" is the control that most often
ships with the pointer path only. Every row carries Move up / Move down buttons
doing exactly what the drag does, each move is announced through an `aria-live`
region, and the pointer path is plain HTML5 drag events — no new dependency for a
list of eight.

`StagesTab.test.tsx` drives the whole feature through those buttons rather than
through synthetic drag events, so the accessible path is the one under test rather
than the one alongside it.

## Help text and errors sit outside the `<label>`

Inside it they are concatenated into the control's accessible name, so a frozen
stage's field announces as *"Code Used by 41 ribbon segments…"* — and stops being
findable by its own label. Explicit `htmlFor`/`id` keeps the association, and
`aria-describedby` carries the description where it belongs.

Found by the tests, not by review: three cases failed with *"Unable to find a
label with the text of: Code"* on exactly the two states that render extra text
inside the label — a frozen code, and an empty create form showing its required
error.

## `isCodeEditable` comes from the server, and is not re-derived

The rename rule is one rule, enforced in `StageService` and reported on the view.
A second copy in TypeScript would be a second thing to keep true, and its failure
mode is a form that greys out a field the server would have accepted — or worse,
offers one it will refuse.

The dialog does render *why*, from `transitionCount` and `openTicketCount`,
because "you cannot rename this" without the number is an assertion an Admin has
no way to check.

## `formToPatch` omits `stageCode` when it has not changed

The form always holds the code, so the obvious mapper round-trips it. That would
turn every unrelated edit into a 409 the moment the screen's `isCodeEditable`
lagged the server's by one ticket — a display-name change refused because
somebody else's ticket entered the stage while the dialog was open.

`canReturnTo` is the other field where "only what changed" has teeth: `undefined`
leaves the targets alone and `[]` clears them, so a mapper that always sent the
array would silently wipe a loop-back somebody authored. The comparison is
order-insensitive, because the server stores them in the order it validated them
rather than the order the checkboxes render in.

## Two tags, two scopes

`useStages` reads a tag over the **whole ribbon** — it is the precondition for the
reorder, which replaces the whole set. `useStage` reads a per-row tag for the
`PATCH`. Preconditioning the `PATCH` on the list's tag would refuse it because
somebody edited a different stage; preconditioning the reorder on a row's tag
would let it discard an edit it never saw.

A write on either invalidates both, plus the template list — `stageCount` is on it
and a create moves it.

## Known divergence: the mock's other stage vocabulary

`db.stages` is Stream C's flat ribbon fixture and its codes are `DEVELOPMENT` and
`VERIFICATION`. The database seeds `DEV` and `VERIFY` (`V20260807_1700`). So the
two disagree about the ribbon's vocabulary, and a screen tested only against the
old fixture would ship looking right.

B-040's mock rows (`db.templateStages`) follow the **database**, because tab 2 is
the screen that edits the database's rows. Reconciling the older fixture means
renaming codes that Stream C's reopen fixture and `ReopenDialog.test.tsx` assert
on — their files, so it is recorded rather than done.

## Tests

| Suite | What it holds |
|---|---|
| `stageForm.test.ts` | 33 — the validation subset, both mappers, the backward-target rule and the reorder model |
| `StagesTab.test.tsx` | 18 against the mock server — the tab turning on, the frozen code with its counts, staged-then-saved reordering, the refusal before the request, the live-region announcement, and that no delete exists |
