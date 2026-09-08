# `features/onboarding/clients` — B-108

OB-03, the onboarding client list, at `/onboarding/clients`. Four files: the
page, its filter state, its cell logic, and their tests.

**This is not `features/clients`.** That is the *ticketing* client master
(S-32, B-025). The two tables are disjoint with no foreign key between them
(plan §1.2); a company present in both is linked at the identity layer through
`client_accounts` by an explicit audited admin action, never matched
automatically. The two screens look alike because they are both keyset grids
with a filter row, and a reader who knows one should not have to learn the
other. Nothing is shared between them, and `moduleSeparation.test.ts` refuses
the import in the other direction.

| File | Does |
|---|---|
| `ObClientListPage.tsx` | The screen — header, six filters, grid, keyset pager |
| `useObClientFilters.ts` | Filter state, in the URL |
| `obClientRow.ts` | The cell logic: health, status, journey count, products |

## The Health column is where three server fields become one

`ObRag`'s description in the contract spends a paragraph refusing to be six
things at once, and this is the screen that made the refusal necessary — the
prototype's client chip merged on-track, at-risk, breached, waiting,
prerequisites-pending and live into one label. Three of those six are not
health: `LIVE` is `ObClientStatus`, "prerequisites pending" is `ObGateStatus`,
and "waiting on client" is `ObStepClockState`.

So the *cell* reconciles them, once, in `healthChip`:

1. **A locked gate wins.** `rag` is null while every journey is locked, there is
   no clock running to colour, and the thing somebody can act on is the
   prerequisite checklist. §9's label is "Prerequisites pending".
2. **Then the colour**, under the module's own vocabulary — On track / At risk /
   Breached, from `journeyStrip.ragLabel`, not GREEN/AMBER/RED. A filter whose
   words differ from the chip it filters reads as two different fields.
3. **Then "Not started"** — an open gate and no colour, meaning every journey is
   finished or archived. Neutral, because a client with nothing running is not a
   problem.

`LIVE` stays in its own column. A client can be live and amber on a journey
bought after go-live, and folding the two would hide the second.

## Six filters, and why two of them are not in the backlog

B-108 names four — status, RAG, owner, sales person. `productId` and
`gateStatus` come from the contract, and `gateStatus` is not optional: it is the
**only** way to ask for §9's "Prerequisites pending" clients, because they have
no colour and are returned by none of the three RAG values.

That makes `gateStatus=LOCKED` **and** a colour the one combination that can
never match anything — it asks for a client that is both running and not. The
empty state says so rather than looking broken.

## `ownerId` is B-108's own contract change

The other five filters are columns on `ob_clients`. This one is not, and it is
deliberately not being made into one: a journey has no owner of its own
(`ObJourneySummary.owner` is the owner of `currentStep`), so a client with four
products has as many owners as it has running services and a single `owner`
field on the row would have to pick one. What the screen needs is the question —
"my clients" — which the server answers by walking journeys to steps.

**Backup owners count**, on `OnboardingScopeResolver.hasStepOwnedBy`'s own
reasoning: the backup exists to cover the step when the owner cannot, and a
filter that hid those clients would hide exactly the ones a stand-in has been
asked to pick up. `ObClientsIT.backupOwnersMatchToo` is the assertion, and
`unownedStepsMatchNobody` is its counterweight — both owner columns are nullable
and SQL equality never matches NULL, so an unowned step attributes the client to
nobody.

## Filter state lives in the URL

A filtered onboarding list is a link. "Every red client Ravi is implementing" is
what one manager sends another during a stand-up, and it cannot be if the state
is private to a component. `useObClientFilters` parses it back through the
enums rather than trusting it — a hand-edited `?status=live` would otherwise
reach the server as a filter it does not recognise while the chip claimed a
narrowing that never happened.

Changing any filter clears the cursor stack. A cursor is a position in one
ordered result set, and resuming it under a different filter returns rows that
are arbitrary rather than empty — which is worse, because it looks like data.

## What is deliberately not here

| Absent | Why |
|---|---|
| A "New client" button | OB-04 is B-109's wizard and does not exist. A button that 404s is worse than none |
| Row selection and bulk actions | Nothing on an onboarding client is safely settable in bulk: `LIVE` is earned rather than set, and `ON_HOLD`/`DROPPED` each need a per-client reason |
| Sort controls | The contract orders by `onboardingDate` descending and the keyset cursor is built on exactly that. A sort control would be a second contract and a second cursor |
| A nav entry | The onboarding shell is B-109's. The module still has no sidebar section, so this page links out to OB-02 rather than pretending to be one |
| A PAN search | The contract is explicit that `q` is name-only. The PAN is masked on every read, so matching it would make search an oracle for a value nothing returns |

## Reading order

`obClientRow.ts` first — it is the argument, and it is forty lines. Then
`useObClientFilters.ts`'s class comment for the RAG/gate split, then the page.
`ObClientListPage.test.tsx` documents the fixture corpus the assertions lean on.
