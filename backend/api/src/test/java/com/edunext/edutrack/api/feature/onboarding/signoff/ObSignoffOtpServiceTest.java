package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.domain.onboarding.ObSignoff;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-121 · what the OTP does, and the four things it must refuse to tell anybody.
 *
 * <p>The clock is fixed rather than {@code systemUTC}, for the reason
 * {@code ObSignoffTokens} records: an expiry cannot be asserted against a clock
 * that only moves forwards, and a test that has to sit either side of one
 * becomes a date-dependent flake the day it is written.
 */
class ObSignoffOtpServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final String TOKEN = "a-link-from-an-email";

    private final ObSignoffRepository signoffs = mock(ObSignoffRepository.class);
    private final PublicSignoffAccess access = mock(PublicSignoffAccess.class);
    private final ObSignoffSessions sessions = mock(ObSignoffSessions.class);
    private final ObSignoffPageReader pages = mock(ObSignoffPageReader.class);
    private final ObOutboxEnqueuer outbox = mock(ObOutboxEnqueuer.class);

    private final ObSignoffOtpService service = new ObSignoffOtpService(
            signoffs, access, sessions, pages, outbox,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static ObSignoff signoff(ObSignoffKind kind) {
        ObSignoff s = new ObSignoff();
        s.setId(42L);
        s.setObClientId(7L);
        s.setJourneyId(9L);
        s.setStepId(kind == ObSignoffKind.STEP ? 11L : null);
        s.setKind(kind);
        s.setSentToContactId(55L);
        return s;
    }

    @BeforeEach
    void pageIsBoring() {
        when(pages.read(any())).thenReturn(
                new ObSignoffPageReader.Page("Acme Ltd", "LMS", "Kick-off", List.of()));
        when(sessions.mint(anyLong()))
                .thenReturn(new ObSignoffSessions.Minted("session-token", Duration.ofMinutes(15)));
        when(outbox.enqueue(any())).thenReturn(OptionalLong.of(1L));
    }

    @Nested
    @DisplayName("issuing a code")
    class Issue {

        @Test
        @DisplayName("an unresolvable token changes nothing and raises nothing")
        void unknownTokenIsSilent() {
            // The enumeration oracle this whole surface exists to close. The
            // route answers 202 either way, so the only way to tell a real
            // token from a guess would be a side effect — and there is none.
            when(access.resolveQuietly(anyString(), any())).thenReturn(Optional.empty());

            service.issue(TOKEN, null);

            verify(signoffs, never()).save(any());
            verify(outbox, never()).enqueue(any());
        }

        @Test
        @DisplayName("stores a hash and an expiry, never the code itself")
        void storesAHashAndAnExpiry() {
            ObSignoff row = signoff(ObSignoffKind.STEP);
            when(access.resolveQuietly(anyString(), any())).thenReturn(Optional.of(row));

            service.issue(TOKEN, null);

            assertThat(row.getOtpHash()).isNotNull().hasSize(64);
            assertThat(row.getOtpExpiresAt()).isEqualTo(NOW.plus(ObSignoffOtpService.OTP_TTL));
            verify(signoffs).save(row);
        }

        @Test
        @DisplayName("the stored hash is the hash of the code that was mailed")
        void theMailedCodeIsTheStoredOne() {
            // The one assertion that proves the two halves belong together. A
            // service that hashed one code and mailed another would satisfy
            // every other test here and lock every client out.
            ObSignoff row = signoff(ObSignoffKind.STEP);
            when(access.resolveQuietly(anyString(), any())).thenReturn(Optional.of(row));

            service.issue(TOKEN, null);

            ArgumentCaptor<ObNotification> sent = ArgumentCaptor.forClass(ObNotification.class);
            verify(outbox).enqueue(sent.capture());
            String mailed = (String) sent.getValue().payload().get("otp_code");

            assertThat(ObSignoffOtpCodes.matches(mailed, row.getOtpHash())).isTrue();
        }

        @Test
        @DisplayName("mails the contact on the row — never an address from the request")
        void mailsTheContactOnTheRow() {
            // The contract: a body that could name its own recipient would let
            // anyone holding a leaked link redirect the code to themselves,
            // which would make the second factor a formality. issue() takes no
            // address, and this is what keeps it that way.
            ObSignoff row = signoff(ObSignoffKind.STEP);
            when(access.resolveQuietly(anyString(), any())).thenReturn(Optional.of(row));

            service.issue(TOKEN, null);

            ArgumentCaptor<ObNotification> sent = ArgumentCaptor.forClass(ObNotification.class);
            verify(outbox).enqueue(sent.capture());

            assertThat(sent.getValue().eventKey()).isEqualTo(ObNotificationEvent.SIGNOFF_OTP.name());
            assertThat(sent.getValue().recipient()).isEqualTo(new ObRecipient.Client(55L));
        }

        @Test
        @DisplayName("asking for a new code does not refill the attempt budget")
        void doesNotResetAttempts() {
            // Otherwise the persisted counter is a formality: burn two guesses,
            // ask for a fresh code, burn two more, forever. That is exactly the
            // unlimited grinding A-107 put the column in the table to stop.
            ObSignoff row = signoff(ObSignoffKind.STEP);
            row.setOtpAttempts(2);
            when(access.resolveQuietly(anyString(), any())).thenReturn(Optional.of(row));

            service.issue(TOKEN, null);

            assertThat(row.getOtpAttempts()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("verifying a code")
    class Verify {

        private ObSignoff withCode(String code) {
            ObSignoff row = signoff(ObSignoffKind.STEP);
            row.setOtpHash(ObSignoffOtpCodes.hash(code));
            row.setOtpExpiresAt(NOW.plusSeconds(60));
            when(access.require(anyString(), any())).thenReturn(row);
            return row;
        }

        @Test
        @DisplayName("the right code mints a session and returns the page")
        void theRightCodeWorks() {
            withCode("123456");

            PublicSignoffOtpDtos.Session session = service.verify(TOKEN, "123456", null);

            assertThat(session.sessionToken()).isEqualTo("session-token");
            assertThat(session.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
            assertThat(session.obClientName()).isEqualTo("Acme Ltd");
            assertThat(session.stepTitle()).isEqualTo("Kick-off");
        }

        @Test
        @DisplayName("a used code cannot be replayed — the hash is cleared on success")
        void theCodeIsSingleUse() {
            // Leaving the hash in place would let the same six digits be
            // replayed from anywhere for as long as the window lasts, which is
            // the property the second factor exists to provide.
            ObSignoff row = withCode("123456");

            service.verify(TOKEN, "123456", null);

            assertThat(row.getOtpHash()).isNull();
            assertThat(row.getOtpExpiresAt()).isNull();
        }

        @Test
        @DisplayName("a wrong code is refused and costs an attempt")
        void aWrongCodeCostsAnAttempt() {
            ObSignoff row = withCode("123456");

            assertThatExceptionOfType(InvalidSignoffTokenException.class)
                    .isThrownBy(() -> service.verify(TOKEN, "999999", null));

            assertThat(row.getOtpAttempts()).isEqualTo(1);
            verify(signoffs).save(row);
        }

        @Test
        @DisplayName("the attempt is spent before the refusal, not after")
        void theAttemptIsPersisted() {
            // Inside the transaction and before the throw, so a caller who
            // disconnects mid-request has still spent it. A counter incremented
            // after the exception is a counter that never moves.
            ObSignoff row = withCode("123456");
            row.setOtpAttempts(ObSignoffOtpService.MAX_ATTEMPTS - 1);

            assertThatExceptionOfType(InvalidSignoffTokenException.class)
                    .isThrownBy(() -> service.verify(TOKEN, "000000", null));

            assertThat(row.getOtpAttempts()).isEqualTo(ObSignoffOtpService.MAX_ATTEMPTS);
        }

        @Test
        @DisplayName("exhausting the attempts kills the link, even for the right code")
        void exhaustedAttemptsKillTheLink() {
            // The contract: a fresh one needs a resend from the staff side,
            // "which is a human deciding to re-ask rather than a counter
            // healing itself". The right code arriving fourth is still refused.
            ObSignoff row = withCode("123456");
            row.setOtpAttempts(ObSignoffOtpService.MAX_ATTEMPTS);

            assertThatExceptionOfType(InvalidSignoffTokenException.class)
                    .isThrownBy(() -> service.verify(TOKEN, "123456", null));

            verify(sessions, never()).mint(anyLong());
        }

        @Test
        @DisplayName("an expired code is refused")
        void anExpiredCodeIsRefused() {
            ObSignoff row = withCode("123456");
            row.setOtpExpiresAt(NOW.minusSeconds(1));

            assertThatExceptionOfType(InvalidSignoffTokenException.class)
                    .isThrownBy(() -> service.verify(TOKEN, "123456", null));
        }

        @Test
        @DisplayName("a sign-off nobody asked for a code on is refused")
        void noCodeRequestedIsRefused() {
            ObSignoff row = signoff(ObSignoffKind.STEP);
            when(access.require(anyString(), any())).thenReturn(row);

            assertThatExceptionOfType(InvalidSignoffTokenException.class)
                    .isThrownBy(() -> service.verify(TOKEN, "123456", null));
        }

        @Test
        @DisplayName("every failure raises the same exception — there is nothing to tell apart")
        void everyFailureIsIdentical() {
            // The property the whole surface rests on, asserted as one test
            // rather than trusted across five. If any branch above ever grows
            // its own exception type, the handler renders a different body and
            // the caller can distinguish "wrong code" from "no such link".
            ObSignoff expired = withCode("123456");
            expired.setOtpExpiresAt(NOW.minusSeconds(1));
            Class<?> fromExpiry = catchType(() -> service.verify(TOKEN, "123456", null));

            ObSignoff wrong = withCode("123456");
            Class<?> fromWrongCode = catchType(() -> service.verify(TOKEN, "999999", null));

            ObSignoff spent = withCode("123456");
            spent.setOtpAttempts(ObSignoffOtpService.MAX_ATTEMPTS);
            Class<?> fromExhaustion = catchType(() -> service.verify(TOKEN, "123456", null));

            assertThat(fromExpiry).isEqualTo(InvalidSignoffTokenException.class);
            assertThat(fromWrongCode).isEqualTo(fromExpiry);
            assertThat(fromExhaustion).isEqualTo(fromExpiry);
        }

        @Test
        @DisplayName("csatOffered is true for a go-live and false for a step")
        void csatIsOfferedOnlyOnGoLive() {
            ObSignoff goLive = signoff(ObSignoffKind.GO_LIVE);
            goLive.setOtpHash(ObSignoffOtpCodes.hash("123456"));
            goLive.setOtpExpiresAt(NOW.plusSeconds(60));
            when(access.require(anyString(), any())).thenReturn(goLive);
            assertThat(service.verify(TOKEN, "123456", null).csatOffered()).isTrue();

            withCode("123456");
            assertThat(service.verify(TOKEN, "123456", null).csatOffered()).isFalse();
        }

        @Test
        @DisplayName("csatOffered is false once the client has already been surveyed (B-119)")
        void csatIsNotOfferedTwice() {
            // The client-level guard, not the row's own: this exact GO_LIVE
            // signoff has never itself carried a csat_submitted_at, but the
            // repository says the client already answered through a sibling
            // journey, and that is enough to stop offering the question again.
            ObSignoff goLive = signoff(ObSignoffKind.GO_LIVE);
            goLive.setOtpHash(ObSignoffOtpCodes.hash("123456"));
            goLive.setOtpExpiresAt(NOW.plusSeconds(60));
            when(access.require(anyString(), any())).thenReturn(goLive);
            when(signoffs.existsByObClientIdAndKindAndCsatSubmittedAtIsNotNull(7L, ObSignoffKind.GO_LIVE))
                    .thenReturn(true);

            assertThat(service.verify(TOKEN, "123456", null).csatOffered()).isFalse();
        }

        private static Class<?> catchType(Runnable call) {
            try {
                call.run();
                return null;
            } catch (RuntimeException e) {
                return e.getClass();
            }
        }
    }
}
