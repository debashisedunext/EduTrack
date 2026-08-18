package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.api.security.TestPrincipals;
import com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter;
import com.edunext.edutrack.domain.clients.ClientRepository;
import com.edunext.edutrack.domain.imports.ImportBatch;
import com.edunext.edutrack.domain.imports.ImportBatchStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B-037 · {@code POST /import-batches/{batchId}/reverse} over HTTP.
 *
 * <p>{@link ImportReversalServiceTest} owns the rules; this owns the wire — the
 * status each refusal answers with, the {@code type} the screen branches on, and
 * the fact that a role without {@code master.write} cannot reach the one route in
 * the product that deletes rows from the client master.
 *
 * <p>The registration here is {@code ClientImportSchema}, unlike the service
 * test's — this is a full context, and reversing a {@code CLIENT} batch is what
 * the wiring actually has to do. Its repository is mocked, so nothing is
 * deleted; {@code ClientImportReversalIT} is where real rows go.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class ImportReverseControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtAuthoritiesConverter authorities;

    /**
     * <b>The batch store is mocked at the service, not at the repository.</b>
     *
     * <p>A reversal <em>writes</em> — {@code markReversed} stamps four columns —
     * and that write is {@code @Transactional} for good reason, so a repository
     * mock would still have Spring open an {@code EntityManager} and put a live
     * MySQL between this route and any test of it. Mocking one level up leaves
     * the controller, {@code ImportReversalService}, the registry and the client
     * registration all real, which is the wiring this test is about.
     *
     * <p>{@code ClientRepository} is mocked for the same reason one step further
     * in: {@code ClientImportSchema.reverse} asks it what the run created, and
     * an empty answer is the right one here. What a reversal actually deletes is
     * {@code ClientImportReversalIT}'s, against a real database, because it is a
     * property of foreign keys rather than of Java.
     */
    @MockitoBean
    ImportBatchService batches;

    @MockitoBean
    ClientRepository clients;

    @Test
    @DisplayName("a finished run reverses, and the response carries what was not reverted")
    void reversesAFinishedRun() throws Exception {
        ImportBatch batch = batch(412, ImportBatchStatus.COMPLETED);
        batch.setCreatedRows(0);
        batch.setUpdatedRows(400);
        when(batches.load(412L)).thenReturn(batch);
        when(clients.findByImportBatchId(412L)).thenReturn(List.of());
        when(batches.find(412L)).thenReturn(view(412));

        mvc.perform(post("/api/v1/import-batches/412/reverse").with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batch.batchId").value(412))
                .andExpect(jsonPath("$.data.deleted").isArray())
                // The honest half of the promise, on the wire rather than left for
                // a user to infer from a count that does not add up.
                .andExpect(jsonPath("$.data.updatedRowsNotReverted").value(400));
    }

    @Test
    @DisplayName("a batch id that names no run is a 404, like every other read on this path")
    void unknownBatchIsNotFound() throws Exception {
        when(batches.load(anyLong()))
                .thenThrow(new ImportBatchNotFoundException(999));

        mvc.perform(post("/api/v1/import-batches/999/reverse").with(admin()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.batchId").value(999));
    }

    /**
     * 422 with its own {@code type}, because the remedy is "wait" — and the
     * screen has to be able to say so rather than offering a retry that will
     * refuse again for the next several minutes.
     */
    @Test
    @DisplayName("a running import is refused with a type the screen can branch on")
    void runningImportIsRefused() throws Exception {
        when(batches.load(412L)).thenReturn(batch(412, ImportBatchStatus.RUNNING));

        mvc.perform(post("/api/v1/import-batches/412/reverse").with(admin()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/import-batch-not-finished"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.batchId").value(412));

        verify(batches, never()).markReversed(anyLong(), anyInt(), anyInt(), any());
    }

    /**
     * A different {@code type} from the one above, and it has to be: this one
     * never clears, so a shared type would put a Try again on a batch that will
     * refuse for ever.
     */
    @Test
    @DisplayName("a second reversal is refused with a different type, and says when the first was")
    void secondReversalIsRefused() throws Exception {
        ImportBatch batch = batch(412, ImportBatchStatus.COMPLETED);
        batch.setReversedAt(Instant.parse("2026-08-18T09:02:00Z"));
        when(batches.load(412L)).thenReturn(batch);

        mvc.perform(post("/api/v1/import-batches/412/reverse").with(admin()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://edutrack/errors/import-batch-already-reversed"))
                // On the body because a caller reaching this has almost always got
                // a stale panel open in another tab, and this is the sentence that
                // explains it without a re-read.
                .andExpect(jsonPath("$.reversedAt").exists());
    }

    /**
     * <b>The refusal that matters most on this route.</b> This is the only route
     * in the product that deletes rows from the client master, and the five
     * non-Admin roles cannot reach it — a rowless 403, decided before the batch
     * id is looked up, so a Developer cannot tell a real run from an invented one.
     */
    @Test
    @DisplayName("a Developer cannot reverse an import, real id or invented")
    void aDeveloperIsRefused() throws Exception {
        RequestPostProcessor developer =
                authentication(TestPrincipals.of(authorities, "DEVELOPER"));

        mvc.perform(post("/api/v1/import-batches/412/reverse").with(developer).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/import-batches/999999/reverse").with(developer).with(csrf()))
                .andExpect(status().isForbidden());

        verify(batches, never()).markReversed(anyLong(), anyInt(), anyInt(), any());
    }

    /** The re-read the reversal returns on its response — its content is the DTO test's. */
    private static ImportDtos.Batch view(long id) {
        return new ImportDtos.Batch(id, "CLIENT", "clients.xlsx", "COMPLETED",
                0, 0, 0, 400, 0, null,
                Instant.parse("2026-08-18T09:00:00Z"), null, null,
                Instant.parse("2026-08-18T09:02:00Z"), 0, 0, false);
    }

    private static ImportBatch batch(long id, ImportBatchStatus status) {
        ImportBatch batch = new ImportBatch();
        batch.setId(id);
        batch.setEntity("CLIENT");
        batch.setStatus(status);
        return batch;
    }

    private RequestPostProcessor admin() {
        return authentication(TestPrincipals.of(authorities, "ADMIN"));
    }
}
