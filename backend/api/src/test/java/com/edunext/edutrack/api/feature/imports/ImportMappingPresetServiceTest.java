package com.edunext.edutrack.api.feature.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-033 · the presets half of step 3.
 *
 * <p>Against {@link TestImportSchema}, for the reason that file gives: a preset
 * test tied to the client field list would break when somebody adds a column to
 * the client master, and would stop proving the thing B-030 is for — that this
 * works for a schema it has never heard of.
 *
 * <p>The repository is mocked. What is worth asserting here is the order of the
 * checks and what does <em>not</em> reach the database; {@code SQL} belongs to
 * {@code ImportMappingPresetIT}, which runs against real MySQL.
 */
class ImportMappingPresetServiceTest {

    private static final Instant T0 = Instant.parse("2026-08-17T09:00:00Z");

    private final TestImportSchema schema = new TestImportSchema();
    private final ImportSchemaRegistry registry = new ImportSchemaRegistry(List.of(schema));
    private final ImportMappingPresetRepository presets = mock(ImportMappingPresetRepository.class);
    private final ImportMappingPresetService service =
            new ImportMappingPresetService(registry, presets);

    // ── the schema is resolved first, on every verb ──────────────────────────

    /**
     * A 404 before a query runs, on all three routes.
     *
     * <p>An empty list for an unregistered schema would be a lie of a particular
     * kind — it says "no presets yet", which invites the caller to save one — and
     * a delete that answered 204 for a schema that does not exist would report
     * success for a row nothing could have held.
     */
    @Test
    @DisplayName("an unregistered schema is refused before the database is touched")
    void anUnregisteredSchemaNeverReachesAQuery() {
        assertThatThrownBy(() -> service.list("users"))
                .isInstanceOf(UnknownImportSchemaException.class);
        assertThatThrownBy(() -> service.save("users", request("CRM", Map.of("code", "Code")), 7L))
                .isInstanceOf(UnknownImportSchemaException.class);
        assertThatThrownBy(() -> service.delete("users", 1L))
                .isInstanceOf(UnknownImportSchemaException.class);

        verify(presets, never()).findAll(anyString());
        verify(presets, never()).save(anyString(), anyString(), any(), any());
        verify(presets, never()).delete(anyString(), anyLong());
    }

    // ── save ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a preset naming declared fields is saved under the registry's own key")
    void savesAValidPreset() {
        stubSave();

        service.save("widgets", request("CRM export", Map.of("code", "Ref", "name", "Title")), 7L);

        ArgumentCaptor<Map<String, String>> mapping = captor();
        verify(presets).save(eq("widgets"), eq("CRM export"), mapping.capture(), eq(7L));
        assertThat(mapping.getValue()).containsExactlyInAnyOrderEntriesOf(
                Map.of("code", "Ref", "name", "Title"));
    }

    /**
     * The refusal this class exists for.
     *
     * <p>A preset is applied weeks after it is saved, against a file nobody is
     * looking at today. Dropping the unknown key silently is the tempting option
     * and the expensive one: the preset then looks complete in the picker, applies
     * cleanly, and the column it was meant to map is simply never imported.
     */
    @Test
    @DisplayName("a mapping naming a field the schema does not declare is refused, not trimmed")
    void refusesAnUnknownTargetField() {
        assertThatThrownBy(() -> service.save("widgets",
                request("Old preset", Map.of("code", "Ref", "phoneNumber", "Phone")), 7L))
                .isInstanceOf(UnknownImportFieldException.class)
                .hasMessageContaining("phoneNumber")
                // The realistic cause is a preset built against an older
                // registration, so the response says what the schema does have.
                .hasMessageContaining("code");

        verify(presets, never()).save(anyString(), anyString(), any(), any());
    }

    /** Every unknown key, not the first — one round trip per mistake is not a fix loop. */
    @Test
    void namesEveryUnknownFieldAtOnce() {
        assertThatThrownBy(() -> service.save("widgets",
                request("Old", new java.util.LinkedHashMap<>(Map.of(
                        "phoneNumber", "Phone", "faxNumber", "Fax"))), 7L))
                .isInstanceOf(UnknownImportFieldException.class)
                .hasMessageContaining("phoneNumber")
                .hasMessageContaining("faxNumber");
    }

    /**
     * A blank source column is how "not mapped" arrives from a {@code <select>}
     * whose empty option was left selected. Stored, the preset would claim a
     * mapping for that field — counted as mapped by the screen that decides
     * whether Next is blocked, and then quietly ignored by
     * {@link ImportMapping#apply}.
     */
    @Test
    @DisplayName("blank source columns are dropped rather than stored as a mapping that maps nothing")
    void dropsBlankSourceColumns() {
        stubSave();

        service.save("widgets", request("Partial",
                new java.util.LinkedHashMap<>(Map.of("code", "Ref", "name", "  "))), 7L);

        ArgumentCaptor<Map<String, String>> mapping = captor();
        verify(presets).save(anyString(), anyString(), mapping.capture(), any());
        assertThat(mapping.getValue()).containsOnlyKeys("code");
    }

    /**
     * 400, and the case {@code @NotEmpty} structurally cannot see: a map of two
     * fields both pointing at {@code ""} is non-empty and maps nothing.
     */
    @Test
    @DisplayName("a mapping whose every column is blank is refused, not stored as {}")
    void refusesAMappingThatMapsNothing() {
        assertThatThrownBy(() -> service.save("widgets",
                request("Empty", Map.of("code", "", "name", "   ")), 7L))
                .isInstanceOf(ImportMappingPresetService.EmptyMappingException.class);

        verify(presets, never()).save(anyString(), anyString(), any(), any());
    }

    /**
     * Trimmed, because the unique key permits two presets differing only in a
     * trailing space and a dropdown cannot tell them apart.
     */
    @Test
    void trimsTheName() {
        stubSave();

        service.save("widgets", request("  CRM export  ", Map.of("code", "Ref")), 7L);

        verify(presets).save(eq("widgets"), eq("CRM export"), any(), any());
    }

    /**
     * An unidentifiable caller saves a preset with no attribution rather than
     * being refused.
     *
     * <p>{@code created_by} is on no key, nothing filters on it, and a preset with
     * no name against it is still a working preset. Failing the save would refuse
     * a legitimate action to protect a column nothing reads.
     */
    @Test
    @DisplayName("a caller with no readable identity still saves — attribution is best-effort")
    void savesWithoutAnIdentity() {
        stubSave();

        service.save("widgets", request("CRM export", Map.of("code", "Ref")), null);

        verify(presets).save(eq("widgets"), eq("CRM export"), any(), isNull());
    }

    // ── delete ──────────────────────────────────────────────────────────────

    /**
     * A delete that removed no row is a 404, not a cheerful 204.
     *
     * <p>The ordinary case is a preset another Admin removed between this screen's
     * list read and this click — and the picker needs to know to drop the entry,
     * which a 204 would not tell it.
     */
    @Test
    void deletingAMissingPresetIsNotFound() {
        when(presets.delete("widgets", 42L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete("widgets", 42L))
                .isInstanceOf(MappingPresetNotFoundException.class)
                .hasMessageContaining("42");
    }

    @Test
    void deletesAPresetThatExists() {
        when(presets.delete("widgets", 42L)).thenReturn(true);

        service.delete("widgets", 42L);

        verify(presets).delete("widgets", 42L);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void stubSave() {
        when(presets.save(anyString(), anyString(), any(), any()))
                .thenAnswer(call -> new ImportDtos.MappingPreset(
                        1L, call.getArgument(1), call.getArgument(2), T0));
    }

    private static ImportDtos.SaveMappingPresetRequest request(String name,
                                                               Map<String, String> mapping) {
        return new ImportDtos.SaveMappingPresetRequest(name, mapping);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, String>> captor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
