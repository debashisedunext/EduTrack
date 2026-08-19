# S-31 Stage Queue / Team Inbox — C-062

> *"each team's own worklist: 'Waiting in QA', 'Waiting in Deployment', sorted by
> time-in-stage descending so the oldest queued item is always on top. This is
> the landing page for QA and Deployment resources, the way My Tasks is for
> Developers."* — blueprint line 1108

§17 item 12 states why it exists: *"QA and Deployment are queue-driven teams, not
assignment-driven ones. Without a shared 'waiting in QA' list, tickets stall
between the handoff and someone noticing."*

## 🔴 Open for Stream A — the scope rule this screen needs does not exist

**This is the one thing to read before touching the screen.**

`ScopeResolver` gives a Developer, QA or Deployment resource `assigned_to = me`
on **every** ticket read (§10.2). Under that rule this screen returns only what
the caller is already holding — "Waiting in QA" becomes My Tasks with a different
title, and the shared list §17 item 12 asks for cannot exist at all.

`StageQueueSubscriptionScope` (D-014) hit the same wall for the matching
WebSocket room, chose **project membership**, and deferred this decision here in
as many words: a subscriber "refetches `GET /stages/queue`, which applies
whatever scope C-062 gives it".

Two things follow that are **not** this stream's to decide:

1. **`GET /stages/queue` has no controller.** The contract declares it, the mock
   serves it, nothing on the server does.
2. **A project-scoped queue lists tickets whose detail page 404s.** If a QA
   resource can see a queued ticket but not open it, the screen is a list of
   dead links — so the queue's scope and `ScopedTickets`' scope have to be
   decided together. Two candidate rules are in the note on issue #3.

Until then the screen is built against the mock, which is DEPENDENCIES §6's
first rule and how every screen in this stream was built.

## Decisions

**The default queue is matched on the stage's `ownerRole`, never on the stage
code.** "Waiting in QA" is the blueprint's example, not its rule — §7.4's
designer lets a project call that stage `RELEASE`, and a screen keyed on the
literal `'QA'` would land its team nowhere the day somebody does. The fixture's
template calls it *QA / Testing*, which is why the heading reads that way.

**A role that owns no queue falls back to the first stage.** A PM or Admin owns
none, and an empty picker reads as a broken page rather than as "pick a team".

**The sort is the server's and is not re-applied here.** Sorting the rows client
side would order *this page* of a cursor-paginated list rather than the queue,
and would put the wrong ticket on top with complete confidence.

**`stage`, `projectId` and `unassignedOnly` live in the URL; the cursor does
not.** A filtered queue is a link somebody pastes into chat, and "look at the
Deployment queue on CRM" is a sentence people say daily — but a pasted link
should land on the top of the queue, not on whatever page the sender scrolled to.

**The breach cue is the server's `stageSlaBreached`, never recomputed.** The
server measures working minutes against the org calendar, holidays and leave; a
client recomputing from wall-clock would mark a Friday-evening handoff breached
by Saturday morning, which is the exact case CLAUDE.md's calendar rule names.

**Shown in the sidebar to every role, not gated to the two queue-driven ones.** A
Developer watching the QA queue is how they see their own handoff land — §16's
walkthrough — and `StageQueueSubscriptionScope` grants the matching room on
exactly that reasoning.

## Two fixture defects fixed on the way

`frontend/src/mocks/` is Stream D's, so these were fixed rather than flagged.

**The handler applied `scopedTickets`.** It read plausibly and returned, for a QA
resource, only their own work — so the screen showed the stall it exists to
prevent, and the bug and the fix were indistinguishable from the outside.

**Every ticket in a queue-driven stage was assigned.** The generator gives each
ticket to the first user whose role owns its stage, so QA's queue was three
tickets, all Anil's. A queue in which everything is already picked up has the
interesting case removed — and `unassignedOnly`, which is on this endpoint *and*
on `GET /tickets`, could not return a single row from either. Half the open
tickets in QA and Deployment are now unassigned, alternated over the queue rather
than over the loop index, because only a handful of the generated tickets land in
those two stages at all.

Narrowed to QA and Deployment deliberately: leaving Development or Triage tickets
unassigned would move numbers on My Tasks, the dashboards and the resource
reports for a reason that has nothing to do with them.
