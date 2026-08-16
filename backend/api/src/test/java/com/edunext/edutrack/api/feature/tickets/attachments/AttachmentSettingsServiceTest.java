package com.edunext.edutrack.api.feature.tickets.attachments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-027 · which source wins, and what the container's own limit does to it.
 *
 * <p>The interesting behaviour here is entirely about disagreement: between the
 * settings row and the properties, and between whatever is configured and what
 * the servlet container will physically accept. Both are cases where the naive
 * answer produces a limit that appears to be set and does nothing.
 */
class AttachmentSettingsServiceTest {

    private static final long TEN_MB = 10L * 1024 * 1024;
    private static final long FIFTY_MB = 50L * 1024 * 1024;
    private static final long TWENTY_FIVE_MB = 25L * 1024 * 1024;

    private final AttachmentSettingsRepository repository = mock(AttachmentSettingsRepository.class);
    private final Authentication admin = new TestingAuthenticationToken("admin", "n/a");

    /** §4B.4's numbers, as {@code application.yml} carries them. */
    private static AttachmentProperties properties() {
        return new AttachmentProperties(
                Duration.ofMinutes(5), TEN_MB, FIFTY_MB, 20, Duration.ofMinutes(15),
                new AttachmentProperties.Scan(false, "localhost", 3310, Duration.ofSeconds(30), false),
                new AttachmentProperties.Thumbnail(true, 320, 50_000_000L));
    }

    private static MultipartProperties container(long maxFileBytes, long maxRequestBytes) {
        MultipartProperties multipart = new MultipartProperties();
        multipart.setMaxFileSize(DataSize.ofBytes(maxFileBytes));
        multipart.setMaxRequestSize(DataSize.ofBytes(maxRequestBytes));
        return multipart;
    }

    private AttachmentSettingsService service(MultipartProperties multipart) {
        return new AttachmentSettingsService(repository, properties(), multipart);
    }

    private AttachmentSettingsService service() {
        return service(container(TEN_MB, FIFTY_MB));
    }

    private static AttachmentSettingsRepository.Stored stored(long file, long ticket, int files) {
        return new AttachmentSettingsRepository.Stored(
                AttachmentLimits.of(file, ticket, files), Instant.parse("2026-08-15T09:00:00Z"), 7L);
    }

    @Nested
    @DisplayName("the settings row is the authority")
    class TheRowWins {

        @Test
        void andTheConfiguredValuesAreWhatIsReturned() {
            when(repository.load()).thenReturn(Optional.of(stored(TEN_MB, 30L * 1024 * 1024, 5)));

            AttachmentLimits limits = service().effective();

            assertThat(limits.maxTicketBytes()).isEqualTo(30L * 1024 * 1024);
            assertThat(limits.maxFiles()).isEqualTo(5);
        }

        /**
         * The fallback exists so a settings row deleted by hand costs the
         * customisation and not the upload path. Refusing every upload would
         * turn a stray {@code DELETE} into a product outage on a surface all six
         * roles reach, and the failure would name a table nobody was thinking
         * about.
         */
        @Test
        void butAnAbsentRowFallsBackToThePropertiesRatherThanRefusingEverything() {
            when(repository.load()).thenReturn(Optional.empty());

            AttachmentLimits limits = service().effective();

            assertThat(limits.maxFileBytes()).isEqualTo(TEN_MB);
            assertThat(limits.maxTicketBytes()).isEqualTo(FIFTY_MB);
            assertThat(limits.maxFiles()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("the container's multipart limit is the real ceiling")
    class ContainerCeiling {

        /**
         * A configured cap above what the container accepts is a limit that does
         * not work: the body is refused during parsing, before any of this
         * feature's code runs, so the caller gets a generic 413 rather than
         * §4B.4's worded one. The read clamps rather than throwing, because a
         * read that threw would take the upload path down over a configuration
         * problem — and clamping down is the safe direction.
         */
        @Test
        void aStoredCapAboveItIsClampedOnRead() {
            when(repository.load()).thenReturn(Optional.of(stored(TWENTY_FIVE_MB, FIFTY_MB, 20)));

            assertThat(service(container(TEN_MB, FIFTY_MB)).effective().maxFileBytes()).isEqualTo(TEN_MB);
        }

        /**
         * {@code max-request-size} bounds the same upload — the request carries
         * the part plus its headers — and raising only {@code max-file-size} is
         * the more likely of the two mistakes, because the file limit is the one
         * anybody thinks about.
         */
        @Test
        void andMaxRequestSizeCountsToo() {
            when(repository.load()).thenReturn(Optional.of(stored(TWENTY_FIVE_MB, FIFTY_MB, 20)));

            assertThat(service(container(TWENTY_FIVE_MB, 8L * 1024 * 1024)).effective().maxFileBytes())
                    .isEqualTo(8L * 1024 * 1024);
        }

        /** Spring spells "no limit" as a negative size; it drops out of the minimum. */
        @Test
        void anUnlimitedContainerLeavesOnlyTheHeapCeiling() {
            when(repository.load()).thenReturn(Optional.of(stored(TWENTY_FIVE_MB, FIFTY_MB, 20)));

            assertThat(service(container(-1, -1)).effective().maxFileBytes()).isEqualTo(TWENTY_FIVE_MB);
        }

        /**
         * Clamping the per-file cap must not leave the trio in a state
         * {@link AttachmentLimits#of} would refuse — lowering one value should
         * not invalidate a per-ticket total that was fine.
         */
        @Test
        void clampingNeverProducesACombinationThatWouldBeRefused() {
            when(repository.load()).thenReturn(Optional.of(stored(TWENTY_FIVE_MB, TWENTY_FIVE_MB, 3)));

            AttachmentLimits limits = service(container(TEN_MB, TEN_MB)).effective();

            assertThat(limits.maxFileBytes()).isEqualTo(TEN_MB);
            assertThat(limits.maxTicketBytes()).isGreaterThanOrEqualTo(limits.maxFileBytes());
        }

        @Test
        void andItIsReportedSoAFormCanShowTheBoundInsteadOfDiscoveringIt() {
            assertThat(service(container(TEN_MB, FIFTY_MB)).ceilingBytes()).isEqualTo(TEN_MB);
        }
    }

    @Nested
    @DisplayName("writes")
    class Replace {

        @Test
        void aValidTrioIsStoredWithTheCallerAgainstIt() {
            service().replace(admin, TEN_MB, 30L * 1024 * 1024, 5);

            verify(repository).replace(AttachmentLimits.of(TEN_MB, 30L * 1024 * 1024, 5), null);
        }

        /**
         * Refused, not clamped — the opposite of the read path, and deliberately.
         * A write is a person stating an intent, and silently storing a smaller
         * number than they typed is how a settings screen loses their trust.
         */
        @Test
        void aPerFileCapAboveWhatTheServerAcceptsIsRefusedRatherThanQuietlyLowered() {
            assertThatThrownBy(() -> service(container(TEN_MB, FIFTY_MB))
                    .replace(admin, TWENTY_FIVE_MB, FIFTY_MB, 20))
                    .isInstanceOf(InvalidAttachmentLimitsException.class)
                    .hasMessageContaining("10 MB")
                    .hasMessageContaining("multipart");

            verify(repository, never()).replace(any(), any());
        }

        @Test
        void anIncoherentTrioNeverReachesTheRepository() {
            assertThatThrownBy(() -> service().replace(admin, TEN_MB, 1024, 20))
                    .isInstanceOf(InvalidAttachmentLimitsException.class);

            verify(repository, never()).replace(any(), any());
        }
    }
}
