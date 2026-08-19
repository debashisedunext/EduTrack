---
name: explain-code
description: Explain a piece of EduTrack — a feature package, a class, an endpoint, or why the code does something surprising — grounded in what the repository already records rather than in a reading of the syntax. Invoke when asked how something works, where something lives, why a decision was made, what a class is for, or when onboarding someone onto a stream.
---

# Explain a piece of EduTrack

The instinct is to read the code and paraphrase it. Resist it. **This
repository already explains itself**, in four places most people never open,
and a paraphrase of the syntax is both slower to produce and worse than what is
already written down.

Someone asking "what does `TicketJournal` do" can read the method names
themselves. What they cannot get from the file is *why the tail is read with a
locking read*, and that is in the javadoc, and the answer took a production
incident to learn.

## Gather first

```bash
.claude/skills/explain-code/map.sh feature <name>     # where it lives, both ends, + its README
.claude/skills/explain-code/map.sh why <path>         # the file's own reasoning + its decisions
.claude/skills/explain-code/map.sh route <fragment>   # controller, DTOs, contract entry
.claude/skills/explain-code/map.sh concept            # the four things newcomers get wrong
```

## The four places the answer usually already is

**1. The class javadoc.** Unusually load-bearing here. `TicketHistoryRepository`
spends thirty lines on why `PESSIMISTIC_WRITE` is not optional; `LoginRequest`
explains why there is deliberately no complexity rule on the way *in*;
`application.yml` explains why the comment edit window is empty. When a comment
says **DELIBERATELY**, **NOT**, or carries a 🔴, that is a decision somebody had
to defend — quote it rather than restating it in weaker words.

**2. The feature's `README.md`.** Every backend feature package has one naming
its owner and its screens. `feature/fixtures/README.md` is the model: what each
class does, and three decisions worth reading before touching it.

**3. The commit body.** Subjects are terse; the reasoning is underneath. `git log
-8 -- <file>` on almost any file in this repo returns paragraphs, not one-liners.
`git log -S'<symbol>'` finds when a thing appeared and what it replaced.

**4. The backlog entry.** `docs/streams/STREAM-*.md` carries per-task
verification notes — often a dozen sub-bullets recording what was tried, what
broke, and what was deliberately left alone. For "why is it built this way", that
is frequently the only place the answer exists.

## Where behaviour is decided, when the sources disagree

| Question | Authority |
|---|---|
| What should it do? | `docs/Ticketing-System-Blueprint.md` |
| How is it built? | `docs/PLAN.md` |
| Why does it differ from the blueprint? | **PLAN.md §4** — every deviation is listed, none are silent |
| What shape is the API? | `contracts/openapi.yaml`, plus the DTO's Bean Validation — those annotations **are** the schema (PLAN.md §2.2, D-4) |
| Who owns this file? | TEAM-PLAN.md §6 |

**The blueprint recommends NestJS and PostgreSQL. We do not use either.** Its
DDL, BullMQ, Socket.IO and SheetJS guidance does not apply as written. Explaining
a blueprint passage as though it describes this system is the most common way to
mislead somebody confidently.

## Four things to get right, because newcomers reliably get them wrong

Run `map.sh concept` for the full text. In short:

- **`iteration_no` and `cycle_no` are two independent counters.** Iteration rises
  when a ticket moves *backwards* inside a cycle; cycle rises when a *closed*
  ticket is reopened. Blueprint §4A. If an explanation of the ribbon does not
  make this distinction, it is wrong.
- **Stage and status are separate layers.** A ticket can be *In Progress* in the
  *QA* stage.
- **Append-only means four layers**, not one: no service method, no route, MySQL
  grants, DB triggers. A correction is a new compensating row.
- **Scope is server-side and returns 404, not 403.** The 404 is the point — a 403
  would confirm the row exists.

## Traps when reading this codebase

- **Feature packaging, not layer packaging.** There is no `controllers/`. If you
  are looking for where tickets are served, it is `api/feature/tickets/`, holding
  its own controller, service, repository and DTOs.
- **`{ticketId}` in a path is the ticket *code*** — `CRM-26-00347` — not the
  numeric row id. Several controllers took `long` here and 400'd against every
  real client. If you are explaining an endpoint, check which it takes.
- **`frontend/src/api/generated/` is generated.** Never explain it as authored
  code, and never suggest editing it.
- **`target/` and `node_modules/` are build output.** A file found there is a
  copy; cite the source.
- **52 of 75 data-access classes are hand-written `JdbcClient` SQL, not JPA.**
  Do not assume Hibernate is in the path — check.
- **A test may not test what its name says.** If asked whether something is
  covered, `/mutation-check` settles it; reading the test does not.

## Explaining

Lead with what the thing is *for*, then the one design decision that makes it
non-obvious, then the mechanics. Most questions are really the middle one.

- **Cite, do not paraphrase.** `file:line` for anything load-bearing. If the
  javadoc says it better, quote it — the author was defending a decision, and
  compression loses the defence.
- **Separate what the code does from what it is supposed to do.** When they
  differ you have found a bug, and that is the more valuable answer. Say which
  one you are describing.
- **Say when you do not know.** "The javadoc does not say and the history is
  silent" is a real finding — it usually means the decision was never made
  deliberately, which is worth knowing before someone relies on it.
- **Do not invent a rationale.** If a choice looks arbitrary and nothing records
  otherwise, it may well be arbitrary. Inventing a reason makes it permanent,
  because the next reader quotes you.
