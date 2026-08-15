package com.edunext.edutrack.api.feature.masters.priorities;

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
 * B-021 · that the Bean Validation annotations on the two write shapes are
 * actually enforced.
 *
 * <p>The contract's {@code pattern} and {@code maxLength} and these annotations
 * describe the same rules, and only one of them is the one that runs — the
 * annotations. springdoc will eventually emit the contract <em>from</em> them
 * (PLAN.md §2.2, D-4), so this is the test that makes the contract's claims true
 * rather than aspirational.
 *
 * <p>The colour rule is worth its own test for the reason B-020 gives one table
 * over, plus one this table has and that one does not: blueprint §12.1 states
 * these four hexes <em>exactly</em>, under "Level chips". A free-text colour
 * would pass through to a {@code VARCHAR(7)}, be truncated by MySQL if longer,
 * and reach the level picker, the ticket grid's chip and the Priority Split
 * chart as something the design system does not contain.
 */
class PriorityBodyValidationTest {

    private static final String COLLECTION = "/api/v1/masters/priorities";
    private static final String ONE = "/api/v1/masters/priorities/2";

    private PriorityService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PriorityService.class);
        when(service.find(anyInt())).thenReturn(Optional.of(view()));
        when(service.create(any())).thenReturn(view());
        when(service.update(anyInt(), any())).thenReturn(Optional.of(view()));

        // Standalone, with a real validator wired the way Spring Boot wires one.
        // No security in the path — that is PermissionMatrix's question, and
        // this is only about the body.
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mvc = MockMvcBuilders.standaloneSetup(new PriorityController(service))
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
                                {"level":"HIGH","name":"High","colour":"#F59E0B","defaultSlaHrs":8}"""))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a missing level is refused before the service is reached")
    void levelIsRequired() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"High","colour":"#F59E0B"}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("a missing name is refused")
    void nameIsRequired() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"level":"HIGH","colour":"#F59E0B"}"""))
                .andExpect(status().isBadRequest());
    }

    /**
     * The column is nullable and the write is not, and that gap is deliberate:
     * a level with no colour is a hole in three renderings at once rather than
     * one absence in one place.
     */
    @Test
    @DisplayName("a missing colour is refused, even though the column allows null")
    void colourIsRequiredOnCreate() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"level":"HIGH","name":"High"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a colour that is not a #RRGGBB token is refused")
    void colourMustBeAToken() throws Exception {
        for (String bad : new String[]{"\"amber\"", "\"#F59E0\"", "\"#F59E0BB\"", "\"F59E0B\"",
                "\"rgb(245,158,11)\""}) {
            mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"level\":\"HIGH\",\"name\":\"High\",\"colour\":" + bad + "}"))
                    .andExpect(status().isBadRequest());
        }

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("lower-case hex is accepted — §12.1 states them upper-case, the pattern allows both")
    void lowerCaseHexIsFine() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"level":"HIGH","name":"High","colour":"#f59e0b"}"""))
                .andExpect(status().isCreated());
    }

    /**
     * The column is {@code VARCHAR(10)}. Ten characters is not a house style —
     * it is what {@code tickets.level}, {@code task_types.default_level} and
     * {@code sla_policies.level} are all declared as, and a code longer than
     * that would be silently truncated into a value none of the three could
     * match on.
     */
    @Test
    @DisplayName("a level code longer than the VARCHAR(10) it is stored in is refused")
    void levelIsBoundedByTheColumn() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"level":"SUPERCRITICAL","name":"Super","colour":"#EF4444"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a name longer than the VARCHAR(40) it is stored in is refused")
    void nameIsBoundedByTheColumn() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"HIGH\",\"name\":\"" + "x".repeat(41)
                                + "\",\"colour\":\"#F59E0B\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a negative default SLA is refused")
    void negativeSlaIsRefused() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"level":"HIGH","name":"High","colour":"#F59E0B","defaultSlaHrs":-1}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a null default SLA is accepted — it means this level adds no rung 4")
    void nullSlaIsAccepted() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"level":"HIGH","name":"High","colour":"#F59E0B","defaultSlaHrs":null}"""))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------
    // patch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an empty patch is well-formed — every field is optional")
    void anEmptyPatchIsWellFormed() throws Exception {
        mvc.perform(patch(ONE).contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "*").content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the patch enforces the same colour rule as the create")
    void patchColourMustBeAToken() throws Exception {
        mvc.perform(patch(ONE).contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "*").content("""
                                {"colour":"amber"}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).update(anyInt(), any());
    }

    @Test
    @DisplayName("the patch refuses a blank name rather than storing one")
    void patchNameCannotBeBlanked() throws Exception {
        // `name` is not clearable — a level with no name is a blank cell in the
        // grid, the picker and every SLA matrix column header.
        mvc.perform(patch(ONE).contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "*").content("""
                                {"name":""}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the patch enforces the same negative-SLA rule inside the Optional")
    void patchNegativeSlaIsRefused() throws Exception {
        // The constraint is on the Optional's type argument. Without it the
        // annotation would sit on a container and never be evaluated.
        mvc.perform(patch(ONE).contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "*").content("""
                                {"defaultSlaHrs":-4}"""))
                .andExpect(status().isBadRequest());
    }

    private static PriorityDtos.PriorityView view() {
        return new PriorityDtos.PriorityView(2, "HIGH", "High", "#F59E0B",
                new BigDecimal("8.00"), false, (short) 30, true, 0L, 0, 0);
    }
}
