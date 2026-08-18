package com.edunext.edutrack.api.feature.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-032 · step 2 as a whole — the order of the checks, and what gets staged.
 *
 * <p>Against {@link TestImportSchema} rather than the client registration, for
 * the reason that file gives: a step-2 test that depended on the client field
 * list would break every time somebody added a column to the client master, and
 * would stop proving that step 2 works for a schema it has never heard of.
 */
class ImportUploadServiceTest {

    private static final Instant T0 = Instant.parse("2026-08-17T09:00:00Z");

    private final TestImportSchema schema = new TestImportSchema();
    private final ImportSchemaRegistry registry = new ImportSchemaRegistry(List.of(schema));
    private final ImportUploadLimits limits = new ImportUploadLimits(5_242_880, 5_000, 200);
    private final ImportFileParser parser =
            new ImportFileParser(new XlsxSheetReader(limits), new CsvSheetReader(limits));
    private final InMemoryImportStagingStore staging =
            new InMemoryImportStagingStore(Clock.fixed(T0, ZoneOffset.UTC), Duration.ofMinutes(30), 3);

    private final ImportUploadService service = new ImportUploadService(
            registry, parser, staging, limits, Clock.fixed(T0, ZoneOffset.UTC));

    @Test
    @DisplayName("the file is staged, and the response describes it without carrying the rows")
    void stagesTheUploadAndDescribesIt() {
        ImportDtos.Upload upload = upload("widgets.csv", """
                Code,Name,Email
                W-1,First widget,one@example.test
                W-2,Second widget,two@example.test
                """);

        assertThat(upload.fileName()).isEqualTo("widgets.csv");
        assertThat(upload.sheet()).isEqualTo("widgets");
        assertThat(upload.sheets()).containsExactly("widgets");
        assertThat(upload.headers()).containsExactly("Code", "Name", "Email");
        assertThat(upload.rowCount()).isEqualTo(2);

        assertThat(staging.find(upload.uploadId()))
                .as("the mapping and dry-run steps read this back; it must be there")
                .isPresent();
    }

    /**
     * The auto-match B-033 opens with, computed here because the headings only
     * exist once the file has been read. It is a suggestion in the strict sense —
     * every entry stays overridable — which is why an unmatched column simply is
     * not in it rather than being guessed at.
     */
    @Test
    void suggestsAMappingFromTheHeadings() {
        ImportDtos.Upload upload = upload("widgets.csv", "Code,Name,Something Else\nW-1,First,x\n");

        assertThat(upload.suggestedMapping())
                .containsEntry("code", "Code")
                .containsEntry("name", "Name")
                .doesNotContainKey("email");
    }

    /**
     * Resolved before anything else, so an unregistered schema costs a map lookup
     * rather than a parse — and answers 404 on a path segment.
     *
     * <p>This runs against a registry holding one invented schema, so it says
     * nothing about which keys the application registers and did not have to
     * change when B-038 registered {@code users}. The controller test asserting
     * the same rule end to end did.
     */
    @Test
    @DisplayName("an unregistered schema is refused before the file is looked at")
    void refusesAnUnregisteredSchemaFirst() {
        assertThatThrownBy(() -> service.upload("users", "widgets.csv", 10,
                "not even a spreadsheet".getBytes(StandardCharsets.UTF_8), null, null))
                .isInstanceOf(UnknownImportSchemaException.class);
    }

    /**
     * Compared against the declared size, so an oversized upload costs a
     * comparison rather than a parse. The bytes are not even looked at — asserted
     * by handing it content that would fail to parse if it were.
     */
    @Test
    void refusesAnOversizedFileWithoutParsingIt() {
        ImportUploadService tiny = new ImportUploadService(registry, parser, staging,
                new ImportUploadLimits(100, 5_000, 200), Clock.fixed(T0, ZoneOffset.UTC));

        assertThatThrownBy(() -> tiny.upload("widgets", "widgets.csv", 5_000,
                "%PDF not a spreadsheet".getBytes(StandardCharsets.UTF_8), null, null))
                .isInstanceOf(ImportLimitExceededException.class)
                .hasMessageContaining("the limit is")
                .extracting(e -> ((ImportLimitExceededException) e).limit())
                .isEqualTo("bytes");
    }

    /**
     * <b>What keeps the sheet selector from eating the staging ceiling.</b> Each
     * look at a different sheet is a fresh upload; without {@code replaces}, a
     * workbook with four sheets costs four of the twenty slots to browse, for
     * thirty minutes, and the fourth user to try is told there are too many
     * imports in progress.
     */
    @Test
    @DisplayName("re-reading another sheet releases the upload it supersedes")
    void replacesReleasesThePreviousStagingSlot() {
        ImportDtos.Upload first = upload("widgets.csv", "Code,Name\nW-1,First\n");

        ImportDtos.Upload second = service.upload("widgets", "widgets.csv", 40,
                "Code,Name\nW-2,Second\n".getBytes(StandardCharsets.UTF_8), null, first.uploadId());

        assertThat(staging.find(first.uploadId())).isEmpty();
        assertThat(staging.find(second.uploadId())).isPresent();
    }

    /**
     * Housekeeping, not a precondition. Failing an upload because the id it
     * superseded had already expired would refuse a request for a reason the
     * caller cannot see and could not have avoided.
     */
    @Test
    void anUnknownReplacesIdIsIgnored() {
        ImportDtos.Upload upload = service.upload("widgets", "widgets.csv", 40,
                "Code,Name\nW-1,First\n".getBytes(StandardCharsets.UTF_8), null, UUID.randomUUID());

        assertThat(staging.find(upload.uploadId())).isPresent();
    }

    /**
     * The staging ceiling, over HTTP for the first time.
     *
     * <p>{@link InMemoryImportStagingStore} has refused past its cap since B-030
     * with a message written for a person to read, and nothing called
     * {@code stage()} until now — so until B-032 that refusal was unreachable.
     * It is a distinct type rather than the bare {@code IllegalStateException} it
     * threw, because the advice has to answer 503 for this and must not answer
     * 503 for every other bug on the path.
     */
    @Test
    @DisplayName("past the staging ceiling it refuses with a type the advice can answer 503 to")
    void refusesWhenEveryStagingSlotIsTaken() {
        // The store this service holds caps at three.
        upload("a.csv", "Code,Name\nA,First\n");
        upload("b.csv", "Code,Name\nB,Second\n");
        upload("c.csv", "Code,Name\nC,Third\n");

        assertThatThrownBy(() -> upload("d.csv", "Code,Name\nD,Fourth\n"))
                .isInstanceOf(ImportStagingFullException.class)
                .hasMessageContaining("Too many imports in progress");
    }

    /**
     * Step 2 is on the same side of §4B.3's line as step 4. A staged upload has
     * changed nothing, and this is the assertion that says so at the level a
     * reader of this service would look for it — {@code ImportEngineIsolationTest}
     * makes the same claim structurally, over the whole package.
     */
    @Test
    @DisplayName("uploading writes nothing to the schema it was uploaded for")
    void nothingIsWritten() {
        upload("widgets.csv", "Code,Name\nW-1,First\n");

        // TestImportSchema#upsert fails the test if it is ever called, and
        // findExisting records every probe. Neither should have happened: this
        // step neither writes nor asks what exists — that is the dry run's job.
        assertThat(schema.probes).isEmpty();
    }

    private ImportDtos.Upload upload(String fileName, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return service.upload("widgets", fileName, bytes.length, bytes, null, null);
    }
}
