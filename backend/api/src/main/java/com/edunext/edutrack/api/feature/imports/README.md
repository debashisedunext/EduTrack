# feature/imports

**Owner: Stream B · Ayush**

The Excel import engine — screen S-34, blueprint §4B.3.

## What is here

| Task | Files |
|---|---|
| **B-030** Import engine as a schema registry | everything below |
| **B-031** Step 1 — template download | `ImportTemplateWriter`, `ImportController`, `ImportExceptionHandler` |
| **B-032** Step 2 — upload and parse | `SheetReader` + `XlsxSheetReader`/`CsvSheetReader`, `SheetHeadings`, `ImportFileParser`, `ImportUploadService`, `ImportUploadLimits`, `StagedRow` |
| **B-033** Step 3 — column mapping | `ImportDtos.SchemaField(s)`, `ImportMappingPresetService`, `ImportMappingPresetRepository`, `UnknownImportFieldException`, `MappingPresetNotFoundException` |
| **B-034** Step 4 — dry-run preview | `ImportValidationService`, `ImportDtos.{ValidateRequest,RowVerdict,Preview}`, `ImportUploadNotAvailableException`, `IncompleteMappingException`, `UnknownSourceColumnException` |
| **B-035** Step 5 — commit as a background job | `ImportRequestResolver`, `ImportCommitService`, `ImportCommitRunner`, `ImportCommitConfig`, `ImportBatchService`, `ImportBatchController`, `ImportDtos.{CommitRequest,Batch}`, `NothingToCommitException`, `RejectedRowsPresentException`, `ImportCommitQueueFullException`, `ImportBatchNotFoundException` |
| **B-036** Error report generation | `ImportErrorReportWriter`, `ImportErrorReportService`, `ImportReportStore` + `S3ImportReportStore` + `ImportReportStoreConfig`, `ImportErrorReportUnavailableException` |

## The one thing to understand

Blueprint §4B.3 ends with *"build it once, register two schemas"*, and CLAUDE.md
restates it as a stop rule: **if you find yourself writing a second import flow,
stop.** This package is that rule made structural.

Everything that differs between importing clients and importing resources is
behind `ImportSchemaDefinition`. Everything else — parsing, mapping, validating,
previewing, committing, error reporting — is written once and mentions neither.

```
                    ImportSchemaRegistry
                     resolve("clients")
                             │
                             ▼
                  ImportSchemaDefinition          ← the only entity-aware surface
                   key / entityCode
                   fields / naturalKey
                   findExisting  (read-only; keys → current values)
                   upsert        (commit only)
                             ▲
              ┌──────────────┴──────────────┐
       ClientImportSchema            (B-038: UserImportSchema)
         .schemas/                       .schemas/

  engine, knows nothing about either:
       ImportField · ImportFieldType · FieldValidator(s)
       HeaderMatcher → ImportMapping → ImportRow
       ImportValidationEngine → ImportPreview → ImportRowVerdict
       ImportStagingStore ← StagedUpload
```

Adding an importable entity is **adding one `@Component`**. No route, no
migration, no edit to the registry — Spring collects implementations by type.
That is what reduces B-038 from a feature to a file.

## Two invariants worth stating plainly

**The dry run cannot write.** `ImportValidationEngine` holds no repository, no
`EntityManager` and no path to `upsert()`. Blueprint §4B.3 makes step 4's
guarantee absolute ("nothing is written yet"), and a guarantee that depends on
nobody adding a convenient `save()` later is not one. `ImportEngineIsolationTest`
fails the build if that dependency appears.

**Upsert on the natural key, never insert.** A user who fixes six rejected rows
and re-uploads the file must not end up with two copies of the other 494.
`ClientImportUpsertIT` runs exactly that scenario against a real MySQL.

## Where the steps land

B-030 is the engine, not the wizard. The five steps plug into it:

| Task | Step | Uses |
|---|---|---|
| B-031 | template download | `ImportField` — `header`, `allowedValues` (the dropdown), `example` |
| B-032 | upload, SAX parse | produces `StagedUpload` — ✅ done, though it kept `InMemoryImportStagingStore`; see below |
| B-033 | column mapping | `HeaderMatcher` → `ImportMapping`; `missingRequired` blocks Next — ✅ done, and it turned out to need **no mapping route at all**; see below |
| B-034 | dry-run preview | `ImportValidationEngine` → `ImportPreview` — ✅ done, and it widened `findExisting`; see below |
| B-035 | commit job | `ImportPreview.writable()` → `ImportSchemaDefinition.upsert` — ✅ done, and it moved step 4's refusals into a shared resolver rather than copying them; see below |
| B-036 | error report | `ImportRowVerdict` — the rejected rows plus their reason — done, and it is written *during* the run because that is the last moment they exist; see below |
| B-037 | batch traceability | `importBatchId`, stamped on insert only |
| B-038 | resource import | one new `@Component` under `.schemas` |

One declaration feeds several of those. `allowedValues` is both the template's
data-validation dropdown and the dry run's `ENUM` check, so **the template cannot
offer a value the import rejects** — held as two lists they drift the first time
somebody adds a support plan.

## Decisions taken in B-030 that later tasks inherit

**`import_batches.status` follows the contract.** The column carried a private
`PENDING|VALIDATING|COMMITTING|DONE` that no client could observe, while
`ImportBatchResponse.status` promised `QUEUED|RUNNING|COMPLETED|FAILED`. The
contract won; `V20260810_2010__import_batch_status_vocabulary.sql` adds a `CHECK`
so they cannot diverge again. Nothing precedes `QUEUED` because the dry run
writes nothing — the first row appears at commit.

**`batchId` is `int64`, not a UUID.** The contract described it as a UUID;
`import_batches.id` is a `BIGINT AUTO_INCREMENT`, like every other identifier
here. The contract was corrected and the client regenerated.

**Account manager and SLA policy are not template columns.** Both are foreign
keys and a spreadsheet carries only a name. A column that resolves when the
spelling happens to match, and silently leaves null when it does not, is worse
than no column — the user sees 412 clients created with no hint that 90 have no
account manager. Assigned on S-33 after import.

**`InMemoryImportStagingStore` is a stopgap, and single-instance.** PLAN.md §2.2
puts import artefacts in MinIO/S3 and no object-storage client is wired into this
backend yet. Two API pods would stage on one and validate on the other. The seam
is named so that replacement is a class, not a refactor.

> B-030 said B-032 would replace it, and **B-032 did not.** Object storage is a
> feature of its own — an S3 client, a bucket, a lifecycle policy, credentials in
> four environments — and folding it into "upload and parse" would have made a
> step-2 task about infrastructure. What B-032 did instead was avoid making the
> store carry more: the sheet selector re-posts the file rather than asking the
> server to keep the original bytes, so staging still holds one sheet's extracted
> text and nothing else. The single-instance limitation is unchanged and still
> the strongest argument for doing it — B-036's error report needs somewhere to
> put a generated file anyway, which is the natural task to introduce the client.
>
> **B-036 introduced the client and still did not replace this.** C-025 had
> already wired an `S3Client` for attachments by then, so the error report needed
> no new infrastructure at all - it needed one bean and a key prefix. Staging is a
> different problem and unchanged: an upload has a thirty-minute TTL, a
> twenty-slot ceiling and a sheet selector that re-posts rather than re-reads, and
> moving it to object storage is a lifecycle policy and a cleanup path rather than
> a `put`. **The single-instance limitation stands and is still the strongest
> argument for doing it** - two API pods would stage on one and validate on the
> other. It is now a smaller job than it was, because the client is there.

## B-031 · the first endpoint, and what it fixed in place

`GET /imports/{schema}/template` is the first route in this package, so
`ImportController` and the `@RestControllerAdvice` this file said would "arrive
with the first endpoint" both land here. Steps 2 to 5 add handlers to that same
controller.

**The header row is exactly `ImportField#header()`, undecorated.** Marking the
required columns with an asterisk was the obvious first draft and it breaks step
3: `HeaderMatcher` matches on that text, so the file this product handed the user
would fail to auto-match when they upload it back, and every column would land in
B-033's manual override dropdown. Required-ness went to a second **Instructions**
sheet instead, which also carries the two rules a user cannot guess — that the
natural key updates rather than duplicating, and that a blank cell does not clear
a stored value. `ImportTemplateWriterTest` pins the round trip (template →
`HeaderMatcher` → nothing missing) rather than pinning the strings.

**A dropdown Excel cannot carry is a failure, not a degradation.** The explicit-
list constraint stops at 255 characters. Writing the sheet without that column's
dropdown would leave it looking like free text, so the user types their own
spelling and the refusal arrives at step 4 pointing at a cell the template never
constrained. The writer throws instead, naming the field and what it needs (a
hidden lookup sheet and a formula constraint). No registration is near the limit
today.

**Every cell is text-formatted.** Left to Excel's general format, `00123` is
stored as 123 and a postal code loses its leading zero — and the row is then
rejected at step 4 for a value the user never typed.

**`master.write`, and the counter-argument is on the record.** The template
carries no organisation data at all, so a wider rule would leak nothing. It is
Admin's anyway because the file's only use is a screen §7.4 puts inside the
Admin-only master data module, and a route whose permission is looser than its
screen is how a screen acquires a second entrance. The 403 is registered in
`check-conventions.py`'s `ROWLESS_403`: a blank template is not a row.

## B-032 · step 2, and the four decisions inside it

`POST /imports/{schema}/upload` reads one sheet of one file and stages it.
Nothing is written: `ImportUploadService` holds no repository, and a user who
abandons the wizard here leaves a staged copy that expires and no other trace.

**The reader is the event API, and nothing is buffered whole.** `XSSFReader` plus
`XSSFSheetXMLHandler`, streaming — PLAN.md §2.2's ban on the DOM reader is about
concurrency, not about any one file, and a bulk-import screen produces exactly
the load it is about. The wanted sheet is parsed *from the iterator's own
stream* rather than read into an array first: XML compresses by an order of
magnitude, so a `.xlsx` well under the 5 MB limit can hold gigabytes of sheet
XML, and buffering it would turn a file that passed every declared check into an
out-of-memory error. **The row cap throws from inside the content handler**, so a
million-row sheet is refused after 5,001 rows rather than after all of them.
There is a third cap the blueprint does not name — columns — because the other
two do not bound the work: 16,384 columns × 5,000 rows is eighty million cells
out of a small file, and the row cap is only reached after each row is built.

**`.xls` is refused, and that is a stated deviation from §4B.3, which lists it.**
The task line says "event-driven SAX parse", and SAX is an XML API: an `.xls` is
a binary OLE container of BIFF records with no XML in it at all, so the only
reader for it is `HSSFWorkbook` — the whole-workbook reader this step exists to
avoid. The refusal is a 415 that names the fix (Save As `.xlsx`), the picker on
S-34 does not offer it, and adding it properly is one `HSSFEventFactory`
implementation of `SheetReader` and nothing else.

**Dates come back ISO whatever the cell is formatted as.** A date typed into
Excel is formatted `dd/MM/yyyy` across most of the world; left alone it reaches
step 4 as `01/04/2026` and `FieldValidators.isoDate()` rejects every row of the
file — over a value the user never typed, in cells that look perfectly correct in
their own spreadsheet. `XlsxSheetReader.IsoDateFormatter` intercepts the date
format only; a number stays a number.

**Rows carry their source row number** — `StagedRow`, new in B-032. Blank rows
are dropped, so position in the list stopped being the row in the sheet, and step
4 quotes that number back to somebody who is going to go and look at it. Excel
leaves trailing blanks constantly and a deleted bad row leaves a gap; either one
would have shifted every number below it.

**Duplicate headings are suffixed, not dropped** — `Email`, `Email (2)`. Rows are
keyed by heading, so two columns with one name means one of them silently
disappears before the user ever sees the mapping screen.

### The sheet selector re-posts the file

§4B.3 asks for a selector when a workbook has several sheets. The response lists
them all and names the one that was read; choosing another re-posts the same file
with `?sheet=`. The alternative — a "re-read what you staged" route — needs the
original bytes of every open upload held for the staging TTL, on a store whose
own javadoc calls itself a stopgap, to save re-sending a file the browser is
still holding. `?replaces=` releases the superseded staging slot, so browsing a
four-sheet workbook does not consume four of the twenty.

### The staging ceiling became reachable

`InMemoryImportStagingStore` has refused past its twenty-upload cap since B-030,
with a message written for a person to read — and nothing called `stage()` until
B-032, so that refusal had never left the process. It threw a bare
`IllegalStateException`, which over HTTP is a 500 and a stack trace for a
condition that is temporary and the caller's to wait out. It is now
`ImportStagingFullException` and answers **503 with `Retry-After`**. Given a type
of its own rather than caught as `IllegalStateException` in the advice: one line
less, and every genuine bug on this path would have become a cheerful "try again
shortly".

### One seam worth knowing about

§4B.3's 5 MB limit is enforced here and answers a problem document naming it. A
file over `spring.servlet.multipart.max-file-size` — 10 MB, set for §4B.4's
attachments and shared by every upload — never reaches the handler: the container
refuses it while resolving the multipart body, before a handler has been chosen,
so a `MaxUploadSizeExceededException` handler on `ImportExceptionHandler` could
not fire. One was written, found unreachable, and removed rather than left in
looking like coverage. Spring's own problem-details handler (A-020) answers those
instead, with the framework's wording rather than ours.

## B-033 · step 3, and the route it did *not* add

**There is no endpoint for the mapping, and that is the finding rather than an
omission.** The mapping is chosen in the browser out of what step 2 returned, and
it travels with step 4's `/validate` body and step 5's `/commit` body — which is
what the contract has encoded since B-030. Parking a copy server-side would give
the wizard a fifth piece of state to keep in step with the other four, and the
dry run needs the mapping in its own request regardless, because the user can go
back a step and change it.

What step 3 genuinely could not get anywhere else is *our* half of the mapping,
and that is what the new routes are:

| Route | Why it has to exist |
|---|---|
| `GET /{schema}/fields` | the field names, their headings, and **`required`** — which decides whether Next is blocked and is derivable from nothing step 2 returns |
| `GET /{schema}/mapping-presets` | §4B.3's "presets can be saved and reused for the next import" |
| `POST /{schema}/mapping-presets` | an upsert on `(schema, name)` — **200, not 201** |
| `DELETE /{schema}/mapping-presets/{presetId}` | a preset is a convenience with nothing referencing it |

**`/fields` exists so the field list stays declared once.** The alternative was a
copy of `ImportField`'s declarations in TypeScript — a second declaration of the
schema, which is exactly what this package's design exists to prevent, and which
B-038's resource import would have had to copy a third time. It is a *projection*
of `ImportField` rather than the record serialised: `validators()` is a list of
lambdas with no wire representation, and serialising the definition whole is how
an internal type becomes a public contract by accident.

It is deliberately **not** folded into the upload response, though that would have
saved a route. `/fields` is a property of the schema and the upload response is a
property of the file; the sheet selector re-posts the upload, and a caller that
wanted to say what the import accepts before a file had been chosen could not.

### Presets are org-wide rows, not browser storage

`import_mapping_presets` (`V20260817_1130`), and the migration carries the
argument at length. In short: §4B.3 says "reused for the *next* import", which is
next month, quite possibly from a reimaged laptop and by a different Admin. A
preset records how another system's export is shaped — organisation knowledge, not
a display preference. `created_by` is recorded and is on no key; nothing filters
on it, and an unidentifiable caller saves a preset with no attribution rather than
being refused.

**Save is an upsert, and the unique key is what makes it one.** Saving under a
name that exists replaces it, because that is what Save means to somebody who has
just corrected one column — the alternative is five presets called *CRM export* in
a picker that cannot tell them apart. Case-insensitive, through the table's own
collation, for the same reason.

**A mapping naming an undeclared field is refused, 422, and not trimmed.** This is
the decision most worth keeping. A preset is applied weeks after it is saved,
against a file nobody is looking at today; dropping the unknown key silently
leaves the preset *looking* complete in the picker, applying cleanly, and the
column it was meant to map simply not imported — which surfaces as four hundred
clients created with no support email and nothing on screen having said so. The
`unknownFields` and `fields` lists both go on the problem body, because the
realistic cause is a preset built against an older registration rather than a typo.

**The source column is not checked, and cannot be.** A preset exists to be applied
to a *different* file, so "does this column exist" has no answer at save time. That
question is asked when the preset is applied, on the client, against the headings
of the file actually in hand — `columnMapping.applyPreset` drops what it cannot
place and the screen says which entries those were.

### No `@Transactional` on the preset service, on purpose

It was written with it. `list` and `delete` are one statement each; `save` is two —
the upsert, then a read of the row it settled on — and the race a transaction looks
like it closes (another Admin re-saving that name in between) is not closed by one:
under `READ COMMITTED` the other session's commit is visible to the second
statement either way, and both statements address the row for `(schema_key, name)`
so the id is the same regardless. What the annotation *did* do was open a JPA
`EntityManager` per call, which put a live database between the routes and any test
of them. Removed; four route tests gained, nothing lost.

The read-back is by `(schema_key, name)` rather than by `LAST_INSERT_ID()`, which
reports 0 for an update that changed nothing — so re-saving a preset whose mapping
was already correct would have answered with a preset id of zero.

### This is not a hole in the isolation rule

`ImportEngineIsolationTest` bans persistence from `ImportValidationEngine` and
business entities from the whole package. `ImportMappingPresetRepository` violates
neither: a preset is *the engine's own* record, like `import_batches`, and it names
no client, user, project or ticket. Nothing on the validate path can reach it,
because nothing on the validate path holds one.

## B-034 · step 4, and the probe that had to grow

`POST /imports/{schema}/validate` takes a staged upload and a mapping and answers
a verdict per row. `ImportValidationService` is thin by construction — the engine
has decided the verdicts since B-030, and what was missing was a way to reach it
and the refusals that keep a caller from reaching it with a request whose answer
would be useless.

### `findExisting` now returns the values, not just the keys

Blueprint §4B.3's step-4 table is explicit about the Message column for an
update:

```
│  3   │ NORTHWIND │ ♻ Will update │ Name, phone │
```

It names **which fields would change**, and existence alone cannot answer that.
So `ImportSchemaDefinition.findExisting` went from `Set<String>` to
`Map<String, Map<String, String>>` — the same one batched query, now carrying
each matched row's current values keyed by this schema's own field names.
Existence is `containsKey`.

Without it, "38 will update" is a verdict nobody can act on: a spreadsheet that
corrects six phone numbers and one that overwrites every address in the account
produce the same word, and the user is being asked to approve one of them.

The comparison itself stays in the engine and stays schema-agnostic. Three rules
in it are worth knowing:

- **Only fields the row carries are compared**, because those are the only fields
  a commit writes — `upsert` leaves a stored value alone where the cell is blank
  or the column unmapped. Reporting the rest would promise an erasure that will
  not happen, which is the most alarming thing this message could get wrong.
- **The natural key is excluded.** It is how the row was matched, so it is equal
  by construction — except in case, where the collation matched `acme` to `ACME`
  and the upsert leaves the stored spelling alone.
- **An empty map is permitted** and means "it exists, I cannot cheaply say what
  is in it". The verdict is still `WILL_UPDATE`; the reason is null, and a client
  must render that as a stated unknown rather than as "nothing changes". That
  keeps the cost of a registration where B-030 put it.

`ClientImportSchema.currentValues` is deliberately written directly alongside
`upsert`, one `put` per `set`. A column added to one and not the other is a field
the preview silently never reports as changing, and adjacency is what makes that
visible in review.

The cost is that the probe returns entities rather than one column for the
matched subset — bounded by the 5,000-row cap and the same order of memory as the
staged sheet already in heap, read inside a read-only transaction, with only
strings escaping it.

### Four refusals, split by remedy rather than by cause

All four are 422 and all four mean "the request refers to something absent", so
one type would be defensible on the status. It would also make the screen parse
English to decide between the only three things it can usefully offer.

| `type` | Cause | What the screen says |
|---|---|---|
| `import-upload-unavailable` | the `uploadId` expired, or `sheet` disagrees with what is staged | go back to step 2 |
| `import-incomplete-mapping` | a required column has no column mapped to it | fix the mapping above |
| `import-unknown-column` | the mapping reads a heading this sheet does not have | fix the mapping above |
| `import-unknown-field` | the mapping names a field the schema does not declare | *B-033's own type, reused* |

**Expired and wrong-sheet are one condition on purpose.** They read as two and
the remedy for both is the same action; two types would let the screen write two
sentences that both end "upload the file again".

**An incomplete mapping is refused rather than previewed.** Running it produces a
screen of rejections reading "Name required" — which points at the rows when the
fault is one dropdown on the previous step, and sends the user looking through a
spreadsheet at a column that is filled in. With the *natural key* unmapped it is
worse than noise: nothing can be matched, so the preview would promise to create
clients that already exist.

**An unknown source column is refused rather than read as blank.** `ImportMapping`
reads the cell by heading, so a heading the sheet lacks silently yields nothing
and the field is absent — the commit runs and the Support Email column the user
carefully mapped was never read. The realistic cause is a preset applied to a
renamed export, which is why the sheet's own headings go on the problem body.

### The upload survives the dry run

Nothing is discarded. Reading the preview, going back to step 3 and changing one
column is the ordinary path through this screen, and a route that consumed the
staging entry would answer "your file expired" to the second attempt.

## B-035 · step 5, and the thing it deliberately does not trust

`POST /imports/{schema}/commit` takes the same upload and mapping step 4 took,
answers **202 with the batch**, and writes on a pool thread.
`GET /import-batches/{batchId}` is the progress.

### The preview is re-derived, never accepted

The commit body carries **no verdicts**, and could have. That would have been a
mistake of the kind that is invisible until it is exploited: the rows written
would be whatever the caller said they were, and step 4's guarantee would be a
convention the browser observes rather than a property of the system. Re-running
the dry run costs one pass over rows already in heap and one existence probe;
the same file and mapping reach the same judgements, so the set the user
approved and the set that gets written are the same by construction.

### The four refusals moved out rather than being copied

`ImportRequestResolver` now holds them, and both `ImportValidationService` and
`ImportCommitService` call it. The alternative was a second copy in the commit
path — which agrees on the day it is written, and where being wrong writes to
the client master. The order and the reasons are B-034's, unchanged.

Two refusals are this route's own, and they are two rather than one because the
remedies are opposite:

| `type` | Cause | What the screen says |
|---|---|---|
| `import-nothing-to-commit` | no row is writable | go back to the spreadsheet |
| `import-rejected-rows-present` | `skipRejected: false` over a file with rejections | import the valid rows only |
| `import-commit-queue-full` | every commit slot taken — 503 with `Retry-After` | try again in a moment |

**A run that would write nothing is refused, not completed instantly.**
`import_batches` is B-037's audit trail, and a row saying a file was imported on
Tuesday when nothing was is a false entry in the record that exists to make bad
imports traceable. It is also the wrong answer to the person: they pressed
Import on a screen that had just told them nothing was importable, and a green
"done" confirms the press rather than the outcome.

**`skipRejected: false` means all-or-nothing, not "write them anyway."** A row
the engine rejected has no valid value to write, so the permissive reading is
not an operation this feature can perform.

### Staging is released before the response, not after the run

The job outlives the thirty-minute staging TTL, so it holds its own immutable
list of rows and never looks the upload up again. Nothing it needs can expire
underneath it, and the slot is freed for the next admin rather than held for the
length of a run.

The visible consequence is that committing the same `uploadId` twice answers
`import-upload-unavailable` — which is the honest refusal for the request that
would otherwise have written the file twice, and is why the route needs no
`Idempotency-Key` handling despite declaring the parameter.

### One row, one transaction

`upsert` is `@Transactional` on the registration and is called from a pool
thread with no ambient transaction, so each row commits on its own. Three
reasons, and the first is the one that matters:

- **A bad row costs one row.** A file of five hundred where row 314 breaks a
  constraint no validator declares — a column widened in the master since the
  registration was written — must not lose the other 499. It is counted rejected
  and the walk continues, which is why `COMPLETED` is not a claim that nothing
  failed.
- **Progress is real.** A run inside one transaction is invisible to every
  reader until it ends, so the poll would return zeros and then jump.
- No connection is held for minutes.

The cost is that an interrupted run leaves half the file imported, and that is
correct here rather than a compromise: the operation is an **upsert on the
natural key**, so re-running the same file finishes the job instead of
duplicating what landed. Partial application being safe is the property the
whole feature is built on.

Counters flush every 50 rows, not per row — otherwise every write to the master
carries a second write to `import_batches`, to make a bar polled every two
seconds accurate to a row nobody can read.

### A private pool, and saturation is a refusal

`ImportCommitConfig`, following `AttachmentScanConfig`'s argument against
`@Async`: a shared executor's bound is whatever somebody else tuned it to.

It diverges on one thing. The attachment scanner saturates to `CallerRunsPolicy`
and is right to — a scan is seconds. A commit is not. Caller-runs here holds an
HTTP connection open for up to five thousand upserts, and the response it is
holding up is the one that tells the browser which batch to poll. So the queue
aborts, the batch is marked `FAILED` rather than deleted (a refused attempt that
left no trace is indistinguishable from an attempt nobody made), and the caller
gets a 503 that says when to come back.

### `processed` is derived

`created + updated + rejected`. There is no `processed_rows` column and there
should not be — a fourth number holding the sum of three is a fourth number that
can disagree with them. It reaches `total` exactly when the run is over, which
is the property the progress bar is built on.

## B-036 - the error report, and why it is written mid-run

`GET /import-batches/{batchId}/error-report` hands back the rows the run did not
write, as an `.xlsx` with a Reason column appended. Section 4B.3's closing
promise: the user fixes those rows and re-uploads **only** them.

### There is no moment after the run when it could be generated

This is the constraint everything else follows from, and it is B-035's doing
rather than a limitation discovered here. The staging entry is released *before*
the job starts, and the preview is re-derived rather than stored - so once
`ImportCommitRunner` has walked its list, nothing anywhere holds the rejected
rows. "Generate it on download" was never an option to weigh; the data is gone.

So `ImportCommitService` keeps the non-writable verdicts instead of counting them
(`unwritten`, which replaced an `int` whose value is now `unwritten.size()`), and
the runner generates the report at the end of the walk.

### It is written *before* the status turns terminal, never after

A client stops polling the instant it reads `COMPLETED`. A key stamped in a later
transaction is a report the screen that wanted it has already given up asking
for - the button would stay disabled on a run that has one.

So the report key travels on `ImportBatchService.finish` rather than in a second
call after it: one write settles the status, the counters and the report
together. `ImportDtos.Batch.etag` gained `errorReportUrl` for the same reason - a
validator that does not cover every field of the representation is a `304` that
withholds a change.

### Every row the run did not write, not only the rejected ones

Three things end up in it, and they interleave in the sheet, so they are sorted
back into file order:

| Source | Reason column |
|---|---|
| the dry run's rejections | the engine's own sentence, verbatim |
| in-file duplicates | `Row 2 wins` - nothing is wrong with the row's content |
| rows the database refused at write time | a sentence naming the import, **never the JDBC message** |

All three are rows the user's file contained and the client master did not
receive, which is the only distinction that matters to somebody fixing a
spreadsheet. The write-time failure's reason is deliberately not the exception's
message: that string carries a constraint name, a table name and sometimes the
SQL, none of it useful to the reader and all of it internal detail on its way
into a file that gets emailed around. The log line has the cause; the batch id in
the cell connects the two.

**One gap, stated because it is real.** A file where *every* row is rejected is
refused at `/commit` with `import-nothing-to-commit`, so there is no batch and no
report - the user's account of those rows is the step-4 preview on screen. That
follows from B-035's refusal being right (a batch claiming to have imported a
file it never touched is a false entry in B-037's audit trail) and is worth
knowing before somebody reports it as a bug.

### The report is in the *template's* shape, not the upload's

The columns are `ImportField#header()` in template order, plus a leading `Row`
and a trailing `Reason`. Not the headings of the file the user sent.

That is what makes "fix and re-upload just those rows" literal: `HeaderMatcher`
matches every column of this file on the way back in, so the corrected rows need
no remapping and *cannot* be remapped wrongly. A report echoing the user's own
headings would look more faithful and would land them back on step 3 with a
mapping to rebuild - the step this file exists to save them.

The cost is honest: **columns the user did not map are not in the report**,
because they were never read. `Row` bridges it - it names the row in the sheet
they still have. `ImportErrorReportWriterTest` pins the round trip (report ->
`HeaderMatcher` -> nothing missing) and that `Row` and `Reason` normalise onto no
declared field, rather than pinning the strings.

### Storage, and the file it moved out of Stream C's package

PLAN.md 2.2 puts import error reports in MinIO/S3, and
`import_batches.error_report_key` has been waiting for one since the baseline.
The client existed - C-025 built it for section 4B.4's attachments - and
`AttachmentStorageProperties` said in writing what should happen when a second
consumer arrived:

> PLAN.md lists three eventual users of the bucket - attachments, avatars and
> import error reports - so **when the second one arrives this record should move
> out of this package** rather than being imported across a feature boundary.

B-036 is the second one, so the move is taken:
`api/storage/ObjectStorageProperties` and `api/storage/ObjectStorageConfig` now
hold the `S3Client` and `S3Presigner`, and `AttachmentStorageConfig` keeps only
the bean that is actually about attachments. **This touches
`feature/tickets/attachments/` - Stream C's directory.** The alternative was a
second `S3Client` declared here, which does not work: two beans of one type make
C's own by-type injection ambiguous, so adding an import feature would have
broken attachments at context startup.

**`errorReportUrl` is a route, not a signed URL**, which is the other place this
diverges from attachments. Section 4B.4 hands out short-lived presigned URLs and
is right to for a 50 MB video served repeatedly. This is a small file read once
and it is a verbatim extract of the client master - a signed URL is a bearer
credential that outlives the screen that minted it, in a browser history, a chat
paste and a proxy log. Proxying it costs a few hundred kilobytes and buys
`master.write` checked at the moment the bytes are read. `ImportReportStore` has
no method that can produce a public address at all, which is the same structural
argument `AttachmentStorage`'s javadoc makes about its own interface.

The value is **relative to the API base** - `/import-batches/412/error-report`,
no `/api/v1`. The file needs an `Authorization` header so it cannot be a plain
link; a client composes it onto its own base either way, and a root-relative
`/api/v1/...` would work in the ordinary deployment and point at the wrong origin
the moment the API is served from another one.

### A report that cannot be stored costs a report, never an import

`ImportErrorReportService.generate` swallows a storage failure and answers null.
By the time it runs the client master has already been written; failing the run
would mark a batch `FAILED` that wrote four hundred clients correctly, and would
tell the user their import broke when what broke was a convenience attached to
it. Logged at `warn`, because a bucket refusing writes is an operational fact
somebody has to see.

The step-5 screen was already in the right shape for this - B-035 left the button
visible and disabled - so a run with no report says so in a sentence instead of
promising a feature.

## Not here yet

**`S3ImportReportStore` is the one class in this package no test exercises.**
There is no MinIO Testcontainer in this project - C-025's attachment tests mock
the storage for the same reason - so `ImportErrorReportControllerTest` and
`ClientImportCommitIT` both substitute `InMemoryImportReportStore` and prove
everything on either side of it. The class is nine lines of AWS SDK calls.
Flagged rather than left looking covered; a MinIO container would cover this and
`S3AttachmentStorage` together, which is the right way round to do it.

`/imports/users/*` answers 404 on all six routes until B-038 registers the second
schema, and `ImportTemplateControllerTest`, `ImportSchemaFieldsControllerTest`,
`ImportMappingPresetControllerTest`, `ImportValidateControllerTest` and
`ImportCommitControllerTest` each assert that — so the day the registration
lands, five tests fail and are deleted.

## Tests

| File | Proves |
|---|---|
| `ImportValidationEngineTest` | the verdict matrix, including blueprint §4B.3's worked example row for row — and, from B-034, the changed-field message next to an update |
| `ImportSchemaRegistryTest` | resolution, and the boot-time refusals that catch a malformed registration |
| `FieldValidatorsTest` | §4B.3's validation rules |
| `HeaderMatcherTest` / `ImportMappingTest` | auto-match, and that it never guesses |
| `InMemoryImportStagingStoreTest` | expiry and the concurrency ceiling |
| `ImportEngineIsolationTest` | the engine names no business entity; the dry run reaches no repository |
| `ClientImportUpsertIT` | upsert against real MySQL — re-upload updates, never duplicates |
| `ImportTemplateWriterTest` | B-031, read back through POI — the header round trip, the dropdowns, the example row, the oversized-enum refusal |
| `ImportTemplateControllerTest` | B-031, the route — §4B.3's two client-specific promises, the 404 for an unregistered schema, and the 403 a Developer gets |
| `XlsxSheetReaderTest` | B-032 — multi-sheet selection, ISO dates, blank-row numbering, the row and column caps, and a refusal that reads like a sentence rather than a POI stack trace. Fixtures are written with the DOM model and read with the streaming one, so the assertions are about real files rather than one implementation against itself |
| `CsvSheetReaderTest` | B-032 — RFC 4180 by hand: the BOM Excel writes and does not mention, a comma inside a quoted address, a line break inside a note, `""` collapsing to one quote |
| `ImportFileParserTest` | B-032 — which reader runs, and that the `.xls` refusal names the conversion |
| `ImportUploadServiceTest` | B-032 — the order of the checks, what gets staged, and that `replaces` releases a slot |
| `ImportUploadControllerTest` | B-032, the route — the response shape, and each refusal arriving as the status and problem document the contract promises |
| `ImportExceptionHandlerTest` | B-032 — the 503 the route-level suite structurally cannot reach, and that the three file refusals do not share a `type` |
| `ImportSchemaFieldsControllerTest` | B-033 — against the **real** client registration, because a test over a stub would prove the projection works while saying nothing about whether it describes what the template hands out. Includes that the headers are undecorated and that validators are not on the wire |
| `ImportMappingPresetServiceTest` | B-033 — the order of the checks, that the unknown-field refusal names every offender at once, and that nothing reaches a query before the schema resolves |
| `ImportMappingPresetControllerTest` | B-033, the routes — 200 for the upsert, 422 for an undeclared field, 400 for a mapping that maps nothing, 404 for a delete that removed no row, and the 403 on all three verbs |
| `ImportMappingPresetIT` | B-033 against real MySQL — everything that is a property of the schema rather than of the Java: the unique index behind the upsert, the collation deciding that `CRM export` and `CRM Export` are one preset, and that the delete is really scoped by schema |
| `ImportValidationServiceTest` | B-034 — the order of the four refusals and why each one is refused rather than previewed, over a real staging store so the expiry is the real expiry. Unchanged by B-035 moving them into `ImportRequestResolver`, which is the point: the assertions are about what a caller sees |
| `ImportCommitServiceTest` | B-035 — that the verdicts are the server's own, that the staging entry is consumed so the same file cannot be committed twice, that a refusal leaves both the file and the database as it found them, and that one row failing at write time costs one row. The executor is same-thread so the counters can be read without waiting; one test uses a real pool to prove the response is genuinely sent first |
| `ImportCommitControllerTest` | B-035, the route — every refusal's status and `type`. All of them are checked before the first query, which is not a coincidence: a refused commit has to leave the staged file and the database untouched |
| `ClientImportCommitIT` | B-035 and B-036 against real MySQL — the whole step, plus that the report exists by the time the batch reads `COMPLETED`, which is when a polling client stops asking. A file with a bad row and a duplicate committed, then the corrected file committed again, ending with three clients rather than five; the batch row and the `import_batch_id` that makes B-037 possible; and the ETag moving with the counters |
| `ImportErrorReportWriterTest` | B-036, read back through POI - the columns 4B.3 asks for, the reason quoted verbatim, text cells so a leading zero survives, and above all the round trip: a report a user downloads auto-maps completely when they upload it back |
| `ImportErrorReportServiceTest` | B-036 - when a report exists, and that an unreachable object store costs the report and never the import |
| `ImportErrorReportControllerTest` | B-036, the route - the media type Excel needs, the `Content-Disposition` name a client reads rather than reconstructs, both 404s, and the rowless 403 |
| `ImportValidateControllerTest` | B-034, the route — each refusal's status and `type`, the properties the screen reads off the body, and a genuine 200 with no database (a file whose every row is rejected never reaches the probe, which is also the response an Admin gets from a file full of mistakes) |

`ImportEngineIsolationTest` reads **source, not bytecode**, and says why in its
javadoc: ArchUnit 1.3.0 cannot parse Java 25 class files, skips every one of them
silently, and reports a pass having examined nothing. Raised for Stream A — the
fix is a version bump in `backend/pom.xml`.
