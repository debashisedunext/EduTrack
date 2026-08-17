package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.api.security.TestPrincipals;
import com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B-031 · the route, and blueprint §4B.3's promises against the real client
 * registration.
 *
 * <p>{@code ImportTemplateWriterTest} asserts the writer's behaviour for a
 * schema it invents. This asserts the two things §4B.3 promises about
 * <em>clients</em> specifically — "a data-validation dropdown on Status and
 * Support Plan, and one filled example row" — because those are claims about
 * {@code ClientImportSchema}'s declarations rather than about the writer, and a
 * writer test written against the client master would break every time somebody
 * added a column to it.
 *
 * <p>No database: the context is built the way {@code RouteAuthorizationTest}
 * builds it, so this runs in surefire everywhere rather than only where Docker
 * is. Nothing on this path reads a row — a template is a function of the
 * registration, which is a class.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class ImportTemplateControllerTest {

    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtAuthoritiesConverter authorities;

    @Test
    @DisplayName("an Admin downloads a workbook, named after the schema")
    void adminDownloadsTheTemplate() throws Exception {
        mvc.perform(get("/api/v1/imports/clients/template").with(admin()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, XLSX))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"clients-import-template.xlsx\""));
    }

    /**
     * §4B.3, quoted: "a data-validation dropdown on Status and Support Plan".
     *
     * <p>Asserted by the values rather than by counting dropdowns, because the
     * values are the half that matters — they are the same list
     * {@link ImportValidationEngine} checks an uploaded cell against, so this
     * failing means the template has started offering something the import would
     * reject.
     */
    @Test
    @DisplayName("Status and Support Plan carry dropdowns of the values the import accepts")
    void statusAndSupportPlanHaveDropdowns() throws Exception {
        try (XSSFWorkbook workbook = download()) {
            List<List<String>> lists = workbook.getSheetAt(0).getDataValidations().stream()
                    .map(DataValidation::getValidationConstraint)
                    .map(c -> List.of(c.getExplicitListValues()))
                    .toList();

            assertThat(lists).containsExactlyInAnyOrder(
                    List.of("ACTIVE", "INACTIVE"),
                    List.of("BASIC", "STANDARD", "PREMIUM"));
        }
    }

    /** §4B.3, quoted: "one filled example row". */
    @Test
    @DisplayName("row 2 is a filled example — a template of bare headers rejects far more rows")
    void theExampleRowIsFilledIn() throws Exception {
        try (XSSFWorkbook workbook = download()) {
            Sheet sheet = workbook.getSheetAt(0);

            assertThat(cells(sheet.getRow(0)).subList(0, 2))
                    .containsExactly("Client Code", "Name");
            assertThat(cells(sheet.getRow(1)).subList(0, 2))
                    .containsExactly("ACME", "Acme Corporation");
        }
    }

    /**
     * The registered key is a path segment, so an unregistered one makes the
     * resource absent rather than the request malformed.
     *
     * <p>{@code users} is the live case and is deliberately the one asserted:
     * the contract declares it, the generated client can already call it, and
     * B-038's registration does not exist yet. The day that {@code @Component}
     * lands this test fails and is deleted, which is the correct amount of
     * ceremony for "the second schema now exists".
     *
     * <p>The content type is asserted too, and not as decoration. The handler
     * declares {@code produces} an .xlsx type; a problem document answered
     * without an explicit type is exactly where that mapping could turn a 404
     * into a 406 the frontend cannot read.
     */
    @Test
    @DisplayName("an unregistered schema is 404 with a problem document, not 406 or 400")
    void unregisteredSchemaIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/imports/users/template").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.title").value("Unknown import schema"))
                .andExpect(jsonPath("$.schema").value("users"));
    }

    /**
     * 403 and not 404: a blank template is not a row, so there is no existence
     * for CLAUDE.md's no-leak rule to protect. Recorded in
     * {@code check-conventions.py}'s {@code ROWLESS_403}.
     *
     * <p>{@code PermissionMatrixTest} asserts this for all six roles from an
     * independently authored expectation. Repeated here for the one role most
     * likely to have a plausible-sounding case made for it — a Developer who
     * "just wants the column list" — so that widening the rule has to delete a
     * test that says why.
     */
    @Test
    @DisplayName("a Developer is refused, because S-34 is an Admin-only screen")
    void developersAreRefused() throws Exception {
        mvc.perform(get("/api/v1/imports/clients/template")
                        .with(authentication(TestPrincipals.of(authorities, "DEVELOPER"))))
                .andExpect(status().isForbidden());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return authentication(TestPrincipals.of(authorities, "ADMIN"));
    }

    private XSSFWorkbook download() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/imports/clients/template").with(admin()))
                .andExpect(status().isOk())
                .andReturn();

        return new XSSFWorkbook(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()));
    }

    private static List<String> cells(Row row) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < row.getLastCellNum(); i++) {
            values.add(row.getCell(i) == null ? "" : row.getCell(i).getStringCellValue());
        }
        return values;
    }
}
