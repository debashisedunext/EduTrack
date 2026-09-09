package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.text.RichTextSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B-106 · the requirement rules, without a database.
 *
 * <p>What earns a container is in {@code ObRequirementsIT}:
 * {@code ck_ob_client_requirements_met}, the {@code MEDIUMTEXT} round trip, and
 * the one thing no mock can assert — that a deleted requirement really does take
 * nothing with it. Everything below is a decision made in Java before any of
 * that is reached, which is {@code ObApplicationServiceTest}'s own split.
 *
 * <p>{@link ObRequirementBody} is <b>real rather than mocked</b> throughout.
 * Mocking it would turn every assertion about what gets stored into an assertion
 * about a stub, on the one collaborator that is a security boundary.
 */
class ObRequirementServiceTest {

    private static final long CLIENT = 42L;
    private static final long REQUIREMENT = 7L;
    private static final long CALLER = 3L;
    private static final long SOMEBODY_ELSE = 9L;

    private static final Instant MARCH = Instant.parse("2026-03-11T09:15:00Z");
    private static final Instant NOVEMBER = Instant.parse("2026-11-04T14:30:00Z");

    private static final ObClientScope ADMIN = new ObClientScope(ObClientScope.OB_ADMIN, CALLER);
    private static final ObClientScope VIEWER = new ObClientScope(ObClientScope.OB_VIEWER, CALLER);

    private ObClientService details;
    private ObClientReadRepository reads;
    private ObRequirementWriteRepository requirements;
    private ObRequirementService service;

    @BeforeEach
    void setUp() {
        details = mock(ObClientService.class);
        reads = mock(ObClientReadRepository.class);
        requirements = mock(ObRequirementWriteRepository.class);
        service = new ObRequirementService(details, reads, requirements,
                new ObRequirementBody(new RichTextSanitizer()),
                Clock.fixed(NOVEMBER, ZoneOffset.UTC));

        when(details.findDetail(any(), anyLong())).thenReturn(Optional.of(detailStub()));
        when(reads.requirementOf(anyLong(), anyLong())).thenReturn(Optional.of(unmetRow()));
        when(reads.nextRequirementSequence(anyLong())).thenReturn(4);
    }

    // ── who may write ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("standing")
    class Standing {

        /**
         * 404 before 403 and never the reverse. A client this caller cannot see
         * must be indistinguishable from one that does not exist, whatever their
         * role — otherwise the requirements routes become a way to test client
         * ids the client routes deny.
         */
        @Test
        @DisplayName("a client out of scope is 404 before the role is even consulted")
        void outOfScopeClientIs404() {
            when(details.findDetail(any(), anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.add(VIEWER, CLIENT, write("<p>SSO</p>")))
                    .isInstanceOf(ObClientNotFoundException.class);

            verifyNoInteractions(requirements);
        }

        @Test
        @DisplayName("a viewer who can see the client is 403, and writes nothing")
        void viewerIs403() {
            assertThatThrownBy(() -> service.add(VIEWER, CLIENT, write("<p>SSO</p>")))
                    .isInstanceOf(ObClientReadOnlyException.class);

            verifyNoInteractions(requirements);
        }

        /**
         * The write-role check precedes the requirement lookup, so a Viewer
         * cannot use the 404/403 difference to probe which requirement ids exist
         * under a client they can see.
         */
        @Test
        @DisplayName("a viewer is refused before the requirement id is resolved")
        void viewerCannotProbeRequirementIds() {
            assertThatThrownBy(() -> service.delete(VIEWER, CLIENT, REQUIREMENT))
                    .isInstanceOf(ObClientReadOnlyException.class);

            verify(reads, never()).requirementOf(anyLong(), anyLong());
        }

        @Test
        @DisplayName("a requirement under another client is 404, not 403")
        void otherClientsRequirementIs404() {
            when(reads.requirementOf(anyLong(), anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(ADMIN, CLIENT, REQUIREMENT, patch()))
                    .isInstanceOf(ObRequirementNotFoundException.class);

            verifyNoInteractions(requirements);
        }
    }

    // ── add ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("adding")
    class Adding {

        @Test
        @DisplayName("lands at the end of the list, sanitised, with both halves stored")
        void addsAtTheEnd() {
            service.add(ADMIN, CLIENT, write("<p>SSO against <script>x</script>Azure AD</p>"));

            ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(requirements).insert(eq(CLIENT), eq(4), any(), html.capture(), text.capture(),
                    eq(false), eq(null), eq(null), eq(CALLER));

            assertThat(html.getValue()).doesNotContain("script").contains("Azure AD");
            assertThat(text.getValue()).isEqualTo("SSO against Azure AD");
        }

        @Test
        @DisplayName("a blank title is stored as null rather than as an empty label")
        void blankTitleBecomesNull() {
            service.add(ADMIN, CLIENT, new ObClientDtos.ObRequirementWriteRequest(
                    "   ", "<p>SSO</p>", null));

            verify(requirements).insert(anyLong(), anyInt(), eq(null), anyString(), anyString(),
                    anyBoolean(), any(), any(), anyLong());
        }

        /**
         * Unusual and permitted — a requirement recorded after it was satisfied
         * is ordinary on a client whose onboarding started before anybody was
         * writing them down. The stamp is the server's clock, because a body
         * that could supply it could backdate the evidence.
         */
        @Test
        @DisplayName("isMet on a create stamps the server clock and the caller")
        void metOnCreateIsStamped() {
            service.add(ADMIN, CLIENT,
                    new ObClientDtos.ObRequirementWriteRequest(null, "<p>SSO</p>", true));

            verify(requirements).insert(eq(CLIENT), anyInt(), any(), anyString(), anyString(),
                    eq(true), eq(NOVEMBER), eq(CALLER), eq(CALLER));
        }

        @Test
        @DisplayName("a body that sanitises away is refused, and nothing is written")
        void refusesAnEmptyBody() {
            assertThatThrownBy(() -> service.add(ADMIN, CLIENT, write("<script>alert(1)</script>")))
                    .isInstanceOf(ObClientValidationException.class);

            verifyNoInteractions(requirements);
        }
    }

    // ── edit ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("editing")
    class Editing {

        @Test
        @DisplayName("an omitted field keeps what was stored")
        void omittedFieldsAreUntouched() {
            ObRequirementUpdateRequest request = new ObRequirementUpdateRequest();
            request.setTitle("Single sign-on");

            service.update(ADMIN, CLIENT, REQUIREMENT, request);

            verify(requirements).update(REQUIREMENT, "Single sign-on",
                    "<p>SSO against their Azure AD</p>", "SSO against their Azure AD",
                    false, null, null, CALLER);
        }

        @Test
        @DisplayName("a body that arrives is re-sanitised rather than merged with what is stored")
        void bodyIsResanitised() {
            ObRequirementUpdateRequest request = new ObRequirementUpdateRequest();
            request.setBodyHtml("<p>Tally import <b>and</b> <script>x</script>SSO</p>");

            service.update(ADMIN, CLIENT, REQUIREMENT, request);

            ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
            verify(requirements).update(anyLong(), any(), html.capture(), anyString(),
                    anyBoolean(), any(), any(), anyLong());

            assertThat(html.getValue()).doesNotContain("script");
            // b is not on §3.9's list; strong is. The text survives either way.
            assertThat(html.getValue()).doesNotContain("<b>").contains("and");
        }

        @Test
        @DisplayName("marking it met stamps the clock and the caller")
        void meetingStamps() {
            service.update(ADMIN, CLIENT, REQUIREMENT, patchMet(true));

            verify(requirements).update(eq(REQUIREMENT), any(), anyString(), anyString(),
                    eq(true), eq(NOVEMBER), eq(CALLER), eq(CALLER));
        }

        /**
         * The rule the whole met-stamp design exists for, and the one an
         * ordinary edit would destroy silently.
         */
        @Test
        @DisplayName("correcting the wording in November does not re-date a requirement met in March")
        void anUnrelatedEditDoesNotRestamp() {
            when(reads.requirementOf(anyLong(), anyLong()))
                    .thenReturn(Optional.of(metRow(MARCH, SOMEBODY_ELSE)));

            ObRequirementUpdateRequest request = new ObRequirementUpdateRequest();
            request.setBodyHtml("<p>SSO against their Entra ID</p>");

            service.update(ADMIN, CLIENT, REQUIREMENT, request);

            verify(requirements).update(eq(REQUIREMENT), any(), anyString(), anyString(),
                    eq(true), eq(MARCH), eq(SOMEBODY_ELSE), eq(CALLER));
        }

        /** Re-asserting a flag that is already set is not a new decision either. */
        @Test
        @DisplayName("re-marking an already-met requirement keeps the original stamp")
        void remarkingKeepsTheStamp() {
            when(reads.requirementOf(anyLong(), anyLong()))
                    .thenReturn(Optional.of(metRow(MARCH, SOMEBODY_ELSE)));

            service.update(ADMIN, CLIENT, REQUIREMENT, patchMet(true));

            verify(requirements).update(eq(REQUIREMENT), any(), anyString(), anyString(),
                    eq(true), eq(MARCH), eq(SOMEBODY_ELSE), eq(CALLER));
        }

        /**
         * Cleared rather than left behind — {@code ck_ob_client_requirements_met}
         * refuses a stamp beside a zero, so this is the readable version of a
         * rule the column also holds.
         */
        @Test
        @DisplayName("un-meeting clears both the stamp and its attribution")
        void unmeetingClearsTheStamp() {
            when(reads.requirementOf(anyLong(), anyLong()))
                    .thenReturn(Optional.of(metRow(MARCH, SOMEBODY_ELSE)));

            service.update(ADMIN, CLIENT, REQUIREMENT, patchMet(false));

            verify(requirements).update(eq(REQUIREMENT), any(), anyString(), anyString(),
                    eq(false), eq(null), eq(null), eq(CALLER));
        }

        @Test
        @DisplayName("an explicit null title clears the label")
        void nullTitleClears() {
            ObRequirementUpdateRequest request = new ObRequirementUpdateRequest();
            request.setTitle(null);

            service.update(ADMIN, CLIENT, REQUIREMENT, request);

            verify(requirements).update(eq(REQUIREMENT), eq(null), anyString(), anyString(),
                    anyBoolean(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("a body that sanitises away is refused, and the stored one survives")
        void refusesAnEmptyBodyOnEdit() {
            ObRequirementUpdateRequest request = new ObRequirementUpdateRequest();
            request.setBodyHtml("<script>alert(1)</script>");

            assertThatThrownBy(() -> service.update(ADMIN, CLIENT, REQUIREMENT, request))
                    .isInstanceOf(ObClientValidationException.class);

            verify(requirements, never()).update(anyLong(), any(), anyString(), anyString(),
                    anyBoolean(), any(), any(), anyLong());
        }
    }

    // ── delete ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("removing")
    class Removing {

        @Test
        @DisplayName("removes the row and answers with the client document")
        void deletesTheRow() {
            ObClientDtos.ObClientDetail after = service.delete(ADMIN, CLIENT, REQUIREMENT);

            verify(requirements).delete(REQUIREMENT);
            assertThat(after.id()).isEqualTo(CLIENT);
        }

        @Test
        @DisplayName("a requirement under another client is 404, and nothing is removed")
        void refusesAnotherClientsRequirement() {
            when(reads.requirementOf(anyLong(), anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(ADMIN, CLIENT, REQUIREMENT))
                    .isInstanceOf(ObRequirementNotFoundException.class);

            verify(requirements, never()).delete(anyLong());
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static ObClientDtos.ObRequirementWriteRequest write(String bodyHtml) {
        return new ObClientDtos.ObRequirementWriteRequest(null, bodyHtml, null);
    }

    private static ObRequirementUpdateRequest patch() {
        ObRequirementUpdateRequest request = new ObRequirementUpdateRequest();
        request.setTitle("Single sign-on");
        return request;
    }

    private static ObRequirementUpdateRequest patchMet(boolean met) {
        ObRequirementUpdateRequest request = new ObRequirementUpdateRequest();
        request.setIsMet(met);
        return request;
    }

    private static ObClientReadRepository.RequirementRow unmetRow() {
        return new ObClientReadRepository.RequirementRow(
                REQUIREMENT, 2, null, "<p>SSO against their Azure AD</p>",
                "SSO against their Azure AD", false, null, null, null,
                CALLER, "Ayush", MARCH, null);
    }

    private static ObClientReadRepository.RequirementRow metRow(Instant at, long by) {
        return new ObClientReadRepository.RequirementRow(
                REQUIREMENT, 2, null, "<p>SSO against their Azure AD</p>",
                "SSO against their Azure AD", true, at, by, "Priya",
                CALLER, "Ayush", MARCH, at);
    }

    private static ObClientDtos.ObClientDetail detailStub() {
        return new ObClientDtos.ObClientDetail(
                CLIENT, "Acme", LocalDate.of(2026, 9, 7), "ONBOARDING", null, "LOCKED", 1, 0,
                null, List.of(), null, null, null, false,
                null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, null, null);
    }
}
