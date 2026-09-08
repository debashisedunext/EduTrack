# `feature/onboarding/prereqs` — B-124

The **prerequisites master** (OB-14): the org-wide, versioned set of tasks a
client owes before any of their journeys can start. Nine routes, built to the
`/onboarding/prereq-template*` operations A-118 had already published.

**This is the master only.** The per-client snapshot of it — instances, ad-hoc
tasks, `PENDING → SUBMITTED → VERIFIED`, the comment threads and
`ob_prereq_history` — is **B-125**, and the gate that reads those instances is
**C-118**. Nothing here evaluates a gate or knows a client exists.

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
- **Nine rules added to `ObModuleRoleRules`** (`api/security/module/`), where
  A-122 moved them from the test-only matrix. `ObModuleRoleFilter` applies them
  on every request, so these routes are enforced rather than merely declared and
  `NOT_YET_ENFORCED` stays empty.

## Not done here

- **The OB-14 admin screen.** No `frontend/src/features/onboarding/prereqs`
  exists yet; the generated client and the MSW handlers for these nine
  operations already do (A-118).
- **The `prereq-aging` report** stays declared-unavailable in
  `onboardingAdmin.ts`. It reads `ob_client_prereq_tasks`, which is B-125's.
