package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.api.security.TestPrincipals;
import com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter;
import com.edunext.edutrack.domain.imports.ImportBatch;
import com.edunext.edutrack.domain.imports.ImportBatchRepository;
import com.edunext.edutrack.domain.imports.ImportBatchStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B-036 · {@code GET /import-batches/{batchId}/error-report} over HTTP.
 *
 * <p>{@link ImportErrorReportServiceTest} owns the rules; this owns the wire —
 * the media type Excel needs, the {@code Content-Disposition} name the browser
 * saves under, and the status each refusal answers with.
 *
 * <p><b>The store is replaced with the in-memory one rather than mocked away.</b>
 * The route's whole job is to move bytes from the object store to the response,
 * so a mock returning {@code Optional.of(new byte[0])} would assert the plumbing
 * against itself. There is no MinIO Testcontainer in this project — C-025's
 * attachment tests make the same substitution for the same reason.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class ImportErrorReportControllerTest {

    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @TestConfiguration
    static class Storage {

        /**
         * {@code @Primary} rather than a same-named override, which Spring Boot
         * refuses by default and rightly: a bean silently replacing another by
         * name is how a test comes to exercise something the application does not
         * have. This is a second bean that wins the injection, and
         * {@code ImportReportStoreConfig}'s S3-backed one is still there —
         * unused, and building it contacts nothing.
         */
        @Bean
        @Primary
        ImportReportStore inMemoryImportReportStore() {
            return new InMemoryImportReportStore();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtAuthoritiesConverter authorities;

    @Autowired
    ImportReportStore store;

    @MockitoBean
    ImportBatchRepository batches;

    @Test
    @DisplayName("the workbook comes back as .xlsx, named for the run it describes")
    void servesTheStoredWorkbook() throws Exception {
        byte[] workbook = {'P', 'K', 3, 4};
        String key = store.put(412, "CLIENT", workbook);
        when(batches.findById(412L))
                .thenReturn(Optional.of(batch(412, ImportBatchStatus.COMPLETED, key)));

        byte[] served = mvc.perform(get("/api/v1/import-batches/412/error-report").with(admin()))
                .andExpect(status().isOk())
                // The contract's media type, not octet-stream: a workbook served
                // as a generic download is one Excel opens with a warning.
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, XLSX))
                // Named per run — the whole reason the client reads this header
                // instead of composing a name of its own.
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"clients-import-errors-412.xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(served).isEqualTo(workbook);
    }

    @Test
    @DisplayName("a batch id that names no run is a 404, like the poll beside it")
    void unknownBatchIsNotFound() throws Exception {
        when(batches.findById(anyLong())).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/import-batches/999/error-report").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Import batch not found"))
                .andExpect(jsonPath("$.batchId").value(999));
    }

    /**
     * The refusal a bookmark hits, and the one the screen is built to avoid
     * reaching: a run that is real and has no report.
     *
     * <p>The status is on the body because it is what makes the sentence honest.
     * {@code RUNNING} means "not yet"; {@code COMPLETED} means "there is none".
     */
    @Test
    @DisplayName("a run with no report is a 404 that says which kind of nothing")
    void aRunWithNoReportIsNotFound() throws Exception {
        when(batches.findById(412L))
                .thenReturn(Optional.of(batch(412, ImportBatchStatus.RUNNING, null)));

        mvc.perform(get("/api/v1/import-batches/412/error-report").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Import error report not available"))
                .andExpect(jsonPath("$.batchId").value(412))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    /**
     * 403, and it is a rowless one — {@code master.write} is decided before the
     * batch id is looked up, so a Developer cannot tell a real run from an
     * invented one either way.
     *
     * <p>This is also the reason the report is served through the API rather than
     * as a presigned object-store URL: the file is a verbatim extract of the
     * client master, and this check happens when the bytes are read rather than
     * when a link was minted.
     */
    @Test
    @DisplayName("a Developer is refused, and the refusal does not depend on the row")
    void aDeveloperIsRefused() throws Exception {
        RequestPostProcessor developer =
                authentication(TestPrincipals.of(authorities, "DEVELOPER"));

        mvc.perform(get("/api/v1/import-batches/412/error-report").with(developer))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/import-batches/999999/error-report").with(developer))
                .andExpect(status().isForbidden());
    }

    private static ImportBatch batch(long id, ImportBatchStatus status, String reportKey) {
        ImportBatch batch = new ImportBatch();
        batch.setId(id);
        batch.setEntity("CLIENT");
        batch.setStatus(status);
        batch.setErrorReportKey(reportKey);
        return batch;
    }

    private RequestPostProcessor admin() {
        return authentication(TestPrincipals.of(authorities, "ADMIN"));
    }
}
