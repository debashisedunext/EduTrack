package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.onboarding.instances.ObJourneyStepLifecycleService;
import com.edunext.edutrack.domain.onboarding.ObSignoff;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotification;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-117 · what an objection records, what it reverts, and the one property
 * the contract states for the whole route: "there is no un-object".
 *
 * <p>The clock is fixed on {@code ObSignoffAcceptServiceTest}'s precedent: a
 * timestamp cannot be asserted against a clock that only moves forwards.
 */
class ObSignoffObjectServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T11:20:00Z");
    private static final String SESSION = "a-session-minted-by-verify";
    private static final long SIGNOFF_ID = 88L;
    private static final long STEP_ID = 501L;
    private static final long CONTACT_ID = 42L;
    private static final long OWNER_ID = 7L;

    private ObSignoffRepository signoffs;
    private ObSignoffSessions sessions;
    private ObSignoffContactReader contacts;
    private ObSignoffPageReader pages;
    private ObJourneyStepLifecycleService stepLifecycle;
    private ObOutboxEnqueuer outbox;
    private ObSignoffObjectService service;

    @BeforeEach
    void setUp() {
        signoffs = mock(ObSignoffRepository.class);
        sessions = mock(ObSignoffSessions.class);
        contacts = mock(ObSignoffContactReader.class);
        pages = mock(ObSignoffPageReader.class);
        stepLifecycle = mock(ObJourneyStepLifecycleService.class);
        outbox = mock(ObOutboxEnqueuer.class);
        service = new ObSignoffObjectService(signoffs, sessions, contacts, pages, stepLifecycle, outbox,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(sessions.resolve(SESSION)).thenReturn(OptionalLong.of(SIGNOFF_ID));
        when(contacts.find(CONTACT_ID)).thenReturn(new PublicSignoffAcceptDtos.Contact(
                CONTACT_ID, "Priya Raman", "Head of Ops", "priya@client.example",
                "+91 99999 00000", true, true));
        when(pages.read(any())).thenReturn(new ObSignoffPageReader.Page(
                "Acme Ltd", "ERP Rollout", "Collect signed agreement", java.util.List.of()));
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

    @Nested
    @DisplayName("the recorded objection")
    class RecordedObjection {

        @Test
        @DisplayName("flips the row to OBJECTED with the note and timestamp")
        void recordsTheObjection() {
            ObSignoff signoff = given(pendingStepSignoff());
            when(stepLifecycle.revertOnClientObjection(STEP_ID, CONTACT_ID, "The invoice total is wrong."))
                    .thenReturn(new ObJourneyStepLifecycleService.ObjectionResult(true, OWNER_ID));

            service.object(SESSION, "The invoice total is wrong.");

            assertThat(signoff.getStatus()).isEqualTo(ObSignoffStatus.OBJECTED);
            assertThat(signoff.getObjectedAt()).isEqualTo(NOW);
            assertThat(signoff.getObjectionNote()).isEqualTo("The invoice total is wrong.");
            verify(signoffs).save(signoff);
        }

        @Test
        @DisplayName("trims the note before storing it")
        void trimsTheNote() {
            ObSignoff signoff = given(pendingStepSignoff());
            when(stepLifecycle.revertOnClientObjection(anyLong(), any(), anyString()))
                    .thenReturn(new ObJourneyStepLifecycleService.ObjectionResult(true, OWNER_ID));

            service.object(SESSION, "  Not what we agreed.  ");

            assertThat(signoff.getObjectionNote()).isEqualTo("Not what we agreed.");
        }
    }

    @Nested
    @DisplayName("the step revert")
    class StepRevert {

        @Test
        @DisplayName("calls the lifecycle service with the sign-off's step, contact and note")
        void callsTheLifecycleService() {
            given(pendingStepSignoff());
            when(stepLifecycle.revertOnClientObjection(STEP_ID, CONTACT_ID, "Please re-check the figures."))
                    .thenReturn(new ObJourneyStepLifecycleService.ObjectionResult(true, OWNER_ID));

            service.object(SESSION, "Please re-check the figures.");

            verify(stepLifecycle).revertOnClientObjection(STEP_ID, CONTACT_ID, "Please re-check the figures.");
        }

        @Test
        @DisplayName("a GO_LIVE sign-off has no step, so the lifecycle service is never consulted")
        void goLiveDoesNotTouchAStep() {
            ObSignoff signoff = given(pendingStepSignoff());
            signoff.setKind(ObSignoffKind.GO_LIVE);
            signoff.setStepId(null);

            service.object(SESSION, "Not going live yet.");

            verify(stepLifecycle, never()).revertOnClientObjection(anyLong(), any(), anyString());
            assertThat(signoff.getStatus()).isEqualTo(ObSignoffStatus.OBJECTED);
        }
    }

    @Nested
    @DisplayName("the owner notification")
    class OwnerNotification {

        @Test
        @DisplayName("enqueues SIGNOFF_OBJECTED on EMAIL and IN_APP to the step's owner")
        void notifiesTheOwner() {
            given(pendingStepSignoff());
            when(stepLifecycle.revertOnClientObjection(STEP_ID, CONTACT_ID, "The invoice total is wrong."))
                    .thenReturn(new ObJourneyStepLifecycleService.ObjectionResult(true, OWNER_ID));

            service.object(SESSION, "The invoice total is wrong.");

            verify(outbox, times(2)).enqueue(any());
        }

        @Test
        @DisplayName("no owner assigned means nothing to notify, not a failure")
        void noOwnerMeansNoNotification() {
            given(pendingStepSignoff());
            when(stepLifecycle.revertOnClientObjection(STEP_ID, CONTACT_ID, "Objecting."))
                    .thenReturn(new ObJourneyStepLifecycleService.ObjectionResult(false, null));

            service.object(SESSION, "Objecting.");

            verify(outbox, never()).enqueue(any());
        }

        @Test
        @DisplayName("a GO_LIVE sign-off notifies nobody through this path — there is no step owner")
        void goLiveNotifiesNobody() {
            ObSignoff signoff = given(pendingStepSignoff());
            signoff.setKind(ObSignoffKind.GO_LIVE);
            signoff.setStepId(null);

            service.object(SESSION, "Not going live yet.");

            verify(outbox, never()).enqueue(any());
        }

        @Test
        @DisplayName("the payload carries the client, step, product and the objection's own reason")
        void payloadCarriesTheFacts() {
            given(pendingStepSignoff());
            when(stepLifecycle.revertOnClientObjection(STEP_ID, CONTACT_ID, "The invoice total is wrong."))
                    .thenReturn(new ObJourneyStepLifecycleService.ObjectionResult(true, OWNER_ID));

            service.object(SESSION, "The invoice total is wrong.");

            org.mockito.ArgumentCaptor<ObNotification> captor = org.mockito.ArgumentCaptor.forClass(ObNotification.class);
            verify(outbox, times(2)).enqueue(captor.capture());
            assertThat(captor.getAllValues()).allSatisfy(notification -> {
                assertThat(notification.eventKey()).isEqualTo("SIGNOFF_OBJECTED");
                assertThat(notification.payload()).containsEntry("client_name", "Acme Ltd");
                assertThat(notification.payload()).containsEntry("step_title", "Collect signed agreement");
                assertThat(notification.payload()).containsEntry("product_name", "ERP Rollout");
                assertThat(notification.payload()).containsEntry("objection_reason", "The invoice total is wrong.");
                assertThat(notification.payload()).containsEntry("objected_by", "Priya Raman");
            });
            assertThat(captor.getAllValues()).extracting(ObNotification::channel)
                    .containsExactlyInAnyOrder(ObChannel.EMAIL, ObChannel.IN_APP);
        }
    }

    @Nested
    @DisplayName("refusals — one generic 401 for all of them, on the accept route's own precedent")
    class Refusals {

        @Test
        @DisplayName("an unknown or expired session")
        void unknownSession() {
            when(sessions.resolve("nope")).thenReturn(OptionalLong.empty());

            assertThatExceptionOfType(InvalidSignoffTokenException.class).isThrownBy(() ->
                    service.object("nope", "Anything"));
        }

        @Test
        @DisplayName("a sign-off already accepted — there is no un-accept, and no object after it")
        void alreadyAccepted() {
            ObSignoff signoff = given(pendingStepSignoff());
            signoff.setStatus(ObSignoffStatus.SIGNED);

            assertThatExceptionOfType(InvalidSignoffTokenException.class).isThrownBy(() ->
                    service.object(SESSION, "Too late"));
            verify(signoffs, never()).save(signoff);
            verify(stepLifecycle, never()).revertOnClientObjection(anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("a sign-off already objected to — there is no un-object, and no second one either")
        void alreadyObjected() {
            ObSignoff signoff = given(pendingStepSignoff());
            signoff.setStatus(ObSignoffStatus.OBJECTED);

            assertThatExceptionOfType(InvalidSignoffTokenException.class).isThrownBy(() ->
                    service.object(SESSION, "Again"));
            verify(signoffs, never()).save(signoff);
        }

        @Test
        @DisplayName("a refused objection does not spend the session")
        void refusalLeavesTheSessionAlone() {
            when(signoffs.findById(SIGNOFF_ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(InvalidSignoffTokenException.class).isThrownBy(() ->
                    service.object(SESSION, "Anything"));
            verify(sessions, never()).invalidate(anyString());
        }
    }

    @Nested
    @DisplayName("single use")
    class SingleUse {

        @Test
        @DisplayName("a successful objection spends the session")
        void spendsTheSession() {
            given(pendingStepSignoff());
            when(stepLifecycle.revertOnClientObjection(anyLong(), any(), anyString()))
                    .thenReturn(new ObJourneyStepLifecycleService.ObjectionResult(true, OWNER_ID));

            service.object(SESSION, "The invoice total is wrong.");

            // Outside a transaction the invalidation runs inline; under one it
            // is deferred to afterCommit. Both spend it.
            verify(sessions).invalidate(SESSION);
        }
    }

    @Nested
    @DisplayName("what the response carries")
    class Disclosure {

        @Test
        @DisplayName("requestedBy is null — it names a member of our staff")
        void noStaffNames() {
            given(pendingStepSignoff());
            when(stepLifecycle.revertOnClientObjection(anyLong(), any(), anyString()))
                    .thenReturn(new ObJourneyStepLifecycleService.ObjectionResult(true, OWNER_ID));

            PublicSignoffObjectDtos.SignoffDetail result = service.object(SESSION, "The invoice total is wrong.");

            assertThat(result.requestedBy()).isNull();
        }

        @Test
        @DisplayName("the contact echoed back is the one the link was sent to")
        void contactIsTheRowsOwn() {
            given(pendingStepSignoff());
            when(stepLifecycle.revertOnClientObjection(anyLong(), any(), anyString()))
                    .thenReturn(new ObJourneyStepLifecycleService.ObjectionResult(true, OWNER_ID));

            PublicSignoffObjectDtos.SignoffDetail result = service.object(SESSION, "The invoice total is wrong.");

            assertThat(result.sentToContact().id()).isEqualTo(CONTACT_ID);
            assertThat(result.status()).isEqualTo(ObSignoffStatus.OBJECTED);
        }
    }
}
