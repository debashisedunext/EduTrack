# `masters/templates` — S-13 tab 3, the Workflow Template Master

**B-041.** Blueprint §7.4 tab 3 and §4A.9. Owned by Stream B.

> Named templates (Standard Dev Flow, Support Fast-Track, Infra Flow) **built by
> picking stages**, then **mapped to project × task type**. A **live ribbon
> preview** renders as the Admin edits, so the flow is validated visually before
> saving. — §7.4

## What was here before

Almost everything except the half that matters. A-005 created
`workflow_templates` and B-004 seeded three rows; B-040 built tab 2 and finally
*served* `listWorkflowTemplates`, which had been declared since D-001 with no
controller behind it.

What did not exist was **the mapping**. §4A.9's whole sentence is "define a
template per project and per task type", and there was no table to define it in.
B-040 removed `projectId` and `taskTypeId` from the response rather than emit two
nulls, and named this task as the one that owed them somewhere to live.

## The table is the interesting part

`workflow_template_mappings` — one row per routing rule, both columns nullable,
and **NULL means "any"**.

```
(project, taskType)  → this project, this task type
(project, NULL)      → this project, whatever the task type
(NULL, taskType)     → this task type, whatever the project
(NULL, NULL)         → an explicit catch-all somebody wrote
[no row]             → workflow_templates.is_default
```

🔴 **The obvious version of this table is silently broken.** `UNIQUE (project_id,
task_type_id)` reads as "one template per pair" and does not enforce it, because
MySQL treats every NULL inside a unique index as distinct — `(5, NULL)` inserts
twice, under two different templates, and neither insert fails. The resolver then
has two answers for the same rung and returns whichever the optimiser reached
first: a ticket raised on Monday and one raised on Tuesday getting different
ribbons, with nothing recording that the configuration was ambiguous.

Two `STORED` generated columns collapsing NULL to 0, with the unique key over
those, restore it. `TemplateMasterIT.duplicateWildcardPairIsRefused` writes
through `JdbcTemplate` rather than through the service on purpose: the service
checks for the collision itself, so going through it would prove the service's
guard and say nothing about the schema.

## The ladder is a service, not a view

`TemplateResolver` evaluates the five rungs above and reports **which one
answered**. A view could express the precedence and could not say which rung
produced the answer — and that is the one thing tab 3 has to show, because a pair
silently falling through to the default is the failure mode §4A.9's configuration
has no other way to surface.

**Project beats task type at equal specificity.** A project is the narrower
population, so "everything on this engagement follows that flow" outranks "this
kind of work usually follows this one". The opposite precedence is defensible and
would route real tickets differently, which is why it is written down and tested
rather than left to whichever `ORDER BY` was typed first.

## Nothing calls the resolver from ticket creation

`tickets.workflow_template_id` has existed since A-004 and is written by nothing
but `SingleTicketFixture` — which is why `RibbonAssembler` and
`NoNextStageException` both carry a documented "ticket with no template" path.

**Wiring it in is Stream C's**, not this task's: `TicketService` is
`feature/tickets/`, and CLAUDE.md asks for that owner's sign-off rather than a
quiet edit. B-041 ships the table, the ladder and `GET
/masters/workflow-templates/resolution`; the call site follows.

## "In use" means two different things

The two guards are different counts on purpose, and folding them into one would
be wrong in both directions.

| Operation | Refused by | Why |
|---|---|---|
| Deactivate | `mappingCount` | What the template is *for*. Switching off a template three rules route to means the next ticket on any of those pairs resolves to a template the master says is out of service. History is irrelevant — a template with ten thousand closed tickets and no live rule *should* be retired. |
| Delete | `ticketCount` | What the template has *done*. The delete cascades `workflow_stages`, and every historical ribbon segment resolves its display name, icon and owner role through those. |

One difference from B-042 worth recording: `tickets.workflow_template_id` is a
**real** foreign key, unlike the stage codes B-042 had to defend by hand — so the
database would refuse the delete on its own. The service check exists so the
refusal arrives as a sentence with a number in it, and so the screen can decline
to offer the button rather than discovering the rule by pressing it.

## Exactly one default, and it can only be moved

`is_default` is the last rung of the ladder, so two of them means a ticket's
ribbon depends on row order and none of them means every unmapped pair routes
nowhere. The database asserts neither — it is a plain `TINYINT` with an index — so
it is held here: setting a new default clears the old in the same transaction, and
clearing the current default without naming a replacement is refused. That is
B-039's *"at least one on-create row must survive"* rule on a different table, for
the same reason: this is the only screen that could undo it.

The clear is **one statement**, not a read-modify-write. The obvious version is a
lost update waiting to happen — two Admins promoting two different templates would
each clear what the other had just set, and the table would end with two defaults
or none.

## Routes

| Verb | Path | Who | Notes |
|---|---|---|---|
| `GET` | `/workflow-templates/{id}` | all six | `ETag`, and it covers the three counts |
| `POST` | `/workflow-templates` | Admin | declared since D-001, served here; `copyStagesFromTemplateId` |
| `PATCH` | `/workflow-templates/{id}` | Admin | `If-Match` required |
| `DELETE` | `/workflow-templates/{id}` | Admin | `If-Match` required; refuses most templates |
| `GET` | `/workflow-templates/{id}/mappings` | all six | `ETag` over the whole set |
| `PUT` | `/workflow-templates/{id}/mappings` | Admin | whole-set replace, `If-Match` required |
| `GET` | `/workflow-templates/resolution` | all six | which template, and which rung |

`GET /workflow-templates` stays on `StageController`. It is tab 2's selector, it
has no `ETag` on purpose, and moving it here would change a route Stream C's
ticket list has read since C-013 in order to tidy a package boundary.

## `createWorkflowTemplate` could not be served as declared

The seventh route this stream has found with a mock, a generated client and no
controller. Two fields left the request:

- **`projectId`, `taskTypeId`** — scalars on the template, which reads as though a
  template belongs to one pair. §4A.9 refutes it in the paragraph that introduces
  it: Standard Dev Flow covers three task types. The mapping is its own resource.
- **`stages`** — `createStage` already writes a stage and holds the `canReturnTo`
  direction check, the code-uniqueness check and the `seq` spacing. A second path
  in would be a second copy of those rules.

`copyStagesFromTemplateId` replaces the third, and is §7.4's "built by picking
stages" done the way A-005's own header asks for: **versioned by copy**.
Deprecated stages are copied with the rest — the copy is a new ribbon whose shape
is the old one, and dropping the retired segments would produce a template
differing from its source in a way nobody asked for and nothing records.

## Two schema-name collisions, avoided rather than discovered

B-040 recorded that `NotificationTemplateDtos.TemplateView` and its own
`TemplateView` both became schema `TemplateView` in springdoc, where the second
silently replaces the first — caught only by `ContractConformanceTest`. This
package would have made it a third time: `TemplateView`, `TemplateResponse` and
`TemplateListResponse` are all already the notification-template master's. The
records here are named `WorkflowTemplateDetail`, `TemplateMapping`,
`TemplateResolution` and so on for that reason alone.

The same collision exists in TypeScript and bites harder, because it is a compile
error rather than a silent overwrite: `templateEtag` in `mocks/handlers/rest.ts`
was already taken.

## Files

| File | What |
|---|---|
| `TemplateController.java` | seven routes, three preconditions, two tags |
| `TemplateService.java` | the refusals, the default flag, the mapping replace |
| `TemplateResolver.java` | §4A.9's ladder. **Public** — it outlives the screen |
| `TemplateUsageRepository.java` | the three counts and the joined mapping rows |
| `TemplateDtos.java` | wire shapes, and why the declared ones changed |
| `TemplateExceptionHandler.java` | six refusals, six remedies |
