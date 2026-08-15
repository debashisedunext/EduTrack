package com.edunext.edutrack.api.feature.masters.tasktypes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B-020 · that the Bean Validation annotations on the two write shapes are
 * actually enforced.
 *
 * <p>The contract's {@code pattern} and {@code maxLength} and these annotations
 * describe the same rules, and only one of them is the one that runs — the
 * annotations. springdoc will eventually emit the contract <em>from</em> them
 * (PLAN.md §2.2, D-4), so this is the test that makes the contract's claims true
 * rather than aspirational.
 *
 * <p>The colour rule is the one worth a test of its own. CLAUDE.md: "never
 * introduce a colour that isn't a token." A free-text colour would pass through
 * to a {@code VARCHAR(7)} column, be truncated by MySQL if longer, and reach the
 * grid, the picker and the Task Type Distribution chart as something the design
 * system does not contain.
 */
class TaskTypeBodyValidationTest {

    private static final String COLLECTION = "/api/v1/masters/task-types";
    private static final String ONE = "/api/v1/masters/task-types/2";

    private TaskTypeService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(TaskTypeService.class);
        when(service.find(anyInt())).thenReturn(Optional.of(view()));
        when(service.create(any())).thenReturn(view());
        when(service.update(anyInt(), any())).thenReturn(Optional.of(view()));

        // Standalone, with a real validator wired the way Spring Boot wires one.
        // No security in the path — that is PermissionMatrix's question, and
        // this is only about the body.
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mvc = MockMvcBuilders.standaloneSetup(new TaskTypeController(service))
                .setValidator(validator)
                .build();
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a colour that is not #RRGGBB is refused")
    void freeTextColourIsRefused() throws Exception {
        mvc.perform(post(COLLECTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"X","name":"X","colour":"cornflowerblue","defaultLevel":"LOW"}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("a three-digit hex shorthand is refused — the column is seven characters")
    void shorthandColourIsRefused() throws Exception {
        mvc.perform(post(COLLECTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"X","name":"X","colour":"#4F4","defaultLevel":"LOW"}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("a code with a hyphen is refused — codes are identifier-shaped")
    void hyphenatedCodeIsRefused() throws Exception {
        mvc.perform(post(COLLECTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"CLIENT-BUG","name":"X","colour":"#4F46E5","defaultLevel":"LOW"}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("a code starting with a digit is refused")
    void codeStartingWithADigitIsRefused() throws Exception {
        mvc.perform(post(COLLECTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"2FA_ISSUE","name":"X","colour":"#4F46E5","defaultLevel":"LOW"}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("a missing colour is refused — create requires one even though the column is nullable")
    void missingColourIsRefused() throws Exception {
        // A type with no colour is a hole in the picker, the grid and the Task
        // Type Distribution chart at once.
        mvc.perform(post(COLLECTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"X","name":"X","defaultLevel":"LOW"}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("a missing defaultLevel is refused")
    void missingDefaultLevelIsRefused() throws Exception {
        // Blueprint §4B.1's "pre-filled from the task type master" silently not
        // happening looks like a bug in the create form, not a gap in the master.
        mvc.perform(post(COLLECTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"X","name":"X","colour":"#4F46E5"}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("a negative default SLA is refused")
    void negativeSlaIsRefused() throws Exception {
        mvc.perform(post(COLLECTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"X","name":"X","colour":"#4F46E5","defaultLevel":"LOW","defaultSlaHrs":-1}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("a valid body reaches the service")
    void validBodyIsAccepted() throws Exception {
        mvc.perform(post(COLLECTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"DATA_FIX","name":"Data Fix","icon":"database",\
                                "colour":"#10B981","defaultLevel":"MEDIUM","defaultSlaHrs":24}"""))
                .andExpect(status().isCreated());

        verify(service).create(any());
    }

    // ------------------------------------------------------------------
    // patch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the patch's colour is validated too, not only the create's")
    void patchColourIsValidated() throws Exception {
        mvc.perform(patch(ONE)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"colour":"red"}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).update(anyInt(), any());
    }

    @Test
    @DisplayName("an empty name is refused — every picker renders it")
    void emptyPatchNameIsRefused() throws Exception {
        mvc.perform(patch(ONE)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":""}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).update(anyInt(), any());
    }

    @Test
    @DisplayName("an explicit null icon passes validation — clearing is not a violation")
    void clearingTheIconIsAllowed() throws Exception {
        // The @Size sits inside the Optional's type argument, so it must not
        // fire on an empty one. If it did, the clear path would be unreachable
        // and the failure would look like a validation bug rather than a
        // container-element annotation in the wrong place.
        mvc.perform(patch(ONE)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"icon":null,"defaultSlaHrs":null}"""))
                .andExpect(status().isOk());

        verify(service).update(anyInt(), any());
    }

    @Test
    @DisplayName("an over-long icon is refused, inside the Optional")
    void oversizedIconIsRefused() throws Exception {
        mvc.perform(patch(ONE)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"icon":"a-very-long-lucide-icon-name-that-will-not-fit-in-thirty"}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).update(anyInt(), any());
    }

    private static TaskTypeDtos.TaskTypeView view() {
        return new TaskTypeDtos.TaskTypeView(2, "PRODUCTION_BUG", "Production Bug", "flame",
                "#06B6D4", "HIGH", new BigDecimal("8.00"), (short) 20, true, 0L);
    }
}
