# `feature/onboarding/prereqs` — B-124 and B-125

The **prerequisites master** (OB-14): the org-wide, versioned set of tasks a
client owes before any of their journeys can start. Nine routes, built to the
`/onboarding/prereq-template*` operations A-118 had already published.

**B-125** adds the per-client half in the same package: the snapshot, ad-hoc
tasks, the `PENDING → SUBMITTED → VERIFIED` lifecycle with its return loop and
its one valve, the comment thread and the hash-chained history. Eleven more
routes.

**The gate itself is still C-118's.** `ObPrereqGate` is the seam; deciding
*whether* the gate is satisfied is arithmetic over the task rows and lives
here, and the four consequences — flipping journeys `OPEN`, activating
dependency-free steps, starting clocks, firing the kickoff — are Stream C's.

## What is here

| Class | Does |
|---|---|
| `ObPrereqTemplateController` | `/prereq-template` — read, revise, publish, add task, reorder |
| `ObPrereqTemplateTaskController` | `/prereq-template-tasks/{id}` and its documents |
| `ObPrereqTemplateService` | Versioning, cloning, publishing, and every guard |
| `ObPrereqTemplateAssembler` | Composes the response from three tables plus two borrowed |
| `ObPrereqTemplateDtos` | Wire shapes, named to match the contract's components |
| `ObPrereqTemplateExceptionHandler` | RFC 9457 problems, scoped to these two controllers |

Entities and repositories are `domain/onboarding/ObPrereqTemplateVersion`,
`…Task` and `…TaskDoc`. `ObAttachment` was **widened** by one column rather than
duplicated — see below.

## One master, not one per product

`ob_journey_templates` is keyed by product because a journey delivers a thing
that was bought. Prerequisites are the client's own responsibilities, which plan
§4 makes the same set regardless of what they bought.

Everything odd about this package follows from that one difference:

- **The paths carry no id.** `/onboarding/prereq-template` *is* the resource; a
  version is a query parameter on it.
- **"One draft at a time" is a database constraint**, not only a service rule —
  `uq_ob_prereq_template_versions_draft`, over a generated column. With one
  draft for the whole organisation, two Admins editing at once is the normal
  case rather than the unlucky one.
- **There is a real `PATCH`**, where `ObJourneyTemplateStepController` has none.
  Delete-plus-re-add would lose a task's position and its reference documents,
  and on a flag that decides whether a gate can ever open it is heavier than the
  edit it stands in for. Hence a real `If-Match` too.

## The guarantee this package exists to hold

**An admin edit never changes what an already-boarded client was asked for.**
Plan §1.1 #2 names the prerequisites master specifically.

The mechanism is the one C-101 established: mutate only a draft, publish by
superseding, and let B-125's instance pin the version it was snapshotted from.
The test is `publishedAt == null` — never `!isActive`, which would reopen a
*retired* version whose clients are still working through it.
`ObPrereqTemplateServiceTest.editingARetiredVersionIsRefused` is the test that
catches exactly that mistake and nothing else; it was mutation-checked.

## Publish refuses an all-optional checklist

`ObJourneyTemplateService#publish` refuses a template with no steps. This one
refuses a version with no **mandatory** task, which is a different emptiness and
the same argument: the gate is satisfied when every mandatory task is verified,
so a checklist with none clears itself the moment it is instantiated. Every
journey would open at boarding and the gate would look present while doing
nothing. Plan §5.3 calls it a hard gate; publish time is where that is enforced.

## Two columns that look like one fact and are not

`ob_prereq_template_task_docs.template_task_id` says **where a reference
document is listed**. `ob_attachments.prereq_template_task_id` says **where the
file was uploaded**.

They agree for every document an admin attaches — `addTaskDoc` refuses one that
does not — and they deliberately differ after a revision, which clones the
caption onto the new draft's task and *shares* the file rather than duplicating
the object in storage. That is why no foreign key ties them together: a
composite key would refuse every cloned row, which is most of them on any
version after the first.

## Owed to Stream A, flagged rather than done quietly

- **`ob_attachments` gains a fourth owner arm** (`prereq_template_task_id`) and
  its `ck_ob_attachments_one_owner` is widened. `V20260903_2045` reserved that
  widening for B-125 and was one task out — it saw the instance side, and the
  *master* side belongs to no client, no step and no sign-off, so
  `addObPrereqTemplateTaskDoc` is unimplementable without it.
- **That arm is the one owner FK that `CASCADE`s** where the other three
  `RESTRICT`. The reasoning is in `V20260908_1100` §4; the cost is an orphaned
  MinIO object, which no service-side tombstone can close.
- **Twenty rules added to `ObModuleRoleRules`** (`api/security/module/`), where
  A-122 moved them from the test-only matrix. `ObModuleRoleFilter` applies them
  on every request, so these routes are enforced rather than merely declared and
  `NOT_YET_ENFORCED` stays empty.
- **B-125 adds the fifth `ob_attachments` owner arm** (`prereq_task_id`) — the
  one `V20260903_2045` actually meant. `RESTRICT`, where the master arm
  cascades: a client's submission is the evidence A-102's tombstone rule
  protects.
- **`ObPrereqJournal` in `domain/journal/`**, on C-107's precedent. There is no
  other legal home — `AppendOnlyRulesTest` names that package.
- **One line of `contracts/openapi.yaml`**: `ObClientPrereqs.gateStatus` was the
  only `gateStatus` in the file wrapped in `allOf`, which made an introspecting
  reader see an object where the server serves the enum string.
  `ContractConformanceTest` caught it; the other three are plain `$ref`s and
  this now matches. The regenerated client changes by one `describe()` line.

## B-125 · the instance side

| Class | Does |
|---|---|
| `ObClientPrereqController` | `/clients/{id}/prereqs` and the ad-hoc create |
| `ObPrereqTaskController` | The task page, four transitions, thread, history |
| `ObClientPrereqService` | Snapshotting at boarding, and the strip's arithmetic |
| `ObPrereqTaskService` | The four transitions, each with its own refusals |
| `ObPrereqGate` / `…ReadOnly` | The C-118 seam, and the fallback that ships today |
| `ObJourneyGateReader` | Reads the gate the client's journeys already carry |
| `ObPrereqClientVisibility` | A-112's row scope, composed from `ObClientScope` |
| `ObPrereqThreadRepository` | The comment thread (read+append) and the chain (read) |

`ObPrereqJournal` lives in `domain/journal/` — Stream A's package, flagged —
because `AppendOnlyRulesTest` names that package specifically.

### The snapshot is a copy, not a reference

`title`, `description`, `tatDays` and `isMandatory` are copied onto the
client's own rows. Reading them through `templateTaskId` would make an OB-14
edit rewrite what a client is being asked for — the corruption the master's
versioning exists to prevent, reintroduced one join later.

Reference *documents* are the exception, and read through: the wording is what
the client agreed to and must be frozen, while a specimen form the admin
replaces with a clearer one is a convenience the client benefits from.

### The valve is the whole design

Plan §5.3 leaves one way to move a gate a client cannot clear, and it is
non-mandatory tasks only. A mandatory task answers **422, not 403** — 403 would
say "not you" and invite the caller to find somebody with a bigger role, and
there is no such person. `ck_ob_client_prereq_tasks_mandatory_not_skipped` says
the same at the column, so a caller that bypassed the service meets it again.

### Two tables, two different guarantees

`ob_prereq_history` is append-only **and hash-chained**, per client — so
`ObPrereqJournal` is its only door and even a read goes around the repository
in SQL. `ob_prereq_comments` is append-only and deliberately not chained, which
is the line `ob_step_communications` and `ob_step_history` already draw: the
state changes must be provably untampered, the conversation is append-only
because it is a conversation. A return's mandatory comment is written to both.

**This is chained where B-101 and B-103 decided not to be**, and their reason
has since been answered: both deferred because the onboarding chain payload was
unwritten, and C-107 then wrote it.

## Not done here

- **The OB-14 admin screen and CP-03/CP-04.** No
  `frontend/src/features/onboarding/prereqs` exists; the generated client and
  the MSW handlers for all twenty operations already do (A-118). The portal
  screens are C-121's in any case.
- **The gate's four consequences** — C-118. `ObPrereqGateReadOnly` reports
  honestly and flips nothing, and says so.
- **The client principal's own routes.** `submit` takes a contact id so the
  portal path is one caller away, but `/api/v1/portal/**` is C-121's.
- **The `prereq-aging` report** stays declared-unavailable in
  `onboardingAdmin.ts`. Its tables now exist, so enabling it is a small
  follow-up rather than a blocked one — flagged for whoever owns OB-10.
