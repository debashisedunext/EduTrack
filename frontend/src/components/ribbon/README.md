# `components/ribbon/`

The Workflow Ribbon's shared pieces — blueprint §4A.

**Ownership.** This directory is Stream C's in `TEAM-PLAN.md` §6 and in
`.github/CODEOWNERS`. `B-050`–`B-053` are Stream B's tasks *inside* it, which is
the arrangement §6's timeline sets out for weeks 10–11 ("**joins C** — ribbon
UI, segment states, compact variant"), not an exception to it. Divyansh is
requested as reviewer automatically. Anything here that is not one of those four
task IDs still needs the ordinary sign-off.

| File | Task | What it is |
|---|---|---|
| `RibbonSegment.tsx` | B-050, C-052 | One segment. Six states, five data points, the rich hover tooltip. |
| `segmentState.ts` | B-050, C-052 | State → words, icon and treatment; the ARIA label; the tooltip's six fields. Pure. |
| `useElapsedMins.ts` | B-050 | The current segment's running timer. |
| `RibbonStrip.tsx` | C-051, C-052 | Lays the segments out in order; wires selection and the current segment's contextual action. |
| `RibbonDots.tsx` | B-051 | The compact variant. One row of S-17's grid, as dots. |
| `compactDots.ts` | B-051 | Template stages + one ticket row → dots; the cell's accessible name and each dot's hover. Pure. |
| `rovingFocus.ts` | B-052 | The strip's single tab stop and the arrow keys that move inside it. `nextFocusIndex` is pure. |
| `collapsedGroup.ts` | B-053 | Segments + which groups are expanded → the strip's row list. `buildRibbonRows` is pure. |
| `CollapsedGroupTile.tsx` | B-053 | The "…" tile — a run of completed stages folded into one control, or unfolded back into them. |

## What C-052 built, and what still sits above this directory

Selection: `RibbonStrip` takes `selectedSegment`/`onSelectSegment`, forwards
clicks and marks the matching tile — but owns none of the filtering itself.
`TicketDetailPage` holds the selection state and narrows History and Effort to
it (`features/tickets/detail/segmentFilter.ts`); Chat will join them once
`D-047` lands a real tab for a filter to reach. Selection is wired even on a
sealed, past cycle — reading an old cycle's own History and Effort one stage
at a time is exactly as useful as it is on the live cycle.

The contextual action: `RibbonStrip` renders an honestly-disabled placeholder
on the current segment when `ribbon.canAdvance` is true — "hidden for everyone
else" is `canAdvance`'s own contract wording. The dialog behind it is still
`C-044`; `TicketDetailHeader`'s `HEADER_ACTIONS` used the identical
rendered-disabled pattern for Close and Reopen before their tasks landed, and
this reuses it rather than inventing a second one.

The hover tooltip: `RibbonSegment` wraps itself in `components/ui/tooltip.tsx`
(`@radix-ui/react-tooltip`, new here) and shows `segmentTooltipDetails` —
entered, exited, owner, note, effort, and the idle-vs-active split, reachable
by keyboard focus as well as pointer hover, which the native `title` it
replaces never was.

Still above this directory: `C-053`'s cycle selector and `C-054`'s
`Cycle 2 · Iteration 3` chips. `B-052`'s roving keyboard navigation and
`B-053`'s auto-centred scroll and collapsed `…` group have both landed; see
below.

The props `RibbonSegment` accepts are the contract between B-050/C-052 and
C-051: `segment`, `isLast`, `onSelect`, `isSelected`, `actionSlot`, `className`,
and — since `B-052` — `tabIndex` and `onFocus`, plus a forwarded `ref` that
lands on whichever element is the tile's focusable one. A segment with no
`onSelect` renders as a labelled `<div>`, not a dead button — that is what a
caller that has not wired selection gets.

## Three things the contract cannot express yet

Each is recorded rather than approximated, and each belongs to another stream.

**1. `BLOCKED` has no reason field.** §4A.3 says a blocked segment's hover
"shows hold reason". `RibbonSegment` in `contracts/openapi.yaml` carries
`skipReason` and `handoffNote` and nothing else, so the tile renders the pause
treatment and the words *On hold* and can say nothing about why. **For Stream D
(contract owner):** a `holdReason` beside `skipReason` closes it. Nothing
produces the state either — the mock's `buildRibbon` emits five of the six
states and never `BLOCKED` — so this is untested end to end by anything but the
unit tests here.

**2. A skip does not record who authorised it.** §4A.3 asks for the reason *and
who authorised it* on hover. `skipReason` is a string; there is no
`skippedBy: UserRef`. The tile shows the reason. `C-047` writes the skip, so it
is the task that would know.

**3. The current segment's duration is wall clock, not working hours.** Every
sealed segment's `durationMins` is *working* minutes computed server-side
against the calendar (`B-024`), and it is `null` for the stage a ticket is
sitting in. The browser has no calendar, so the running timer counts clock time
and is labelled **`elapsed`** where the sealed segments read **`in stage`**. A
ticket handed to Deployment at 18:00 on Friday shows `62h elapsed` on Monday
morning against the ~8h that gets recorded when it seals. If the ribbon endpoint
ever serves a live working-minutes figure for the open hop, `RibbonSegment`
prefers it automatically and the timer stops being reached.

## Two decisions worth not relitigating

**The template's `icon` is not rendered.** `RibbonSegment.icon` is a lucide name
an Admin picks in `B-043`'s designer. Resolving an arbitrary name costs the
whole lucide set or a dynamic import per segment, and no screen in this codebase
resolves one — all forty-odd call sites import the icons they name. §4A.3's own
diagram puts a **state** icon in that position (`✔ Development`), which is six
static imports. When `B-043` ships, one resolver for the whole app is that
task's to introduce.

**Skipped and blocked reuse the pending tokens.** `styles/tokens.css` ships
`--ribbon-done|current|pending|reworked|breached` and nothing for either, and
that file is frozen — "others request, never add". Neither needs a new colour:
§4A.3 describes skipped as a dashed outline with a struck-through label and
blocked as grey with a pause icon, so the distinction is shape and word, which
is where the blueprint put it. **No token was requested and none is pending.**

`--ribbon-breached` stays unused here. An SLA breach is a fact about a stage's
clock, is not in `SegmentState`, and has no field on `RibbonSegment` to render
from; when D's stage-SLA scanner surfaces one it is an overlay on a state, not a
seventh state.

## B-051 — the compact variant, and what it shares with the tile

`RibbonDots` is the same ribbon at grid scale: blueprint line 984's "eight
small dots per row (filled = done, ringed = current, hollow = pending, amber =
reworked) so a manager can scan a whole grid and see exactly where every ticket
sits without opening any of them."

**It shares `SegmentState` and nothing else.** `segmentState.ts`'s own header
asked for that — "B-051's compact dot and B-053's collapsed group read the same
vocabulary instead of inventing a second one three weeks from now" — so a dot's
state is the wire enum, not a private four-value union. What it does *not*
share is the source: a tile is drawn from a server-built `Ribbon`, and a dot is
derived from the row's workflow template plus its `currentStageCode`, because
`GET /tickets` returns `TicketSummary` and no transitions. Four of the six
states are producible from that; `SKIPPED` and `BLOCKED` are facts in
`ticket_stage_transitions` and are absent rather than approximated.
`features/tickets/list/README.md` carries how a row finds its template.

**Shape carries the state and colour repeats it**, because at 7px there is no
room for an icon or a word and `tokens.css` requires a second channel: filled
circle, larger circle inside a ring, hollow outline, and a **diamond** for sent
back — four marks that stay apart in greyscale. `RibbonDots.test.tsx` pins that
as a behaviour, so a future tidy-up that collapses them to four colours fails.
No new token was needed or requested.

**One `role="img"` per cell, not eight focusable marks.** Eight per row across
25 rows is 200 stops between a keyboard reader and the bottom of the page, in a
cell with nothing to activate — so the strip carries one sentence naming where
the ticket is, how far along and whether it has been sent back, and the per-dot
naming §S-17 asks of a hover is a native `title`. That is deliberately *not*
the Radix tooltip `RibbonSegment` uses: right for one ribbon of eight tiles on
a detail page, a different question with a different answer for 200 in a grid.
`B-052` owns keyboard navigation across the detail strip, where every segment
is a control.

## B-052 — the keyboard, and the button that was inside a button

§4A.3's closing bullet asks for two things: *"Fully keyboard-navigable; each
segment has an ARIA label reading the stage, owner, state and effort."* The
second was B-050's and shipped with it — `segmentAriaLabel` emits stage, owner,
state, **duration** and effort, plus the loop count and the skip reason, and
`segmentState.test.ts` pins all seven. This task is the first half.

**Eight segments are one tab stop.** `useRovingFocus` in `rovingFocus.ts` makes
the strip a composite widget in the APG sense: one element in the tab order,
`←`/`→` between segments, `Home`/`End` to the ends, both wrapping. The keys are
`TicketDetailTabs`' and the rich-text toolbar's on purpose — a third set of
arrow-key semantics on the same screen would *be* the accessibility problem. The
alternative is not "slightly more presses": S-20 stacks History, Effort, Chat and
the roll-up grid **below** the ribbon, so eight tab stops is eight presses
between a keyboard reader and everything they came for, and sixteen on a ticket
that has looped.

**The tab stop starts on the current segment.** Landing a reader on an Intake
finished four days ago and making them arrow forward to discover where the
ticket actually is would be a working keyboard interface answering the wrong
question. It is a *preference*, not a controlled value: once the reader moves
it, it stays moved, because the strip re-renders on every `stage.changed` frame
D-058 pushes and a live handoff must not yank the tab stop out from under
someone reading with it.

**Focus does not select, and this is the one place the ribbon differs from the
tabs.** `TicketDetailTabs` selects on focus and says why — every panel is
already loaded from the one `/full` call, so moving costs nothing. Selecting
here is not free: C-052 filters History, Effort and (once `D-047` lands) Chat
down to one stage and iteration, so arrowing across eight stages to *read* the
ribbon would refilter three panels eight times, and a keyboard user could not
inspect the journey without changing what sits under it. Activation is explicit
— `Enter` or `Space`.

**Every tile joins the roving, including the read-only ones.** A strip rendered
without `onSelectSegment` draws `<div role="group">` tiles, and *nothing could
focus them* before this task — so C-052's tooltip, the only place a segment's
entered, exited, note and idle-vs-active figures are written down, was
pointer-only on all three callers that render one: a sealed past cycle, S-13
tab 3's live preview and S-30's designer preview. They take a `tabIndex` now.
They are still not buttons, because there is still nothing to activate.

**The defect this found: `actionSlot` rendered inside the tile's own
`<button>`.** §4A.3 puts *Hand off to QA →* on the current segment and B-050
read that literally enough to render it among the tile's children — a `<button>`
inside a `<button>`, which the HTML spec forbids, which every browser recovers
from differently, and which **no keyboard can reach**, because the outer control
consumes Enter and Space before the inner one sees them. The card is now a
`<div>` holding two siblings: the trigger with the five data points, and the
action. The focus ring moved to the card with `has-[:focus-visible]` — the
pattern `CommentBox` already uses — so it still outlines the whole tile rather
than an inset rectangle, and it draws for the action button too, which is
honest.

**`itemProps` caches its two callbacks per index, and that is not a
micro-optimisation.** A fresh `ref` closure on every render makes React detach and
reattach the node — `ref(null)` then `ref(node)` — on all eight tiles, and each
tile's ref is composed by Radix's `Slot` with `TooltipTrigger`'s own, which is
the Popper **anchor**. The first cut of this hook did exactly that, and
`TicketDetailPage.test.tsx` started timing out under parallel load in a
different test each run while its single-file time was unchanged — eight
tooltips being torn down and re-anchored on every render of a strip that
re-renders on selection, on focus and on every realtime frame. Stable identities
fixed it. If a future change makes those closures depend on anything but the
index, that regression comes straight back.

**What is still not here:** `C-053`'s cycle selector and `C-054`'s
`Cycle 2 · Iteration 3` chips, both of which render above this strip rather
than inside it.

## B-053 — the collapsed group and the auto-centred scroll

§17's own mitigation for "ribbon becomes unreadable at 8 stages on a laptop"
names two behaviours, and both are here rather than in `RibbonSegment` —
a tile does not know how many siblings it has, or where the strip has
scrolled to.

**Only `COMPLETED` collapses, and only beyond the third one.** `collapsedGroup.ts`'s
`buildRibbonRows` walks the segment list once: the first three completed
stages stay individual tiles, and a run of completed stages after that folds
into one `CollapsedGroupTile`. `REWORKED`, `SKIPPED` and `BLOCKED` never join
a run — each carries something a completed tile does not (a loop badge, a
reason, a hold), and `segmentState.ts`'s own header asks this feature to share
that vocabulary rather than invent a private one, so folding them into "…"
would be the summary hiding the one thing worth reading. A skipped or blocked
stage in the middle of an otherwise-collapsible run splits it into two groups
rather than pretending the run is still contiguous.

**Collapsing hides a tile, never a segment.** B-052 made every stage reachable,
including the read-only ones, and a group that silently dropped a stage from
the DOM would undo that for whichever one a reader most wants to click to
filter History and Effort. So a collapsed run's segments are still in
`ribbon.segments` — `expandedGroups` (a `Set<string>` of run keys, kept in
`RibbonStrip`, not in the pure builder) is the only thing deciding whether they
render as one tile or as themselves. The group's own tile stays on screen
either way: collapsed, it reads "N completed stages collapsed" with `+N`;
activated once (click, Enter or Space), it renders the real segments right
after itself and relabels to "Collapse N completed stages"; activated again,
they fold back. One control rather than a pair to keep in sync.

**The group is always a button, unlike a segment tile.** `RibbonSegment`
renders a `<div role="group">` when the caller passes no `onSelect` — a sealed
past cycle's ribbon is read-only for advancing it. Folding and unfolding a
group is a display decision, not a selection, so `CollapsedGroupTile` takes no
`onSelect` at all and is a real button on every ribbon, including a sealed one.
Clicking it never calls `onSelectSegment` — `RibbonStrip.test.tsx` pins that
distinction directly.

**The group joins the roving same as any tile, and does not disturb it.**
`useRovingFocus` counts `rows.length`, not `segments.length` — expanding a
group changes how many rows there are, exactly the way a live `stage.changed`
frame changing `segments.length` already could, and the hook's own tolerance
for an index outliving its list (`rovingFocus.ts`'s own note on `current`
outside `[0, count)`) is what keeps that safe.

**Auto-centring keys on the current *stage*, not on the roving tab stop or on
which groups are expanded.** The two are independent axes: arrowing across the
strip to read it, or expanding a group to look inside it, must not drag the
scroll position along, the same reason B-052's own tab stop does not chase a
live `stage.changed` frame once a reader has moved it. So `RibbonStrip` keeps
one `React.useEffect` keyed only on the current segment's own stage code and
iteration, and calls `scrollIntoView({ inline: 'center', block: 'nearest' })`
on it — the same optional-chained `scrollIntoView` `HandoffDialog.tsx` already
uses for its own auto-scroll, not a new pattern. `prefers-reduced-motion`
decides `smooth` versus `auto`, checked the same way `useCountUp.ts` already
does; the scroll still happens either way, it simply has no motion.
