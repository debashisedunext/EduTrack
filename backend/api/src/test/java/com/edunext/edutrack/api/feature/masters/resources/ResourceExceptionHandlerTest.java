package com.edunext.edutrack.api.feature.masters.resources;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What each refusal looks like <b>on the wire</b>.
 *
 * <p>The gap this fills: {@code ResourceWriteServiceTest} proves which exception
 * the service throws and {@code ResourceFormIT} proves the database agrees, but
 * between them nothing asserted that {@link ResourceExceptionHandler} turns
 * those into the status and document the contract promises. The advice is
 * scoped with {@code assignableTypes}, so it is registered by a configuration
 * detail that a rename or a package move can silently break — and a 409 that
 * quietly became a 500 still fails the save, which is why nobody would notice
 * from the screen.
 *
 * <p>{@code standaloneSetup} with the advice attached, following
 * {@code BounceWebhookControllerTest}: no Spring context, so this stays a unit
 * test that runs in milliseconds.
 */
class ResourceExceptionHandlerTest {

    private static final String URL = "/api/v1/users/42";

    /** The five fields every write requires; the tests vary only the manager. */
    private static final String BODY = """
            {"employeeCode":"EMP-042","username":"ravi.kumar",
             "email":"ravi.kumar@edunext.test","displayName":"Ravi Kumar",
             "role":"DEVELOPER","reportingManagerId":7}
            """;

    private ResourceWriteService writes;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        writes = mock(ResourceWriteService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ResourceController(
                        mock(ResourceService.class), mock(ResourceExportWriter.class), writes))
                .setControllerAdvice(new ResourceExceptionHandler())
                .build();

        // The PATCH reads the row and checks the precondition before it writes,
        // so every test needs the read to succeed and the tag to match.
        when(writes.detail(42L)).thenReturn(detail());
    }

    /**
     * B-012. The status is the part a client branches on, and `type` is the part
     * that distinguishes this 409 from the duplicate-field one — both of which
     * are promised by the contract and neither of which the service can deliver
     * on its own.
     */
    @Test
    @DisplayName("a reporting cycle is a 409 with type manager-cycle, keyed to the picker")
    void managerCycleIs409() throws Exception {
        when(writes.update(any(), any())).thenThrow(
                new ResourceWriteService.ManagerCycleException("that would create a reporting cycle"));

        mockMvc.perform(patch(URL).contentType(APPLICATION_JSON).header("If-Match", "*").content(BODY))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/manager-cycle"))
                // Field-keyed, which is what lets the form put the message on the
                // input rather than in a banner above five sections.
                .andExpect(jsonPath("$.errors.reportingManagerId[0]")
                        .value("that would create a reporting cycle"));
    }

    /**
     * The two 409s must not be one shape. A client that has to read the prose to
     * tell "you cannot report to your own reportee" from "that username is
     * taken" is reading the part of the document that may be reworded without
     * notice.
     */
    @Test
    @DisplayName("a duplicate field is also a 409, but a distinguishable one")
    void duplicateIsADifferentType() throws Exception {
        when(writes.update(any(), any())).thenThrow(new DuplicateKeyException("uq_users_username"));

        mockMvc.perform(patch(URL).contentType(APPLICATION_JSON).header("If-Match", "*").content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/duplicate"))
                .andExpect(jsonPath("$.errors.username").exists());
    }

    /**
     * A manager id that names nobody stays a 400: the caller got a field wrong,
     * which is not the same as asking for a shape the organisation cannot hold.
     */
    @Test
    @DisplayName("an unknown manager is a 400, not a 409")
    void unknownManagerIs400() throws Exception {
        when(writes.update(any(), any())).thenThrow(
                new ResourceWriteService.ResourceValidationException(
                        "reportingManagerId", "no resource with id 7"));

        mockMvc.perform(patch(URL).contentType(APPLICATION_JSON).header("If-Match", "*").content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/validation"))
                .andExpect(jsonPath("$.errors.reportingManagerId[0]").value("no resource with id 7"));
    }

    private static ResourceDtos.ResourceDetail detail() {
        return new ResourceDtos.ResourceDetail(
                42L, "Ravi Kumar", "DEVELOPER", "ravi.kumar", "ravi.kumar@edunext.test",
                "EMP-042", null, null, null, List.of(), List.of(), true, 0,
                null, null, null, null, null, null, "Asia/Kolkata",
                new java.math.BigDecimal("8.00"), null, List.of(), List.of(), true);
    }
}
