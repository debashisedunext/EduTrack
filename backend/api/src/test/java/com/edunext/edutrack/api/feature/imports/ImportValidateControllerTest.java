package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.api.security.TestPrincipals;
import com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B-034 · {@code POST /imports/{schema}/validate} over HTTP.
 *
 * <p>{@link ImportValidationServiceTest} owns the rules; this owns the wire —
 * the status each refusal answers with, the problem {@code type} the screen
 * branches on, and the properties it reads off the body. Those are the half a
 * service test cannot see, and CONVENTIONS.md §3 makes the {@code type} the
 * stable contract rather than the prose.
 *
 * <h2>No database, and the happy path still runs</h2>
 *
 * <p>The context is built the way {@code ImportSchemaFieldsControllerTest}
 * builds it. That normally rules out a 200 here, because
 * {@code ClientImportSchema.findExisting} is a query — but the engine only
 * probes for rows that survived content validation, so a file whose every row is
 * rejected reaches a real 200 without a connection. That is not a contrivance to
 * dodge a container: it is the response an Admin gets from a file full of
 * mistakes, which is the case this screen exists for.
 *
 * <p>{@link ClientImportUpsertIT} covers the create-and-update verdicts against
 * a real MySQL, where they belong.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class ImportValidateControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtAuthoritiesConverter authorities;

    /** The real store the upload route writes to, so these are genuinely staged. */
    @Autowired
    ImportStagingStore staging;

    // ── the preview ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a staged file comes back as counts and one row per line of the sheet")
    void previewsAStagedFile() throws Exception {
        UUID uploadId = stage(
                List.of("Client Code", "Name", "Primary Email"),
                row(2, "Client Code", "", "Name", "No Code Here"),
                row(3, "Client Code", "ZENITH", "Name", "Zenith",
                        "Primary Email", "not-an-email"));

        mvc.perform(validate("clients", body(uploadId, Map.of(
                        "clientCode", "Client Code",
                        "name", "Name",
                        "primaryEmail", "Primary Email"))).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rejected").value(2))
                .andExpect(jsonPath("$.data.willCreate").value(0))
                // Blueprint §4B.3's Message column: the first rule the row broke,
                // in the user's words and naming the column they can go and look
                // at rather than the field name we store it under.
                .andExpect(jsonPath("$.data.rows[0].rowNumber").value(2))
                .andExpect(jsonPath("$.data.rows[0].verdict").value("REJECTED"))
                .andExpect(jsonPath("$.data.rows[0].reason").value("Client Code required"))
                .andExpect(jsonPath("$.data.rows[1].reason").value("Primary Email: Invalid email"))
                // The row as mapped, keyed by target field — enough to render the
                // row without re-reading the file.
                .andExpect(jsonPath("$.data.rows[1].values.clientCode").value("ZENITH"));
    }

    @Test
    @DisplayName("the upload survives the dry run, because the user will run it again")
    void doesNotConsumeTheUpload() throws Exception {
        UUID uploadId = stage(List.of("Client Code", "Name"),
                row(2, "Client Code", "", "Name", "No Code"));

        var request = body(uploadId, Map.of("clientCode", "Client Code", "name", "Name"));
        mvc.perform(validate("clients", request).with(admin())).andExpect(status().isOk());
        // Reading the preview, going back to step 3 and changing one column is
        // the ordinary path through this screen. A route that discarded the
        // upload would answer "your file expired" to the second attempt.
        mvc.perform(validate("clients", request).with(admin())).andExpect(status().isOk());
    }

    // ── the refusals ────────────────────────────────────────────────────────

    @Test
    @DisplayName("an expired or unknown uploadId is 422 import-upload-unavailable")
    void expiredUploadIsUnprocessable() throws Exception {
        mvc.perform(validate("clients", body(UUID.randomUUID(), Map.of(
                        "clientCode", "Client Code", "name", "Name"))).with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://edutrack/errors/import-upload-unavailable"))
                .andExpect(jsonPath("$.title").value("Uploaded file is no longer available"));
    }

    @Test
    @DisplayName("a sheet that is not the staged one names the sheet that is")
    void wrongSheetNamesTheStagedOne() throws Exception {
        UUID uploadId = stage(List.of("Client Code", "Name"),
                row(2, "Client Code", "A", "Name", "First"));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("uploadId", uploadId.toString());
        request.put("sheet", "Archive");
        request.put("mapping", Map.of("clientCode", "Client Code", "name", "Name"));

        mvc.perform(validate("clients", json(request)).with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://edutrack/errors/import-upload-unavailable"))
                .andExpect(jsonPath("$.sheet").value("Clients"))
                .andExpect(jsonPath("$.requestedSheet").value("Archive"));
    }

    @Test
    @DisplayName("an unmapped required column is 422 with both the fields and their headers")
    void incompleteMappingIsUnprocessable() throws Exception {
        UUID uploadId = stage(List.of("Client Code", "Name"),
                row(2, "Client Code", "A", "Name", "First"));

        mvc.perform(validate("clients", body(uploadId, Map.of("clientCode", "Client Code")))
                        .with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://edutrack/errors/import-incomplete-mapping"))
                // The field name for the screen's own table, which is keyed by
                // it; the header for the sentence the screen writes.
                .andExpect(jsonPath("$.missingFields[0]").value("name"))
                .andExpect(jsonPath("$.missingHeaders[0]").value("Name"));
    }

    @Test
    @DisplayName("a column this sheet does not have is 422, with the sheet's own headings")
    void unknownColumnIsUnprocessable() throws Exception {
        UUID uploadId = stage(List.of("Client Code", "Name"),
                row(2, "Client Code", "A", "Name", "First"));

        mvc.perform(validate("clients", body(uploadId, Map.of(
                        "clientCode", "Client Code",
                        "name", "Name",
                        "phone", "Telephone"))).with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://edutrack/errors/import-unknown-column"))
                .andExpect(jsonPath("$.unknownColumns[0]").value("Telephone"))
                .andExpect(jsonPath("$.headers[0]").value("Client Code"));
    }

    @Test
    @DisplayName("a field the schema does not declare reuses B-033's import-unknown-field")
    void unknownFieldReusesThePresetRefusal() throws Exception {
        // Deliberately the same type the preset save answers with. To a caller
        // it is one condition — "this import has no such field" — and a second
        // type would be a second thing for step 3 to handle identically.
        UUID uploadId = stage(List.of("Client Code", "Name"),
                row(2, "Client Code", "A", "Name", "First"));

        mvc.perform(validate("clients", body(uploadId, Map.of(
                        "clientCode", "Client Code",
                        "name", "Name",
                        "accountManager", "Name"))).with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://edutrack/errors/import-unknown-field"))
                .andExpect(jsonPath("$.unknownFields[0]").value("accountManager"));
    }

    @Test
    @DisplayName("a body with no mapping is a 400 from bean validation, not a 422")
    void anEmptyMappingIsABadRequest() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("uploadId", UUID.randomUUID().toString());
        request.put("mapping", Map.of());

        mvc.perform(validate("clients", json(request)).with(admin()))
                .andExpect(status().isBadRequest());
    }

    /**
     * The same 404 the other four routes on this path answer. <b>Delete this when
     * B-038 lands</b>, like its siblings — the failure is the right amount of
     * ceremony for "the second schema now exists".
     */
    @Test
    void anUnregisteredSchemaIsNotFound() throws Exception {
        mvc.perform(validate("users", body(UUID.randomUUID(), Map.of("a", "b"))).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unknown import schema"));
    }

    /**
     * The schema is resolved before the body, so this is a 404 rather than the
     * 422 the mapping would otherwise earn.
     *
     * <p>Worth pinning: told to fix a mapping for an import that does not exist,
     * a caller would go looking at their spreadsheet.
     */
    @Test
    @DisplayName("an unregistered schema wins over a broken body")
    void theSchemaIsResolvedFirst() throws Exception {
        mvc.perform(validate("users", body(UUID.randomUUID(), Map.of("invented", "Column")))
                        .with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aDeveloperIsRefused() throws Exception {
        mvc.perform(validate("clients", body(UUID.randomUUID(), Map.of("clientCode", "Code")))
                        .with(authentication(TestPrincipals.of(authorities, "DEVELOPER"))))
                .andExpect(status().isForbidden());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private UUID stage(List<String> headers, StagedRow... rows) {
        StagedUpload upload = new StagedUpload(UUID.randomUUID(), "clients.xlsx",
                List.of("Clients", "Archive"), "Clients", headers, List.of(rows), Instant.now());
        staging.stage(upload);
        return upload.uploadId();
    }

    private static StagedRow row(int number, String... headingsAndCells) {
        Map<String, String> cells = new LinkedHashMap<>();
        for (int i = 0; i < headingsAndCells.length; i += 2) {
            if (!headingsAndCells[i + 1].isEmpty()) {
                cells.put(headingsAndCells[i], headingsAndCells[i + 1]);
            }
        }
        return new StagedRow(number, cells);
    }

    private static String body(UUID uploadId, Map<String, String> mapping) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("uploadId", uploadId.toString());
        request.put("mapping", mapping);
        return json(request);
    }

    /** Hand-rolled rather than an ObjectMapper — every value here is a string. */
    private static String json(Map<String, Object> request) {
        StringBuilder out = new StringBuilder("{");
        request.forEach((key, value) -> {
            if (out.length() > 1) {
                out.append(',');
            }
            out.append('"').append(key).append("\":");
            if (value instanceof Map<?, ?> nested) {
                out.append('{');
                int written = 0;
                for (Map.Entry<?, ?> entry : nested.entrySet()) {
                    if (written++ > 0) {
                        out.append(',');
                    }
                    out.append('"').append(entry.getKey()).append("\":\"")
                            .append(entry.getValue()).append('"');
                }
                out.append('}');
            } else {
                out.append('"').append(value).append('"');
            }
        });
        return out.append('}').toString();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            validate(String schema, String body) {
        return post("/api/v1/imports/" + schema + "/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private RequestPostProcessor admin() {
        return authentication(TestPrincipals.of(authorities, "ADMIN"));
    }
}
