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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B-032 · the route — blueprint §4B.3 step 2, end to end over HTTP.
 *
 * <p>Uses {@code .csv} fixtures throughout, and not to avoid the workbook path:
 * {@code XlsxSheetReaderTest} covers that in depth against real POI, and building
 * a workbook per case here would make these tests about the fixture rather than
 * about the route. What this class asserts is the layer above the reader — the
 * response shape, and that each refusal arrives as the status and the problem
 * document the contract promises.
 *
 * <p>No database. The context is built the way {@code ImportTemplateControllerTest}
 * builds it, so it runs in surefire wherever Maven does — step 2 reads no row,
 * which is the guarantee §4B.3 makes about everything before the commit.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class ImportUploadControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtAuthoritiesConverter authorities;

    /**
     * The response carries the columns, not the rows.
     *
     * <p>A 5,000-row file would be megabytes of JSON that the mapping screen has
     * no use for — B-033 maps columns. The rows stay staged and are next read by
     * the dry run, which is the step that looks at them.
     */
    @Test
    @DisplayName("a client file uploads, and the response describes the columns rather than the data")
    void uploadsAClientFile() throws Exception {
        mvc.perform(multipart("/api/v1/imports/clients/upload")
                        .file(csv("clients.csv", """
                                Client Code,Name,Status
                                ACME,Acme Corporation,ACTIVE
                                NORTHWIND,Northwind Traders,ACTIVE
                                """))
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uploadId").isNotEmpty())
                .andExpect(jsonPath("$.data.fileName").value("clients.csv"))
                .andExpect(jsonPath("$.data.sheet").value("clients"))
                .andExpect(jsonPath("$.data.sheets[0]").value("clients"))
                .andExpect(jsonPath("$.data.rowCount").value(2))
                .andExpect(jsonPath("$.data.headers[0]").value("Client Code"))
                // The auto-match, against the real client registration: these are
                // ClientImportSchema's own field names, so this failing means the
                // headings the template hands out have stopped matching the
                // schema behind it.
                .andExpect(jsonPath("$.data.suggestedMapping.clientCode").value("Client Code"))
                .andExpect(jsonPath("$.data.suggestedMapping.status").value("Status"))
                .andExpect(jsonPath("$.data.rows").doesNotExist());
    }

    /**
     * §4B.3 lists {@code .xls} and this refuses it — see
     * {@link UnsupportedImportFileException} for why. 415 rather than 422,
     * because the request is well-formed and the content is presumably fine: we
     * simply do not read this kind of file, and the fix is one Save As away.
     */
    @Test
    @DisplayName("a legacy .xls is 415, with the instruction the user needs")
    void legacyXlsIsUnsupportedMediaType() throws Exception {
        mvc.perform(multipart("/api/v1/imports/clients/upload")
                        .file(csv("clients.xls", "Client Code\nACME\n"))
                        .with(admin()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/import-unsupported-file"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Save As")))
                .andExpect(jsonPath("$.extension").value("xls"))
                .andExpect(jsonPath("$.accepted[0]").value("xlsx"));
    }

    @Test
    @DisplayName("the right extension over the wrong bytes is 422, not 415")
    void unreadableContentIsUnprocessable() throws Exception {
        mvc.perform(multipart("/api/v1/imports/clients/upload")
                        .file(csv("clients.xlsx", "%PDF-1.7 not a spreadsheet"))
                        .with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/import-unreadable-file"));
    }

    /**
     * The sheet the selector asked for is gone — usually because the user changed
     * sheets and then changed file. The body lists the sheets the new file does
     * have, which turns a dead end into a correction.
     */
    @Test
    void anUnknownSheetIsUnprocessableAndListsTheRealOnes() throws Exception {
        mvc.perform(multipart("/api/v1/imports/clients/upload")
                        .file(csv("clients.csv", "Client Code\nACME\n"))
                        .param("sheet", "Archive")
                        .with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.sheets[0]").value("clients"));
    }

    @Test
    @DisplayName("a sheet with no heading row is refused rather than staged as an empty upload")
    void aSheetWithNoHeadingRowIsRefused() throws Exception {
        mvc.perform(multipart("/api/v1/imports/clients/upload")
                        .file(csv("clients.csv", "\n\n"))
                        .with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("no heading row")));
    }

    /**
     * The same 404 the template route gives, from the same handler:
     * {@code schema} is a path segment, so an unregistered key makes the resource
     * absent. {@code users} is the live case until B-038 registers it.
     */
    @Test
    void anUnregisteredSchemaIsNotFound() throws Exception {
        mvc.perform(multipart("/api/v1/imports/users/upload")
                        .file(csv("users.csv", "Email\nsomebody@example.test\n"))
                        .with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unknown import schema"));
    }

    /**
     * 403, and rowless: the decision is taken before the body is looked at, so
     * there is no existence a 404 could be protecting. Asserted here for the one
     * role with a plausible-sounding case — a Developer loading a colleague's
     * spreadsheet — so that widening the rule has to delete a test that says why.
     * {@code PermissionMatrixTest} covers all six from an independent expectation.
     */
    @Test
    void aDeveloperIsRefused() throws Exception {
        mvc.perform(multipart("/api/v1/imports/clients/upload")
                        .file(csv("clients.csv", "Client Code\nACME\n"))
                        .with(authentication(TestPrincipals.of(authorities, "DEVELOPER"))))
                .andExpect(status().isForbidden());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private RequestPostProcessor admin() {
        return authentication(TestPrincipals.of(authorities, "ADMIN"));
    }

    private static MockMultipartFile csv(String fileName, String content) {
        return new MockMultipartFile("file", fileName, "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }
}
