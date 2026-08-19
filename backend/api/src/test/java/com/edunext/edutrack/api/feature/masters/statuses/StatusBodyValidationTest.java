package com.edunext.edutrack.api.feature.masters.statuses;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B-039 · that the Bean Validation annotations on the three write shapes are
 * actually enforced.
 *
 * <p>The contract's {@code pattern} and {@code maxLength} and these annotations
 * describe the same rules, and only one of them is the one that runs — the
 * annotations. springdoc will eventually emit the contract <em>from</em> them
 * (PLAN.md §2.2, D-4), so this is the test that makes the contract's claims true
 * rather than aspirational.
 *
 * <p><b>The category pattern is the one worth writing down.</b> It is the only
 * field on this screen whose legal values are a closed set the database also
 * enforces — {@code ck_statuses_category}. Without the annotation a bad value
 * reaches MySQL and comes back as a constraint-violation 500 naming a constraint
 * the user has never heard of, instead of a 400 on the field they picked.
 */
class StatusBodyValidationTest {

    private static final String COLLECTION = "/api/v1/masters/statuses";
    private static final String ONE = "/api/v1/masters/statuses/2";
    private static final String MATRIX = "/api/v1/masters/status-transitions";

    private StatusService service;
    private StatusTransitionService matrix;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StatusService.class);
        matrix = mock(StatusTransitionService.class);
        when(service.find(anyInt())).thenReturn(Optional.of(view()));
        when(service.create(any())).thenReturn(view());
        when(service.update(anyInt(), any())).thenReturn(Optional.of(view()));
        when(matrix.list(isNull())).thenReturn(List.of());
        when(matrix.replace(any())).thenReturn(List.of());

        // Standalone, with a real validator wired the way Spring Boot wires one.
        // No security in the path — that is PermissionMatrix's question, and this
        // is only about the body.
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mvc = MockMvcBuilders.standaloneSetup(new StatusController(service, matrix))
                .setValidator(validator)
                .build();
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a well-formed create is accepted")
    void aGoodCreateIsAccepted() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"ON_HOLD","name":"On Hold","category":"IN_PROGRESS","colour":"#F59E0B"}"""))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a missing code is refused before the service is reached")
    void codeIsRequired() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"On Hold","category":"TODO","colour":"#F59E0B"}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("a missing name is refused")
    void nameIsRequired() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"ON_HOLD","category":"TODO","colour":"#F59E0B"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a missing category is refused — the column is NOT NULL with no default")
    void categoryIsRequired() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"ON_HOLD","name":"On Hold","colour":"#F59E0B"}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any());
    }

    /**
     * The four-value mistake: {@code DOING} reads like a category and is not one.
     * Caught here rather than by {@code ck_statuses_category}, which would answer
     * a 500 naming a constraint the user has never heard of.
     */
    @Test
    @DisplayName("a category outside the three is refused")
    void categoryMustBeOneOfThree() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"ON_HOLD","name":"On Hold","category":"DOING","colour":"#F59E0B"}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("a lower-case category is refused rather than silently upper-cased")
    void categoryIsCaseSensitiveOnTheWire() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"ON_HOLD","name":"On Hold","category":"todo","colour":"#F59E0B"}"""))
                .andExpect(status().isBadRequest());
    }

    /**
     * The column is nullable and the write is not, and that gap is deliberate: a
     * status with no colour is a hole in three renderings at once rather than one
     * absence in one place.
     */
    @Test
    @DisplayName("a missing colour is refused, even though the column allows null")
    void colourIsRequiredOnCreate() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"ON_HOLD","name":"On Hold","category":"TODO"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a colour that is not a #RRGGBB token is refused")
    void colourMustBeAToken() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"ON_HOLD","name":"On Hold","category":"TODO","colour":"amber"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a name over 40 characters is refused — the column is VARCHAR(40)")
    void nameLengthIsBounded() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"ON_HOLD","name":"%s","category":"TODO","colour":"#F59E0B"}"""
                                .formatted("x".repeat(41))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a code starting with a digit is refused")
    void codeShape() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"1NEW","name":"New","category":"TODO","colour":"#F59E0B"}"""))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // patch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an empty patch is well formed — every field is optional")
    void emptyPatchIsValid() throws Exception {
        mvc.perform(patch(ONE).header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a bad category on the patch is refused too")
    void patchCategoryIsValidated() throws Exception {
        mvc.perform(patch(ONE).header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"BLOCKED"}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).update(anyInt(), any());
    }

    @Test
    @DisplayName("an empty name on the patch is refused — there is nothing to clear")
    void patchNameCannotBeEmptied() throws Exception {
        mvc.perform(patch(ONE).header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":""}"""))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // the matrix
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a well-formed matrix replace is accepted")
    void aGoodMatrixIsAccepted() throws Exception {
        mvc.perform(put(MATRIX).header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transitions":[{"toStatus":"NEW","roleCode":"ADMIN"}]}"""))
                .andExpect(status().isOk());
    }

    /**
     * {@code @NotNull} and deliberately not {@code @NotEmpty}. An empty array is
     * a well-formed statement that reaches the service and is refused there with
     * a message about locking every role out of raising a ticket — which is a
     * different remedy from "fix your JSON", and the two must not answer the same
     * status.
     */
    @Test
    @DisplayName("an empty transitions array is well formed and reaches the service")
    void emptyMatrixIsNotABodyProblem() throws Exception {
        mvc.perform(put(MATRIX).header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transitions":[]}"""))
                .andExpect(status().isOk());

        verify(matrix).replace(any());
    }

    @Test
    @DisplayName("an absent transitions list is refused")
    void transitionsIsRequired() throws Exception {
        mvc.perform(put(MATRIX).header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verify(matrix, never()).replace(any());
    }

    @Test
    @DisplayName("a cell without a toStatus is refused")
    void cellNeedsAToStatus() throws Exception {
        mvc.perform(put(MATRIX).header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transitions":[{"roleCode":"ADMIN"}]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a cell without a roleCode is refused")
    void cellNeedsARole() throws Exception {
        mvc.perform(put(MATRIX).header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transitions":[{"toStatus":"NEW"}]}"""))
                .andExpect(status().isBadRequest());
    }

    /**
     * {@code @Valid} on the element type. Without it the nested constraints are
     * declared and never run, which is the quietest way for a validation test
     * suite to be entirely green and entirely wrong.
     */
    @Test
    @DisplayName("nested cell constraints run on the second element, not only the first")
    void nestedValidationRunsOnEveryElement() throws Exception {
        mvc.perform(put(MATRIX).header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transitions":[
                                  {"toStatus":"NEW","roleCode":"ADMIN"},
                                  {"toStatus":"IN_PROGRESS"}
                                ]}"""))
                .andExpect(status().isBadRequest());

        verify(matrix, never()).replace(any());
    }

    // ------------------------------------------------------------------

    private static StatusDtos.StatusView view() {
        return new StatusDtos.StatusView(2, "ON_HOLD", "On Hold", "IN_PROGRESS", "#F59E0B",
                (short) 30, true, false, true, 0L, 0, null);
    }
}
