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
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B-033 · {@code GET /imports/{schema}/fields} — the route step 3 reads its own
 * half of the mapping from.
 *
 * <p>No database. The context is built the way {@code ImportTemplateControllerTest}
 * builds it, because a schema's column list is a property of a Java class and
 * reaches no table.
 *
 * <p><b>These assertions are deliberately about the real client registration
 * rather than a fixture.</b> The whole reason this route exists is that the
 * mapping screen must not carry its own copy of the field list, and a test
 * against a stub schema would prove the projection works while saying nothing
 * about whether it describes what the template actually hands out.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class ImportSchemaFieldsControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtAuthoritiesConverter authorities;

    /**
     * The natural key is first, required, and flagged as the natural key.
     *
     * <p>All three matter to the screen for different reasons: template order so
     * the rows read the way the workbook does, {@code required} because it is what
     * blocks Next, and {@code naturalKey} because "rows will be matched on this
     * column" is a different warning from "this column cannot be blank".
     */
    @Test
    @DisplayName("the client schema describes its columns in template order")
    void describesTheClientSchema() throws Exception {
        mvc.perform(get("/api/v1/imports/clients/fields").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schema").value("clients"))
                .andExpect(jsonPath("$.data.entity").value("CLIENT"))
                .andExpect(jsonPath("$.data.naturalKey").value("clientCode"))
                .andExpect(jsonPath("$.data.fields[0].name").value("clientCode"))
                .andExpect(jsonPath("$.data.fields[0].header").value("Client Code"))
                .andExpect(jsonPath("$.data.fields[0].required").value(true))
                .andExpect(jsonPath("$.data.fields[0].naturalKey").value(true))
                .andExpect(jsonPath("$.data.fields[0].maxLength").value(20));
    }

    /**
     * The headers here are the headers the template writes, and that is the
     * assertion worth keeping.
     *
     * <p>B-031 made the template's header row exactly {@code ImportField#header()}
     * — undecorated, so a file exported from this product auto-matches when it is
     * uploaded back. If a header ever gains an asterisk, this route starts telling
     * the mapping screen to display one, and step 3 shows the user a column name
     * that is in no file they have.
     */
    @Test
    @DisplayName("the headers are the template's own, undecorated")
    void headersAreTheTemplateHeaders() throws Exception {
        mvc.perform(get("/api/v1/imports/clients/fields").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fields[?(@.name == 'name')].header").value("Name"))
                .andExpect(jsonPath("$.data.fields[?(@.name == 'name')].required").value(true))
                .andExpect(jsonPath(
                        "$.data.fields[?(@.name == 'primaryEmail')].header").value("Primary Email"))
                .andExpect(jsonPath(
                        "$.data.fields[?(@.name == 'primaryEmail')].required").value(false));
    }

    /**
     * {@code allowedValues} is the same list the template's dropdown is built
     * from, which is what makes it safe for step 3 to show it.
     *
     * <p>One declaration feeds both, so the values the mapping screen names are
     * the values the dry run accepts. Held as two lists they drift the first time
     * somebody adds a support plan — and the drift would surface as a row rejected
     * for a value the screen said was fine.
     */
    @Test
    @DisplayName("an ENUM column carries its domain, the same list the template's dropdown uses")
    void enumColumnsCarryTheirDomain() throws Exception {
        mvc.perform(get("/api/v1/imports/clients/fields").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fields[?(@.name == 'supportPlan')].type")
                        .value("ENUM"))
                .andExpect(jsonPath("$.data.fields[?(@.name == 'supportPlan')].allowedValues[*]")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                "BASIC", "STANDARD", "PREMIUM")));
    }

    /**
     * No validators on the wire, and that is the point of projecting
     * {@link ImportField} rather than serialising it.
     *
     * <p>They are lambdas with no wire representation, and a schema definition
     * serialised whole is how an internal type becomes a public contract by
     * accident — the next person to add a field to {@code ImportField} would be
     * changing the API without knowing it.
     */
    @Test
    @DisplayName("validators are not on the wire — this is a projection, not the record")
    void validatorsAreNotSerialised() throws Exception {
        mvc.perform(get("/api/v1/imports/clients/fields").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fields[0].validators").doesNotExist());
    }

    /**
     * The same 404 the template and upload routes answer, and for the same reason:
     * {@code users} is declared in the contract's enum and not registered until
     * B-038. <b>Delete this when that registration lands</b> — like the assertion
     * in {@code ImportTemplateControllerTest}, its failing is the right amount of
     * ceremony for "the second schema now exists".
     */
    @Test
    void anUnregisteredSchemaIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/imports/users/fields").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unknown import schema"));
    }

    /**
     * 403, and rowless — a column list is not a row and contains nothing of the
     * organisation's. Refused anyway because the only screen that reads it is
     * inside §7.4's Admin-only module; see {@code ImportController}'s javadoc for
     * the counter-argument, which is a real one.
     */
    @Test
    void aDeveloperIsRefused() throws Exception {
        mvc.perform(get("/api/v1/imports/clients/fields")
                        .with(authentication(TestPrincipals.of(authorities, "DEVELOPER"))))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return authentication(TestPrincipals.of(authorities, "ADMIN"));
    }
}
