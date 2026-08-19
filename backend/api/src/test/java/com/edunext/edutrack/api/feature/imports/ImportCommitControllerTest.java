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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
 * B-035 · {@code POST /imports/{schema}/commit} over HTTP.
 *
 * <p>{@link ImportCommitServiceTest} owns the rules; this owns the wire — the
 * status each refusal answers with and the problem {@code type} the screen
 * branches on. CONVENTIONS.md §3 makes the type the stable contract and the
 * prose the changeable half, so these are the assertions that would break a
 * client.
 *
 * <h2>No database, and that decides what is testable here</h2>
 *
 * <p>The context is built the way {@link ImportValidateControllerTest} builds
 * it, and this route reaches further than that one did: a commit that gets as
 * far as opening a batch needs a connection. So the cases here are the ones
 * refused <em>before</em> any query — which is every refusal the route has, and
 * is not a coincidence. Every one of them is checked before the first write for
 * the same reason: a refused commit must leave the staged file and the database
 * exactly as it found them, so the user can fix one dropdown and press the
 * button again.
 *
 * <p>The 202 and everything past it is {@link ClientImportCommitIT}, against a
 * real MySQL, where an upsert can be proved to be one.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class ImportCommitControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtAuthoritiesConverter authorities;

    @Autowired
    ImportStagingStore staging;

    // ── the refusals shared with step 4 ─────────────────────────────────────

    @Test
    @DisplayName("an expired uploadId is the same 422 and the same type step 4 answers")
    void expiredUploadIsUnprocessable() throws Exception {
        // Deliberately identical to ImportValidateControllerTest's. An
        // incomplete mapping is not a different condition for having arrived one
        // step later, and a screen that handled these at step 4 must not need a
        // second branch here — which is why B-035 moved the checks into a shared
        // resolver rather than writing them twice.
        mvc.perform(commit("clients", body(UUID.randomUUID(), mapping())).with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://edutrack/errors/import-upload-unavailable"));
    }

    @Test
    @DisplayName("an unmapped required column is 422 import-incomplete-mapping")
    void incompleteMappingIsUnprocessable() throws Exception {
        UUID uploadId = stage(row(2, "Client Code", "ACME", "Name", "Acme"));

        mvc.perform(commit("clients", body(uploadId, Map.of("clientCode", "Client Code")))
                        .with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://edutrack/errors/import-incomplete-mapping"))
                .andExpect(jsonPath("$.missingFields[0]").value("name"));

        // Nothing consumed. The remedy is one dropdown on the previous step, and
        // a route that had released the staging entry would make it a re-upload.
        mvc.perform(commit("clients", body(uploadId, Map.of("clientCode", "Client Code")))
                        .with(admin()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("a column this sheet does not have is 422 import-unknown-column")
    void unknownColumnIsUnprocessable() throws Exception {
        UUID uploadId = stage(row(2, "Client Code", "ACME", "Name", "Acme"));

        mvc.perform(commit("clients", body(uploadId, Map.of(
                        "clientCode", "Client Code", "name", "Name", "phone", "Telephone")))
                        .with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://edutrack/errors/import-unknown-column"))
                .andExpect(jsonPath("$.unknownColumns[0]").value("Telephone"));
    }

    @Test
    @DisplayName("a field the schema does not declare reuses B-033's import-unknown-field")
    void unknownFieldIsUnprocessable() throws Exception {
        UUID uploadId = stage(row(2, "Client Code", "ACME", "Name", "Acme"));

        mvc.perform(commit("clients", body(uploadId, Map.of(
                        "clientCode", "Client Code", "name", "Name", "accountManager", "Name")))
                        .with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://edutrack/errors/import-unknown-field"));
    }

    // ── the two this route adds ─────────────────────────────────────────────

    @Test
    @DisplayName("a file with nothing writable is 422 import-nothing-to-commit, not an empty run")
    void nothingToCommit() throws Exception {
        // Every row rejected, so the engine never probes for existence and this
        // reaches a genuine refusal with no connection — which is also the file
        // this refusal exists for.
        UUID uploadId = stage(
                row(2, "Name", "No code at all"),
                row(3, "Client Code", "NOT A VALID CODE AT ALL", "Name", "Too long"));

        mvc.perform(commit("clients", body(uploadId, mapping())).with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://edutrack/errors/import-nothing-to-commit"))
                .andExpect(jsonPath("$.title").value("Nothing to import"))
                // The counts, so the screen says why without re-running the dry
                // run it just ran.
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.rejected").value(2))
                .andExpect(jsonPath("$.duplicates").value(0));
    }

    @Test
    @DisplayName("skipRejected:false over a file with bad rows is its own type, not the same one")
    void allOrNothingHasItsOwnType() throws Exception {
        // Two conditions that both mean "this file has bad rows" and have
        // opposite remedies: there is nothing to import, versus there is plenty
        // and you asked for all-or-nothing. One type would put an "import the
        // valid rows only" offer on a screen where there are none.
        UUID uploadId = stage(row(2, "Name", "No code at all"));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("uploadId", uploadId.toString());
        request.put("mapping", mapping());
        request.put("skipRejected", false);

        mvc.perform(commit("clients", json(request)).with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://edutrack/errors/import-rejected-rows-present"))
                .andExpect(jsonPath("$.rejected").value(1));
    }

    // ── the shape of the request ────────────────────────────────────────────

    @Test
    @DisplayName("a body with no mapping is a 400 from bean validation, not a 422")
    void anEmptyMappingIsABadRequest() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("uploadId", UUID.randomUUID().toString());
        request.put("mapping", Map.of());

        mvc.perform(commit("clients", json(request)).with(admin()))
                .andExpect(status().isBadRequest());
    }

    /**
     * The same 404 the other five routes on this path answer. <b>Repointed by
     * B-038</b>, like its siblings — see {@code ImportTemplateControllerTest}.
     */
    @Test
    void anUnregisteredSchemaIsNotFound() throws Exception {
        mvc.perform(commit("widgets", body(UUID.randomUUID(), mapping())).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unknown import schema"));
    }

    @Test
    void aDeveloperIsRefused() throws Exception {
        // 403 and not 404: the capability is decided before the uploadId in the
        // body is resolved, so there is no row whose existence a 404 could
        // protect. Recorded in check-conventions.py's ROWLESS_403.
        mvc.perform(commit("clients", body(UUID.randomUUID(), mapping()))
                        .with(authentication(TestPrincipals.of(authorities, "DEVELOPER"))))
                .andExpect(status().isForbidden());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private UUID stage(StagedRow... rows) {
        StagedUpload upload = new StagedUpload(UUID.randomUUID(), "clients.xlsx",
                List.of("Clients"), "Clients", List.of("Client Code", "Name"),
                List.of(rows), Instant.now());
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

    private static Map<String, String> mapping() {
        return Map.of("clientCode", "Client Code", "name", "Name");
    }

    private static String body(UUID uploadId, Map<String, String> mapping) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("uploadId", uploadId.toString());
        request.put("mapping", mapping);
        return json(request);
    }

    /** Hand-rolled, like the validate route's — every value here is a string or a boolean. */
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
            } else if (value instanceof Boolean flag) {
                out.append(flag);
            } else {
                out.append('"').append(value).append('"');
            }
        });
        return out.append('}').toString();
    }

    private static MockHttpServletRequestBuilder commit(String schema, String body) {
        return post("/api/v1/imports/" + schema + "/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return authentication(TestPrincipals.of(authorities, "ADMIN"));
    }
}
