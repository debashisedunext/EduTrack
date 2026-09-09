package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.domain.onboarding.ObSignoff;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-119 · what the survey records, and the two 422s that are not the
 * surface's usual 401 — the whole reason it needed its own exception types.
 *
 * <p>The clock is fixed on {@code ObSignoffAcceptServiceTest}'s precedent: a
 * timestamp cannot be asserted against a clock that only moves forwards.
 */
class ObSignoffCsatServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T11:20:00Z");
    private static final String SESSION = "a-session-minted-by-verify-and-kept-alive-by-accept";
    private static final long SIGNOFF_ID = 91L;
    private static final long CLIENT_ID = 9L;

    private ObSignoffRepository signoffs;
    private ObSignoffSessions sessions;
    private ObSignoffCsatService service;

    @BeforeEach
    void setUp() {
        signoffs = mock(ObSignoffRepository.class);
        sessions = mock(ObSignoffSessions.class);
        service = new ObSignoffCsatService(signoffs, sessions, Clock.fixed(NOW, ZoneOffset.UTC));

        when(sessions.resolve(SESSION)).thenReturn(OptionalLong.of(SIGNOFF_ID));
    }

    private ObSignoff signedGoLive() {
        ObSignoff signoff = new ObSignoff();
        signoff.setId(SIGNOFF_ID);
        signoff.setObClientId(CLIENT_ID);
        signoff.setJourneyId(31L);
        signoff.setStepId(null);
        signoff.setKind(ObSignoffKind.GO_LIVE);
        signoff.setStatus(ObSignoffStatus.SIGNED);
        signoff.setSentToContactId(42L);
        signoff.setSignedAt(NOW.minusSeconds(60));
        return signoff;
    }

    private ObSignoff given(ObSignoff signoff) {
        when(signoffs.findById(SIGNOFF_ID)).thenReturn(Optional.of(signoff));
        return signoff;
    }

    @Nested
    @DisplayName("recording the answer")
    class RecordingTheAnswer {

        @Test
        @DisplayName("writes the score, the comment and the instant")
        void recordsTheAnswer() {
            ObSignoff signoff = given(signedGoLive());

            service.submit(SESSION, 4, "Onboarding took longer than we expected.");

            assertThat(signoff.getCsatScore()).isEqualTo(4);
            assertThat(signoff.getCsatComment()).isEqualTo("Onboarding took longer than we expected.");
            assertThat(signoff.getCsatSubmittedAt()).isEqualTo(NOW);
            verify(signoffs).save(signoff);
        }

        @Test
        @DisplayName("a blank comment is stored as null, not as empty text")
        void blankCommentIsNull() {
            ObSignoff signoff = given(signedGoLive());

            service.submit(SESSION, 5, "   ");

            assertThat(signoff.getCsatComment()).isNull();
        }

        @Test
        @DisplayName("no comment at all is null")
        void noCommentIsNull() {
            ObSignoff signoff = given(signedGoLive());

            service.submit(SESSION, 5, null);

            assertThat(signoff.getCsatComment()).isNull();
        }

        @Test
        @DisplayName("trims a comment before storing it")
        void trimsTheComment() {
            ObSignoff signoff = given(signedGoLive());

            service.submit(SESSION, 3, "  Fine, could be faster.  ");

            assertThat(signoff.getCsatComment()).isEqualTo("Fine, could be faster.");
        }
    }

    @Nested
    @DisplayName("eligibility — the two 422s")
    class Eligibility {

        @Test
        @DisplayName("a STEP sign-off's session — the contract's own named case")
        void stepSessionIsRefused() {
            ObSignoff signoff = given(signedGoLive());
            signoff.setKind(ObSignoffKind.STEP);
            signoff.setStepId(501L);

            assertThatExceptionOfType(CsatNotOfferedException.class)
                    .isThrownBy(() -> service.submit(SESSION, 5, null));
            verify(signoffs, never()).save(signoff);
        }

        @Test
        @DisplayName("a GO_LIVE session that has not actually been accepted yet")
        void notYetAcceptedIsRefused() {
            ObSignoff signoff = given(signedGoLive());
            signoff.setStatus(ObSignoffStatus.PENDING);
            signoff.setSignedAt(null);

            assertThatExceptionOfType(CsatNotOfferedException.class)
                    .isThrownBy(() -> service.submit(SESSION, 5, null));
            verify(signoffs, never()).save(signoff);
        }

        @Test
        @DisplayName("already surveyed — checked across the client's GO_LIVE sign-offs, not only this row")
        void alreadySurveyedIsRefused() {
            given(signedGoLive());
            when(signoffs.existsByObClientIdAndKindAndCsatSubmittedAtIsNotNull(CLIENT_ID, ObSignoffKind.GO_LIVE))
                    .thenReturn(true);

            assertThatExceptionOfType(CsatAlreadySubmittedException.class)
                    .isThrownBy(() -> service.submit(SESSION, 5, null));
            verify(signoffs, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("an eligible session is not refused")
        void eligibleSessionSucceeds() {
            ObSignoff signoff = given(signedGoLive());
            when(signoffs.existsByObClientIdAndKindAndCsatSubmittedAtIsNotNull(CLIENT_ID, ObSignoffKind.GO_LIVE))
                    .thenReturn(false);

            service.submit(SESSION, 5, null);

            assertThat(signoff.getCsatScore()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("refusals — the surface's generic 401")
    class Refusals {

        @Test
        @DisplayName("an unknown or expired session")
        void unknownSession() {
            when(sessions.resolve("nope")).thenReturn(OptionalLong.empty());

            assertThatExceptionOfType(InvalidSignoffTokenException.class)
                    .isThrownBy(() -> service.submit("nope", 5, null));
        }

        @Test
        @DisplayName("a session pointing at a row that is gone")
        void sessionWithoutARow() {
            when(signoffs.findById(SIGNOFF_ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(InvalidSignoffTokenException.class)
                    .isThrownBy(() -> service.submit(SESSION, 5, null));
        }
    }

    @Nested
    @DisplayName("session spend")
    class SessionSpend {

        @Test
        @DisplayName("a successful submission spends the session — its purpose is served")
        void spendsTheSessionOnSuccess() {
            given(signedGoLive());

            service.submit(SESSION, 5, null);

            verify(sessions).invalidate(SESSION);
        }

        @Test
        @DisplayName("a refused submission (not eligible) leaves the session alone")
        void notEligibleLeavesTheSessionAlone() {
            ObSignoff signoff = given(signedGoLive());
            signoff.setKind(ObSignoffKind.STEP);
            signoff.setStepId(501L);

            assertThatExceptionOfType(CsatNotOfferedException.class)
                    .isThrownBy(() -> service.submit(SESSION, 5, null));

            verify(sessions, never()).invalidate(anyString());
        }

        @Test
        @DisplayName("a refused submission (already surveyed) leaves the session alone")
        void alreadySurveyedLeavesTheSessionAlone() {
            given(signedGoLive());
            when(signoffs.existsByObClientIdAndKindAndCsatSubmittedAtIsNotNull(CLIENT_ID, ObSignoffKind.GO_LIVE))
                    .thenReturn(true);

            assertThatExceptionOfType(CsatAlreadySubmittedException.class)
                    .isThrownBy(() -> service.submit(SESSION, 5, null));

            verify(sessions, never()).invalidate(anyString());
        }
    }
}
