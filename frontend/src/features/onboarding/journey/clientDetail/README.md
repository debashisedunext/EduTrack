# `features/onboarding/journey/clientDetail/`

C-110 — OB-05, the onboarding client detail page. `Onboarding-Module-Plan.md`
§9, OB-05 row.

| File | What it is |
|---|---|
| `ObClientDetailPage.tsx` | The route page — header, the accordion stack, the one directory read the ribbons resolve owners from. |
| `ObAccordion.tsx` | One accordion: an always-visible strip, a region that mounts on expand, and the anchoring below. |
| `useAnchoredToggle.ts` | §9's "never scrolls the page", made mechanical. |
| `PrereqAccordion.tsx` | The gate — chip, mandatory-progress meter, task rows with verify / return / waive. |
| `PrereqReasonDialog.tsx` | The mandatory reason behind a return and a waiver. |
| `JourneyAccordion.tsx` | One product's strip, and the ribbon + step panel it expands to. |
| `StepDotStrip.tsx` | The collapsed strip's RAG dots, from `ObStepDot`. |
| `JourneyStepPanel.tsx` | The selected step, read-only. C-111 adds the actions. |
| `journeyStrip.ts` | TAT bands, holds, RAG presentation, prerequisite progress. Pure. |
| `ribbonSteps.ts` | The one translation between `ObJourneyStepView` and the ribbon's own vocabulary. Pure. |

## Why this is not in `features/onboarding/clients/`

`PHASE-2-BUILD-PLAN.md` §7 gives `features/onboarding/journey/` to Stream C and
`features/onboarding/clients/` to Stream B. OB-05 is a **C** task (C-110) whose
whole content is the journey surface — the prerequisite gate, the ribbons, the
step panels — and Stream B's own OB-05 work (B-126's client-account panel) is a
panel added *to* this page rather than the page itself.

A C-owned screen sitting in a B-owned directory would make every future edit to
it a cross-stream edit, which is precisely the friction §7 spends its longest
note pre-empting for `worker/onboarding/`.

**The route is still `/onboarding/clients/{obClientId}`.** B-108's `ObMailLinks`
already points every onboarding mail there, and a directory name is not a URL.

## Two changes this task made to C-109's ribbon

Both additive, both in Stream C's own directory, and both forced by data the
contract carries and the ribbon could not draw.

**1. `SKIPPED` is a sixth step state.** C-109's README said onboarding "has no
SKIPPED equivalent". C-107 had already shipped the skip transition and
`ObJourneyStepStatus` carries `SKIPPED`, so that sentence was describing the
Storybook fixtures rather than the domain. Folding it into `DONE` would have
OB-05 tell a reader that a **waived service was delivered**, on the screen
sign-off is decided from. `ribbonSteps.test.ts` asserts the mapping directly so
nobody re-collapses it as a simplification.

**2. Breach is read from `rag` first, `tatPercent` second.** `getObJourney`
returns a per-step `rag` and **no elapsed figure at all** — `elapsedHours` is on
`ObJourneyStepDetail`, one step at a time. A percent-only breach test would
therefore have silently never fired on the one screen the ribbon was built for.
Preferring the server's RAG is also the right order on its own merits: C-114
computes it on the working calendar against the org's amber threshold, and
anything derived here would be a second opinion about the same fact.

The ribbon's auto-centring also stopped using `scrollIntoView`, which walks
every scrollable ancestor including the document — see `JourneyRibbonStrip`'s
own comment. It sets the strip's `scrollLeft` instead, which cannot move
anything but the strip.

## The accordion rule is a mechanism, not a convention

> expanding/collapsing or selecting a step never scrolls the page — scroll
> position is preserved on all same-page interactions

The browser already preserves `window.scrollY`, and that is the problem:
collapse an accordion above the one you are reading and the offset is kept while
the content moves up by the height of what closed. What has to stay put is **the
row the user clicked**, so `useAnchoredToggle` measures that row's distance from
the top of the viewport before the state change and corrects by the difference
in a layout effect, before paint.

jsdom performs no layout, so the correction is a no-op in tests. What
`ObClientDetailPage.test.tsx` can and does prove is the half that regresses:
that nothing on the page asks the window to scroll at all.

## What this task is not

- **The step update panel** (OB-06 — start, complete, block, the task-list gate,
  communications, history) is **C-111**, which names C-110 as its dependency for
  this reason. `JourneyStepPanel` is read-only until then, with **no disabled
  buttons standing in for the actions** — B-121's line about a dead control
  teaching the user the board is broken.
- **SD/FD in the ribbon meta line and the animated status emojis** are **C-125**.
  `stepState.ts` already has `statusEmoji`; no call site on this page uses it yet.
- **The client-account panel** (create / reset / disable a portal login) is
  **B-126**, on Stream B's side of the map. The header's `hasPortalLogin` line is
  the read-only fact until it lands.
- **The stitched client-level communications tab** is **C-112**.
- **A payments card.** Not an omission — plan §1.2 removed financial tracking
  from the module after the prototype had one.
