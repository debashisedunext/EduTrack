# feature/tickets/attachments

**Owner: Stream C · Divyansh** — C-025 and C-026, blueprint §4B.4.

The security half of attachments: extension allow-list **and** MIME sniffing, EXIF
stripped, AV scan before the file becomes visible, `tickets/{id}/{uuid}` keys,
short-lived signed URLs, never a public bucket — plus C-026's thumbnails, which
are served on exactly those same terms.

## What is here

| Class | What it does |
|---|---|
| `AttachmentType` | §4B.4's allow-list, keyed by format family. Extension ↔ family index, and the content type each pair is stored and served as |
| `AttachmentSniffer` | What the bytes are. Signatures, then ZIP/OLE2 containers opened, then the text residual. **Never sees a file name** |
| `AttachmentTypePolicy` | Reconciles the two opinions. Disagreement is 415 |
| `ImageMetadataStripper` | Drops EXIF/XMP/IPTC from JPEG, PNG and WebP at the container level — no re-encode |
| `AttachmentStorageKey` | `tickets/{ticket_id}/{uuid}`, plus C-026's derived `-thumb`. The user's filename never enters it |
| `AttachmentStorage` / `S3AttachmentStorage` | The narrow port, and MinIO/S3 behind it. No method can produce a public address |
| `AttachmentScanner` / `ClamAvScanner` | clamd over INSTREAM. Three verdicts, and no failure is ever CLEAN |
| `AttachmentScanTask` | The scan that runs after the response, on this feature's own bounded pool |
| `ThumbnailGenerator` | **C-026.** The only image decoder in this feature. Bytes in, a small PNG out — or nothing |
| `ThumbnailTask` | **C-026.** Stores the reduction, after the CLEAN verdict and in its own transaction |
| `AttachmentService` | The pipeline, and the one place a signed URL can be issued |
| `AttachmentController` | `POST` and `GET /api/v1/tickets/{ticketId}/attachments` |
| `AttachmentProperties`, `AttachmentStorageProperties`, the two `…Config` classes | Knobs, beans, and the startup guard on `fail-open` |

**No contract change and no migration, for either task.** `uploadAttachment`,
`listAttachments`, `Attachment.scanStatus`, the nullable `downloadUrl` **and the
nullable `thumbnailUrl`** were all specified exactly as §4B.4 describes, and
`ticket_attachments` — `storage_key`, `mime_type`, `scan_status` defaulting to
`PENDING`, **`thumbnail_key`** — was created by Stream A's baseline with C-025
and C-026 named in its comments. These tasks are the code those two were written
for.

## The five decisions worth knowing

### 1. Neither check is the guard; the conjunction is

Each has a hole the other closes, and they are different holes.

- **Extension alone** accepts `payroll.pdf` that is a Windows executable. This is
  the failure §4B.4 names.
- **Sniffing alone** accepts an OLE2 Installer package as "a legacy Office
  document" — at the container level that is what it is — and accepts a `.docx`
  renamed to `.zip`, so the archive path smuggles a document past a policy
  written about documents.

The cost is that a PNG somebody saved as `.jpg` is refused with a message saying
so. That is deliberate: the alternative is silently renaming a user's file, and a
rule that sometimes rewrites the name it was given cannot be reasoned about
later.

### 2. The sniffer cannot see the file name, structurally

`sniff(byte[])` takes no other argument. A sniffer that *could* read the
extension would eventually be helped by it, and §4B.4's second opinion would stop
being independent. The client's declared `Content-Type` is not read either — it
is whatever the uploader wrote, and the point of reading the bytes is that they
are the one part of the request the client cannot restate.

**Not POI's `FileMagic`**, though it is on the classpath: it has no PNG, WebP or
MP4, and it answers `OLE2`/`OOXML` — the containers — without saying which
document is inside, which is the whole question. Using it would mean two sniffers,
and two sniffers disagree eventually. POI *is* used for the part it is better at:
`POIFSFileSystem` parses the OLE2 directory, which is a sector-allocation-table
walk nobody should reimplement.

### 3. EXIF is stripped by surgery, not by re-encoding

`ImageIO.read` then `ImageIO.write` would drop metadata as a side effect. It is
rejected because it is lossy for JPEG (re-encoding a screenshot of text visibly
softens it, and that file is the evidence on the ticket), because it rewrites 10
MB of pixels to delete a few hundred bytes, and — the real reason — because it
**runs a full image decoder over hostile input** on a surface all six roles can
reach. Walking a chunk table and copying byte ranges reads no pixel data, so a
malformed image cannot become worse than a rejected upload.

What is dropped: JPEG APP1–APP15 and COM (allow-listed the safe way round, so a
metadata block in an APPn nobody has thought about yet goes by default); PNG
`eXIf`/`tEXt`/`zTXt`/`iTXt`/`tIME`; WebP `EXIF`/`XMP `. What is kept: JFIF, and
**the ICC colour profile** — dropping it makes a UI screenshot render with
visibly wrong colours, which changes the evidence for no privacy gain, since a
colour profile describes a monitor and not a person.

Two WebP details that are easy to miss and both corrupt the file: the `VP8X`
flags byte still announcing chunks that are no longer there, and the RIFF size
field left describing the longer file. Both are rewritten; both have a test.

If a walk hits something it does not understand, **the input is returned
unchanged** — a half-rewritten array must never reach storage, and the AV scan
and the private bucket are still in front of it.

### 4. PENDING is the default, and no failure is ever CLEAN

`AttachmentScanner` has three outcomes and `UNKNOWN` is a first-class one rather
than an exception, precisely so a caller has to decide what to do about it — an
implementation that threw would invite a `catch` that logs and carries on, which
is how an outage becomes a silent policy change. Unreachable scanner, timeout,
unrecognised reply, **and no scanner configured at all** are all `UNKNOWN`, and
`UNKNOWN` leaves the row `PENDING` and the file unreadable.

The unconfigured case deliberately runs through the same path as an outage rather
than through a second, more permissive one nobody tests.

`edutrack.attachments.scan.fail-open` is the one exception and
`AttachmentScanConfig` **refuses to start** with it set outside `local` — the
same guard `RefreshTokenConfig` puts on `secure-cookie` and `TotpConfig` on the
placeholder key, and for the same reason: an outage would otherwise serve every
upload unscanned and nothing about the running product would look different.

### 5. "Never a public bucket" is enforced by an absence

`PutObjectRequest` sets **no ACL at all** — not `PRIVATE`, none — so the object
takes the bucket's default. Setting `private` explicitly would read as more
careful and be worse: it puts the ACL vocabulary in the file, and
`ObjectCannedACL.PRIVATE` is one word from `PUBLIC_READ` in a line that already
looks reasoned about.

An absence cannot be asserted by calling a method, so
`AttachmentStorageSecurityTest` reads the source of this package (comments
stripped) and fails the build on any reference to a canned ACL. The
`AttachmentStorage` interface is the other half: nothing on it returns a bucket
URL, so publishing an attachment would take a new method, in a diff, that
somebody sees.

Downloads carry the **sniffed** content type and `Content-Disposition:
attachment`, overridden on the presign rather than only stored on the object — so
a row edited in the database cannot change what the browser is told to do with
the bytes. That is what makes an uploaded `.txt` full of markup harmless even if
every check above it had failed.

## C-026 · thumbnails, and the decoder C-025 refused to run

### The refusal was right, and three things had to change before this was allowed

`ImageMetadataStripper` argues at length against `ImageIO.read`, and none of that
argument is retracted. EXIF stripping runs on the **request** thread over bytes
that have been sniffed and nothing more, on a surface all six roles reach — so it
walks a chunk table and copies byte ranges, and a malformed image cannot become
worse than a rejected upload.

A thumbnail cannot avoid decoding. So rather than arguing the risk away, the
three things that made a decoder unacceptable there are removed:

1. **Not on a request thread.** It runs on the existing scan pool, after the
   response has gone. A decoder that hangs costs one of two background threads,
   not the caller's connection.
2. **Not before the scan.** `ThumbnailTask` runs only on the CLEAN branch, so
   anything reaching the decoder has already passed the extension allow-list, the
   sniffer, the metadata strip *and* clamd.
3. **The bomb is checked before it is opened.** Dimensions come from the header
   via `ImageReader#getWidth`, which decodes nothing. A 10 MB PNG may legitimately
   declare 40,000 × 40,000 — 1.6 **billion** pixels and several gigabytes of heap
   — and §4B.4's file-size cap cannot see it, because the bomb is small until it
   is opened. Over `max-source-pixels`, nothing is allocated at all. Under it, the
   read is **subsampled**, so even a real 50 MP photograph never materialises at
   full size.

`ThumbnailGeneratorTest` covers all three; the bomb test carries a `@Timeout`
because a version that decoded first would hang rather than fail an assertion.

> **Not mutation-checked, deliberately.** Every other guard in this feature was
> verified by breaking it and watching a test go red. The pixel ceiling was not:
> the mutation *is* the attack, and reproducing a multi-gigabyte allocation on a
> 4-core laptop to prove it is stopped is not a trade worth making. The timeout
> is what stands in for it.

### After the verdict, and in its own transaction

The ordering is load-bearing and is pinned by
`theVerdictIsCommittedBeforeAThumbnailIsEvenAttempted`, which verifies **commit**
rather than merely `save` — a version that generated inside the scan's
transaction would still save first and would still pass a weaker assertion.

If the two shared a transaction, a decoder that threw on a truncated GIF would
roll a CLEAN verdict back to PENDING, and the file would be permanently
unreadable because its *preview* failed — intermittently, since it would depend
on the image. Moving the generation inside `resolve` turns exactly those two
tests red and nothing else; that was checked.

### PNG out, whatever went in

One output type, so nothing has to store what a thumbnail is: the presigner is
told `image/png` from a constant and there is no second MIME column to drift out
of step with the bytes. PNG rather than JPEG because §4B.4's driving case — and
C-024's — is a pasted screenshot, which is a picture of *text*, exactly what
JPEG's chroma subsampling smears. Alpha is kept only where the source had it,
since a 24-bit PNG is appreciably smaller than a 32-bit one.

### No thumbnail is an ordinary outcome, not a failure

`thumbnail_key` stays null, `thumbnailUrl` renders null, and the client falls
back to the full image. Five reasons, none of them an error:

- **not an image at all** — a PDF, a spreadsheet, a log, an mp4;
- **WebP** — it is on §4B.4's allow-list and the JDK ships no reader for it.
  Adding one means a native library on the server for a format that arrives
  rarely, so the client renders the original instead;
- **already small enough** — storing a reduction that is not a reduction doubles
  the object count for nothing;
- **undecodable** — truncated, malformed, or over the pixel ceiling;
- **switched off** — `edutrack.attachments.thumbnail.enabled`, an operator's
  escape hatch for a broken `ImageIO` rather than a feature flag.

The client rule that matters is `attachmentPreviewSource` in
`components/ui/attachments.ts`: **a null `thumbnailUrl` never means "not an
image"**, and a UI that treated it that way would show a file icon for a
perfectly good screenshot in four of those five cases.

### The key is derived, not minted

`tickets/{id}/{uuid}-thumb` — the original's key with one suffix, so there is one
random component per attachment rather than two. That makes `thumbnail()` total:
every attachment has a thumbnail key whether or not an object sits under it, so
nothing stores a second key to find the first and C-028's delete removes both
from the one column it already reads. It is idempotent, so no path can build
`-thumb-thumb`.

Holding the original's key hands you the thumbnail's, and that costs nothing: a
caller with the original key can already reach the larger file, and neither key
is an address on its own.

### Both signers ask the same question

`thumbnailUrlFor` goes through the **same** `isReadable` as `signedUrlFor`. A
thumbnail is the file on screen — it is the most tempting place in the codebase
to write "PENDING is probably fine", because it looks like a preview rather than
like the file. It also re-validates the stored `thumbnail_key` against the row's
own ticket, since a row edited to name another ticket's object would otherwise
have that object signed and served.

**No new route.** A `GET …/thumbnail` endpoint would have to re-derive the scope
check, the scan-status check and the expiry — three chances to get §4B.4 wrong,
for a redirect.

## Two traps that cost time here

**The scan must be queued after commit, not after `saveAndFlush`.** The upload
runs in a transaction, so a scan thread starting at the flush opens its own
transaction, cannot see the row, finds nothing to do and returns — leaving the
attachment `PENDING` for ever with no error anywhere. The race is won by the
scanner often enough to look intermittent. `AttachmentScanTask.submit` registers
a `TransactionSynchronization` for this.

**`@Transactional` on `scanNow` would never have applied.** The executor's lambda
calls it on `this`, not through the Spring proxy. Nothing would fail — the read
would open its own connection and the save would autocommit — right up to the
first time two writes needed to land together. The boundary is a
`TransactionTemplate` instead, where no call site can bypass it.

## What is still *not* done here

- **Delete (C-028).** No `DELETE` route. It needs a 15-minute window, an uploader
  check and a tombstone, and one that did none of that would be worse than none.
  When it lands it must delete **both** objects — `AttachmentStorageKey.thumbnail()`
  derives the second from the column it already reads, so this is one extra line
  and not a schema question.
- **Configurable limits (C-027).** §4B.4's three caps are enforced — a pipeline
  that stores an unbounded upload before asking how big it is has already lost —
  but from `edutrack.attachments.*`. C-027 is a settings source, not a rewrite.
- **The `ATTACHMENT_ADDED` history row (§4B.4 traceability).** There is no ticket
  history write service yet; it belongs with C-034's timeline, not here.
- **The PENDING sweeper.** If the process dies between the insert and the verdict
  the row stays `PENDING` for ever — a storage leak, not a safety one. The table
  already carries `ix_attachments_scan` and
  `TicketAttachmentRepository.findByScanStatus` for it, and the migration calls it
  "the AV worker's work queue". **That worker lives in `worker/`, which is Stream
  D's**, so it is flagged rather than written.
- **A backfill for attachments uploaded before C-026.** Thumbnails are built on
  the CLEAN branch of the scan, and an already-CLEAN row never takes that branch
  again — so every image attached before this landed keeps `thumbnail_key` null
  for ever. **Nothing is broken by that**: `attachmentPreviewSource` falls back to
  the full image, which is exactly the WebP path, so an old ticket's strip renders
  correctly and merely heavily. It is worth a one-off pass eventually and is not
  worth a migration now — the corpus is nine days old. `ThumbnailTask.generateFor`
  is already idempotent and re-entrant, so a backfill is a loop over
  `findByScanStatus("CLEAN")` and needs no new code here.

## Open for other streams

- ⚠ **Stream D — `TicketCreateRequest` still has no `attachmentIds`**, so a
  create-with-attachments is two round trips and is not atomic. C-023 flagged this
  from the client side; nothing here changes it.
- ⚠ **Stream D — the generated `uploadAttachment` still cannot reach this
  endpoint.** Orval emits `Content-Type: multipart/form-data` with no boundary,
  which Spring's `@RequestPart` rejects. C-023's note has the one-line fix in
  `api/http.ts` (D-003). Until it lands the frontend must keep using
  `features/tickets/attachments/uploadTicketAttachment.ts`.
- ⚠ **Stream A / the blueprint — §4B.4 still gives mp4 two size limits**, 50 MB in
  the type row and 10 MB in the limits row. The server enforces 10 MB, matching
  the contract and the client. If 50 MB for video is meant, the contract has to
  say so first.
- ⚠ **`edutrack.storage` now has a consumer, and PLAN.md §2.1 names two more** —
  avatars and import error reports. When the second one arrives,
  `AttachmentStorageProperties` and the S3 beans should move out of this package
  rather than be imported across a feature boundary. It is a rename, and doing it
  now would be speculative generality.
