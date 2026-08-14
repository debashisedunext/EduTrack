package com.edunext.edutrack.api.feature.masters.projects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B-019 · that the {@code PUT} body's rules are enforced, and — the half worth
 * writing — that <b>an empty list is not one of the things refused</b>.
 *
 * <p>{@code allowedTaskTypeIds: []} is the request an administrator makes by
 * unticking the last checkbox, and it removes the restriction rather than
 * forbidding everything. A {@code @NotEmpty} added here in the belief that an
 * empty allow-list must be a mistake would make the restriction impossible to
 * remove through the only screen that can set it — so the test that it is
 * forwarded is a test of a decision, not of a framework.
 *
 * <p>The mirror matters too: all three fields are {@code @NotNull}, because this
 * is a wholesale replace and an omitted field on a replace is ambiguous between
 * "leave it alone" and "clear it" in exactly the way that loses somebody's
 * setting quietly.
 */
class ProjectSettingsBodyValidationTest {

    private static final String PATH = "/api/v1/projects/7/settings";

    private ProjectSettingsService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ProjectSettingsService.class);
        when(service.settings(anyLong())).thenReturn(settings());
        when(service.replace(anyLong(), any())).thenReturn(settings());

        // Standalone, with a real validator wired the way Spring Boot wires
        // one. No security, so the guard is not in the path — that is
        // PermissionMatrix's question and this is only about the body.
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mvc = MockMvcBuilders.standaloneSetup(new ProjectSettingsController(service))
                .setValidator(validator)
                .build();
    }

    // ------------------------------------------------------------------
    // what is accepted
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an empty allowedTaskTypeIds is forwarded — it is how the restriction is removed")
    void anEmptyAllowListIsForwarded() throws Exception {
        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"autoAssignRule":"MANUAL","mandatoryFields":[],"allowedTaskTypeIds":[]}"""))
                .andExpect(status().isOk());

        verify(service).replace(7L, new ProjectSettingsDtos.ProjectSettingsWrite(
                "MANUAL", List.of(), List.of()));
    }

    @Test
    @DisplayName("an empty mandatoryFields is forwarded — requiring nothing extra is the common case")
    void anEmptyFieldListIsForwarded() throws Exception {
        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"autoAssignRule":"ROUND_ROBIN","mandatoryFields":[],
                                 "allowedTaskTypeIds":[2]}"""))
                .andExpect(status().isOk());

        verify(service).replace(7L, new ProjectSettingsDtos.ProjectSettingsWrite(
                "ROUND_ROBIN", List.of(), List.of(2)));
    }

    @Test
    @DisplayName("a full body is forwarded verbatim — the service, not the binder, normalises it")
    void aFullBodyIsForwarded() throws Exception {
        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"autoAssignRule":"LEAST_LOADED",
                                 "mandatoryFields":["MODULE","ESTIMATED_HRS"],
                                 "allowedTaskTypeIds":[1,2,3]}"""))
                .andExpect(status().isOk());

        verify(service).replace(7L, new ProjectSettingsDtos.ProjectSettingsWrite(
                "LEAST_LOADED", List.of("MODULE", "ESTIMATED_HRS"), List.of(1, 2, 3)));
    }

    @Test
    @DisplayName("an unknown vocabulary value reaches the service, so the 400 can name the field")
    void anUnknownValueReachesTheService() throws Exception {
        // Typed as String rather than as an enum precisely so this happens.
        // Jackson refusing an unknown constant is a 400 whose body is a parser
        // message about a Java type, carrying no `errors` map — the screen would
        // have nothing to put on the control that caused it.
        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"autoAssignRule":"WHOEVER_IS_FREE","mandatoryFields":[],
                                 "allowedTaskTypeIds":[]}"""))
                .andExpect(status().isOk());

        verify(service).replace(7L, new ProjectSettingsDtos.ProjectSettingsWrite(
                "WHOEVER_IS_FREE", List.of(), List.of()));
    }

    // ------------------------------------------------------------------
    // what is refused
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an omitted autoAssignRule is refused, not defaulted by the binder")
    void missingRuleIsRefused() throws Exception {
        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mandatoryFields":[],"allowedTaskTypeIds":[]}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).replace(anyLong(), any());
    }

    @Test
    @DisplayName("an omitted allowedTaskTypeIds is refused — on a replace, absent is not empty")
    void missingAllowListIsRefused() throws Exception {
        // The one that would hurt. Absent and [] would both arrive as null
        // without @NotNull, and treating null as "clear it" silently removes a
        // restriction a client never meant to touch.
        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"autoAssignRule":"MANUAL","mandatoryFields":[]}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).replace(anyLong(), any());
    }

    @Test
    @DisplayName("an omitted mandatoryFields is refused for the same reason")
    void missingFieldListIsRefused() throws Exception {
        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"autoAssignRule":"MANUAL","allowedTaskTypeIds":[]}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).replace(anyLong(), any());
    }

    @Test
    @DisplayName("an oversized allowedTaskTypeIds is refused before it reaches the database")
    void anOversizedAllowListIsRefused() throws Exception {
        String ids = java.util.stream.IntStream.rangeClosed(1, 201)
                .mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));

        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"autoAssignRule\":\"MANUAL\",\"mandatoryFields\":[],"
                                + "\"allowedTaskTypeIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).replace(anyLong(), any());
    }

    @Test
    @DisplayName("an oversized mandatoryFields is refused — 20 is twice the vocabulary")
    void anOversizedFieldListIsRefused() throws Exception {
        String codes = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(i -> "\"MODULE\"")
                .collect(java.util.stream.Collectors.joining(","));

        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"autoAssignRule\":\"MANUAL\",\"mandatoryFields\":[" + codes + "],"
                                + "\"allowedTaskTypeIds\":[]}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).replace(anyLong(), any());
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static ProjectSettingsDtos.ProjectSettings settings() {
        return new ProjectSettingsDtos.ProjectSettings(
                7L,
                ProjectSettingsDtos.AutoAssignRule.MANUAL,
                List.of(),
                false,
                List.of(new ProjectSettingsDtos.SettingsTaskType(
                        2, "PROD_BUG", "Production Bug", true, true)));
    }
}
