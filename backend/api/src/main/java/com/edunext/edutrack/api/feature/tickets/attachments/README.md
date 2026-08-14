# feature/tickets/attachments

**Owner: Stream C · Divyansh** — C-025, blueprint §4B.4.

The security half of attachments: extension allow-list **and** MIME sniffing, EXIF
stripped, AV scan before the file becomes visible, `tickets/{id}/{uuid}` keys,
short-lived signed URLs, never a public bucket.

## What is here

| Class | What it does |
|---|---|
| `AttachmentType` | §4B.4's allow-list, keyed by format family. Extension ↔ family index, and the content type each pair is stored and served as |
| `AttachmentSniffer` | What the bytes are. Signatures, then ZIP/OLE2 containers opened, then the text residual. **Never sees a file name** |
| `AttachmentTypePolicy` | Reconciles the two opinions. Disagreement is 415 |
| `ImageMetadataStripper` | Drops EXIF/XMP/IPTC from JPEG, PNG and WebP at the container level — no re-encode |
| `AttachmentStorageKey` | `tickets/{ticket_id}/{uuid}`, minted and parsed. The user's filename never enters it |
| `AttachmentStorage` / `S3AttachmentStorage` | The narrow port, and MinIO/S3 behind it. No method can produce a public address |
| `AttachmentScanner` / `ClamAvScanner` | clamd over INSTREAM. Three verdicts, and no failure is ever CLEAN |
| `AttachmentScanTask` | The scan that runs after the response, on this feature's own bounded pool |
| `AttachmentService` | The pipeline, and the one place a signed URL can be issued |
| `AttachmentController` | `POST` and `GET /api/v1/tickets/{ticketId}/attachments` |
| `AttachmentProperties`, `AttachmentStorageProperties`, the two `…Config` classes | Knobs, beans, and the startup guard on `fail-open` |

**No contract change and no migration.** `uploadAttachment`, `listAttachments`,
`Attachment.scanStatus` and the nullable `downloadUrl` were already specified
exactly as §4B.4 describes, and `ticket_attachments` — `storage_key`,
`mime_type`, `scan_status` defaulting to `PENDING` — was created by Stream A's
baseline with C-025 named in its comments. This task is the code those two were
written for.

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

## What C-025 does *not* do

- **Thumbnails (C-026).** `thumbnail_key` stays null and the DTO renders
  `thumbnailUrl: null` rather than omitting it.
- **Delete (C-028).** No `DELETE` route. It needs a 15-minute window, an uploader
  check and a tombstone, and one that did none of that would be worse than none.
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
