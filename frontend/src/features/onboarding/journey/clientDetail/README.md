# `features/onboarding/journey/clientDetail/`

C-110 — OB-05, the onboarding client detail page — and **C-111 — OB-06**, the
step update panel inside it. `Onboarding-Module-Plan.md` §9, OB-05 and OB-06.

| File | What it is |
|---|---|
| `ObClientDetailPage.tsx` | The client route page — header, the prerequisites gate, one card per purchased product, and §9's closing pair. |
| `ObClientProductPage.tsx` | One purchased product — `/onboarding/clients/:obClientId/products/:productId`. The journey accordions, and the one directory read their ribbons resolve owners from. |
| `productGroups.ts` | The fold from journeys to products, and the figures a card prints. Pure. |
| `ObAccordion.tsx` | One accordion: an always-visible strip, a region that mounts on expand, and the anchoring below. |
| `useAnchoredToggle.ts` | §9's "never scrolls the page", made mechanical. |
| `PrereqAccordion.tsx` | The gate — chip, mandatory-progress meter, task rows with verify / return / waive. |
| `PrereqReasonDialog.tsx` | The mandatory reason behind a return and a waiver. |
| `JourneyAccordion.tsx` | One product's strip, and the ribbon + step panel it expands to. |
| `StepDotStrip.tsx` | The collapsed strip's RAG dots, from `ObStepDot`. |
| `JourneyStepPanel.tsx` | The selected step — summary, task list, actions (C-110 + C-111). |
| `StepActionBar.tsx` | OB-06's transitions: start / complete / block / waiting / resume. |
| `StepTaskList.tsx` | §5.8's Task List and the required documents, with the gate visible. |
| `BlockStepDialog.tsx` | The mandatory block reason, and the clock warning. |
| `blockReasons.ts` | The block-reason vocabulary. **Unratified — see the file.** |
| `stepActions.ts` | Who may act, what each status permits, and the completion gate. Pure. |
| `journeyStrip.ts` | TAT bands, holds, RAG presentation, prerequisite progress. Pure. |
| `ribbonSteps.ts` | The one translation between `ObJourneyStepView` and the ribbon's own vocabulary. Pure. |

## OB-05 is two screens: the client, then one product at a time

**This is a deviation from plan §9 and it is recorded here rather than
implied.** §9's OB-05 row orders the page *prerequisites accordion on top → one
journey accordion per purchased product → client portal access + client info*,
and that middle row was written when a purchased product meant one journey.

It no longer does. A product publishes **any number of Module Services at
once** (plan §20, and the multi-service change that implemented it), a client is
boarded through one journey per service of every product they bought, and the
live corpus already seeds ERP's *Enterprise (with data migration audit)* service
live beside its standard one. So a client with two products carries five or six
accordions, and the screen stopped answering the question people open it with —
*how is the ERP going* — because the ERP's answer was interleaved with the
biometric rollout's.

| Screen | Route | What it answers |
|---|---|---|
| `ObClientDetailPage` | `/onboarding/clients/:obClientId` | *Which product?* The gate, a card per purchased product, the closing pair, the stitched timeline. |
| `ObClientProductPage` | `/onboarding/clients/:obClientId/products/:productId` | *How is it going?* That product's journeys — ribbons, step panels, sign-offs — and nothing else's. |

Three things this preserves rather than changes:

- **§9's ordering inside each page.** The gate is still above everything on the
  client page, because nothing below it can move while it is locked, and the
  product page says so rather than drawing live-looking ribbons over a locked
  gate.
- **The strip/ribbon split.** The contract splits them so "a client with six
  journeys does not pay for six ribbons on first paint". The client page now
  pays for **none**, which is the strongest form of that rule; its test asserts
  exactly that.
- **Cross-product sibling holds.** `JourneyAccordion` is handed *every* journey
  of the client as `siblings`, not only the product's, because a service-level
  dependency crosses products (plan §5.5) — Horizon's biometric rollout is held
  behind its ERP journey and has to be able to name it from a page the ERP is
  not on.

The one thing deliberately **not** carried onto the product page is
`ClientCommunicationsPanel`. It is the *client-level stitched* view by
construction — "everything said to this client, across every service" — and its
read is client-scoped with an optional per-journey filter. Mounting it under one
product would either lie about its scope or need a product filter the contract
does not carry. Per-service communications are already in the step panel, where
C-112 put them.

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

- ~~**The step update panel** is C-111.~~ **Done** — C-111 landed it here, and
  C-110's prediction that it would "add an action row under this summary;
  nothing here has to be rebuilt for it" held exactly.
- **SD/FD in the ribbon meta line and the animated status emojis** are **C-125**.
  `stepState.ts` already has `statusEmoji`; no call site on this page uses it yet.
- **The client-account panel** (create / reset / disable a portal login) is
  **B-126**, on Stream B's side of the map. The header's `hasPortalLogin` line is
  the read-only fact until it lands.
- **The stitched client-level communications tab** is **C-112**.
- **A payments card.** Not an omission — plan §1.2 removed financial tracking
  from the module after the prototype had one.

---

# C-111 — OB-06, the step update panel

The owner's working surface, inside OB-05's expanded accordion. Three rules
decide almost everything about it.

## 1. The server owns the rules; this mirrors them

CLAUDE.md is explicit that the golden rule lives in the transition service, not
the UI, and nothing here softens that. What a screen owes its user is to stop
offering a control whose only outcome is a refusal, and to say *why* before the
click rather than after it. `stepActions.ts` is that mirror, and every rule in
it names the server counterpart it transcribes.

The refusal the server makes that this **cannot** predict is `signoffMissing` —
whether a `SIGNED` sign-off exists is on no read the panel makes. So when the
server refuses anyway, its own `detail` is surfaced verbatim rather than
paraphrased into something friendlier that would be inventing a reason.

## 2. Absent, not disabled — except where the owner can act on it

For anybody who is not the owner or backup owner, the action bar renders
**nothing**. A row of greyed-out controls suggests a permission the reader
might acquire by asking; the step simply is not theirs, so the panel names who
does own it instead — the actionable fact.

Complete is the opposite case and *is* rendered disabled: it is the owner's own
button, held by something the owner can go and fix, and both the tooltip and a
sentence beside it name which mandatory item is outstanding. (A `title` alone
is unreachable by keyboard and invisible on touch, hence both.)

## 3. Blocked and waiting-on-client are not interchangeable, and the dialog says so

Plan §5.7: an internal `BLOCKED` step keeps the TAT clock **running** and
charges the delay to us; `WAITING_ON_CLIENT` **pauses** it and attributes the
wait to the client. An owner who picks the wrong one is not making a cosmetic
mistake — they are moving a TAT breach onto the wrong party's account. So the
block dialog states the difference and points at the alternative, and every
reason code in `blockReasons.ts` is an *internal* one.

## The two reads behind this panel were written by C-111 too

`GET /onboarding/journey-steps/{stepId}` and
`PATCH /onboarding/journey-step-items/{itemId}` were in A-118's contract and in
the MSW mock, and in **no** Java controller. C-103 wrote the rows, C-104 wrote
the five POST transitions, C-106 wrote the gate over the items — and nothing
ever read them back. No backlog task owned the read side, so this one built it.

Two things about that server code matter to anybody editing this directory:

**`isDone` means "answered", not "answered yes".** The completion gate filters
on `answer == null`, so an item answered **False** satisfies it exactly as True
does. `completionGate` here treats `!isDone` as outstanding, which is correct
*because* the server maps `isDone = answer != null` — if anybody "fixes" that
mapping to mean "answered True", this screen starts refusing completions the
server would allow. A backend test pins it.

🔴 **False-with-remark is unreachable.** The request carries one boolean against
a three-state column whose False arm requires a remark. Recording "no, and here
is why" needs a contract change (Stream A's), which is why the checkbox is
honest about only offering two states.

## Two things deliberately absent

**Skip**, because the caller's own identity cannot support it. `POST /skip`
needs the onboarding module role `OB_MANAGER` or `OB_ADMIN`
(`NotAnOnboardingModeratorException`, 403), and **`Me` carries no module role** —
`MeAllOf` has `permissions`, `projectIds` and `reporteeIds`, none of which say
anything about `ONBOARDING`. The grants list that would is Admin-only. There is
no honest way for this panel to decide who should see the button, and rendering
it for everybody puts a 403 behind a control most callers can never use. It
lands when `/me` carries the module claim.

**Communications and history**, because they are **C-112**, whose backlog line
owns "per-step, **plus** the client-level stitched view". `listObStepCommunications`
and `listObStepHistory` exist and are not called here: splitting the per-step
timeline from the stitched one across two tasks would mean building the same
component twice.
