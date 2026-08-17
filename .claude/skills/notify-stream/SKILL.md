---
name: notify-stream
description: Post a message to another EduTrack developer on their daily-brief GitHub issue — an unblock they can now pick up, a red develop, a hand-back from a failed integration gate, or a dependency you need. Invoke when asked to notify, tell, message or inform another stream or developer.
---

# Notify a stream

Each developer has one standing GitHub issue where `plan brief` posts their
daily brief. That is where a message reaches them next to the context it is
about.

| Stream | Developer | Issue | GitHub |
|---|---|---|---|
| A — Platform & Security | Shivendra | **#3** | `@shivendraedunext-18` |
| B — Masters & Clients | Ayush | **#4** | `@Ayushedunext` |
| C — Tickets & Ribbon | Divyansh | **#5** | `@Divyanshedunext` |
| D — Engines & Realtime | Debashis | **#6** | `@debashisedunext` |

Confirm before posting — the numbers come from `plan.config.json` and the issues
are recreated if deleted:

```bash
gh issue list --state open --json number,title
```

## Post it

```bash
gh issue comment <n> --body "$(cat <<'EOF'
...
EOF
)"
```

## What makes one of these worth reading

Four kinds of message actually need sending. Anything else is noise on an issue
people are meant to read every morning.

**An unblock.** Their task can start now, and they may not know.

> `B-022` is what four of my tasks wait on (D-029 → D-030 → D-037 → D-038, plus
> D-040), and it also gates Shivendra's A-065. Nothing of mine is in your way —
> all its predecessors are done. No pressure on timing, just so the size of it
> is visible.

**A hand-back from the gate.** Name the failing check, not "the gate failed".

> #114 came out of the batch on **Backend build & tests** —
> `PermissionMatrixTest` expects entries for the four `/projects` routes B-016
> added. The other three PRs in the batch merged. Your branch is unchanged and
> still open; re-mark it ready when the entries are in. They should come from
> blueprint §2 rather than from whatever satisfies the test.

**A broken `develop`.** State what is broken, what you fixed, and explicitly
what you did *not*.

> `develop` is red on `PermissionMatrixTest`. A-036 and B-016 merged 41 seconds
> apart without a gate run between them. I have fixed the one entry that was
> mine (`/chat/ticket-cards`); I have deliberately not guessed the four
> `/projects` entries, because the matrix is meant to be read off blueprint §2
> by the person who knows the routes.

**A dependency you need.** Say what you are blocked on and what you need
decided — not just that you are waiting.

> D-053's file share has nowhere to put the row: `ticket_attachments.ticket_id`
> is `NOT NULL`, so an attachment on a DM is unrepresentable. C-024/C-025 own
> upload, MinIO keys, MIME sniffing and AV scan, and I am not going to build a
> second pipeline — two answers to "is this file safe" is worse than waiting.
> Needs your call on the table shape.

## Tone and accuracy

- **Never report someone's task status from memory.** Run
  `.claude/skills/task-progress/status.py mine --stream B` first. Telling
  somebody they are blocking you when they are not is expensive to walk back.
- **Never assign work.** Streams own their backlogs. State the dependency and
  its size; the sequencing is theirs.
- **Do not guess at their code.** If a fix is in their paths, hand it back with
  the failing check named — that is the whole reason for the ownership map.
- One message per topic. Two topics in one comment means one gets missed.
- If it needs a decision rather than an FYI, end with the question.

## When it is not a notification

If the answer is "I should just fix this", and it is in your own paths, fix it
and let the PR carry the news. An issue comment about work already done is a
notification the reader has to do nothing with.
