package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.domain.onboarding.ObJourney;
import com.edunext.edutrack.domain.onboarding.ObJourneyRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoff;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the demo simulator is allowed to do, and — the half that matters — what
 * it is not.
 *
 * <p>The load-bearing assertion in this class is
 * {@link Guarantees#writesOnlyAPendingRowAndNeverSignsItItself}. Everything
 * else here is behaviour; that one is the reason the class was written the long
 * way round instead of setting {@code status = SIGNED} in two lines. If it ever
 * fails, the simulator has become a second completion path enforcing different
 * rules from the real one, which is the bug PHASE-2-BUILD-PLAN §3 #4 ruled
 * against.
 */
class DevSignoffSimulationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-14T09:30:00Z");
    private static final long JOURNEY_ID = 900L;
    private static final long CLIENT_ID = 12L;
    private static final long STEP_ID = 501L;
    private static final long CONTACT_ID = 42L;
    private static final long NEW_SIGNOFF_ID = 77L;
    private static final String MINTED = "a-session-this-service-minted";

    private ObSignoffRepository signoffs;
    private ObJourneyRepository journeys;
    private ObJourneyStepRepository steps;
    private ObSignoffSessions sessions;
    private ObSignoffAcceptService accepts;
    private JdbcClient jdbc;
    private DevSignoffSimulationService service;

    @BeforeEach
    void setUp() {
        signoffs = mock(ObSignoffRepository.class);
        journeys = mock(ObJourneyRepository.class);
        steps = mock(ObJourneyStepRepository.class);
        sessions = mock(ObSignoffSessions.class);
        accepts = mock(ObSignoffAcceptService.class);
        // Deep stubs only for JdbcClient: sql().param().query().optional() is
        // four interfaces deep and stubbing each by hand would be four mocks
        // that say nothing about the behaviour under test.
        jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);

        service = new DevSignoffSimulationService(signoffs, journeys, steps, sessions, accepts, jdbc,
                Clock.fixed(NOW, ZoneOffset.UTC));

        ObJourney journey = mock(ObJourney.class);
        when(journey.getId()).thenReturn(JOURNEY_ID);
        when(journey.getObClientId()).thenReturn(CLIENT_ID);
        when(journeys.findById(JOURNEY_ID)).thenReturn(Optional.of(journey));

        // Built before it is handed to when(), not inside the call: stubbing a
        // mock while Mockito is mid-stubbing another is an UnfinishedStubbing.
        ObJourneyStep step = signoffableStep();
        when(steps.findById(STEP_ID)).thenReturn(Optional.of(step));
        contactLookupReturns(Optional.of(CONTACT_ID));

        // saveAndFlush hands back the row with the id the IDENTITY insert
        // assigned, which is what the session is then minted against.
        when(signoffs.saveAndFlush(any(ObSignoff.class))).thenAnswer(invocation -> {
            ObSignoff saved = invocation.getArgument(0);
            saved.setId(NEW_SIGNOFF_ID);
            return saved;
        });

        when(sessions.mint(NEW_SIGNOFF_ID)).thenReturn(
                new ObSignoffSessions.Minted(MINTED, java.time.Duration.ofMinutes(15)));
        when(accepts.accept(anyString(), anyString(), anyString(), eq(null)))
                .thenReturn(acceptResult(true, List.of()));
    }

    private ObJourneyStep signoffableStep() {
        ObJourneyStep step = mock(ObJourneyStep.class);
        when(step.getJourneyId()).thenReturn(JOURNEY_ID);
        when(step.isRequiresSignoff()).thenReturn(true);
        return step;
    }

    @SuppressWarnings("unchecked")
    private void contactLookupReturns(Optional<Long> contactId) {
        when(jdbc.sql(anyString()).param(any()).query(Long.class).optional())
                .thenReturn((Optional<Long>) (Optional<?>) contactId);
    }

    private static PublicSignoffAcceptDtos.AcceptResult acceptResult(boolean completed,
                                                                     List<String> gateFailures) {
        return new PublicSignoffAcceptDtos.AcceptResult(null, completed, gateFailures, false);
    }

    private ObSignoff savedRow() {
        ArgumentCaptor<ObSignoff> captor = ArgumentCaptor.forClass(ObSignoff.class);
        verify(signoffs).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("the guarantees")
    class Guarantees {

        @Test
        @DisplayName("writes only a PENDING row, and never signs it itself")
        void writesOnlyAPendingRowAndNeverSignsItItself() {
            service.simulate(JOURNEY_ID, ObSignoffKind.STEP, STEP_ID, null, null);

            ObSignoff written = savedRow();
            assertThat(written.getStatus())
                    .as("the simulator must hand a PENDING row to accept(), not a signed one — "
                            + "signing it here would be a second completion path that skips the gate")
                    .isEqualTo(ObSignoffStatus.PENDING);
            assertThat(written.getSignedAt()).isNull();
            assertThat(written.getSignedByContactId()).isNull();
            assertThat(written.getSignedName()).isNull();
        }

        @Test
        @DisplayName("the acceptance goes through the real accept(), on a minted session")
        void delegatesToTheRealAccept() {
            service.simulate(JOURNEY_ID, ObSignoffKind.STEP, STEP_ID, null, null);

            verify(sessions).mint(NEW_SIGNOFF_ID);
            verify(accepts).accept(eq(MINTED), anyString(), anyString(), eq(null));
        }

        @Test
        @DisplayName("a gate refusal comes back as a result, not as an exception")
        void gateRefusalIsAResultNotAnError() {
            // The whole point of returning the public surface's own shape: the
            // acceptance stands and our side is what is unfinished. A thrown
            // exception here would report "the simulation failed" for the one
            // outcome the simulation exists to reveal.
            when(accepts.accept(anyString(), anyString(), anyString(), eq(null)))
                    .thenReturn(acceptResult(false, List.of("ob-step-docs-missing")));

            PublicSignoffAcceptDtos.AcceptResult result =
                    service.simulate(JOURNEY_ID, ObSignoffKind.STEP, STEP_ID, null, null);

            assertThat(result.stepCompleted()).isFalse();
            assertThat(result.gateFailures()).containsExactly("ob-step-docs-missing");
        }

        @Test
        @DisplayName("the default signed name cannot be mistaken for a real one")
        void defaultSignedNameIsObviouslySynthetic() {
            service.simulate(JOURNEY_ID, ObSignoffKind.STEP, STEP_ID, "   ", null);

            verify(accepts).accept(eq(MINTED), eq(DevSignoffSimulationService.DEFAULT_SIGNED_NAME),
                    anyString(), eq(null));
        }
    }

    @Nested
    @DisplayName("the row it writes")
    class TheRow {

        @Test
        @DisplayName("carries the journey, its client, the step and an unusable token")
        void carriesTheIdentifyingColumns() {
            service.simulate(JOURNEY_ID, ObSignoffKind.STEP, STEP_ID, null, null);

            ObSignoff written = savedRow();
            assertThat(written.getJourneyId()).isEqualTo(JOURNEY_ID);
            assertThat(written.getObClientId()).isEqualTo(CLIENT_ID);
            assertThat(written.getStepId()).isEqualTo(STEP_ID);
            assertThat(written.getKind()).isEqualTo(ObSignoffKind.STEP);
            assertThat(written.getSentToContactId()).isEqualTo(CONTACT_ID);
            assertThat(written.getRequestedAt()).isEqualTo(NOW);
            // A SHA-256 hex of a value that was discarded — the column cannot
            // yield a working link here any more than it can anywhere else.
            assertThat(written.getTokenHash()).hasSize(64).matches("[0-9a-f]{64}");
            assertThat(written.getTokenExpiresAt()).isAfter(NOW);
        }

        @Test
        @DisplayName("names no requester, because no staff member asked for it")
        void requestedByStaysNull() {
            service.simulate(JOURNEY_ID, ObSignoffKind.STEP, STEP_ID, null, null);

            assertThat(savedRow().getRequestedBy()).isNull();
        }

        @Test
        @DisplayName("two simulations do not reuse a token hash")
        void tokenHashIsFreshEachTime() {
            // uq_ob_signoffs_token is unique, so a constant would make the
            // second simulation a constraint violation on a demo box.
            service.simulate(JOURNEY_ID, ObSignoffKind.STEP, STEP_ID, null, null);
            service.simulate(JOURNEY_ID, ObSignoffKind.STEP, STEP_ID, null, null);

            ArgumentCaptor<ObSignoff> captor = ArgumentCaptor.forClass(ObSignoff.class);
            verify(signoffs, org.mockito.Mockito.times(2)).saveAndFlush(captor.capture());
            assertThat(captor.getAllValues().get(0).getTokenHash())
                    .isNotEqualTo(captor.getAllValues().get(1).getTokenHash());
        }

        @Test
        @DisplayName("a GO_LIVE row names no step, whatever the caller sent")
        void goLiveIgnoresTheStepId() {
            // ck_ob_signoffs_step_matches_kind would refuse the insert; the
            // caller naming the step they were looking at is not a mistake
            // worth a 422.
            service.simulate(JOURNEY_ID, ObSignoffKind.GO_LIVE, STEP_ID, null, null);

            assertThat(savedRow().getStepId()).isNull();
            assertThat(savedRow().getKind()).isEqualTo(ObSignoffKind.GO_LIVE);
        }
    }

    @Nested
    @DisplayName("what it refuses")
    class Refusals {

        @Test
        @DisplayName("an unknown journey is a 404")
        void unknownJourney() {
            when(journeys.findById(404L)).thenReturn(Optional.empty());

            assertThatExceptionOfType(ResponseStatusException.class)
                    .isThrownBy(() -> service.simulate(404L, ObSignoffKind.GO_LIVE, null, null, null))
                    .satisfies(e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("a STEP sign-off with no step is a 422")
        void stepKindNeedsAStep() {
            assertThatExceptionOfType(ResponseStatusException.class)
                    .isThrownBy(() -> service.simulate(JOURNEY_ID, ObSignoffKind.STEP, null, null, null))
                    .satisfies(e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

            verify(signoffs, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a step belonging to another journey is a 404, not a 403")
        void foreignStepIsNotFound() {
            // The row-scoping rule's own reasoning: a caller who guessed an id
            // must not learn from the status code that it exists.
            ObJourneyStep elsewhere = mock(ObJourneyStep.class);
            when(elsewhere.getJourneyId()).thenReturn(JOURNEY_ID + 1);
            when(steps.findById(STEP_ID)).thenReturn(Optional.of(elsewhere));

            assertThatExceptionOfType(ResponseStatusException.class)
                    .isThrownBy(() -> service.simulate(JOURNEY_ID, ObSignoffKind.STEP, STEP_ID, null, null))
                    .satisfies(e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("a step that was never flagged for sign-off is a 422")
        void unflaggedStepIsRefused() {
            ObJourneyStep unflagged = mock(ObJourneyStep.class);
            when(unflagged.getJourneyId()).thenReturn(JOURNEY_ID);
            when(unflagged.isRequiresSignoff()).thenReturn(false);
            when(steps.findById(STEP_ID)).thenReturn(Optional.of(unflagged));

            assertThatExceptionOfType(ResponseStatusException.class)
                    .isThrownBy(() -> service.simulate(JOURNEY_ID, ObSignoffKind.STEP, STEP_ID, null, null))
                    .satisfies(e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

            verify(signoffs, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a client with no active contact is a 422 that says what to do")
        void noContactToSignAgainst() {
            contactLookupReturns(Optional.empty());

            assertThatExceptionOfType(ResponseStatusException.class)
                    .isThrownBy(() -> service.simulate(JOURNEY_ID, ObSignoffKind.STEP, STEP_ID, null, null))
                    .satisfies(e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                    .satisfies(e -> assertThat(e.getReason()).contains("Add a SPOC"));
        }
    }
}
