# `masters/templates` — S-13 tab 3

**B-041.** Blueprint §7.4 tab 3 and §4A.9. Owned by Stream B.

The last of S-13's three tabs. B-039 shipped it as a disabled button naming this
task, B-040 left it that way, and this fills it in.

## The three things §7.4 asks for

1. **Named templates** — the list, the settings pane, and a create dialog.
2. **Built by picking stages** — which is a **copy**, see below.
3. **Mapped to project × task type** — the routing rules panel, plus a checker.

Plus the one the blueprint states as a requirement rather than a feature: **a live
ribbon preview renders as the Admin edits.**

## "Built by picking stages" is a copy, not a picker

The phrase implies a catalogue of stage definitions to choose from. There is no
such table — B-040 recorded this at length. `workflow_stages.template_id` is `NOT
NULL`, so `DEV` on Standard Dev Flow and `DEV` on Support Fast-Track are two rows
sharing a code.

So "picking" here means **cloning an existing ribbon and editing it on tab 2**,
which is also the operation A-005's own header asks for by name: a template is
versioned by copy, never edited in place. The alternative — a second stage editor
on this tab — would be a second copy of the `canReturnTo` direction rule, the
code-uniqueness rule and the `seq` spacing, all of which tab 2 already holds.

## The preview is a data problem, not a rendering one

`previewRibbon.ts` turns a stage list into the `Ribbon` shape B-050's
`RibbonStrip` already draws, and the strip is used **unmodified**.

A second renderer here would be a second thing to keep in step with §4A.3, and
the first time the two diverged the preview would stop being a preview — it would
be showing the Admin something other than what a ticket will show.

What a template does not have is history, and every history field is therefore
left `undefined` rather than filled with a zero:

- `RibbonSegment` renders each data point only when present, so absent fields
  produce a segment showing what a template knows and nothing more.
- A `0` in `effortHrs` or `durationMins` renders as a real measurement of zero,
  which is a claim about a ticket that does not exist. `enteredAt` is worse — a
  date would make the preview look like a journey somebody could click into.

`slaHours` is the exception and is carried through, because it belongs to the
*stage definition* rather than to a run of it.

**Every segment is `PENDING` and there is no `CURRENT`.** That is the decision
most likely to be revisited: a current segment would put the ribbon's "you are
here" ring somewhere on a flow no ticket is standing in, and `RibbonStrip` hangs
its contextual handoff button off exactly that index. The preview would then be
inviting a handoff on a template. `canAdvance: false` suppresses the button and
would not suppress the ring.

A deprecated stage (B-042) renders `SKIPPED` — §4A.3's closest state to "part of
this flow, not entered". Inventing a seventh state for the preview alone would
put a shape on the ribbon that no ticket page can produce.

## The resolution checker is the point of the mapping panel

A list of rules does not answer the question an Admin actually has, which is
*"where does a Production Bug on the CRM project end up?"* — because the answer
may come from a rule on **another template**, or from no rule at all. In the
second case the ticket still goes somewhere, and a screen that only listed this
template's rules would leave that invisible.

So the panel carries a pair picker that asks the server and reports which rung of
§4A.9's ladder answered, in prose rather than as the raw enum: `DEFAULT` on its
own does not say "nothing matched" to anybody who has not read §4A.9.

## One client-side check, and it earns its place

`duplicatePairKeys` refuses two rules naming the same pair **before** anything is
sent. Every other refusal on this screen is a fact about rows the client cannot
see — the name already taken, the pair claimed by another template, a count that
is not zero — and a form that guessed at those would refuse things the server
would have accepted. This one is different: both offending rules are on screen,
in the list the Admin is looking at, so the check is complete here and letting it
reach the server means a round trip returning an error about two rows already
visible.

It marks **both ends** of a clash. Marking only the later one reads as though the
earlier is fine and the new one is wrong, when they are the same mistake.

`mappingsChanged` is order-insensitive, because the server sorts by specificity —
a saved set comes back in a different order than it was sent, and a naive
comparison would leave Save permanently enabled.

## Two tags, two scopes

`useTemplate` and `useTemplateMappings` each cache their own `ETag`, and each
write takes the one matching what it replaces — the split `stageQueries.ts` drew
between `useStage` and `useStages`.

It matters more here: a stage added on **tab 2** moves the template's tag and does
not touch the rules, so a mapping replace preconditioned on the template's tag
would be refused for an edit that has nothing to do with routing.

## Files

| File | What |
|---|---|
| `TemplatesTab.tsx` | the screen — list, settings, preview, rules, checker |
| `templateQueries.ts` | three reads, four writes, two tags |
| `templateForm.ts` | validation, dirty tracking, the duplicate-pair check |
| `previewRibbon.ts` | stage list → `Ribbon`, and what a template does not have |
