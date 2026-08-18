package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.api.security.TestPrincipals;
import com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter;
import com.edunext.edutrack.domain.identity.User;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.imports.ImportBatch;
import com.edunext.edutrack.domain.imports.ImportBatchRepository;
import com.edunext.edutrack.domain.imports.ImportBatchStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B-037 · {@code GET /import-batches} over HTTP — the import history.
 *
 * <p>This is the route that makes blueprint §4B.3's "every import writes an
 * {@code import_batch} row so a bad import can be <b>identified</b>" true of
 * something a person can reach. Until it existed, a batch id was known only to
 * the browser tab that started the run.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class ImportBatchHistoryControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtAuthoritiesConverter authorities;

    @MockitoBean
    ImportBatchRepository batches;

    @MockitoBean
    UserRepository users;

    @Test
    @DisplayName("the runs come back with their provenance, and the cap is stated")
    void listsRuns() throws Exception {
        when(batches.findByEntityOrderByCreatedAtDesc(eq("CLIENT"), any(Pageable.class)))
                .thenReturn(List.of(batch(412, "clients-august.xlsx", 7L)));
        when(users.findAllById(any())).thenReturn(List.of(user(7L, "Anita Desai")));

        mvc.perform(get("/api/v1/import-batches").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entity").value("CLIENT"))
                .andExpect(jsonPath("$.data.batches[0].batchId").value(412))
                .andExpect(jsonPath("$.data.batches[0].fileName").value("clients-august.xlsx"))
                // Provenance: by whom. The other half — when — is `startedAt`, and
                // it is asserted in ClientImportReversalIT rather than here:
                // `created_at` is stamped by MySQL and mapped `insertable = false`,
                // so it is genuinely null on an entity this test constructed. A
                // fixture that forced a value in would be asserting the fixture.
                .andExpect(jsonPath("$.data.batches[0].importedByName").value("Anita Desai"))
                // The cap, on the response rather than applied silently: a bounded
                // answer that looks unbounded reads as "these are all of them".
                .andExpect(jsonPath("$.data.limit").value(ImportBatchService.HISTORY_LIMIT));
    }

    /**
     * Filtered by the <em>stored discriminator</em>, not by the URL segment a
     * schema is mounted at. The two names are kept apart deliberately, and
     * translating one into the other on a read is the collapse that separation
     * prevents.
     */
    @Test
    @DisplayName("the entity filter is the stored code, and it reaches the query")
    void filtersByStoredEntityCode() throws Exception {
        when(batches.findByEntityOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
                .thenReturn(List.of());

        mvc.perform(get("/api/v1/import-batches").param("entity", "RESOURCE").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entity").value("RESOURCE"));

        ArgumentCaptor<String> entity = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(batches)
                .findByEntityOrderByCreatedAtDesc(entity.capture(), page.capture());

        assertThat(entity.getValue()).isEqualTo("RESOURCE");
        // The limit is pushed into the query rather than applied after it. A read
        // of every row followed by a `subList` would be the same answer at a cost
        // that grows for ever, on a table that only grows.
        assertThat(page.getValue().getPageSize()).isEqualTo(ImportBatchService.HISTORY_LIMIT);
    }

    @Test
    @DisplayName("CLIENT is the default, because S-34 is the screen with this panel")
    void defaultsToClient() throws Exception {
        when(batches.findByEntityOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
                .thenReturn(List.of());

        mvc.perform(get("/api/v1/import-batches").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entity").value("CLIENT"));
    }

    /**
     * An account deleted since the import leaves the run unattributed, which is
     * the truth, rather than an "Unknown user" placeholder that looks like a
     * person.
     */
    @Test
    @DisplayName("a run whose importer no longer exists renders unattributed, not as a placeholder")
    void unresolvableActor() throws Exception {
        when(batches.findByEntityOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
                .thenReturn(List.of(batch(412, "clients.xlsx", 999L)));
        when(users.findAllById(any())).thenReturn(List.of());

        mvc.perform(get("/api/v1/import-batches").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batches[0].importedBy").value(999))
                .andExpect(jsonPath("$.data.batches[0].importedByName").doesNotExist());
    }

    /**
     * The server decides whether the Reverse button is enabled — see
     * {@code ImportBatch.reversible} in the contract. A client re-deriving it
     * from {@code status} and {@code reversedAt} is a second copy of the
     * service's refusals, on a screen whose job is deleting rows.
     */
    @Test
    @DisplayName("a finished, un-reversed run is reversible; a reversed one is not")
    void reversibleIsTheServersAnswer() throws Exception {
        ImportBatch reversed = batch(1, "old.xlsx", 7L);
        reversed.setReversedAt(Instant.parse("2026-08-11T07:02:00Z"));
        reversed.setReversedRows(40);
        reversed.setRetainedRows(2);

        when(batches.findByEntityOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
                .thenReturn(List.of(reversed, batch(2, "new.xlsx", 7L)));
        when(users.findAllById(any())).thenReturn(List.of(user(7L, "Anita Desai")));

        mvc.perform(get("/api/v1/import-batches").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batches[0].reversible").value(false))
                .andExpect(jsonPath("$.data.batches[0].reversedRows").value(40))
                .andExpect(jsonPath("$.data.batches[0].retainedRows").value(2))
                .andExpect(jsonPath("$.data.batches[1].reversible").value(true));
    }

    /**
     * A run still in flight is not reversible, and the flag says so before any
     * button is drawn.
     */
    @Test
    @DisplayName("a running import is not reversible")
    void runningIsNotReversible() throws Exception {
        ImportBatch running = batch(412, "clients.xlsx", 7L);
        running.setStatus(ImportBatchStatus.RUNNING);

        when(batches.findByEntityOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
                .thenReturn(List.of(running));
        when(users.findAllById(any())).thenReturn(List.of(user(7L, "Anita Desai")));

        mvc.perform(get("/api/v1/import-batches").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batches[0].reversible").value(false));
    }

    /**
     * A rowless 403 — the capability is decided before any row is read, so it
     * conceals nothing a 404 would have.
     */
    @Test
    @DisplayName("a Developer is refused the history")
    void aDeveloperIsRefused() throws Exception {
        RequestPostProcessor developer =
                authentication(TestPrincipals.of(authorities, "DEVELOPER"));

        mvc.perform(get("/api/v1/import-batches").with(developer))
                .andExpect(status().isForbidden());
    }

    private static ImportBatch batch(long id, String fileName, Long importedBy) {
        ImportBatch batch = new ImportBatch();
        batch.setId(id);
        batch.setEntity("CLIENT");
        batch.setFileName(fileName);
        batch.setStatus(ImportBatchStatus.COMPLETED);
        batch.setTotalRows(31);
        batch.setCreatedRows(24);
        batch.setUpdatedRows(4);
        batch.setRejectedRows(3);
        batch.setImportedBy(importedBy);
        return batch;
    }

    private static User user(long id, String fullName) {
        User user = new User();
        user.setId(id);
        user.setFullName(fullName);
        user.setUsername("anita");
        return user;
    }

    private RequestPostProcessor admin() {
        return authentication(TestPrincipals.of(authorities, "ADMIN"));
    }
}
