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
                   findExisting  (read-only)
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
| B-034 | dry-run preview | `ImportValidationEngine` → `ImportPreview` |
| B-035 | commit job | `ImportPreview.writable()` → `ImportSchemaDefinition.upsert` |
| B-036 | error report | `ImportRowVerdict` — the rejected rows plus their reason |
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

## Not here yet

No dry run or commit — B-034, B-035. `/imports/users/*` answers 404 on all four
routes until B-038 registers the second schema, and
`ImportTemplateControllerTest`, `ImportSchemaFieldsControllerTest` and
`ImportMappingPresetControllerTest` each assert that — so the day the registration
lands, three tests fail and are deleted.

## Tests

| File | Proves |
|---|---|
| `ImportValidationEngineTest` | the verdict matrix, including blueprint §4B.3's worked example row for row |
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

`ImportEngineIsolationTest` reads **source, not bytecode**, and says why in its
javadoc: ArchUnit 1.3.0 cannot parse Java 25 class files, skips every one of them
silently, and reports a pass having examined nothing. Raised for Stream A — the
fix is a version bump in `backend/pom.xml`.
