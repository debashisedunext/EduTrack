package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.onboarding.instances.ObJourneyStepLifecycleService;
import com.edunext.edutrack.domain.onboarding.ObSignoff;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-115 · what acceptance records, what it does not decide, and the one
 * ordering the whole design turns on.
 *
 * <p>The clock is fixed on {@code ObSignoffOtpServiceTest}'s precedent: a
 * timestamp cannot be asserted against a clock that only moves forwards.
 */
class ObSignoffAcceptServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T11:20:00Z");
    private static final String SESSION = "a-session-minted-by-verify";
    private static final long SIGNOFF_ID = 77L;
    private static final long STEP_ID = 501L;
    private static final long CONTACT_ID = 42L;

    private ObSignoffRepository signoffs;
    private ObSignoffSessions sessions;
    private ObSignoffContactReader contacts;
    private ObJourneyStepLifecycleService stepLifecycle;
    private ObSignoffAcceptService service;

    @BeforeEach
    void setUp() {
        signoffs = mock(ObSignoffRepository.class);
        sessions = mock(ObSignoffSessions.class);
        contacts = mock(ObSignoffContactReader.class);
        stepLifecycle = mock(ObJourneyStepLifecycleService.class);
        service = new ObSignoffAcceptService(signoffs, sessions, contacts, stepLifecycle,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(sessions.resolve(SESSION)).thenReturn(OptionalLong.of(SIGNOFF_ID));
        when(contacts.find(CONTACT_ID)).thenReturn(new PublicSignoffAcceptDtos.Contact(
                CONTACT_ID, "Priya Raman", "Head of Ops", "priya@client.example",
                "+91 99999 00000", true, true));
    }

    private ObSignoff pendingStepSignoff() {
        ObSignoff signoff = new ObSignoff();
        signoff.setId(SIGNOFF_ID);
        signoff.setObClientId(9L);
        signoff.setJourneyId(31L);
        signoff.setStepId(STEP_ID);
        signoff.setKind(ObSignoffKind.STEP);
        signoff.setStatus(ObSignoffStatus.PENDING);
        signoff.setSentToContactId(CONTACT_ID);
        signoff.setRequestedAt(NOW.minusSeconds(3600));
        signoff.setTokenExpiresAt(NOW.plusSeconds(86_400));
        return signoff;
    }

    private ObSignoff given(ObSignoff signoff) {
        when(signoffs.findById(SIGNOFF_ID)).thenReturn(Optional.of(signoff));
        return signoff;
    }

    private static HttpServletRequest requestFrom(String ip, String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        if (userAgent != null) {
            request.addHeader("User-Agent", userAgent);
        }
        return request;
    }

    @Nested
    @DisplayName("the recorded acceptance")
    class RecordedAcceptance {

        @Test
        @DisplayName("writes name, timestamp, IP and user agent, and flips the row to SIGNED")
        void recordsTheFourFacts() {
            ObSignoff signoff = given(pendingStepSignoff());
            when(stepLifecycle.completeOnClientAcceptance(STEP_ID)).thenReturn(List.of());

            service.accept(SESSION, "Priya Raman", null,
                    requestFrom("203.0.113.9", "Mozilla/5.0"));

            assertThat(signoff.getStatus()).isEqualTo(ObSignoffStatus.SIGNED);
            assertThat(signoff.getSignedName()).isEqualTo("Priya Raman");
            assertThat(signoff.getSignedAt()).isEqualTo(NOW);
            assertThat(signoff.getSignedIp()).isEqualTo("203.0.113.9");
            assertThat(signoff.getSignedUserAgent()).isEqualTo("Mozilla/5.0");
            verify(signoffs).save(signoff);
        }

        @Test
        @DisplayName("names the contact the link was sent to, never one the caller supplies")
        void signatoryIsTheContactOnTheRow() {
            ObSignoff signoff = given(pendingStepSignoff());
            when(stepLifecycle.completeOnClientAcceptance(STEP_ID)).thenReturn(List.of());

            service.accept(SESSION, "Somebody Else", null, requestFrom("203.0.113.9", "UA"));

            // The OTP proved possession of the mailbox on sent_to_contact_id and
            // nothing else. The typed name is recorded as typed; who signed is
            // ours to say.
            assertThat(signoff.getSignedByContactId()).isEqualTo(CONTACT_ID);
            assertThat(signoff.getSignedName()).isEqualTo("Somebody Else");
        }

        @Test
        @DisplayName("truncates an over-long user agent rather than losing the signature")
        void truncatesUserAgent() {
            ObSignoff signoff = given(pendingStepSignoff());
            when(stepLifecycle.completeOnClientAcceptance(STEP_ID)).thenReturn(List.of());

            service.accept(SESSION, "Priya Raman", null,
                    requestFrom("203.0.113.9", "U".repeat(900)));

            assertThat(signoff.getSignedUserAgent()).hasSize(500);
            assertThat(signoff.getStatus()).isEqualTo(ObSignoffStatus.SIGNED);
        }

        @Test
        @DisplayName("stores an acceptance note on its own column, never as an objection reason")
        void keepsTheNote() {
            ObSignoff signoff = given(pendingStepSignoff());
            when(stepLifecycle.completeOnClientAcceptance(STEP_ID)).thenReturn(List.of());

            service.accept(SESSION, "Priya Raman", "  Happy, subject to the March invoice.  ",
                    requestFrom("203.0.113.9", "UA"));

            assertThat(signoff.getAcceptanceNote()).isEqualTo("Happy, subject to the March invoice.");
            assertThat(signoff.getObjectionNote()).isNull();
        }

        @Test
        @DisplayName("a blank note is null, so the column does not fill with empty strings")
        void blankNoteIsNull() {
            ObSignoff signoff = given(pendingStepSignoff());
            when(stepLifecycle.completeOnClientAcceptance(STEP_ID)).thenReturn(List.of());

            service.accept(SESSION, "Priya Raman", "   ", requestFrom("203.0.113.9", "UA"));

            assertThat(signoff.getAcceptanceNote()).isNull();
        }
    }

    @Nested
    @DisplayName("the completion gate")
    class Gate {

        @Test
        @DisplayName("a satisfied gate completes the step and says so")
        void completesTheStep() {
            given(pendingStepSignoff());
            when(stepLifecycle.completeOnClientAcceptance(STEP_ID)).thenReturn(List.of());

            PublicSignoffAcceptDtos.AcceptResult result =
                    service.accept(SESSION, "Priya Raman", null, requestFrom("203.0.113.9", "UA"));

            assertThat(result.stepCompleted()).isTrue();
            assertThat(result.gateFailures()).isEmpty();
        }

        @Test
        @DisplayName("a refused gate keeps the acceptance — the client is not asked to click twice")
        void acceptanceSurvivesAGateFailure() {
            ObSignoff signoff = given(pendingStepSignoff());
            when(stepLifecycle.completeOnClientAcceptance(STEP_ID))
                    .thenReturn(List.of("ob-step-docs-missing"));

            PublicSignoffAcceptDtos.AcceptResult result =
                    service.accept(SESSION, "Priya Raman", null, requestFrom("203.0.113.9", "UA"));

            // PHASE-2-BUILD-PLAN §3 #4: the acceptance is never discarded for
            // our own incomplete record. This is the assertion that stops a
            // later refactor from turning a gate failure into a rollback.
            assertThat(signoff.getStatus()).isEqualTo(ObSignoffStatus.SIGNED);
            assertThat(signoff.getSignedAt()).isEqualTo(NOW);
            assertThat(result.stepCompleted()).isFalse();
            assertThat(result.gateFailures()).containsExactly("ob-step-docs-missing");
        }

        @Test
        @DisplayName("a GO_LIVE sign-off has no step, so the gate is never consulted")
        void goLiveDoesNotTouchAStep() {
            ObSignoff signoff = given(pendingStepSignoff());
            signoff.setKind(ObSignoffKind.GO_LIVE);
            signoff.setStepId(null);

            PublicSignoffAcceptDtos.AcceptResult result =
                    service.accept(SESSION, "Priya Raman", null, requestFrom("203.0.113.9", "UA"));

            verify(stepLifecycle, never()).completeOnClientAcceptance(anyLong());
            assertThat(signoff.getStatus()).isEqualTo(ObSignoffStatus.SIGNED);
            assertThat(result.stepCompleted()).isFalse();
            assertThat(result.gateFailures()).isEmpty();
        }

        @Test
        @DisplayName("clientWentLive is false until B-118 builds the flip")
        void goLiveFlipIsNotThisTask() {
            ObSignoff signoff = given(pendingStepSignoff());
            signoff.setKind(ObSignoffKind.GO_LIVE);
            signoff.setStepId(null);

            PublicSignoffAcceptDtos.AcceptResult result =
                    service.accept(SESSION, "Priya Raman", null, requestFrom("203.0.113.9", "UA"));

            // Not a placeholder: nothing in the application flips a client to
            // Live-Green today, so no acceptance can have been the one that did.
            // This assertion is expected to change when B-118 lands.
            assertThat(result.clientWentLive()).isFalse();
        }
    }

    @Nested
    @DisplayName("refusals — one generic 401 for all of them")
    class Refusals {

        @Test
        @DisplayName("an unknown or expired session")
        void unknownSession() {
            when(sessions.resolve("nope")).thenReturn(OptionalLong.empty());

            assertThatExceptionOfType(InvalidSignoffTokenException.class).isThrownBy(() ->
                    service.accept("nope", "Priya Raman", null, requestFrom("203.0.113.9", "UA")));
        }

        @Test
        @DisplayName("a session pointing at a row that is gone")
        void sessionWithoutARow() {
            when(signoffs.findById(SIGNOFF_ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(InvalidSignoffTokenException.class).isThrownBy(() ->
                    service.accept(SESSION, "Priya Raman", null, requestFrom("203.0.113.9", "UA")));
        }

        @Test
        @DisplayName("a sign-off already decided in another tab, inside the session's own window")
        void alreadyDecided() {
            ObSignoff signoff = given(pendingStepSignoff());
            signoff.setStatus(ObSignoffStatus.SIGNED);

            // The session lives fifteen minutes and the row can be cancelled or
            // decided inside it, so this check is not redundant with resolve().
            assertThatExceptionOfType(InvalidSignoffTokenException.class).isThrownBy(() ->
                    service.accept(SESSION, "Priya Raman", null, requestFrom("203.0.113.9", "UA")));
            verify(signoffs, never()).save(signoff);
            verify(stepLifecycle, never()).completeOnClientAcceptance(anyLong());
        }

        @Test
        @DisplayName("a refused acceptance does not spend the session")
        void refusalLeavesTheSessionAlone() {
            when(signoffs.findById(SIGNOFF_ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(InvalidSignoffTokenException.class).isThrownBy(() ->
                    service.accept(SESSION, "Priya Raman", null, requestFrom("203.0.113.9", "UA")));
            verify(sessions, never()).invalidate(anyString());
        }
    }

    @Nested
    @DisplayName("single use")
    class SingleUse {

        @Test
        @DisplayName("a successful acceptance spends the session")
        void spendsTheSession() {
            given(pendingStepSignoff());
            when(stepLifecycle.completeOnClientAcceptance(STEP_ID)).thenReturn(List.of());

            service.accept(SESSION, "Priya Raman", null, requestFrom("203.0.113.9", "UA"));

            // Outside a transaction the invalidation runs inline; under one it
            // is deferred to afterCommit. Both spend it — what must not happen
            // is a committed acceptance leaving a live session behind.
            verify(sessions).invalidate(SESSION);
        }

        @Test
        @DisplayName("a gate failure still spends it — the client accepted, and cannot accept twice")
        void spendsItEvenWhenTheGateRefuses() {
            given(pendingStepSignoff());
            when(stepLifecycle.completeOnClientAcceptance(STEP_ID))
                    .thenReturn(List.of("ob-step-items-unanswered"));

            service.accept(SESSION, "Priya Raman", null, requestFrom("203.0.113.9", "UA"));

            verify(sessions).invalidate(SESSION);
        }
    }

    @Nested
    @DisplayName("what the response does not carry")
    class Disclosure {

        @Test
        @DisplayName("requestedBy is null — it names a member of our staff")
        void noStaffNames() {
            given(pendingStepSignoff());
            when(stepLifecycle.completeOnClientAcceptance(STEP_ID)).thenReturn(List.of());

            PublicSignoffAcceptDtos.AcceptResult result =
                    service.accept(SESSION, "Priya Raman", null, requestFrom("203.0.113.9", "UA"));

            assertThat(result.signoff().requestedBy()).isNull();
        }

        @Test
        @DisplayName("the contact echoed back is the one on the row, and carries no consent fields")
        void contactIsTheRowsOwn() {
            given(pendingStepSignoff());
            when(stepLifecycle.completeOnClientAcceptance(STEP_ID)).thenReturn(List.of());

            PublicSignoffAcceptDtos.AcceptResult result =
                    service.accept(SESSION, "Priya Raman", null, requestFrom("203.0.113.9", "UA"));

            assertThat(result.signoff().sentToContact().id()).isEqualTo(CONTACT_ID);
            assertThat(result.signoff().signedByContact().id()).isEqualTo(CONTACT_ID);
            // Contact is a seven-field record. whatsappOptIn and its two
            // companions are not on it, so no mapper can put them on the wire.
            assertThat(PublicSignoffAcceptDtos.Contact.class.getRecordComponents()).hasSize(7);
        }
    }
}
