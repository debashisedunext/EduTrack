package com.edunext.edutrack.api.feature.masters.stages;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B-040 · that the Bean Validation annotations on the three write shapes are
 * actually enforced.
 *
 * <p>The contract's {@code pattern}, {@code maxLength} and {@code minimum} and
 * these annotations describe the same rules, and only one of them is the one that
 * runs. springdoc will eventually emit the contract <em>from</em> them (PLAN.md
 * §2.2, D-4), so this is the test that makes the contract's claims true rather
 * than aspirational.
 *
 * <p><b>The {@code slaHours} floor is the one worth writing down.</b> A zero-hour
 * stage SLA is not "no SLA" — it is a stage that breaches the moment it is
 * entered, so every ticket passing through raises an alert Stream D's scanner has
 * no way to suppress. Absent means no SLA, which is what {@code DEV} is seeded
 * with; zero means something the product cannot act on.
 */
class StageBodyValidationTest {

    private static final String COLLECTION = "/api/v1/masters/workflow-templates/1/stages";
    private static final String ONE = "/api/v1/masters/workflow-templates/1/stages/30";
    private static final String ORDER = "/api/v1/masters/workflow-templates/1/stages/order";

    private StageService service;
    private MockMvc mvc;

    private static StageDtos.StageView view() {
        return new StageDtos.StageView(30L, 1L, "DEV", "Development", "DEVELOPER",
                new BigDecimal("4.00"), false, List.of(), "code-2", (short) 30, 3,
                0L, 0L, true);
    }

    @BeforeEach
    void setUp() {
        service = mock(StageService.class);
        when(service.find(anyLong(), anyLong())).thenReturn(Optional.of(view()));
        when(service.list(anyLong())).thenReturn(Optional.of(List.of(view())));
        when(service.create(anyLong(), any())).thenReturn(view());
        when(service.update(anyLong(), anyLong(), any())).thenReturn(view());
        when(service.reorder(anyLong(), anyList())).thenReturn(List.of(view()));

        // Standalone, with a real validator wired the way Spring Boot wires one.
        // No security in the path — that is PermissionMatrix's question, and this
        // is only about the body.
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mvc = MockMvcBuilders.standaloneSetup(new StageController(service))
                .setValidator(validator)
                .build();
    }

    /** The tag the two guarded writes need, taken from the route that emits it. */
    private String tagForOne() throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(ONE))
                .andReturn().getResponse().getHeader("ETag");
    }

    private String tagForList() throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(COLLECTION))
                .andReturn().getResponse().getHeader("ETag");
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a well-formed create is accepted")
    void aGoodCreateIsAccepted() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stageCode":"DEPLOY","displayName":"Deployment",\
                                "ownerRole":"DEPLOYMENT","slaHours":4.0,"icon":"rocket"}"""))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a missing stageCode is refused before the service is reached")
    void stageCodeIsRequired() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Deployment","ownerRole":"DEPLOYMENT"}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(anyLong(), any());
    }

    @Test
    @DisplayName("a lower-case stageCode is refused by the pattern")
    void stageCodeMustBeUpperCase() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stageCode":"deploy","displayName":"Deployment",\
                                "ownerRole":"DEPLOYMENT"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a stageCode with a space is refused — it is stored on every transition row")
    void stageCodeMayNotContainSpaces() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stageCode":"GO LIVE","displayName":"Go live",\
                                "ownerRole":"DEPLOYMENT"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a stageCode over 20 characters is refused — the column is VARCHAR(20)")
    void stageCodeIsBounded() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stageCode":"AAAAAAAAAAAAAAAAAAAAAAAAA","displayName":"x",\
                                "ownerRole":"DEPLOYMENT"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a missing displayName is refused")
    void displayNameIsRequired() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stageCode":"DEPLOY","ownerRole":"DEPLOYMENT"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a blank displayName is refused, not stored as whitespace")
    void displayNameMayNotBeBlank() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stageCode":"DEPLOY","displayName":"   ",\
                                "ownerRole":"DEPLOYMENT"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a missing ownerRole is refused — the golden rule of §2 depends on it")
    void ownerRoleIsRequired() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stageCode":"DEPLOY","displayName":"Deployment"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("slaHours of zero is refused — a stage that breaches on entry is not 'no SLA'")
    void slaHoursHasAFloor() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stageCode":"DEPLOY","displayName":"Deployment",\
                                "ownerRole":"DEPLOYMENT","slaHours":0}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("slaHours absent is accepted — that is DEV, resolved from the SLA matrix")
    void slaHoursMayBeAbsent() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stageCode":"DEPLOY","displayName":"Deployment",\
                                "ownerRole":"DEPLOYMENT"}"""))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("slaHours beyond DECIMAL(6,2) is refused rather than truncated by MySQL")
    void slaHoursHasACeiling() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stageCode":"DEPLOY","displayName":"Deployment",\
                                "ownerRole":"DEPLOYMENT","slaHours":100000}"""))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // patch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an empty patch body is accepted — every field is optional")
    void anEmptyPatchIsAccepted() throws Exception {
        mvc.perform(patch(ONE).contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", tagForOne())
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the same pattern applies on the patch — a rename cannot smuggle a bad code in")
    void patchEnforcesTheCodePattern() throws Exception {
        mvc.perform(patch(ONE).contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", tagForOne())
                        .content("""
                                {"stageCode":"dev-2"}"""))
                .andExpect(status().isBadRequest());

        verify(service, never()).update(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("seq is not a field on the patch — sending one changes nothing")
    void seqIsNotPatchable() throws Exception {
        mvc.perform(patch(ONE).contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", tagForOne())
                        .content("""
                                {"seq":90}"""))
                .andExpect(status().isOk());

        verify(service).update(1L, 30L,
                new StageDtos.StagePatch(null, null, null, null, null, null, null));
    }

    // ------------------------------------------------------------------
    // reorder
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a well-formed reorder is accepted")
    void aGoodReorderIsAccepted() throws Exception {
        mvc.perform(put(ORDER).contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", tagForList())
                        .content("""
                                {"stageIds":[30,10,20]}"""))
                .andExpect(status().isOk());
    }
}
