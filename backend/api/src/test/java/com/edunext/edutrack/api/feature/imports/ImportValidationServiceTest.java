package com.edunext.edutrack.api.feature.imports;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-034 · the four ways step 4 refuses a request, and the one way it does not.
 *
 * <p>{@link ImportValidationEngineTest} owns the verdicts. This file owns what
 * happens <em>before</em> the engine is reached — which is where every one of
 * these tasks' real defects has been, because the engine is pure and this part
 * is where the wizard's four requests have to agree with each other.
 *
 * <p>A real {@link InMemoryImportStagingStore} rather than a stub, because the
 * expiry behaviour is half of what is under test and a stub would have to
 * reimplement it to be interesting.
 */
class ImportValidationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T09:00:00Z");

    private final ImportSchemaRegistry registry =
            new ImportSchemaRegistry(List.of(new TestImportSchema()));
    private InMemoryImportStagingStore staging;
    private ImportValidationService service;

    @BeforeEach
    void setUp() {
        staging = new InMemoryImportStagingStore(Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                Duration.ofMinutes(30), 20);
        // B-035 moved the four refusals into ImportRequestResolver so /commit
        // could not answer them differently. The assertions below are unchanged:
        // they are about what the caller sees, and the caller sees the same
        // exceptions from the same route.
        service = new ImportValidationService(
                new ImportRequestResolver(registry, staging), new ImportValidationEngine());
    }

    // ── the happy path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a mapped file comes back as a preview, and the store still holds it")
    void previewsAMappedFile() {
        UUID uploadId = stage(
                List.of("Their Code", "Their Name"),
                row(2, "Their Code", "ACME", "Their Name", "Acme Corporation"),
                row(3, "Their Code", "", "Their Name", "No Code"));

        ImportPreview preview = service.validate("widgets", request(uploadId, Map.of(
                "code", "Their Code", "name", "Their Name")));

        assertThat(preview.rows()).extracting(ImportRowVerdict::rowNumber,
                        ImportRowVerdict::verdict)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2, ImportVerdict.WILL_CREATE),
                        org.assertj.core.groups.Tuple.tuple(3, ImportVerdict.REJECTED));

        // The upload is *not* discarded. The user reads the preview, goes back
        // to step 3, changes a column and runs it again — discarding here would
        // make the second run answer "your upload expired".
        assertThat(staging.find(uploadId)).isPresent();
    }

    @Test
    @DisplayName("the row numbers are the sheet's, not the list's")
    void quotesTheSourceRowNumbers() {
        // The reason StagedRow carries a number at all: blank rows are dropped
        // at parse, so position stopped being the row in the sheet. A preview
        // that says "row 41" about row 44 is worse than one that says nothing —
        // the user goes and looks, finds the cell filled in, and stops believing
        // the screen.
        UUID uploadId = stage(
                List.of("Code", "Name"),
                row(2, "Code", "A", "Name", "First"),
                row(44, "Code", "B", "Name", "After a gap"));

        ImportPreview preview = service.validate("widgets",
                request(uploadId, Map.of("code", "Code", "name", "Name")));

        assertThat(preview.rows()).extracting(ImportRowVerdict::rowNumber)
                .containsExactly(2, 44);
    }

    @Test
    @DisplayName("a mapping entry with a blank column is ignored, not counted as mapped")
    void blankColumnsAreDropped() {
        UUID uploadId = stage(List.of("Code", "Name"),
                row(2, "Code", "A", "Name", "First"));

        // `notes` left on the empty option. If it survived cleaning it would be
        // a key that reads nothing, and — for a required field — one that
        // satisfied the missing-required check while mapping no column at all.
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("code", "Code");
        mapping.put("name", "Name");
        mapping.put("notes", "");

        assertThat(service.validate("widgets", request(uploadId, mapping)).rows())
                .singleElement()
                .extracting(ImportRowVerdict::verdict).isEqualTo(ImportVerdict.WILL_CREATE);
    }

    // ── the refusals ────────────────────────────────────────────────────────

    @Nested
    class Refusals {

        @Test
        @DisplayName("an unregistered schema is refused before the body is looked at")
        void unknownSchemaFirst() {
            // A deliberately broken body: if the schema were not resolved first,
            // this would fail on the mapping instead and the caller would be
            // told to fix a mapping for an import that does not exist.
            assertThatThrownBy(() -> service.validate("nonesuch",
                    request(UUID.randomUUID(), Map.of("nope", "Nope"))))
                    .isInstanceOf(UnknownImportSchemaException.class);
        }

        @Test
        @DisplayName("an upload that expired is refused, and cannot be distinguished from an invented id")
        void expiredUpload() {
            UUID uploadId = stage(List.of("Code"), row(2, "Code", "A"));

            // Thirty-one minutes later — a wizard left open over lunch.
            service = new ImportValidationService(
                    new ImportRequestResolver(registry, new InMemoryImportStagingStore(
                            Clock.fixed(NOW.plus(Duration.ofMinutes(31)), java.time.ZoneOffset.UTC),
                            Duration.ofMinutes(30), 20)),
                    new ImportValidationEngine());

            assertThatThrownBy(() -> service.validate("widgets",
                    request(uploadId, Map.of("code", "Code", "name", "Code"))))
                    .isInstanceOf(ImportUploadNotAvailableException.class)
                    .hasMessageContaining("no longer available");
        }

        @Test
        @DisplayName("a sheet that disagrees with what is staged is refused, not silently substituted")
        void wrongSheet() {
            UUID uploadId = stage(List.of("Code", "Name"), row(2, "Code", "A", "Name", "First"));

            ImportDtos.ValidateRequest request = new ImportDtos.ValidateRequest(
                    uploadId, "Archive", Map.of("code", "Code", "name", "Name"));

            assertThatThrownBy(() -> service.validate("widgets", request))
                    .isInstanceOf(ImportUploadNotAvailableException.class)
                    // The staged sheet is named, because "they disagree" without
                    // saying which is staged leaves the user nothing to do.
                    .hasMessageContaining("'Clients' sheet, not 'Archive'");
        }

        @Test
        @DisplayName("the staged sheet needs no restating — sheet is optional")
        void sheetMayBeOmitted() {
            UUID uploadId = stage(List.of("Code", "Name"), row(2, "Code", "A", "Name", "First"));

            assertThat(service.validate("widgets",
                    request(uploadId, Map.of("code", "Code", "name", "Name"))).rows())
                    .hasSize(1);
        }

        @Test
        @DisplayName("an unmapped required column is refused rather than rejecting every row")
        void incompleteMapping() {
            UUID uploadId = stage(List.of("Code", "Name"),
                    row(2, "Code", "A", "Name", "First"),
                    row(3, "Code", "B", "Name", "Second"));

            // Run anyway, this is two rows of "Name required" — a preview that
            // points at the spreadsheet when the fault is one dropdown on the
            // previous step.
            assertThatThrownBy(() -> service.validate("widgets",
                    request(uploadId, Map.of("code", "Code"))))
                    .isInstanceOf(IncompleteMappingException.class)
                    .hasMessageContaining("Name")
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            IncompleteMappingException.class))
                    .satisfies(e -> {
                        assertThat(e.missingFields()).containsExactly("name");
                        assertThat(e.missingHeaders()).containsExactly("Name");
                    });
        }

        @Test
        @DisplayName("a mapping naming a column this sheet does not have is refused, not read as blank")
        void unknownSourceColumn() {
            // The ordinary cause is a preset saved against an export that has
            // since been renamed. Dropped silently, the column is simply never
            // imported and nothing on the screen says so.
            UUID uploadId = stage(List.of("Code", "Name"), row(2, "Code", "A", "Name", "First"));

            assertThatThrownBy(() -> service.validate("widgets", request(uploadId, Map.of(
                    "code", "Code", "name", "Name", "email", "E-mail Address"))))
                    .isInstanceOf(UnknownSourceColumnException.class)
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            UnknownSourceColumnException.class))
                    .satisfies(e -> {
                        assertThat(e.unknownColumns()).containsExactly("E-mail Address");
                        // The sheet's own headings, so the caller can offer the
                        // right column rather than ask the user to compare lists.
                        assertThat(e.headers()).containsExactly("Code", "Name");
                    });
        }

        @Test
        @DisplayName("a mapping naming a field the schema does not declare is refused")
        void unknownTargetField() {
            UUID uploadId = stage(List.of("Code", "Name"), row(2, "Code", "A", "Name", "First"));

            assertThatThrownBy(() -> service.validate("widgets", request(uploadId, Map.of(
                    "code", "Code", "name", "Name", "supportPlan", "Name"))))
                    .isInstanceOf(UnknownImportFieldException.class);
        }

        @Test
        @DisplayName("the target side is checked before the source side")
        void targetSideFirst() {
            // Both halves are wrong. The field is the more fundamental error —
            // there is no such column in this import at all — and reporting the
            // heading first would have the user hunting their spreadsheet for a
            // column that could never have been mapped.
            UUID uploadId = stage(List.of("Code", "Name"), row(2, "Code", "A", "Name", "First"));

            assertThatThrownBy(() -> service.validate("widgets", request(uploadId, Map.of(
                    "code", "Code", "name", "Name", "invented", "Also Invented"))))
                    .isInstanceOf(UnknownImportFieldException.class);
        }
    }

    // ── the guarantee ───────────────────────────────────────────────────────

    @Test
    @DisplayName("the dry run never calls upsert — TestImportSchema throws if it does")
    void writesNothing() {
        UUID uploadId = stage(List.of("Code", "Name"),
                row(2, "Code", "A", "Name", "Creates"),
                row(3, "Code", "A", "Name", "Duplicates"),
                row(4, "Name", "Rejected, no code"));

        service.validate("widgets", request(uploadId, Map.of("code", "Code", "name", "Name")));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private UUID stage(List<String> headers, StagedRow... rows) {
        StagedUpload upload = new StagedUpload(UUID.randomUUID(), "widgets.xlsx",
                List.of("Clients", "Archive"), "Clients", headers, List.of(rows), NOW);
        staging.stage(upload);
        return upload.uploadId();
    }

    private static StagedRow row(int number, String... headingsAndCells) {
        Map<String, String> cells = new LinkedHashMap<>();
        for (int i = 0; i < headingsAndCells.length; i += 2) {
            cells.put(headingsAndCells[i], headingsAndCells[i + 1]);
        }
        return new StagedRow(number, cells);
    }

    private static ImportDtos.ValidateRequest request(UUID uploadId, Map<String, String> mapping) {
        return new ImportDtos.ValidateRequest(uploadId, null, mapping);
    }
}
