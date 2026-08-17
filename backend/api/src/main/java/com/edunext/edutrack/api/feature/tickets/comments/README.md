# Comments — blueprint §4B.5

The ticket's conversational record. Distinct from chat (D-047), which is
ephemeral discussion: this is what a person reads two years later to find out
what happened.

**C-029 built the box and the thread.** C-030–C-034 layer the rest onto it.

## What is here

| File | |
|---|---|
| `CommentController` | `GET` and `POST /tickets/{ticketId}/comments` |
| `CommentService` | scope → sanitise → default → stamp, in that order |
| `CommentSanitizer` | **PLAN.md §3.9 on the write path** — the first server-side sanitiser in the codebase |
| `CommentRows` | the one paged read `TicketCommentRepository` cannot serve |
| `CommentUserRefs` | author ids → `UserRef` + `RoleCode`, one query per listing |
| `CommentDtos` | the contract's `Comment`, field for field |
| `InvalidCommentException` / `CommentExceptionHandler` | the three 400s, field-keyed |

No migration. `ticket_comments` has existed since the A-006 baseline, whose
header names C-029–C-034 as the tasks it was waiting for.

## The three things worth knowing before changing anything here

**1. Sanitisation is the deliverable, not the toolbar.** §3.9 is normative and
says it happens "on the server, on write, always — the only sanitiser an
attacker cannot skip is the one on the write path". `components/ui/rich-text.ts`
is the client's copy and §3.9 itself calls it advice. `CommentSanitizerTest` is
written adversarially rather than as a happy path, and two of its cases exist
because the first draft of `CommentSanitizer` was wrong:

- jsoup's `data:` protocol check matches the prefix and stops, so a Safelist
  permitting `data` permits `data:text/html` — which is a document, not an
  image. `stripNonImageData` checks the media type §3.9 actually specifies.
- **A Safelist that permits a tag permits it with none of its attributes.**
  `addAttributes("a", "href")` means *href is allowed on a*, not *a requires
  href* — so refusing a `javascript:` protocol strips the attribute and leaves
  a bare `<a>` standing. `dropStrandedElements` clears up after it.

**2. Internal is the default and it is not negotiable at this layer.** §4B.5's
table says the default "follows whether the ticket is client-raised"; the
contract, the column default, the mock and §16 all say internal-always, and
PLAN.md §5 records it as an accepted deviation. The boxed `Boolean` on the
request is what makes an omitted field, an older client, an email importer and
a fixture all get the safe answer. Four tests pin it.

**3. The stamp is written here even though C-032 displays it.** Cycle and stage
are copied onto every comment at write time because a stamp is the one field
that cannot be backfilled — once the ticket moves on, what stage it was in when
a comment was written is gone. `iterationNo` stays **null**: it lives on the
open `ticket_stage_transitions` row and nothing can read one until C-042.
Writing `1` would be worse than nothing, because a real first iteration is also
`1` and C-032 would have no way to find the rows needing repair.

## Deliberately absent

- **`PATCH` and `DELETE`** — C-033's. Not stubbed, not routed. A client calling
  one today gets Spring's 404, which is the honest answer for a verb this
  server does not serve. C-028 is the cautionary tale: the contract promised
  `deleteAttachment` from D-001, no server implemented it, and the picker's ×
  worked against the mock and did nothing in production for three tasks.
- **Comment attachments** — `attachmentIds` is **refused with a 400**, not
  accepted and ignored. §4B.5 does allow them and
  `ticket_attachments.comment_id` exists, but rendering them means minting
  signed download URLs, which lives behind `AttachmentService` in the
  neighbouring package; widening that class's surface is its own change with
  its own review.
## C-030 · `@mentions`

`CommentMentionParser` → `CommentMentions` → `CommentMentionNotifier`, called
from `CommentService.create` after the row is saved.

- **The server parses; the request never says who it mentioned.** D-052 settled
  this for chat and the argument is unchanged: a caller-supplied recipient list
  is a notification-and-email fan-out anybody can aim at anybody, with no
  membership check in front of it. **This changes what C-029 did** —
  `CommentWriteRequest.mentionUserIds` was stored verbatim, which was harmless
  while nothing read the column and is not harmless now. The field stays on the
  request because the contract declares it and older clients send it; it is
  accepted and has no effect.
- **Project membership is the check.** A handle resolves only against active
  members of the ticket's project (`project_members`), which is the same table
  `ScopeResolver` scopes PM and Support by — so a resolved mention is always
  somebody who can open the ticket the notification links to. Anything else
  stays plain text, and "no such user" and "not on this project" are
  deliberately the same answer.
- **The notification quotes nothing that was said.** C-033 gives an author five
  minutes to edit and leaves a tombstone on delete; a bell entry carrying the
  body would be the one copy a correction cannot reach. §4B.6's own example
  subject carries no preview either.
- **Email is sent, where D-052 deliberately sent none.** That task's stated
  blocker was that the preference matrix making mention mail optional did not
  exist yet — *"an unsuppressible optional mail is worse than a late one"*.
  D-042 has since landed and the suppression is inside `OutboxEnqueuer.enqueue`,
  along with D-035's rate limit, so the condition is met. See the note to
  Stream D below.
- **No new endpoint and no contract change.** `GET /users?projectId=&q=` already
  is the type-ahead's data source — the contract says so in as many words, and
  `ResourceRepository` filters it through the same `project_members` predicate
  this resolver uses.

## Two things for other streams

⚠ **Stream A — one shared build file, one shared test file.** `backend/pom.xml`
and `backend/api/pom.xml` gain **jsoup**, for §3.9's write-path sanitiser; there
was no HTML sanitiser on the backend classpath at all. `PermissionMatrix.java`
gains two rows, as the Definition of Done requires.

⚠ **Stream A / C-034 — `TicketDetailDtos.Comment` does not match the contract.**
`TicketDetailService` projects seven fields (`authorId`, `bodyHtml`,
`isInternal`) where the contract's `Comment` declares fifteen (`author`, `body`,
`isClientVisible` — the inverse). The generated TypeScript types
`TicketDetailResponse.comments` as `Comment[]`, so anything reading it gets
`undefined` for every field it asks for. **C-029 does not touch it** — that
projection is C-019's and the drift predates this task — and the frontend reads
the thread from `GET /comments` instead, which it wants anyway because a thread
is unbounded and `/full` has no way to page. **C-034 cannot avoid it**:
interleaving comments into the History tab means reading them from somewhere.

⚠ **Stream D — C-030 enqueues a `MENTIONED` mail, which D-052 deliberately did
not.** Not a disagreement: D-052's reason was that D-042's preference matrix did
not exist, and it now does — `OutboxEnqueuer.enqueue` applies both the
preference (D-042/D-036) and the rate limit (D-035), so this class has nothing
to remember and a user who has switched mention mail off gets none. **D-037
still owns §4B.6's fifteen events as a set.** If D-037 wires `MENTIONED` from a
shared producer, `CommentMentionNotifier`'s `mail.enqueue` call is the one to
delete rather than to keep alongside it. Nothing in `feature/chat/` was touched;
`CommentMentionParser` is a deliberate twin of `MentionParser` rather than an
import, and the two are meant to stay in step.

⚠ **Stream D — `frontend/src/mocks/` is yours, and the POST handler now
refuses two things it used to accept**: a non-empty `attachmentIds`, and a body
that sanitises to nothing. Both mirror the server. The handler also stores the
*sanitised* body rather than the raw one, so what `npm run dev` shows is what
the database would hold.

⚠ **PLAN.md §3.9 asks for `MEDIUMTEXT` and the baseline created `TEXT`.**
Stream A's file, not corrected here. It matters because sanitising can make a
body *longer* — 20 000 `&` characters pass Bean Validation and leave the
sanitiser as 100 000 — against a 65 535-byte column, which truncates mid-entity
and stores markup that will never parse again. `CommentService` re-applies the
20 000 bound to the **sanitised** value, which closes it from this side; the
column is still worth widening.

---

# C-033 · the edit window and the tombstone

Blueprint §4B.5's immutability row: *"editable for 5 minutes; after that the
comment is locked and shows an 'edited' marker with the original preserved.
Deletion leaves a tombstone. No role, including Admin, can silently rewrite a
comment."*

**No migration and no new route.** `edited_at`, `original_body`, `is_deleted`,
`deleted_by` and `deleted_at` have all been on `ticket_comments` since the A-006
baseline, whose header names C-029–C-034 as the tasks it was waiting for, and
`editComment` / `deleteComment` have been in `openapi.yaml` since D-001 with
nothing serving them — the same state C-028 found for `deleteAttachment` and
named a defect, since the generated client has always exported both.

## The asymmetry, which is the whole task

Editing and deleting look like one permission and are two.

| | who | window | leaves a mark |
|---|---|---|---|
| **Edit** | the author, and **nobody else** | five minutes | "edited", original preserved |
| **Delete** | the author, a PM or an Admin | **none** | always a tombstone |

**No role widens the edit, Admin included.** The obvious reading of §4B.5 is that
an Admin edit is fine if it is marked — and the marker cannot carry the fact that
matters. However the card is labelled, the thread still attributes the words to
their original author, so "Ravi said X" becoming "Ravi said Y" over an Admin's
signature is a rewrite of Ravi. The remedies that do work are both open: reply,
or delete and leave a tombstone naming who removed it.

**Deletion widens to PM and Admin**, following C-028 and for its reason: a comment
posted client-visible by mistake is a disclosure, and waiting for its author to
come back from leave is not a remedy.

**Deletion has no window at all**, which is where this deliberately departs from
C-028. That task derives whether a tombstone is *visible* from the fifteen
minutes and the actor, because §4B.4 spells out both cases. §4B.5 attaches its
five minutes to editing and says of deletion only that it leaves a tombstone — so
every removal here is recorded, including an author's own ten seconds after
posting. The stricter rule is also the better fit: a mis-pasted screenshot is a
mistake, a sentence is a statement colleagues have already read.

## Three guards, each verified by breaking it

1. **`original_body` is written on the first edit only.** A second edit
   overwriting it leaves the *first revision* standing as the "original" and
   loses what the author actually posted — and it looks entirely fine, because
   the field is populated and the marker is showing.
   `preservesTheFirstOriginalNotTheLast` is the only test that goes red.

2. **A tombstone clears `original_body` as well as `body_html`.** This is the
   trap the task set out to close and the one worth reading twice: a comment
   edited *before* it was deleted keeps its first wording in the field designed
   to preserve wording — usually the exact text the author was taking back.
   Cleared on the write **and** again in `CommentDto.of`, so a row written by a
   fixture or by hand cannot leak either.

3. **An edit re-notifies only somebody the previous wording did not already
   name.** Editing re-parses the body, so notifying everyone it names makes
   post-edit-edit a way to ring the same bell indefinitely. The case that
   actually reaches production is duller — somebody fixing a typo in a sentence
   containing a name — and D-035's rate limit catches neither.

## Statuses

`422` for a refused edit and `403` for a refused delete, which is not an
inconsistency. An edit is refused by the **row** — the window closed, and the
caller was entitled four minutes ago — and 422 is the status for a well-formed
request that cannot be applied to the resource as it stands. A deletion is
refused by **who is asking**, with no clock involved at all, which is what 403
means. The contract has typed the edit's 422 since D-001; the delete's 403 is
added here.

Neither weakens A-035. Row scope is settled by `ScopedTickets` before this
package sees the request, and `CommentRows.find` keys on **both** ids so a
comment on another ticket is 404 from the same line as one that does not exist.
By the time either refusal is reachable the caller is looking at the comment in a
thread they just fetched.

## The thread read now returns tombstones, and C-029's filter is gone

`CommentRows.page` filtered `isDeleted = false`. That was a reasonable default
while nothing could delete, and it had to go: a tombstone that is never returned
is not a tombstone, it is a hard delete, and C-034's timeline cannot place a
comment the thread read refuses to hand it. The domain repository's
`findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc` still filters and is
deliberately left alone — it feeds `/full`'s projection, which is Stream A's and
already drifted from the contract.

## For other streams

⚠ **Stream D — `contracts/openapi.yaml`, additive.** `Comment.deletedBy` and
`Comment.deletedAt`, mirroring `Attachment`'s pair exactly, plus a `403` on
`deleteComment` and a longer description on both verbs. Without the two fields a
tombstone can only read "comment removed", which is the record §4B.5 asked for
minus the fact that matters. Client regenerated; the diff is those three files
and nothing else. Same flagged-sign-off precedent C-011, C-012 and C-015 set.

⚠ **Stream D — `frontend/src/mocks/` is yours, and both handlers were looser
than the server they stand in for.** The PATCH found its row by **comment id
alone**, so `/tickets/{one-I-can-see}/comments/{one-I-cannot}` edited a comment
on a ticket the caller has no scope for; it never sanitised, so the mock stored
markup the real server strips. The DELETE had **no permission check of any
kind** — anybody could tombstone anybody's comment — plus the same cross-ticket
hole and no tombstone fields. Both now mirror `CommentService`, the 403
included: a client that has only ever seen 204 has no reason to handle a
refusal, and the first person to meet one would have been a user.

⚠ **Stream A — `application.yml` gains `edutrack.comments.edit-window`.** A
property and not a settings row, for the reason C-028 gave about its own fifteen
minutes: §4B.4's "all configurable in system settings" is a sentence about the
attachment *limits*. This is a retention rule, and an operator able to raise it
to a year could quietly reopen every comment on the product. `PermissionMatrix`
gains two rows, as the Definition of Done requires.

**The client holds no copy of the five minutes.** `Comment.editableUntil` is
derived server-side from that property and the countdown renders it, so
shortening the window cannot leave an author watching a timer the server has
already stopped honouring — the C-027 failure, where a client-side copy of a
server cap silently *overrode* it with nothing in any log.

⚠ **Still open, deliberately.** The `COMMENT_EDITED` and `COMMENT_DELETED`
history rows belong with C-034's timeline, alongside the `ATTACHMENT_ADDED` row
C-025 left for the same reason: there is still no ticket-history write service.
`iteration_no` on a comment stays null until C-042 (C-032's dependency), and
`PLAN.md §3.9`'s `MEDIUMTEXT` is still `TEXT`.
