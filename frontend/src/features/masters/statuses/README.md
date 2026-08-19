# `features/masters/statuses` — S-13 tab 1, the Status Master (B-039)

| File | What it is |
|---|---|
| `StatusMasterPage.tsx` | The three-tab shell, the status grid with its two dialogs, and the transition matrix. One route, `/masters/statuses`. |
| `statusForm.ts` | Form state, validation, the matrix's cell model and the four mappers. |
| `statusQueries.ts` | The data layer — the hand-written parts orval cannot emit. |

## Three tabs, two of them disabled

§7.4 specifies S-13 as three tabs: statuses, stages, workflow templates. B-039
builds tab 1; **B-040 and B-041 are rendered as disabled tabs naming their task
in the tooltip** rather than omitted.

A screen that grows two tabs later is a screen whose shape nobody could see
coming. Showing them greyed out tells an Admin what this master will hold, and
tells the next developer where their work goes without reading the backlog.

**Status is not stage, and the tab split is the product saying so.** Blueprint §3
keeps them apart on purpose: a ticket can be In Progress while sitting in the QA
stage. Tab 1 is "is work moving?"; tab 2 will be "who owns it right now?".

## Both halves on one panel

The status list and the matrix sit one under the other rather than in two
sub-tabs. §7.4 describes them as one tab, and the matrix's rows are moves between
the statuses above it — an Admin who has just retired a status needs to see,
without navigating, that its rows have gone.

## The grid asks for retired statuses. Nothing else will.

Following S-12 rather than S-11: `GET /masters/statuses` is active-only by
default and takes `?includeInactive=true`, and the two lists are kept apart **in
the cache by their query keys**.

There is no consumer to break yet — nothing has ever read this route — which is
the difference from B-021, where two shipped Stream C screens already mapped the
response straight into a picker. The separation is set up before there is
something to get wrong, rather than after.

## Category is not `isOpen` renamed

The single most likely future "simplification", and the one the screen argues
against in three places.

`RESOLVED` is **Done** work on a ticket that still **counts as open** — the work
is claimed complete and the ticket stays open until sign-off. Five other statuses
carry `isOpen: true, isTerminal: false` while falling into two different
categories. So:

- `statusFormErrors` deliberately has **no** "DONE implies not open" rule. It
  reads like an obvious fourth check and would refuse a seeded row.
- The grid renders category, "counts as open" and "terminal" as three separate
  columns, and a test asserts Resolved's values across all three.
- The category control's helper text says so at the point of editing.

## The retire dialog states the cost before the click

Retiring is `isActive: false` — there is no delete anywhere on this screen, and
`StatusMasterPage.test.tsx` asserts the absence.

Two things the dialog says that a user would otherwise discover afterwards:

- **How many transitions the retire will take with it**, from the row's own
  `transitionCount`, before the save. The count of what it *did* comes back as
  `deactivatedTransitions` and lands in the toast.
- **That reactivating will not bring them back.** Somebody who reads that
  afterwards has already pressed the button believing something else.

The refusal — live tickets in the status — is field-keyed onto the Active
checkbox, because the server sends an `errors` map for exactly that. The MSW
handler carries the same full sentence in that map rather than a placeholder; a
mock that answered `"In use"` would let the screen ship looking fine and read
uselessly against a real backend.

## The matrix flags G-3 rather than locking it

An unticked cell means the move is impossible for that role. There is no default
and no second rule — which is why governance decision G-3 (*may a Developer close
a ticket?*) is expressed as the **absence** of a row rather than as code.

`GOVERNANCE_NOTES` marks the four moves PLAN.md §5 and blueprint §2 settled, with
the reason. **They stay editable.** Locking them in the client would put back the
one decision the whitelist exists to keep out of code, and an organisation whose
sign-off process differs from ours has to be able to say so. What the screen owes
them is knowing which cells already carry somebody's decision.

The notes are keyed by `moveKey`, so a typo in either half would silently show no
flag at all — which is the failure mode of advice, since nobody notices its
absence. `statusForm.test.ts` pins the keys.

## Moves down the side, roles across the top

Both layouts were drawn. This one wins because the question an Admin arrives with
is "who may close a ticket?", which is one row read across — and because the
from × to grid is 64 cells of which 14 are ever populated, so it renders mostly
emptiness and hides the answer in it.

`MatrixRow.cells` is keyed by a plain `string`, **not** `Record<RoleCode, …>`.
The columns come from whatever roles the matrix actually contains, because S-09
lets an Admin add a seventh, and typing it to the contract's six would make the
grid silently drop that column. The narrowing to `RoleCode` happens once, on the
way onto the wire, where the contract is the authority.

## The save is blocked when the last on-create cell is unticked

`fromStatus: null` is how a ticket enters the system. With none ticked, no role
can raise a ticket on any screen — and this is the only screen that could undo
it.

Checked in `matrixHasOnCreateMove` so the button can explain itself rather than
the server refusing after the click, and checked again on the server because a
browser is not a guarantee. It is the only edit on this screen with a global
consequence, and the only one refused unconditionally.

## The matrix read carries an `ETag`, and it is the only collection here that does

Every other master reads its tag from a single-row route. There is no single-row
route for a transition — there is no per-cell verb — so `useStatusTransitions`
reads the tag off the collection, which is why it is a raw `fetch` where every
other list is a plain `http()`.

Without it the `PUT` would have had to go unguarded, and a whole-matrix replace
is exactly the write where a lost update is worst: two Admins with the tab open
would each save their own screen state, and the second would delete every cell
the first had added with nothing to show it had happened.

## A status write invalidates the matrix, and that is not obvious

`invalidate()` clears both status list keys **and** the matrix key. Retiring a
status deactivates every transition touching it, so a write on one tab silently
changes rows on the other. Leaving the matrix stale would show an Admin live
cells the server had just cleared — and the next save would send them straight
back.

## Tests

| Suite | What it holds |
|---|---|
| `statusForm.test.ts` | 26 — the validation subset, the four mappers, the matrix round trip, the governance keys |
| `StatusMasterPage.test.tsx` | 18 against the mock server — the disabled tabs, the retire refusal and its report, the blocked save, and that no delete exists |
