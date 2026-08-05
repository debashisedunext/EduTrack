# Screen prototype

`index.html` is a self-contained, clickable prototype of the five hero screens.
Open it in any browser — no build, no server, no backend.

| Screen | Blueprint |
|---|---|
| Ticket detail with the Workflow Ribbon | S-20, §4A |
| Dashboard | S-05, §7.3 |
| Ticket list with the compact ribbon column | S-17 |
| My Tasks | S-18 |
| Quick update slide-over | S-21 |

Sample data is blueprint §14 walkthrough A — the same ticket end to end, so the
numbers reconcile: 24.5 h in cycle 2, 38.0 h across both cycles.

## What it is for

Design sign-off before the screens are built for real. **Stream C ports the
approved design into the React app** as the component library (C-003) and screen
shells, backed by Stream D's MSW mocks (D-004). This file is a reference, not a
source — nothing here is imported into the app.

## Two open decisions it surfaces

**The §12.1 chart palette is labelled colour-blind safe and is not.** Validated
with an OKLab CVD check: teal `#14B8A6` and pink `#EC4899` sit at ΔE 3.7 for
deuteranopes — effectively identical — and green `#10B981` against cyan `#06B6D4`
is ΔE 12.5 even in full colour vision, below the 15 floor. A validated
replacement that passes every check:

```
#4F46E5  #F59E0B  #BE185D  #06B6D4  #9A3412  #84CC16  #9333EA  #0891B2
```

**The task-type donut should be bars.** §7.3 specifies a donut for 11 categories,
which cannot be read reliably. The prototype uses ranked horizontal bars.

Both are for the team to accept or reject — neither has been changed in the
blueprint.

## Known substitution

The blueprint specifies Inter. This file uses the system font stack because the
shareable version cannot load external fonts. The real app should bundle Inter
properly.
