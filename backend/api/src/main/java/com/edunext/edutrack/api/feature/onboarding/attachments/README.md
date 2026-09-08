# `feature/onboarding/attachments` — B-107

The module's shared upload plumbing over `ob_attachments` (A-102). **No routes
live here.** Each owner arm's route surface belongs to the feature that owns the
owner: OB-05's client documents are `feature/onboarding/clients`
(`ObClientAttachmentController`), C-121's step uploads and B-116's sign-off
evidence will be theirs.

## What is here

| Class | Does |
|---|---|
| `ObAttachmentPipeline` | Store, sign, tombstone. The one place `isReadable` is decided |
| `ObAttachmentScanTask` | The AV verdict, sealing `scan_status` and `scanned_at` |
| `ObAttachmentStorageKey` | `onboarding/{owner}/{ownerId}/{uuid}`, anchored and parsed |
| `ObAttachmentOwner` | The four owner arms A-102's CHECK enumerates |
| `ObAttachmentDtos` | `ObAttachment` on the wire — public, because four packages render it |
| `ObAttachmentTooLargeException` | 413 |

## The safety decision is not made here

What a file may be, what is stripped from it and whether it is scanned are
C-025's beans, reached through `api/upload/UploadPipeline`. That indirection is
not decoration — see that interface's own javadoc: A-115 forbids this package
from naming `feature/tickets`, and D-053 forbids a second answer to "is this
file safe". The port is what satisfies both, and the durable fix (moving the
shared beans out of `feature/tickets/attachments`, as B-036 already did for the
S3 clients) is named there and is Stream C's to make.

What **is** written here is row plumbing — an `ob_attachments` row has none of a
ticket attachment's columns — and the storage namespace, which is disjoint from
`tickets/` and `chat/` by construction.

## Who may is not this package's question

`ObAttachmentPipeline.store` takes a resolved owner and asks nothing about the
caller. Each arm scopes differently: a client document by `ObClientScope`, a step
upload by the journey resolver, a sign-off's evidence by a token on a public
page. A pipeline that decided all three would hold every scope rule in the module
and be the one place a mistake reaches all of them.

The corollary is a rule for callers: **run your scope check first.** An
unauthorised caller must not be able to spend the server's CPU sniffing a 10 MB
file for a client that is not theirs.

## Three things a new owner arm must get right

- **Mint the key with your own arm.** `belongsTo` refuses a well-formed key of
  the wrong arm, so a row pointed at another owner's object costs its download
  URL rather than serving it.
- **Never restate the readable rule.** `signedUrlFor` is the only method that can
  turn a stored file into something readable. A second restatement is where
  "PENDING is probably fine" gets written.
- **Pick your `kind` from the arms that make sense for you, and refuse the
  others.** The CHECK constrains which owner *column* is set and says nothing
  about which kinds go with which owner — `ObClientAttachmentService.kindOf`
  draws that line for the client arm and each arm has to draw its own.

## What B-107 did not build, and why

- **No migration.** `ob_attachments` and its client arm are A-102's, and
  `ObAttachment` (C-106) already maps every column.
- **No thumbnails.** C-026's reduction is a ticket-gallery feature; OB-05 lists
  documents by name. Chat made the same call.
- **No per-owner size or count cap.** §4B.4's 50 MB and 20-file caps are per
  *ticket*; the onboarding plan publishes no equivalent for a client, and
  inventing one would refuse a legitimate twenty-first document on nobody's
  authority. Plan §11's portal upload caps are decided with CP-04, and
  `ObAttachmentPipeline.store` is the method that will enforce them.
- **No re-upload detection.** `content_sha256` is written on every row because
  the column exists to be written, but nothing acts on it: refuse, return the
  existing row, or store both is a product decision no screen has asked for.
