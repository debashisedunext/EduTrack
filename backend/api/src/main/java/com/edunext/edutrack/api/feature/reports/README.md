# feature/reports

**Owner: Stream A · Shivendra**

18 reports, exports, scheduling. Screens S-27, S-28.

## What is here today (A-063)

The hub and the runner, not the reports.

| Route | What |
|---|---|
| `GET /reports` | the catalogue behind S-27's card grid |
| `GET /reports/{reportKey}` | the parameterised runner behind the viewer |

`ReportService` owns scope, the ETag and the 404 rules. A `ReportRunner` owns
only "what are the columns and rows" and never sees the request — so a report
added by A-066 cannot get scoping wrong by forgetting it. Spring injects every
runner, so a new one is registered by existing rather than by being added to a
list.

## Three decisions worth knowing before adding a report

**The catalogue is served, not hardcoded in the client.** A list of keys living
in the frontend would be a second copy of this package's vocabulary, and a
nineteenth report would be runnable by URL and invisible on the only screen
that lists reports. Same argument `/me/notification-preferences` makes in the
contract.

**Unbuilt reports are listed with a reason, not hidden.** Seventeen of the
eighteen are A-066 to A-068 and appear greyed with a sentence. Hiding them
would make "not built yet" indistinguishable from "does not exist". This is
A-056's `unavailableReason` answer applied one screen over.

**Running an unbuilt key is a 404, not an empty 200** — the asymmetry is
deliberate. The catalogue can say "exists, not built yet" in words. A runner
has no columns to name and no rows to return, so a 200 would have to invent an
empty report, which asserts the query ran and found nothing.

## Scope

`ReportScope`, from blueprint §2's "Reports section" row. Admin unrestricted ·
PM and Support bounded by their projects · Developer, QA and Deployment bounded
to themselves.

The one thing genuinely withheld: **`?resourceId=` is ignored for the three
delivery roles**, silently rather than with a 400, because the client is
usually posting back a filter bar it rendered. `meta.appliedScope` states what
was applied, so a filter that did nothing is distinguishable from a filter that
matched nothing.

This is the **third** statement of the row rule in this codebase — over
`tickets` (`ScopeResolver`), over the dashboard's summary tables
(`DashboardScope`), and here. That is one more than is comfortable and it is
recorded rather than hidden: the honest fix is a shared scope vocabulary once
A-066 to A-068 show what the runners actually need, not a premature extraction
that would be redone when the first report joins `project_members`. `ReportsIT`
is what keeps this copy honest.

## Not here yet

- **Export** (`?export=xlsx|csv|pdf`) — declared in the contract, ignored by the
  controller. A-064.
- **Scheduling** (`POST /reports/schedule`) — A-065.
- **Seventeen reports** — A-066, A-067, A-068. `date-wise` is implemented as the
  reference because it reads `daily_ticket_stats` and so needed no schema of its
  own; it belongs to A-067, which still owns the rest of its group.
