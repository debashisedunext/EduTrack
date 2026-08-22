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

Still above this directory: `C-053`'s cycle selector, `C-054`'s
`Cycle 2 · Iteration 3` chips, `B-052`'s roving keyboard navigation across the
whole strip, and `B-053`'s auto-centred scroll and collapsed `…` group at eight
stages — `RibbonStrip` still only goes as far as `overflow-x-auto`, the floor a
strip needs not to visibly break before that lands.

The props `RibbonSegment` accepts are the contract between B-050/C-052 and
C-051: `segment`, `isLast`, `onSelect`, `isSelected`, `actionSlot`,
`className`. A segment with no `onSelect` renders as a labelled `<div>`, not a
dead button — that is what a caller that has not wired selection gets.

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
