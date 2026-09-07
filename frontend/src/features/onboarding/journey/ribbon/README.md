# `features/onboarding/journey/ribbon/`

C-109 — the onboarding journey ribbon, Onboarding-Module-Plan.md §9 (OB-05).

**Ownership.** `features/onboarding/journey/` is Stream C's per
PHASE-2-BUILD-PLAN.md §7's ownership map ("`features/onboarding/journey/` → C,
including the onboarding ribbon").

| File | What it is |
|---|---|
| `types.ts` | `JourneyStep` — the local wire shape this task builds against until A-101 lands an OpenAPI model. |
| `stepState.ts` | State → icon, words and treatment; the breach overlay; the animated status emoji; the ARIA label. Pure. |
| `rovingFocus.ts` | The strip's single tab stop and the arrow keys inside it. `nextFocusIndex` is pure. |
| `JourneyRibbonSegment.tsx` | One step tile. |
| `JourneyRibbonStrip.tsx` | Lays the steps out in order, owns the roving focus and the auto-centred scroll. |

## Why this does not import `components/ribbon/`

PHASE-2-BUILD-PLAN.md names it directly, in the section listing the four
riskiest decisions in the whole phase 2 plan:

> **`C-109` — the ribbon, built fresh.** No import from `components/ribbon/`.
> Importing couples two release cycles on day one and is very hard to undo
> once four screens depend on it.

The two ribbons look alike because they solve the same UI problem —
segments-in-a-row, states, a tooltip, keyboard nav — not because one reuses
the other's code. They diverge in ways that would make a shared component an
increasingly awkward compromise for both sides:

- **Different state sets.** Tickets have six (`COMPLETED/CURRENT/PENDING/
  REWORKED/SKIPPED/BLOCKED`, hash-chained history behind every one).
  Onboarding steps have six (`PENDING/CURRENT/DONE/WAITING/BLOCKED/SKIPPED`)
  plus a breach *overlay* that is a fact about health, not a state.
  (`SKIPPED` was added by C-110 — see `clientDetail/README.md`. This line
  originally said five, describing the Storybook fixtures rather than
  `ObJourneyStepStatus`, which has carried `SKIPPED` since C-107.)
- **Different time model.** A ticket segment's duration is wall-clock minutes
  measured server-side; a journey step's is a working-day **TAT budget** with
  a running percent-consumed bar, and DONE steps carry SD/FD dates with an
  on-time/early/delayed marker instead.
- **Different dependency shape.** Ticket stages are a strict sequence with
  loop-backs. Journey steps declare `dependsOnSeqNo` and can run several at
  once in parallel (§5.6) — the `↳ N` / `∥` badge has no ticket-ribbon
  equivalent to reuse.
- **A different consumer set.** `components/ribbon/` is `TicketDetailPage`'s.
  This one is headed for OB-05's client detail page (C-110), CP-03's
  read-only client-portal view, and potentially the OB4 sign-off screens —
  none of which are Stream C's phase-1 ticket surface, and coupling them to
  it would mean a ticket-ribbon change risking an onboarding regression (or
  the reverse) for the rest of phase 1 and phase 2 both.

So the shape is deliberately familiar — same tile anatomy, same
pure-treatment-map split, same roving-focus mechanism — and the code is
deliberately separate. `rovingFocus.ts` here is a line-for-line re-derivation
of the same generic composite-widget keyboard math `components/ribbon/
rovingFocus.ts` already has, not a copy-paste that happens to also exist
twice; if a third ribbon-shaped widget shows up, promoting the *mechanism*
(not the domain treatment) to `lib/` is a reasonable follow-up, but that is
its own task, not this one's to decide unilaterally.

## Tokens

`--ribbon-waiting-*` and `--ribbon-blocked-*` are new in `styles/tokens.css`
(light + dark) and in `tailwind.config.ts`'s `colors.ribbon` — the two states
phase 1 never needed. Both reuse the existing `--warning`/`--danger`/
`--bg-subtle` hex values already in the palette; nothing here is a new
colour. `--ribbon-breached` (declared in phase 1, deliberately left unused
there) is what the breach overlay draws from — the first real consumer of a
token that already existed.

The five animated status emojis (Onboarding-Module-Plan.md §9: 👍🙌👎👏😢) are
five new Tailwind keyframes/animations (`emo-bounce/pop/shake/clap/sad`) in
`tailwind.config.ts`, ported at the same durations and easings as the
approved prototype (`docs/prototype/onboarding.html`'s `stEmoji`). Every call
site pairs the animation class with `motion-reduce:animate-none`, per
CLAUDE.md's accessibility line and the design decision log's own
`prefers-reduced-motion` rule for these exact emojis.

## What this task is not

- **The step panel** (§9 OB-05's expanded step detail below the ribbon,
  actions, task-list gate) — C-110's for the read-only half; the actions and
  the task-list gate are C-111's (OB-06).
- **The prerequisites accordion above the ribbon, and the journey accordion
  strip around it** — also C-110's; this task is the ribbon alone.
- ~~**A real data source.**~~ **Superseded.** This said `JourneyStep` was
  unwired with no endpoint behind it. A-101 landed and C-110 wired it:
  `clientDetail/ribbonSteps.ts` feeds this ribbon from
  `GET /onboarding/journeys/{journeyId}`. `types.ts` stayed local anyway, for
  a different reason it now states in its own header.
- **Collapsed grouping.** See `JourneyRibbonStrip`'s own docstring — the
  seeded default template is 8 steps and nothing in the module plan asks for
  one; adding it ahead of a template that needs it would be scope this task
  was not given.
