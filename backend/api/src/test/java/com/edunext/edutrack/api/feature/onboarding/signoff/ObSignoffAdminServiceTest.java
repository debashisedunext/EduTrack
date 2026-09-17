package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoff;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotification;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The staff sign-off surface: every refusal the contract names, and the two
 * properties that are easy to get wrong and expensive to get wrong.
 *
 * <p>The clock is fixed on {@code ObSignoffAcceptServiceTest}'s precedent — a
 * token expiry cannot be asserted against a clock that only moves forwards.
 */
class ObSignoffAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-15T09:00:00Z");
    private static final long JOURNEY_ID = 18L;
    private static final long STEP_ID = 68L;
    private static final long CLIENT_ID = 15L;
    private static final long CONTACT_ID = 7L;
    private static final long ACTOR_ID = 1L;

    /** ADMIN — the scope predicate is exercised in the IT, against real SQL. */
    private static final ObClientScope SCOPE = new ObClientScope("OB_ADMIN", ACTOR_ID);

    private ObSignoffRepository signoffs;
    private ObSignoffAdminRepository reads;
    private ObJourneyStepRepository steps;
    private ObSignoffPageReader pages;
    private ObOutboxEnqueuer outbox;
    private ObSignoffAdminService service;

    @BeforeEach
    void setUp() {
        signoffs = mock(ObSignoffRepository.class);
        reads = mock(ObSignoffAdminRepository.class);
        steps = mock(ObJourneyStepRepository.class);
        pages = mock(ObSignoffPageReader.class);
        outbox = mock(ObOutboxEnqueuer.class);
        service = new ObSignoffAdminService(signoffs, reads, steps, pages, outbox,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(reads.findJourney(any(), anyLong()))
                .thenReturn(Optional.of(new ObSignoffAdminRepository.SignoffJourney(JOURNEY_ID, CLIENT_ID)));
        when(reads.isActiveContactOf(CLIENT_ID, CONTACT_ID)).thenReturn(true);
        when(steps.findById(STEP_ID)).thenReturn(Optional.of(signoffableStep()));
        when(signoffs.saveAndFlush(any())).thenAnswer(call -> {
            ObSignoff saved = call.getArgument(0);
            saved.setId(2L);
            return saved;
        });
        when(reads.find(any(), anyLong())).thenReturn(Optional.of(row(ObSignoffStatus.PENDING)));
        when(pages.read(any())).thenReturn(new ObSignoffPageReader.Page(
                "saphire international school", "EDUNEXT-ERP", "Admission No Scheme", List.of()));
        when(outbox.enqueue(any())).thenReturn(OptionalLong.of(1L));
    }

    private static ObJourneyStep signoffableStep() {
        ObJourneyStep step = new ObJourneyStep();
        step.setId(STEP_ID);
        step.setJourneyId(JOURNEY_ID);
        step.setRequiresSignoff(true);
        return step;
    }

    private static ObSignoffAdminRepository.Row row(ObSignoffStatus status) {
        return new ObSignoffAdminRepository.Row(
                2L, CLIENT_ID, JOURNEY_ID, STEP_ID, ObSignoffKind.STEP, status,
                ACTOR_ID, "Priya Nair", NOW, NOW.plusSeconds(60), null, null,
                false, null, null, null,
                new ObSignoffAdminDtos.ObContact(CONTACT_ID, "sumit yadav", null, "sumit@gmail.com",
                        null, false, null, null, true, true),
                null);
    }

    private static ObSignoffAdminDtos.ObSignoffRequestBody stepRequest() {
        return new ObSignoffAdminDtos.ObSignoffRequestBody(ObSignoffKind.STEP, STEP_ID, CONTACT_ID);
    }

    private ObSignoff captureSaved() {
        ArgumentCaptor<ObSignoff> saved = ArgumentCaptor.forClass(ObSignoff.class);
        verify(signoffs).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    @Nested
    @DisplayName("requesting one")
    class Requesting {

        @Test
        @DisplayName("writes a PENDING row against the step, attributed to the requester")
        void writesPending() {
            service.request(SCOPE, JOURNEY_ID, stepRequest(), ACTOR_ID);

            ObSignoff written = captureSaved();
            assertThat(written.getStatus()).isEqualTo(ObSignoffStatus.PENDING);
            assertThat(written.getStepId()).isEqualTo(STEP_ID);
            assertThat(written.getObClientId()).isEqualTo(CLIENT_ID);
            assertThat(written.getSentToContactId()).isEqualTo(CONTACT_ID);
            assertThat(written.getRequestedBy()).isEqualTo(ACTOR_ID);
            assertThat(written.getRequestedAt()).isEqualTo(NOW);
        }

        /**
         * The property the whole record depends on. A-107 stores only the
         * SHA-256 so that reading the table cannot yield a working link; a
         * regression that stored the plaintext would be invisible to every
         * other test here, because everything else would still work.
         */
        @Test
        @DisplayName("stores a 64-character hash, never anything a reader could use as a link")
        void storesOnlyAHash() {
            service.request(SCOPE, JOURNEY_ID, stepRequest(), ACTOR_ID);

            ObSignoff written = captureSaved();
            assertThat(written.getTokenHash())
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
            assertThat(written.getTokenExpiresAt()).isEqualTo(NOW.plus(java.time.Duration.ofDays(14)));
        }

        /** Two requests must not collide on {@code uq_ob_signoffs_token}. */
        @Test
        @DisplayName("mints a different token every time")
        void mintsAFreshTokenEachTime() {
            service.request(SCOPE, JOURNEY_ID, stepRequest(), ACTOR_ID);
            String first = captureSaved().getTokenHash();

            setUp();
            service.request(SCOPE, JOURNEY_ID, stepRequest(), ACTOR_ID);
            String second = captureSaved().getTokenHash();

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("mails the named contact a link, and the link is the only way in")
        void mailsTheContact() {
            service.request(SCOPE, JOURNEY_ID, stepRequest(), ACTOR_ID);

            ArgumentCaptor<ObNotification> queued = ArgumentCaptor.forClass(ObNotification.class);
            verify(outbox).enqueue(queued.capture());
            ObNotification mail = queued.getValue();

            assertThat(mail.eventKey()).isEqualTo(ObNotificationEvent.SIGNOFF_REQUESTED.name());
            assertThat(mail.recipient()).isEqualTo(new ObRecipient.Client(CONTACT_ID));
            assertThat(mail.payload())
                    .containsEntry("client_name", "saphire international school")
                    .containsEntry("step_title", "Admission No Scheme")
                    .containsEntry("product_name", "EDUNEXT-ERP");
            assertThat((String) mail.payload().get("action_url")).startsWith("/signoff?token=");
        }

        /**
         * The mail carries the only live token, so a resend that dedupes away
         * is a client who can never sign. {@code aboutStep}'s default key is
         * (event, channel, step, contact) — identical for both mails.
         */
        @Test
        @DisplayName("dedupes on the sign-off, not the step, so a second link is a second mail")
        void dedupeKeyNamesTheSignoff() {
            service.request(SCOPE, JOURNEY_ID, stepRequest(), ACTOR_ID);

            ArgumentCaptor<ObNotification> queued = ArgumentCaptor.forClass(ObNotification.class);
            verify(outbox).enqueue(queued.capture());
            assertThat(queued.getValue().dedupeKey()).contains(":2:");
        }

        @Test
        @DisplayName("refuses a STEP request with no step — 400")
        void stepIdRequired() {
            var body = new ObSignoffAdminDtos.ObSignoffRequestBody(ObSignoffKind.STEP, null, CONTACT_ID);

            assertThatExceptionOfType(ObSignoffKindMismatchException.class)
                    .isThrownBy(() -> service.request(SCOPE, JOURNEY_ID, body, ACTOR_ID));
            verify(signoffs, never()).saveAndFlush(any());
        }

        /**
         * Refused rather than ignored. {@code DevSignoffSimulationService}
         * ignores it, and is right to for a demo button; on the real route a
         * silently dropped field is how a caller comes to believe they
         * requested a step sign-off and got one.
         */
        @Test
        @DisplayName("refuses a GO_LIVE request that names a step — 400")
        void stepIdForbiddenOnGoLive() {
            var body = new ObSignoffAdminDtos.ObSignoffRequestBody(ObSignoffKind.GO_LIVE, STEP_ID, CONTACT_ID);

            assertThatExceptionOfType(ObSignoffKindMismatchException.class)
                    .isThrownBy(() -> service.request(SCOPE, JOURNEY_ID, body, ACTOR_ID));
            verify(signoffs, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("refuses a step whose template never asked for a sign-off — 422")
        void refusesUnflaggedStep() {
            ObJourneyStep unflagged = signoffableStep();
            unflagged.setRequiresSignoff(false);
            when(steps.findById(STEP_ID)).thenReturn(Optional.of(unflagged));

            assertThatExceptionOfType(ObSignoffNotSignoffableException.class)
                    .isThrownBy(() -> service.request(SCOPE, JOURNEY_ID, stepRequest(), ACTOR_ID));
        }

        /** 404, not 422 — a guessed id must not confirm that it exists elsewhere. */
        @Test
        @DisplayName("refuses a step belonging to another journey — 404")
        void refusesForeignStep() {
            ObJourneyStep elsewhere = signoffableStep();
            elsewhere.setJourneyId(999L);
            when(steps.findById(STEP_ID)).thenReturn(Optional.of(elsewhere));

            assertThatExceptionOfType(ObSignoffNotFoundException.class)
                    .isThrownBy(() -> service.request(SCOPE, JOURNEY_ID, stepRequest(), ACTOR_ID));
        }

        @Test
        @DisplayName("refuses a journey the caller cannot see — 404, never 403")
        void refusesOutOfScopeJourney() {
            when(reads.findJourney(any(), anyLong())).thenReturn(Optional.empty());

            assertThatExceptionOfType(ObSignoffNotFoundException.class)
                    .isThrownBy(() -> service.request(SCOPE, JOURNEY_ID, stepRequest(), ACTOR_ID));
            verify(signoffs, never()).saveAndFlush(any());
        }

        /**
         * The foreign key would accept this: it constrains the contact to
         * exist, not to belong to this client. A sign-off mailed to another
         * client's SPOC is a cross-client disclosure.
         */
        @Test
        @DisplayName("refuses a contact that is not this client's own active SPOC — 422")
        void refusesForeignContact() {
            when(reads.isActiveContactOf(CLIENT_ID, CONTACT_ID)).thenReturn(false);

            assertThatExceptionOfType(ObSignoffContactInvalidException.class)
                    .isThrownBy(() -> service.request(SCOPE, JOURNEY_ID, stepRequest(), ACTOR_ID));
            verify(outbox, never()).enqueue(any());
        }

        @Test
        @DisplayName("refuses a second live request for one decision — 409, naming the open one")
        void refusesASecondPending() {
            ObSignoff open = new ObSignoff();
            open.setId(41L);
            when(signoffs.findFirstByStepIdAndKindAndStatusOrderByIdAsc(
                    STEP_ID, ObSignoffKind.STEP, ObSignoffStatus.PENDING)).thenReturn(Optional.of(open));

            assertThatExceptionOfType(ObSignoffAlreadyPendingException.class)
                    .isThrownBy(() -> service.request(SCOPE, JOURNEY_ID, stepRequest(), ACTOR_ID))
                    .satisfies(e -> assertThat(e.existingId()).isEqualTo(41L));
        }

        @Test
        @DisplayName("refuses a go-live while any service is still running — 422")
        void refusesPrematureGoLive() {
            when(reads.unfinishedStepsOn(JOURNEY_ID)).thenReturn(3L);
            var body = new ObSignoffAdminDtos.ObSignoffRequestBody(ObSignoffKind.GO_LIVE, null, CONTACT_ID);

            assertThatExceptionOfType(ObSignoffJourneyIncompleteException.class)
                    .isThrownBy(() -> service.request(SCOPE, JOURNEY_ID, body, ACTOR_ID));
            verify(signoffs, never()).saveAndFlush(any());
        }

        /**
         * {@code SIGNOFF_REQUESTED} declares {@code step_title} required and a
         * go-live names no step, so the reader returns null for it. Rendering a
         * required variable as nothing leaves "Please sign off:" with a blank
         * after the colon.
         */
        @Test
        @DisplayName("says Go-live in the mail where a step sign-off would name the service")
        void goLiveMailHasATitle() {
            when(reads.unfinishedStepsOn(JOURNEY_ID)).thenReturn(0L);
            when(pages.read(any())).thenReturn(new ObSignoffPageReader.Page(
                    "saphire international school", "EDUNEXT-ERP", null, List.of()));
            var body = new ObSignoffAdminDtos.ObSignoffRequestBody(ObSignoffKind.GO_LIVE, null, CONTACT_ID);

            service.request(SCOPE, JOURNEY_ID, body, ACTOR_ID);

            ArgumentCaptor<ObNotification> queued = ArgumentCaptor.forClass(ObNotification.class);
            verify(outbox).enqueue(queued.capture());
            assertThat(queued.getValue().payload()).containsEntry("step_title", "Go-live");
        }
    }

    @Nested
    @DisplayName("resending")
    class Resending {

        private ObSignoff pending() {
            ObSignoff signoff = new ObSignoff();
            signoff.setId(2L);
            signoff.setStatus(ObSignoffStatus.PENDING);
            signoff.setKind(ObSignoffKind.STEP);
            signoff.setStepId(STEP_ID);
            signoff.setJourneyId(JOURNEY_ID);
            signoff.setObClientId(CLIENT_ID);
            signoff.setSentToContactId(CONTACT_ID);
            signoff.setTokenHash("0".repeat(64));
            signoff.setTokenExpiresAt(NOW.minusSeconds(1));
            signoff.setOtpHash("f".repeat(64));
            signoff.setOtpExpiresAt(NOW);
            signoff.setOtpAttempts(3);
            return signoff;
        }

        /**
         * "Resend" is a mint. There is nothing to send again — only the
         * SHA-256 is stored — and the contract wants it that way regardless:
         * two working links to one decision leave a record that cannot say
         * which was used.
         */
        @Test
        @DisplayName("replaces the token, so the previous link stops working")
        void mintsAFreshTokenAndKillsTheOld() {
            ObSignoff signoff = pending();
            String before = signoff.getTokenHash();
            when(signoffs.findById(2L)).thenReturn(Optional.of(signoff));

            service.resend(SCOPE, 2L);

            assertThat(signoff.getTokenHash()).isNotEqualTo(before).hasSize(64);
            assertThat(signoff.getTokenExpiresAt()).isEqualTo(NOW.plus(java.time.Duration.ofDays(14)));
            verify(outbox).enqueue(any());
        }

        /** The lockout slows an attacker guessing at a link they hold. That link is now dead. */
        @Test
        @DisplayName("resets the OTP and its attempt counter with the token")
        void resetsTheOtp() {
            ObSignoff signoff = pending();
            when(signoffs.findById(2L)).thenReturn(Optional.of(signoff));

            service.resend(SCOPE, 2L);

            assertThat(signoff.getOtpHash()).isNull();
            assertThat(signoff.getOtpExpiresAt()).isNull();
            assertThat(signoff.getOtpAttempts()).isZero();
        }

        /**
         * Left at EXPIRED beside a live token, the row is in a state
         * {@code ObSignoffTokens} refuses — it checks status <em>and</em>
         * expiry — so the client would follow a good link to a generic 401.
         */
        @Test
        @DisplayName("returns an expired sign-off to PENDING rather than leaving a live token on a dead row")
        void expiredBecomesPendingAgain() {
            ObSignoff signoff = pending();
            signoff.setStatus(ObSignoffStatus.EXPIRED);
            when(signoffs.findById(2L)).thenReturn(Optional.of(signoff));

            service.resend(SCOPE, 2L);

            assertThat(signoff.getStatus()).isEqualTo(ObSignoffStatus.PENDING);
        }

        @Test
        @DisplayName("refuses a decision already made — 422 for SIGNED, OBJECTED and CANCELLED")
        void refusesSettled() {
            for (ObSignoffStatus settled : List.of(ObSignoffStatus.SIGNED,
                    ObSignoffStatus.OBJECTED, ObSignoffStatus.CANCELLED)) {
                ObSignoff signoff = pending();
                signoff.setStatus(settled);
                when(signoffs.findById(2L)).thenReturn(Optional.of(signoff));

                assertThatExceptionOfType(ObSignoffSettledException.class)
                        .as("resend on %s", settled)
                        .isThrownBy(() -> service.resend(SCOPE, 2L));
            }
            verify(outbox, never()).enqueue(any());
        }

        @Test
        @DisplayName("refuses a sign-off the caller cannot see — 404, never 403")
        void refusesOutOfScope() {
            when(reads.find(any(), anyLong())).thenReturn(Optional.empty());

            assertThatExceptionOfType(ObSignoffNotFoundException.class)
                    .isThrownBy(() -> service.resend(SCOPE, 2L));
            verify(signoffs, never()).findById(anyLong());
        }
    }

    @Nested
    @DisplayName("cancelling")
    class Cancelling {

        private ObSignoff pending() {
            ObSignoff signoff = new ObSignoff();
            signoff.setId(2L);
            signoff.setStatus(ObSignoffStatus.PENDING);
            signoff.setKind(ObSignoffKind.STEP);
            signoff.setTokenHash("0".repeat(64));
            signoff.setTokenExpiresAt(NOW.plusSeconds(600));
            return signoff;
        }

        /**
         * The status change <em>is</em> the revocation:
         * {@code ObSignoffTokens} treats anything but PENDING as unusable, so
         * no second mechanism is needed to kill the link.
         */
        @Test
        @DisplayName("records who withdrew it, when, and why — and the row survives")
        void recordsTheWithdrawal() {
            ObSignoff signoff = pending();
            when(signoffs.findById(2L)).thenReturn(Optional.of(signoff));
            when(reads.find(any(), anyLong())).thenReturn(Optional.of(row(ObSignoffStatus.CANCELLED)));

            service.cancel(SCOPE, 2L, "Sent to the wrong SPOC.", ACTOR_ID);

            assertThat(signoff.getStatus()).isEqualTo(ObSignoffStatus.CANCELLED);
            assertThat(signoff.getCancelledAt()).isEqualTo(NOW);
            assertThat(signoff.getCancelledBy()).isEqualTo(ACTOR_ID);
            assertThat(signoff.getCancellationReason()).isEqualTo("Sent to the wrong SPOC.");
        }

        @Test
        @DisplayName("refuses to withdraw a decision the client has already made — 422")
        void refusesSettled() {
            for (ObSignoffStatus decided : List.of(ObSignoffStatus.SIGNED, ObSignoffStatus.OBJECTED)) {
                ObSignoff signoff = pending();
                signoff.setStatus(decided);
                when(signoffs.findById(2L)).thenReturn(Optional.of(signoff));

                assertThatExceptionOfType(ObSignoffSettledException.class)
                        .as("cancel on %s", decided)
                        .isThrownBy(() -> service.cancel(SCOPE, 2L, "too late", ACTOR_ID));
            }
        }

        /** The link is already dead; refusing would leave a typo uncorrectable. */
        @Test
        @DisplayName("allows re-cancelling, so a mistyped reason can be corrected")
        void cancellingTwiceIsAllowed() {
            ObSignoff signoff = pending();
            signoff.setStatus(ObSignoffStatus.CANCELLED);
            signoff.setCancellationReason("wrong reason");
            when(signoffs.findById(2L)).thenReturn(Optional.of(signoff));
            when(reads.find(any(), anyLong())).thenReturn(Optional.of(row(ObSignoffStatus.CANCELLED)));

            service.cancel(SCOPE, 2L, "the right reason", ACTOR_ID);

            assertThat(signoff.getCancellationReason()).isEqualTo("the right reason");
        }

        /** Nothing in this class may decide a sign-off. That belongs to the client. */
        @Test
        @DisplayName("never writes SIGNED or OBJECTED")
        void neverDecides() {
            ObSignoff signoff = pending();
            when(signoffs.findById(2L)).thenReturn(Optional.of(signoff));
            when(reads.find(any(), anyLong())).thenReturn(Optional.of(row(ObSignoffStatus.CANCELLED)));

            service.cancel(SCOPE, 2L, "withdrawn", ACTOR_ID);

            assertThat(signoff.getStatus()).isNotIn(ObSignoffStatus.SIGNED, ObSignoffStatus.OBJECTED);
            assertThat(signoff.getSignedAt()).isNull();
            assertThat(signoff.getObjectedAt()).isNull();
        }
    }
}
