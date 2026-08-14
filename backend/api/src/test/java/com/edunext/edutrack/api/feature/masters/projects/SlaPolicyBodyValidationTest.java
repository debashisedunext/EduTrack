package com.edunext.edutrack.api.feature.masters.projects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
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
 * B-018 · that Bean Validation actually reaches the <em>elements</em> of the
 * request body.
 *
 * <h2>Why this test exists at all</h2>
 *
 * <p>Every other write in this feature takes a single DTO, where
 * {@code @Valid @RequestBody Foo} is unambiguous. This one takes a bare array,
 * and {@code @Valid} on a {@code List} parameter validates <em>the list</em> —
 * which has no constraints — not the objects in it. Spring resolves the element
 * cascade through method validation, which is a different mechanism with
 * different triggers, and whether it engages depends on how the parameter is
 * declared.
 *
 * <p>So the risk is not that validation is wrong; it is that it silently does
 * not run. A {@code resolutionHrs} of {@code 0} would then be stored, and
 * {@code SlaResolution.hasTarget()} treats a non-positive figure as no target at
 * all — the cell would read as configured on the SLA tab and behave as absent
 * everywhere else, taking the ticket out of the breach sweep, the pre-breach
 * warning and the delayed KPI with nothing on any screen saying so. That is a
 * defect nobody would find by reading the code, because the annotation is right
 * there on the record.
 *
 * <p>{@code CONVENTIONS.md} and the OpenAPI spec both state these rules as the
 * server's, and springdoc emits them into the contract from the annotations. A
 * test that the annotations are enforced is what makes that claim true rather
 * than documentation.
 */
class SlaPolicyBodyValidationTest {

    private static final String PATH = "/api/v1/projects/7/sla-policies";

    private SlaMatrixService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SlaMatrixService.class);
        when(service.matrix(anyLong())).thenReturn(List.of());
        when(service.replace(anyLong(), any())).thenReturn(List.of());

        // Standalone, with a real validator wired the way Spring Boot wires
        // one. No security, so the guard is not in the path — that is
        // PermissionMatrix's question and this is only about the body.
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mvc = MockMvcBuilders.standaloneSetup(new SlaPolicyController(service))
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("a zero resolution target is refused — it would read as configured and behave as absent")
    void zeroResolutionHoursIsRefused() throws Exception {
        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"taskTypeId":2,"level":"HIGH","resolutionHrs":0}]"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).replace(anyLong(), any());
    }

    @Test
    @DisplayName("a negative response target is refused")
    void negativeResponseHoursIsRefused() throws Exception {
        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"taskTypeId":2,"level":"HIGH","responseHrs":-1,"resolutionHrs":6}]"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).replace(anyLong(), any());
    }

    @Test
    @DisplayName("a figure the DECIMAL(6,2) column cannot hold is refused here, not by the driver")
    void outOfRangeHoursIsRefused() throws Exception {
        // resolution_hrs is DECIMAL(6,2). Without the bound the insert fails
        // with a MySQL data-truncation error, which reaches the caller as a 500
        // naming a column rather than a 400 naming a field.
        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"taskTypeId":2,"level":"HIGH","resolutionHrs":10000}]"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).replace(anyLong(), any());
    }

    @Test
    @DisplayName("a missing taskTypeId is refused")
    void missingTaskTypeIsRefused() throws Exception {
        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"level":"HIGH","resolutionHrs":6}]"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).replace(anyLong(), any());
    }

    @Test
    @DisplayName("a blank level is refused")
    void blankLevelIsRefused() throws Exception {
        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"taskTypeId":2,"level":"   ","resolutionHrs":6}]"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).replace(anyLong(), any());
    }

    @Test
    @DisplayName("the second element is validated too, not only the first")
    void everyElementIsValidated() throws Exception {
        // The failure mode this whole class exists for: a cascade that reaches
        // the head of the list and stops would pass every single-element test
        // above and let a grid of forty-four cells through on the strength of
        // its first one.
        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"taskTypeId":2,"level":"HIGH","resolutionHrs":6},
                                 {"taskTypeId":4,"level":"HIGH","resolutionHrs":0}]"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).replace(anyLong(), any());
    }

    @Test
    @DisplayName("a valid body is forwarded")
    void aValidBodyIsForwarded() throws Exception {
        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"taskTypeId":2,"level":"HIGH","responseHrs":1,"resolutionHrs":6,
                                  "escalateToL1":true,"escalateToL2":false}]"""))
                .andExpect(status().isOk());

        verify(service).replace(7L, List.of(new SlaPolicyDtos.SlaPolicyWrite(
                2, "HIGH", BigDecimal.valueOf(1), BigDecimal.valueOf(6), true, false)));
    }

    @Test
    @DisplayName("an empty array is forwarded — clearing every override is a real request")
    void anEmptyArrayIsForwarded() throws Exception {
        mvc.perform(put(PATH)
                        .header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk());

        verify(service).replace(7L, List.of());
    }
}
