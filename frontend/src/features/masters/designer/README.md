# `masters/designer` — S-30, the workflow template designer

**B-043.** Blueprint §7.4 S-30 and §4A.1. Owned by Stream B.

> *"the visual builder inside S-13: drag stages onto a canvas, set owner role and
> SLA per stage, draw the allowed return paths, preview the rendered ribbon, then
> map it to project × task type."*

| File | What |
|---|---|
| `WorkflowDesignerPage.tsx` | the screen — canvas, palette, inspector, preview, rules |
| `canvasModel.ts` | the palette, the drop arithmetic, the arc layout, the refusals |

## What was already built, and what this adds

Every *rule* S-30 needs was shipped by B-040, B-041 and B-042, and so was every
*route*. Tab 2 holds the stage editor and its drag-to-reorder; tab 3 holds the
preview and the routing rules. What did not exist is the thing S-30 names: **one
surface** where the flow is laid out spatially, the arrows are arrows, and the
ribbon redraws under your hands — rather than three panels on two tabs each
holding a third of the answer.

So this is a screen, not a subsystem. It reuses `stageForm`, `stageQueries`,
`templateQueries`, `previewRibbon` and `RibbonStrip` unchanged.

## Nothing new reaches the server

Create a stage · patch one · replace the order · replace the mappings · create a
template with `copyStagesFromTemplateId`. All five existed.

**No endpoint, no `PermissionMatrix` row, no `contracts/` change, no migration.**
The six roles are already decided on every route this screen calls, which is why
B-043 touches neither Stream A's directory nor Stream D's — the first task in
this run of the backlog that touches only its own stream.

## The order is staged; everything else writes when you finish it

The arrangement worth arguing about, because a canvas invites the opposite.

A designer holding every edit until one Save would be a batch of creates, patches
and a reorder **across three route families with no rollback between them** —
half a flow saved and no way to say which half. The append-only core cannot give
this screen a transaction, and inventing a bulk "save the whole flow" route would
be pretending otherwise.

So: dropping a node creates it, the inspector patches on Save, drawing an arc
patches `canReturnTo`. Only **reordering** is staged — the reorder route is a
whole-set `PUT` under one `If-Match`, which is exactly what tab 2 does with its
drag, and one Save means one request and one precondition.

**One honest seam, named rather than hidden.** A node dropped in the middle is
created *last* — the create route appends, because a caller-chosen `seq` collides
with `uq_workflow_stages_seq` — and the canvas then moves it to where it was
dropped as a *staged* move. So the Save button lights up and the live region says
"added at position 3, save the flow to keep it there".

## The palette is a vocabulary, and the screen says so

"Drag stages onto a canvas" needs something to drag, and **there is no stage
catalogue**. `workflow_stages.template_id` is `NOT NULL`, so `DEV` on Standard Dev
Flow and `DEV` on Support Fast-Track are two independent rows. B-041 resolved the
same phrase on tab 3 by reading "picking" as cloning a whole template — right for
standing up a new flow, useless for adding one stage to an existing one, which is
the operation this screen exists for.

What does exist is a **vocabulary**: `WorkflowTemplate.stages`, the inline array
B-040 fought to keep on the list response, carrying code, name, sequence and
owner role across every template. Its union is the set of stage names this
organisation has agreed on, and it is a genuinely useful thing to drag — a fourth
template's QA stage should be called `QA` and owned by the QA role, because the
other three call it that.

Dropping one **creates a new row here**, pre-filled. It does not link back, so
every card names the templates the code came from. An Admin who thought they were
editing a shared definition would be wrong, and the screen should not let them
think it.

Codes already on the canvas are excluded — `uq_workflow_stages_code` is per
template, so a second `DEV` is a 409 the palette can see coming.

## Two bugs the tests found, both silent, both about `seq`

**The preview did not follow the drag.** `buildPreviewRibbon` and `previewChain`
both sort by `seq` — correctly, for B-041's caller, which hands them exactly what
the server returned. A staged reorder is an array in a new order whose rows carry
the *old* `seq`, so handing it over raw sorts the drag back out: the canvas showed
the dragged flow and the preview underneath it showed the saved one. That lands on
the single thing §7.4 words as a requirement — *"a live ribbon preview renders as
the Admin edits"* — and it would have looked right in every screenshot taken
before the first drag. `resequence` renumbers on B-004's 10/20/30 spacing, which
is what the reorder route will actually write.

**The return-target picker offered forward stages for one round trip.**
`returnTargetOptions(stages, position, …)` reads `null` as *"appended last, so
everything qualifies"* — right for a stage being created, wrong for an existing
one whose detail read has not landed. Ticking a target in that window sent a patch
the server refuses. The list row already carries the same `position`, so it is the
fallback rather than `null`.

## Arc lanes, and why narrowest-first is the whole of the correctness

`DEV → TRIAGE` and `QA → TRIAGE` share an endpoint and `SIGNOFF → DEV` spans both.
Drawn at one depth they overlap into a smear, and the screen whose justification
is *"see the flow"* hides two of the three things §4A.1 says about the template.

Lanes are the interval-graph greedy, run **narrowest first**. Widest-first is the
natural way to write it and it draws the containing arc on the inner lane with the
nested one dipping *underneath* it — a loop-back crossing its own container twice,
which reads as two arrows tangled rather than one inside the other. Nesting on the
page has to match nesting in the flow.

Deterministic matters more than optimal: an arc that changed depth when an
unrelated one was added would make the canvas appear to move on its own.

**A broken arc is laid out, not filtered.** A staged reorder can leave an arrow
pointing forwards — exactly what `forwardReturnPaths` refuses to save. Dropping it
from the layout would make the offending arrow vanish at the moment it became a
problem, leaving an Admin reading a Save button that names a pair they can no
longer see. It is drawn in the danger token instead.

## The SVG is `aria-hidden`, and the inspector is the accessible copy

Every arc is also a row of text in the inspector's *Returns to* list. An SVG path
announced as a path is a second, worse reading of the same fact. The picture is
for the eye; the list is the accessible copy — and it is **editable**, which the
picture is not.

## Keyboard parity, not a keyboard fallback

A canvas is the archetype of the control that ships pointer-only, and B-040's
README already makes the case one screen over. Every node carries Move left /
Move right, the palette carries Add, every arrow can be drawn from the inspector's
checkboxes, and each move is announced through an `aria-live` region.

`WorkflowDesignerPage.test.tsx` drives all of it through those buttons rather than
through synthetic drag events, so the accessible path is the one under test rather
than the one alongside it.

## `MappingPanel` moved rather than being rebuilt

S-30's sentence ends *"then map it to project × task type"*, so the designer needs
tab 3's panel. A second mapping editor would be a second copy of
`duplicatePairKeys`, of the order-insensitive dirty check and of the resolution
checker's reading of §4A.9's ladder — B-041's own README argues that case one
level down about the stage editor and reaches the same answer.

So `templates/MappingPanel.tsx` is a **pure move** out of `TemplatesTab.tsx`.
Nothing about the behaviour changed and both screens render the same component.

## Versioned by copy, never edited in place

§4A.5, and the generated client names *this screen* as where the rule is kept:
there is no `version` column — B-040 removed the contract's field rather than
serve a hard-coded `1` — so a new version is a **new template** whose ribbon is a
copy. `copyStagesFromTemplateId` does it, deprecated stages included, because a
copy that quietly dropped them would differ from its source in a way nothing
records.

## Its own route

`App.tsx` has carried *"a template designer gets its own route (S-30) when B-043
lands"* since B-039 mounted `/masters/statuses`. This is that route:
`/masters/workflow/designer/:templateId` — under `/masters/workflow/` rather than
beside `/masters/statuses/:id` so a template id can never be read as a status id.

Reached from tab 3, **not from the sidebar**. S-30 is the builder *inside* S-13,
and a nav entry beside "Statuses, stages & workflow" would read as a second,
competing master.

## Tests

| Suite | What it holds |
|---|---|
| `canvasModel.test.ts` | 31 — the vocabulary, the drop arithmetic, three refusals, lane assignment including the nesting case, the arc path, and `resequence` |
| `WorkflowDesignerPage.test.tsx` | 26 against the mock server — the flow drawn, staged-then-saved reordering, the refusal before the request, the broken arrow that stays visible, the palette on a template that is missing codes and on one that is not, the inspector's SLA floor, drawing an arrow forwards and backwards, the preview following the drag, and duplication |
