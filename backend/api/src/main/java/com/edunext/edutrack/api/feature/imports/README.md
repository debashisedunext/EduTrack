# feature/imports

**Owner: Stream B · Ayush**

The Excel import engine — screen S-34, blueprint §4B.3.

## What is here

| Task | Files |
|---|---|
| **B-030** Import engine as a schema registry | everything below |

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
| B-032 | upload, SAX parse | produces `StagedUpload`, replaces `InMemoryImportStagingStore` |
| B-033 | column mapping | `HeaderMatcher` → `ImportMapping`; `missingRequired` blocks Next |
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
backend yet. Two API pods would stage on one and validate on the other. B-032
replaces it; the seam is named so that replacement is a class, not a refactor.

## Not here yet

No controller. Each endpoint belongs to the step that introduces it (B-031
onwards), and `UnknownImportSchemaException` gets its `@RestControllerAdvice`
alongside the first one — advice on a controller that does not exist is dead code
until it silently is not.

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

`ImportEngineIsolationTest` reads **source, not bytecode**, and says why in its
javadoc: ArchUnit 1.3.0 cannot parse Java 25 class files, skips every one of them
silently, and reports a pass having examined nothing. Raised for Stream A — the
fix is a version bump in `backend/pom.xml`.
