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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B-033 · the three preset routes over HTTP.
 *
 * <p>The repository is a {@link MockitoBean}, so this runs in surefire with no
 * container. What it asserts is the layer a database cannot: the response shape,
 * the status each refusal arrives as, and that the schema is resolved before a
 * query is attempted. The SQL — the upsert on {@code (schema_key, name)} and the
 * schema-scoped delete — is {@code ImportMappingPresetIT}'s, against real MySQL,
 * because those are properties of a unique index rather than of Java.
 *
 * <p><b>Against the real client registration</b>, unlike
 * {@code ImportMappingPresetServiceTest}. The service test proves the rules hold
 * for a schema the engine has never heard of; this one proves the route the
 * screen actually calls answers for the schema the screen actually names.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class ImportMappingPresetControllerTest {

    private static final Instant T0 = Instant.parse("2026-08-17T09:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtAuthoritiesConverter authorities;

    @MockitoBean
    ImportMappingPresetRepository presets;

    @Test
    @DisplayName("the saved presets are listed for the picker")
    void listsPresets() throws Exception {
        when(presets.findAll("clients")).thenReturn(List.of(
                new ImportDtos.MappingPreset(3L, "CRM export",
                        Map.of("clientCode", "Account Ref", "name", "Account Name"), T0)));

        mvc.perform(get("/api/v1/imports/clients/mapping-presets").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].presetId").value(3))
                .andExpect(jsonPath("$.data[0].name").value("CRM export"))
                .andExpect(jsonPath("$.data[0].mapping.clientCode").value("Account Ref"))
                // Org-wide, so nobody's name is beside it — see ImportDtos.MappingPreset.
                .andExpect(jsonPath("$.data[0].createdBy").doesNotExist());
    }

    /**
     * <b>200, not 201.</b> Saving is an upsert on {@code (schema, name)}, so the
     * status follows the behaviour rather than the verb — which is also why the
     * operation declares no {@code Idempotency-Key} and why CONVENTIONS.md §4 does
     * not ask it to.
     */
    @Test
    @DisplayName("saving answers 200, because it is an upsert and not a create")
    void savingIsAnUpsert() throws Exception {
        when(presets.save(anyString(), anyString(), any(), any()))
                .thenAnswer(call -> new ImportDtos.MappingPreset(
                        9L, call.getArgument(1), call.getArgument(2), T0));

        mvc.perform(post("/api/v1/imports/clients/mapping-presets")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"CRM export","mapping":{"clientCode":"Account Ref"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.presetId").value(9))
                .andExpect(jsonPath("$.data.name").value("CRM export"))
                .andExpect(jsonPath("$.data.mapping.clientCode").value("Account Ref"));
    }

    /**
     * 422, and the body names both lists.
     *
     * <p>The realistic cause is a preset built against an older registration
     * rather than a typo, so the screen needs to be able to say which entries it
     * would have to drop — not ask the user to compare two column lists by eye.
     */
    @Test
    @DisplayName("a mapping naming an unknown field is 422, listing what the schema does have")
    void anUnknownFieldIs422() throws Exception {
        mvc.perform(post("/api/v1/imports/clients/mapping-presets")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Old","mapping":{"clientCode":"Code","faxNumber":"Fax"}}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://edutrack/errors/import-unknown-field"))
                .andExpect(jsonPath("$.unknownFields[0]").value("faxNumber"))
                .andExpect(jsonPath("$.fields").isArray());

        verify(presets, never()).save(anyString(), anyString(), any(), any());
    }

    /**
     * 400, and the same {@code type} every other form in the product answers with:
     * this is {@code @NotEmpty}'s failure reached by a path the annotation cannot
     * see, not a new kind of refusal.
     */
    @Test
    @DisplayName("a mapping whose every column is blank is 400, not a preset that maps nothing")
    void aMappingThatMapsNothingIs400() throws Exception {
        mvc.perform(post("/api/v1/imports/clients/mapping-presets")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Empty","mapping":{"clientCode":"","name":"  "}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("https://edutrack/errors/validation-failed"))
                .andExpect(jsonPath("$.errors.mapping").exists());

        verify(presets, never()).save(anyString(), anyString(), any(), any());
    }

    /** {@code @NotBlank} on the name, so a preset nobody can pick out of a list is refused. */
    @Test
    void aBlankNameIsRefused() throws Exception {
        mvc.perform(post("/api/v1/imports/clients/mapping-presets")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  ","mapping":{"clientCode":"Code"}}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletingAPresetIsNoContent() throws Exception {
        when(presets.delete("clients", 3L)).thenReturn(true);

        mvc.perform(delete("/api/v1/imports/clients/mapping-presets/3")
                        .with(admin()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    /**
     * A delete that removed nothing is a 404 carrying the id.
     *
     * <p>The ordinary case is a preset another Admin removed between this screen's
     * list read and this click, and the picker has to know to drop the entry — a
     * cheerful 204 would leave it on screen until the next reload.
     */
    @Test
    @DisplayName("deleting a preset that is not there is 404 with the id, so the picker can drop it")
    void deletingAMissingPresetIs404() throws Exception {
        when(presets.delete(anyString(), anyLong())).thenReturn(false);

        mvc.perform(delete("/api/v1/imports/clients/mapping-presets/404")
                        .with(admin()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Mapping preset not found"))
                .andExpect(jsonPath("$.presetId").value(404));
    }

    /**
     * The same 404 as the other three routes on this path, and it fires before a
     * query. <b>Delete this when B-038 registers {@code users}</b>, like the
     * matching assertions in {@code ImportTemplateControllerTest} and
     * {@code ImportSchemaFieldsControllerTest}.
     */
    @Test
    void anUnregisteredSchemaIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/imports/users/mapping-presets").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unknown import schema"));

        verify(presets, never()).findAll(anyString());
    }

    /**
     * 403 on all three verbs. Rowless, because {@code master.write} is decided
     * before the id is looked up — so a Developer cannot tell a real preset id
     * from an invented one either way, which is what makes the 403 leak nothing a
     * 404 would have hidden.
     */
    @Test
    void aDeveloperIsRefusedOnEveryVerb() throws Exception {
        RequestPostProcessor developer =
                authentication(TestPrincipals.of(authorities, "DEVELOPER"));

        mvc.perform(get("/api/v1/imports/clients/mapping-presets").with(developer))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/imports/clients/mapping-presets")
                        .with(developer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"CRM","mapping":{"clientCode":"Code"}}"""))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/imports/clients/mapping-presets/3")
                        .with(developer).with(csrf()))
                .andExpect(status().isForbidden());
    }

    private RequestPostProcessor admin() {
        return authentication(TestPrincipals.of(authorities, "ADMIN"));
    }
}
