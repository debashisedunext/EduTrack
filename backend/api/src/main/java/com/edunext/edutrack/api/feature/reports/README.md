# feature/reports

**Owner: Stream A · Shivendra**

18 reports, exports, scheduling. Screens S-27, S-28.

`client-report` is **Stream B's**, by `STREAM-B-MASTERS.md`'s M6 split ("Weeks
12–14 — M6 reports. Split with Stream A. Typically yours"). B-060 is the first
task in this package written from outside it; `ClientReportRunner`,
`ReportFilters`, `ReportEntityKind` and `TicketReportRepository.clientReport` are
its files.

`resource-scorecard` and `workload-capacity` are Stream B's by the same split.
B-061 added no runner — both existed — and changed `ReportRunner`,
`ReportService`, `ReportRepository.workload`, `ReportDtos.ColumnType` and the
three exporters. See **The subject that never arrived** and **Allocation** below.

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

## Which table answers (A-066)

`ReportRepository` reads the summary tables. `TicketReportRepository` aggregates
`tickets` and `ticket_effort_logs`, because five of §7.8's first six reports ask
questions no summary table can answer at any grain: average cycle time,
estimated-versus-actual variance, a per-person reopen rate, and a *list* of
breached tickets are per-ticket facts.

That is not a relaxation of the dashboard rule. CLAUDE.md and PLAN.md §480 scope
it to dashboards — a screen that loads unbidden on every login and must paint in
1.5 seconds — and it stays absolute there. A report is opened deliberately, with
a date range, by somebody prepared to wait for a figure they intend to quote.
Every one of those queries is bounded by that range and scoped in SQL rather than
filtered afterwards.

Velocity is the exception and reads `resource_daily_stats`: A-050 already records
closed and effort per person per day, and both are flow, so weeks are a sum.

## Filters, and the seam B-060 opened

`ReportRunner` originally took `(scope, from, to, projectIds)`, which was every
parameter the first seven reports needed. The client report cannot be written
against it — `CLIENT` is the filter §7.8 gives it — so B-060 added a
`ReportFilters(clientId, taskTypeId, level)` record beside the scope.

Two of the three were already broken and it did not show. The contract has
declared `?taskTypeId=` and `?level=` since A-066, `TicketReportRepository`
takes both on three queries, and every caller passed `null`, because
`ReportController` accepted neither. `sla-breach` and `effort-summary` now read
them from `ReportFilters`.

**Filters are not scope, and the split is load-bearing.** `ReportScope` stays a
separate argument: a filter narrows what the caller *asked for* and may be
ignored; a scope narrows what they are *allowed* and may not. Folding
`resourceId` in would put the one value with a security consequence — the one
`ReportScope.resourceSubject` overrules for the three delivery roles — into a
bag of optional preferences, where the next runner reads it directly and quietly
reopens what §2 withholds.

**Filters are in the ETag.** Two clients asked for from the same URL differ only
by `?clientId=`, and a validator that ignored it would hand the second caller a
304 against the first client's rows.

**RESOURCE was drawn and unhonoured until B-061** — the opposite failure, and
the worse one. See **The subject that never arrived** above.

**TASK_TYPE and LEVEL are honoured but undrawn.** `ReportFilterBar` renders four
of the six kinds; those two have no control yet, so they are reachable by URL
and by `?export=` and not from the bar. That is a gap rather than the failure the
per-report filter list exists to prevent — nothing on screen claims to filter and
then does not. One branch each, and they belong to those two reports.

## The subject that never arrived (B-061)

`ReportService.run` resolved `?resourceId=` into a subject, put it in the ETag
and in `meta.appliedScope`, and **did not pass it to the runner**. Every runner
re-derived it as `scope.resourceSubject(null)`, which answers null for anybody
who is not a delivery role — so the Resource control narrowed nothing for the
Admins and PMs it exists for, on all five reports declaring it. `workload` had
no query parameter for it at all.

**It presented as an applied filter, not an absent one.** The viewer printed
"showing one resource, across all projects" over rows that had not been
narrowed, and the ETag varied by a parameter the body did not depend on, so two
subjects asked for from one URL could share a validator. B-060 found the same
shape in `?taskTypeId=` and `?level=`; this is the seam below it.

`resourceSubject` is now the fifth argument to `ReportRunner.run`, sitting
beside `projectIds` because they are the two **resolved narrowings**. It is
deliberately not a fourth field on `ReportFilters`: a filter narrows what was
asked for and may be ignored, a scope narrows what is allowed and may not, and
that record is where an optional preference belongs. `ReportScope.resourceSubject`
now says at the method that a runner must read the parameter rather than call it.

Making an inert parameter live is the change that can leak, so
`ReportRunnersIT.ResourceFilter` pins both halves — that it narrows, and that a
delivery role naming a colleague still reads their own rows. Each case filters
to the person who is *not* in the project under test: asking for the only person
present passes against the bug, which is how this survived two tasks.

## Trend is a column type (B-061)

§7.8 ends the scorecard's column list with "Trend arrows". The column was typed
`NUMBER`, so the cell read `-3` and `ReportChart` plotted a signed delta as a
series beside an SLA percentage and a cycle time.

`TREND` is the one `ColumnType` that is not a data type — it says what the number
*means*: a change against the comparable preceding window. A `key === 'trend'`
branch in the table would have drawn the arrow and left the chart wrong, and
would be the second copy of the server's vocabulary this package refuses to keep.

**Direction is not a verdict.** Up is not drawn as good. The same type will carry
a reopen-rate trend, where up is bad. **Exports write the number, not the arrow**
— a spreadsheet cell holding a glyph cannot be sorted or summed, which is what an
export is for, so `TREND` joins the numeric arm in `XlsxReportExporter` and
`PdfReportExporter` and stays out of `PdfChart`'s series, matching the screen.

## Allocation, and why it is not project-scoped (B-061)

B-017 built the Team tab's per-project allocation and could not answer the
question that is actually a warning — a resource's total across *all* their
projects — because that screen holds one project's rows. It flagged the figure
for B-061 by name.

`workload-capacity` reports three columns from `project_members`: projects,
allocated, and allocation stated on. The aggregate spans **every active
membership, including projects the caller cannot see**. That is not a widening:
`resource_daily_stats` is keyed by user with no project column, so the load
columns beside it have always shown a person's whole load. Narrowing only the
allocation reproduces the bug B-017 flagged — a PM owning one of somebody's
three projects reads 50% and concludes there is room. A percentage and a count
cross the boundary; a project name never does.

**The total is a floor and says so.** `allocation_pct` is nullable and means "not
stated"; B-017 refused the contract's `default: 100` because a backfill would
read 300% for every fixture resource on three projects. So the sum covers only
memberships that stated a figure, `SUM` over an all-null set stays null
(`getBigDecimal`, never `getInt`), and the count that stated one is published
beside the total. The card's description names the limit, which is the call
`client-report` made about the figure it does not have.

`workload-capacity` also moved from `stacked-bar` to `bar`. Stacking asserts the
series partition a total and these never did — an open ticket is also counted
under critical and under delayed — so the height was double-counting before
B-061 added a percentage to it. **Still not a good chart**: eight series on one
axis is hard to read, and that is A-067's to redesign.

## Linked cells (B-060)

§7.8 ends the Client Report's line with "drills into the client 360 view", and
the generic column/row shape had no notion of a destination. A column may now
declare `linkTo` (a `ReportEntityKind`) and `linkIdKey` (the row key holding the
id), and `ReportTable` renders an anchor.

**The server names an entity and never a path.** `/clients/42` from here would be
a second copy of the router, in another language, that nothing keeps in step with
`entityLinks.ts` — and the first symptom would be report links 404ing after a
rename every other screen survived. The two properties are absent together or
present together: a kind with nothing to key on renders a dead anchor, which is
harder to notice than a missing one.

The id is carried in the row with **no column of its own**, so it stays out of
the table and out of `?export=`, which iterates columns. An internal id is not a
figure to put in a spreadsheet sent to a client.

## The figure the client report does not have

§7.8 asks for five per client and four are recorded. There is no CSAT, rating or
feedback column in this schema, in the contract, or in any migration — blueprint
§17 item 19 puts the closure rating in **phase 2–3**. So `client-report` declares
no satisfaction column and its catalogue description says so.

Three alternatives were available and each is worse. A column of em dashes reads
as "we asked and they did not answer", a claim about the clients rather than
about the schema. Reopen rate relabelled is a plausible number under the wrong
word — reopens measure our quality, not whether the client was content. SLA
compliance standing in collapses two of the five into one and makes the report
agree with itself by construction. This is the report §7.8 describes as shaped to
be sent to a client, which is the worst place in the product for an invented
figure. One migration closes it whenever CSAT lands.


## Scheduled reports (A-065)

| Route | What |
|---|---|
| `POST /reports/schedule` | create one — D-001 declared this |
| `GET /reports/schedules` | the caller's own, cancelled included |
| `DELETE /reports/schedules/{id}` | stop it |
| `GET /reports/schedules/{id}/runs/{runId}/download` | **what the emailed link points at** |

D-001 declared only the first, answering a bare `201`. The other three are what
make it a feature rather than a subscription with no unsubscribe.

**Scope is re-resolved on every run, never stored.** This is the whole security
design and it is worth understanding before touching anything here. A report is
scoped to whoever runs it (§2), and a schedule runs with nobody logged in — so
the obvious move is to freeze the creator's role and projects onto the row. That
is wrong in a way that never announces itself: roles change, and a PM moved to
Developer would go on receiving project-wide figures every Monday from a row
recording what they used to be, with the mail arriving on time being exactly
what stops anybody noticing.

`ReportScheduleRepository.callerFor` reads the owner's *current* role and
*current* project memberships and hands back the same `CallerIdentity` an HTTP
request would carry — so the run goes through the identical `ReportService.run`
the viewer uses, and there is no second path where scope could be forgotten. A
demotion narrows the next email; deactivating a leaver stops it, and empty means
stop rather than "unrestricted".

**The mail carries a link, not the file.** `ImportReportStore`'s argument, and
it matters more here: a report is a verbatim extract of the organisation's data,
and an attachment is an uncontrolled copy that outlives every permission change
made afterwards — in an inbox, in a mail archive, and in every relay in between.
`ReportFileStore` deliberately has no method that can mint a public address, so
there is no presigned URL to leak either. The download route re-checks ownership
*at the moment of the click*, which is the check an attachment cannot make and a
signed URL makes once.

**The period comes from the cadence and is never stored.** A stored `from`/`to`
would win over it and make every run email the same window for ever — a failure
indistinguishable from a working schedule until two files are compared. Both the
dialog and the service drop dates, and both say why.

**The scheduler lives in `api`, not `worker`.** The only one that does. `worker`
depends on `domain` and not on `api`, and the eighteen runners are here — so the
alternatives were moving three thousand lines into `domain` or giving a mail
worker a dependency on the web module. `ApiSchedulingConfig` is opt-in and
switched on in `application.yml`; with it off there is no `@EnableScheduling`,
which is what keeps ShedLock away from a Redis that test contexts do not run.

## Not here yet

- ~~**Scheduling**~~ — landed with A-065. See below.
- **Five reports** — A-068 and whoever takes `resource-contribution`,
  `rework-analysis`, `deployment-report`, `audit-compliance` and
  `email-delivery-log`. Thirteen run today: `date-wise` (A-063), §7.8's first six
  (A-066), five more (A-067) and `client-report` (B-060).
