# Attachments — §4B.4

**C-023 · Upload surfaces** and **C-024 · Clipboard paste.** The upload lifecycle every surface shares. The
control itself is `components/ui/attachment-picker.tsx`, because three other
streams consume that directory; this folder is the half that talks to the API.

| File | What it is |
|---|---|
| `useTicketAttachments.ts` | Upload/delete lifecycle, in two modes. The only thing a surface needs. |
| `uploadTicketAttachment.ts` | `POST …/attachments` by hand. ⚠ Exists because of a defect — see below. |

Shared, in `components/ui/`:

| File | What it is |
|---|---|
| `attachments.ts` | §4B.4's allow-list, limits, validation, formatting and the clipboard rules. Pure. |
| `attachment-picker.tsx` | Drop zone + file picker + paste + file list. Presentational; no API knowledge. |
| `use-attachment-paste.ts` | The `document` paste listener and its arbitration. C-024. |

---

## Decisions

### The picker does not upload

`components/ui/` is the shared library. No component in it imports from `@/api`,
and a picker that knew how to upload would pull the generated client into Stream
A's and Stream B's bundles the first time either used it. So the picker
validates and renders, `useTicketAttachments` fetches, and the seam between them
is `File[]` in and `AttachmentItem[]` out.

It also keeps the two genuinely different upload models out of a shared
component — see the next section.

### Two modes, because the create form has no ticket

**Immediate** (`ticketId` given) — ticket detail, quick update. The ticket
exists, so a file uploads the moment it is chosen and its server ID is available
by the time the form is submitted.

**Deferred** (`ticketId: null`) — the create form. There is nothing to attach to
until the 201 comes back, and **`TicketCreateRequest` carries no
`attachmentIds`**, so there is nothing to send even if the files were already
somewhere. Files stage locally; `flush(newTicketId)` uploads them afterwards.

`flush` returns `{ uploaded, failed }` rather than throwing. This is the sharp
edge of deferred mode: by the time it runs **the ticket is already created**, so
a failed upload must never surface as "the ticket was not created" — the user
would go looking for a ticket that exists. The create form reports the failed
names in a toast and navigates anyway, because the detail page it lands on is
itself an upload surface.

### Uploads are sequential, not `Promise.all`

The per-ticket 50 MB and 20-file caps can only be enforced against a settled
total. Twenty concurrent multipart requests arrive in an order the server cannot
reconcile, and the cap becomes advisory. One at a time is also what makes the
per-file failure in `flush` attributable to a file.

### The hook takes `existing` instead of fetching

Ticket detail already loads its attachments through the single aggregated
`GET /tickets/{id}/full` — C-019 fetches once on purpose, and a second request
for the same rows is exactly the waterfall that endpoint exists to remove. So
the surface passes what it already has and invalidates its own query through
`onUploaded`.

Rows this hook uploaded are kept separately and merged over `existing`, keyed by
ID, with the surface's copy winning. Without that, quick update — which holds a
`Ticket`, not the `/full` payload, and so passes no `existing` at all — would
watch every file vanish the instant it uploaded, which reads exactly like a
failure.

### Scan status is refreshed once, not polled

`POST` answers `201` with `scanStatus: PENDING`; §4B.4 says the file is not
visible until the scan passes, so the row renders as **Scanning** with no
thumbnail and no download. One follow-up refresh fires ~2s later. If the scan
outlasts it the row stays "Scanning", which is honest — there is no push channel
for scan completion, and inventing one is Stream D's WebSocket work.

### Validation runs across a whole drop, not per file

Twelve 5 MB files are each under both caps and the twelve of them are 10 MB over
the ticket's. `selectAttachments` carries running totals so the eleventh is
refused; validating each file against the original counts lets all twelve
through. A mixed drop accepts what it can and reports the rest, because dragging
a folder is normal and rejecting all seven files over one `.exe` is not.

The extension allow-list is the **client** gate and is not the security
boundary. §4B.4 requires sniffing as well, `File.type` is derived from the
extension on most platforms and is empty for `.log` on all of them, and a `.doc`
is an OLE container that proves nothing. Real sniffing, the AV scan and EXIF
stripping are **C-025**, server-side. Everything here exists to fail fast and say
why.

### Clipboard paste — C-024

**Every pasted screenshot arrives named `image.png`.** Chrome, Edge and Firefox
all do it; Safari has used `Image (1).png`; some paths supply no name at all. So
the duplicate check — which is right about everything else — refuses the *second*
capture a user pastes, and paste appears to work once and then break. Pasted
files whose names are the browser's invention are renamed
`screenshot-2026-08-12-143005.png` from the local clock.

**A clipboard name must never cause a rejection**, which is why naming lives in
the picker rather than in the paste listener: only the picker knows what is
already attached. The OS clipboard holds one image, so "attach several
screenshots" is necessarily *paste, paste, paste* — and a user doing that moves
faster than a one-second stamp. Two different captures inside the same second
collide, and the collision was refused by the duplicate check as though the same
thing had been pasted twice: the second screenshot vanished behind "already
attached", which reads as paste working once and then breaking.

So a clipboard name steps around what is taken — `screenshot-…-2.png`, `-3`, and
so on, case-insensitively, accumulating across a batch as well as against the
ticket. The cost is that a genuine double `Ctrl`+`V` now attaches the same image
twice rather than being refused, and that is the right side to err on: a
duplicate is visible in the list and one click to remove, while a silently
dropped screenshot is invisible and indistinguishable from a broken feature.
Files with real names — a drop, a browse, a copy out of a file manager — are
untouched and still get the duplicate check, where a repeated name means
something.

Several images in one paste is handled by the same mechanism rather than a
special case. It is not hypothetical: a multi-select copied out of a file
manager, or a run of spreadsheet cells, arrives as one event carrying several
bitmaps, all of them called `image.png`.

The rename allocates a new `File` over the same blob rather than redefining
`name` on the existing one. `FormData` reads a file's name from an internal slot
in Chrome, not from the JS property, so a shadowed `name` would upload under the
old one — the rename would appear to work everywhere except where it matters.
The cost of that is a trap in tests: a fixture with a redefined `size` does not
survive reconstruction, because jsdom computes size from content. Both
`attachment-picker.test.tsx` and this note exist so the next person does not lose
an hour to a size cap that silently measures one byte.

**The listener is on `document`.** A paste fires at whatever has focus, and
nothing ever focuses a drop zone — the agent is typing in the description or has
clicked nowhere at all. A listener on the picker's own subtree would catch almost
no real paste.

That makes it global, and `use-attachment-paste.ts` answers the two questions
global listeners raise:

- **Which picker gets it.** Registrations are a stack and only the top handles
  the event. Ticket detail mounts a picker and quick update mounts a second over
  it; both listening means one paste uploads the file twice, against two
  different requests. Most-recently-mounted is the one in front for a slide-over,
  a modal or a dialog over a page.
- **When to keep its hands off.** A rich-text editor is skipped entirely —
  `RichTextEditor` intercepts paste itself and routes image files through
  `onPasteFiles` (C-066), and the event bubbles to `document` afterwards, so
  handling it in both places attaches every screenshot twice. An ordinary text
  paste into an `input` or `textarea` is skipped too: copying an image out of a
  web page puts the `<img>` tag on the clipboard as `text/html` beside the file,
  so a file being present is not evidence the user meant to attach it. An
  image-only clipboard **is** taken even from a text field, because pasting a
  screenshot into a plain input otherwise does nothing at all.

A paste is validated by the same `accept` as a drop, so no route reaches `onAdd`
having skipped the caps. The editor's files get there through the picker's
`addFiles` ref handle for exactly that reason — `useTicketAttachments.add`
uploads whatever it is handed, and validation lives in the picker. C-029's
comment box takes the same pair.

And it is **announced**: `Pasted screenshot-….png` in the live region. A drop and
a browse both have a visible action behind them; `Ctrl`+`V` has none, so silence
reads as "the paste was ignored". The region is now mounted whenever the control
is live rather than appearing with its first message — several screen readers
only watch regions that were already in the tree. It stays absent while
disabled, so a sealed cycle still does not land a second `role="status"` beside
its banner.

---

## Open, and for whom

### ⚠ 🔴 Stream D — the generated `uploadAttachment` cannot work against a real server

Orval emits `headers: { 'Content-Type': 'multipart/form-data' }` on the generated
call (`api/generated/attachments/attachments.ts`), and `api/http.ts` spreads
caller headers **after** the branch that deliberately omits a content type for a
`FormData` body:

```ts
...(isForm ? {} : …),
...headers,          // ← the generated header wins
```

A multipart body is unparseable without the `boundary` parameter, and the
boundary is generated by the browser only when it is left to set the header
itself. Stream D's own `api/http.test.ts` already asserts the intended
behaviour — *"does not set Content-Type on FormData — the browser must add the
boundary"* — against a mocked fetch, so the intent is documented and the
generated header silently defeats it. A real Spring `@RequestPart` returns 400 or
500 for **every** upload.

The fix is one line in `api/http.ts`, which is **Stream D's file** (D-003, stated
in its own header): drop an incoming `Content-Type` when the body is `FormData`.
`api/generated/` cannot be the fix — it is generated.

`uploadTicketAttachment.ts` sends the same request without the header and is
correct either way. **Delete it in favour of `useUploadAttachment` once
`http.ts` is fixed.** It imports the body and response types from the generated
code, so a contract change still reaches it.

### ⚠ Stream D / shared test infrastructure — multipart cannot be exercised under vitest

Under vitest's jsdom environment `FormData`, `Blob` and `File` are **jsdom's**
while `fetch` and `Request` are **Node's**. Node refuses to serialise a foreign
`FormData` and stringifies it, so every upload leaves as the literal
`[object FormData]` under `Content-Type: text/plain`, and any handler calling
`request.formData()` dies inside undici with *"Content-Type was not one of
multipart/form-data…"* — pointing at the request builder when nothing is wrong
with it.

It is not bridgeable from a test file: undici brand-checks its own `Blob`/`File`
and rejects both `node:buffer`'s and the one off `Response.blob()`. The fix
belongs in `test/setup.ts`, which already carries
`makeAbortSignalsCrossRealmSafe` for the identical class of problem — jsdom and
Node disagreeing about a realm — and which is shared infrastructure this stream
does not own.

Consequence: `useTicketAttachments.test.tsx` overrides the upload handler and
mirrors its contract without parsing the body, and
`uploadTicketAttachment.test.ts` pins the header behaviour against a mocked
fetch. **The multipart round trip itself is unverified in CI** and needs a real
browser. Stream B's import wizard (`/imports/{schema}/upload`) has the same
problem waiting for it.

### ⚠ Stream D — `contracts/openapi.yaml`: two gaps

1. **`TicketCreateRequest` has no `attachmentIds`.** Deferred mode exists to
   work around it, and works, but it means a create-with-attachments is two
   round trips and is not atomic — a ticket can exist with its evidence missing.
   `QuickUpdateRequest`, `HandoffRequest` and `CommentWriteRequest` all carry the
   field; the create request is the odd one out.
2. **No settings endpoint for the limits.** §4B.4 calls 10 MB / 50 MB / 20 files
   *"all configurable in system settings"*. There is no contract constant to
   import the way length bounds are imported, so the defaults live in
   `components/ui/attachments.ts` and the picker takes overrides as a prop.

### ⚠ Stream D — the mock is looser than the contract

`mocks/handlers/tickets.ts` enforces the 10 MB per-file cap and nothing else: no
415 path, no per-ticket 50 MB or 20-file cap, and `commentId` is accepted and
ignored. `commentDto` also hard-codes `attachments: []`, so a comment's files
will not round-trip when C-029 arrives. The client enforces all of it, so the
gap is invisible under `npm run dev` — until a real server enforces something the
UI has never seen a rejection for.

Also: `attachmentDto` builds `/mock-files/{id}/{name}` URLs that **no handler
serves**. Nothing renders them today (a thumbnail is only shown once the scan
passes, and the mock's rows are `PENDING` until they are `CLEAN`), but C-026's
gallery and lightbox will 404 on every image until either a handler exists or the
mapper returns a data URI.

### ⚠ Blueprint — mp4 has two different size limits

§4B.4's type row allows *"video (mp4, up to 50 MB)"* while its limits row says
*"10 MB per file"*, and the contract and mock both enforce a flat 10 MB. The
client mirrors the API at 10 MB: allowing 50 MB would let a user watch a 40 MB
upload run and then be refused by the server, which is worse than refusing it
instantly. Same reasoning C-010 used in the other direction for the 200-vs-300
character title. **If 50 MB for video is meant, the contract and the server have
to say so first.**

---

## Not in this task

- **MIME sniffing, AV, EXIF, S3 keys, signed URLs — C-025.** Server-side.
- **Thumbnails, gallery, lightbox — C-026.** The picker shows a 28px preview only
  once the scan has passed.
- **The 15-minute delete window and the tombstone — C-028.** `onRemove` is
  optional and a surface that does not pass it renders no remove control at all,
  so nothing offers an affordance the API will refuse.
- **The Attachments tab — C-060.** The detail page's strip is the upload surface
  and the at-a-glance list; the grouped, filterable gallery is that task.
- **The comment box (C-029) and the handoff dialog (C-052)** are two of §4B.4's
  six surfaces and **neither screen exists yet**. Both already carry
  `attachmentIds` on the wire, and the picker's `compact` variant was built for
  §4B.5's inline `[📎]`. They adopt it unchanged.
- **Inbound email**, §4B.4's sixth surface, is Stream D's.
