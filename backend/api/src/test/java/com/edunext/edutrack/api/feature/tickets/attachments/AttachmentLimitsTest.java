package com.edunext.edutrack.api.feature.tickets.attachments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C-027 · the combinations that must not be storable.
 *
 * <p>Each of these is a way to make the product silently wrong from a settings
 * form — the values would look unremarkable next to each other and the symptom
 * would appear somewhere else entirely.
 */
class AttachmentLimitsTest {

    private static final long TEN_MB = 10L * 1024 * 1024;
    private static final long FIFTY_MB = 50L * 1024 * 1024;

    @Test
    void blueprintDefaultsAreValid() {
        AttachmentLimits limits = AttachmentLimits.of(TEN_MB, FIFTY_MB, 20);

        assertThat(limits.maxFileBytes()).isEqualTo(TEN_MB);
        assertThat(limits.maxTicketBytes()).isEqualTo(FIFTY_MB);
        assertThat(limits.maxFiles()).isEqualTo(20);
    }

    @Nested
    @DisplayName("a per-ticket total below the per-file cap is refused")
    class TicketTotalCoversTheFileCap {

        /**
         * The failure this prevents is remote from its cause: every file large
         * enough to test the per-file cap is refused by the ticket total first,
         * and the message tells the user to remove an attachment from a ticket
         * that may well have none.
         */
        @Test
        void becauseThePerFileCapWouldBeUnreachable() {
            assertThatThrownBy(() -> AttachmentLimits.of(TEN_MB, 5L * 1024 * 1024, 20))
                    .isInstanceOf(InvalidAttachmentLimitsException.class)
                    .hasMessageContaining("at least maxFileBytes");
        }

        @Test
        void equalIsFine() {
            assertThat(AttachmentLimits.of(TEN_MB, TEN_MB, 1).maxTicketBytes()).isEqualTo(TEN_MB);
        }
    }

    @Nested
    @DisplayName("zero is not unlimited")
    class ZeroIsRefused {

        /**
         * Zero would read as "no attachment may ever be uploaded" — the feature
         * switched off everywhere with nothing saying so. §4B.4 has no unlimited
         * state and an uncapped upload path is not a setting, so it is not
         * expressible in either direction.
         */
        @Test
        void perFile() {
            assertThatThrownBy(() -> AttachmentLimits.of(0, FIFTY_MB, 20))
                    .isInstanceOf(InvalidAttachmentLimitsException.class)
                    .hasMessageContaining("maxFileBytes");
        }

        @Test
        void fileCount() {
            assertThatThrownBy(() -> AttachmentLimits.of(TEN_MB, FIFTY_MB, 0))
                    .isInstanceOf(InvalidAttachmentLimitsException.class)
                    .hasMessageContaining("maxFiles");
        }

        @Test
        void andNegativeIsNotAWayRoundIt() {
            assertThatThrownBy(() -> AttachmentLimits.of(-1, FIFTY_MB, 20))
                    .isInstanceOf(InvalidAttachmentLimitsException.class);
        }
    }

    @Nested
    @DisplayName("the upper bounds match the table's CHECK constraints")
    class Ceilings {

        @Test
        void aPerFileCapAboveTheHeapCeilingIsRefused() {
            assertThatThrownBy(() ->
                    AttachmentLimits.of(AttachmentLimits.MAX_FILE_BYTES_CEILING + 1, Long.MAX_VALUE, 20))
                    .isInstanceOf(InvalidAttachmentLimitsException.class)
                    .hasMessageContaining("100 MB");
        }

        @Test
        void exactlyAtTheCeilingIsAccepted() {
            assertThat(AttachmentLimits.of(AttachmentLimits.MAX_FILE_BYTES_CEILING,
                    AttachmentLimits.MAX_FILE_BYTES_CEILING, 20).maxFileBytes())
                    .isEqualTo(AttachmentLimits.MAX_FILE_BYTES_CEILING);
        }

        @Test
        void aFileCountAboveTwoHundredIsRefused() {
            assertThatThrownBy(() -> AttachmentLimits.of(TEN_MB, FIFTY_MB, AttachmentLimits.MAX_FILES_CEILING + 1))
                    .isInstanceOf(InvalidAttachmentLimitsException.class)
                    .hasMessageContaining("200");
        }
    }

    @Nested
    @DisplayName("the message names the value, because the administrator has three to choose from")
    class Messages {

        @Test
        void andSpellsBytesTheWayTheRestOfTheProductDoes() {
            // The same spelling as AttachmentLimitExceededException and as
            // `formatFileSize` in components/ui/attachments.ts. A user who hits
            // a cap on the client and then on the server must not be shown two
            // different numbers for one rule.
            assertThat(AttachmentLimits.Bytes.human(TEN_MB)).isEqualTo("10 MB");
            assertThat(AttachmentLimits.Bytes.human(412 * 1024)).isEqualTo("412 KB");
            assertThat(AttachmentLimits.Bytes.human(1536)).isEqualTo("1.5 KB");
            assertThat(AttachmentLimits.Bytes.human(512)).isEqualTo("512 B");
        }

        /**
         * No trailing {@code .0} — and this is the case every earlier assertion
         * missed.
         *
         * <p>Values at or above 10 take the integer branch anyway, and 1536 B is
         * genuinely fractional, so the whole numbers <em>below</em> 10 were the
         * one shape untested. `Math.round(value * 10) / 10.0` renders a 2 MB cap
         * as "2.0 MB" where JavaScript's identical arithmetic renders "2 MB",
         * because JS drops an integral decimal and Java does not. Found by
         * running the server against a lowered limit, not by a test.
         */
        @Test
        void includingWholeNumbersBelowTen() {
            assertThat(AttachmentLimits.Bytes.human(2 * 1024 * 1024)).isEqualTo("2 MB");
            assertThat(AttachmentLimits.Bytes.human(5 * 1024)).isEqualTo("5 KB");
            assertThat(AttachmentLimits.Bytes.human(9L * 1024 * 1024 * 1024)).isEqualTo("9 GB");
        }

        /** The message a user actually sees, end to end. */
        @Test
        void andTheRejectionReadsTheWayTheClientWouldWriteIt() {
            assertThat(AttachmentLimitExceededException
                    .fileTooLarge(3_300_000L, 2 * 1024 * 1024).getMessage())
                    .isEqualTo("3.1 MB exceeds the 2 MB limit for one file.");
        }
    }
}
