# S-19 Create Ticket — C-010

All five blueprint §7.5 field groups: **Identity · Core · People · Effort · Extra**.

| File | What it is |
|---|---|
| `CreateTicketPage.tsx` | The screen. Route `/tickets/new`. |
| `ticketForm.ts` | Form state, validation, and the mapping onto `TicketCreateRequest`. |
| `createTicketMutation.ts` | `POST /tickets` carrying an `Idempotency-Key`. |
| `FormField.tsx` · `LevelPicker.tsx` · `WatcherPicker.tsx` | Feature-local controls. Not shared — see below. |

## Decisions worth knowing about

**Validation bounds come out of the generated Zod, not a copy.** `ticketForm.ts`
imports `createTicketBodyTitleMin/Max` and `createTicketBodyDescriptionMax` from
`api/generated/zod`, and `ticketForm.test.ts` parses the mapper's output against
the generated `createTicketBody`. The form and the contract cannot drift apart
without a test failing. This is the same guard C-011 put on `TicketCode`.

**`plannedCloseDate` is omitted, never nulled, when the user leaves it blank.**
Omitting it is the contract's signal to compute it from the SLA policy against
the working calendar. An explicit `null` means "this ticket has no planned close
date", which silently takes it out of every delay calculation. The override
input is only offered to PM and Admin, matching the contract; the server is what
actually enforces that.

**The description is required here although the contract has it optional.**
Blueprint §7.5 marks it mandatory and the blueprint wins on behaviour.

**Level is a chip radio group, not a dropdown.** §7.5 draws a colour-chip
dropdown. With four options a radio group shows all four chips at once — which
is the information the colour carries — and stays operable from the keyboard
with no popup. Which levels exist, and their order, still come from the priority
master. The chips use the frozen `level-*` tokens rather than the hex the master
returns, because §12.1 owns those colours and the token variants are the pair
that passes AA at chip text size.

**Level pre-fills from the task type's default until the user picks one.** After
that, changing the task type does not overwrite their choice.

**One idempotency key per logical ticket.** Minted on mount, rotated only once a
ticket actually exists. A key regenerated per click defends against nothing — the
resubmit-after-timeout case is exactly the one it is for, and without it that
resubmit allocates a second ID the sequence never gives back. This is why the
page does not use the generated `useCreateTicket`: orval drops header
parameters, so the generated function cannot send the header at all.

**`components/ui/searchable-dropdown.tsx` gained four optional props** — `id`,
`aria-labelledby`, `aria-describedby`, `aria-invalid` — forwarded to the trigger.
Purely additive; every existing call site renders identically. Without `id` the
control reaches a screen reader unnamed, which fails AA. Documented in Storybook
as `LabelledWithError`.

**The watcher multi-select is feature-local.** It is the only multi-select in the
product so far, and anything in `components/ui/` becomes a contract the other
three streams depend on. Promote it when a second caller appears.

## Deliberately not here

| Not built | Owner |
|---|---|
| Inline planned-close-date preview | C-012 |
| Save as Draft · Save & Assign · Save & Create Another | C-013 |
| Inline "+ Add contact", client auto-fills (SLA, account manager as watcher, timezone) | C-021 |
| Attachments, clipboard paste | C-023 · C-024 |
| Opening comment, and the rich-text editor the description will share with it | C-029 |

The description is a plain textarea for now. §7.5 wants rich text, and C-029
wants a rich-text comment box — one editor should serve both, and that choice is
worth making once rather than smuggling into this form.

## Open for Stream D — `contracts/openapi.yaml`

`TicketCreateRequest` has no field for four things §7.5 asks for, so they are not
rendered rather than rendered and silently dropped:

- **Sub-type / category** — the optional second level under task type.
- **Environment** — Prod / UAT / Dev. §7.5 calls it "critical for bug triage".
- **Linked tickets** — blocks / is blocked by / duplicate of / relates to.
- **Tags** — free-form labels.

Two more are rendered read-only at their server default, which is right for the
common path but blocks the override §7.5 allows:

- **Date reported** — defaults to now. Backdating is Admin/PM only, and there is
  no field to backdate through.
- **Reported by** — defaults to the current user. A Support Desk agent raising a
  ticket on a client contact's behalf cannot say so.

Also for Stream D, smaller:

- `GET /clients?projectId=` is in the contract and the form sends it, but D-004's
  mock ignores it and returns every client. Against the real API the list is
  filtered; in `npm run dev` it looks like it is not.
- §7.5 caps the title at 200 characters, the contract at 300. The form uses the
  contract's, since refusing at 200 would reject input the API accepts.

## Open for Stream B — Task Type master

§4B.2 calls the "requires a client" rule *configurable per task type*, but
`TaskType` carries no `requiresClient` flag. `CLIENT_REQUIRING_TASK_TYPES`
matches on the master's name instead, so a rename in the Task Type master
silently disables the rule. `ticketForm.test.ts` has a test that spells out that
failure mode. Move it onto a flag when one exists.
