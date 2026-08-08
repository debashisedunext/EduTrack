package com.edunext.edutrack.api.feature.tickets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-011 · the wiring between the counter and the format, in isolation.
 *
 * <p>{@link TicketCodeTest} owns the rendering rules and {@code TicketIdGenerationIT}
 * owns the SQL. What is left for this class is the ordering and the clock — the
 * parts that only exist because the two halves have to meet.
 */
class TicketCodeGeneratorTest {

    private final TicketSequenceRepository sequences = mock(TicketSequenceRepository.class);

    private TicketCodeGenerator generatorAt(String instant) {
        return new TicketCodeGenerator(sequences, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("combines the project's code with the freshly allocated sequence")
    void combinesProjectCodeAndSequence() {
        when(sequences.findProjectCode(7L)).thenReturn(Optional.of("CRM"));
        when(sequences.allocateNextSequence(7L)).thenReturn(347L);

        assertThat(generatorAt("2026-08-08T10:15:00Z").nextTicketCode(7L)).isEqualTo("CRM-26-00347");
    }

    @Test
    @DisplayName("an unknown project costs no sequence number")
    void unknownProjectDoesNotBurnASequence() {
        when(sequences.findProjectCode(404L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(UnknownProjectException.class)
                .isThrownBy(() -> generatorAt("2026-08-08T10:15:00Z").nextTicketCode(404L));

        verify(sequences, never()).allocateNextSequence(anyLong());
    }

    @Test
    @DisplayName("the year comes from the clock, and the sequence carries across it")
    void yearAdvancesButSequenceDoesNot() {
        when(sequences.findProjectCode(7L)).thenReturn(Optional.of("CRM"));
        when(sequences.allocateNextSequence(7L)).thenReturn(347L, 348L);

        assertThat(generatorAt("2026-12-31T23:59:59Z").nextTicketCode(7L)).isEqualTo("CRM-26-00347");
        assertThat(generatorAt("2027-01-01T00:00:01Z").nextTicketCode(7L)).isEqualTo("CRM-27-00348");
    }

    @Test
    @DisplayName("the year is read in UTC, not in the JVM's default zone")
    void yearIsResolvedInUtc() {
        // 1 Jan 05:30 in Kolkata is still 31 Dec in UTC. Storage is UTC
        // everywhere (CLAUDE.md), so the code reads -26-; a generator that
        // quietly used the system zone would emit -27- on a developer laptop in
        // India and -26- on a UTC CI runner, for the same instant.
        when(sequences.findProjectCode(7L)).thenReturn(Optional.of("CRM"));
        when(sequences.allocateNextSequence(7L)).thenReturn(1L);

        Clock kolkata = Clock.fixed(Instant.parse("2026-12-31T20:00:00Z"), ZoneId.of("Asia/Kolkata"));
        assertThat(new TicketCodeGenerator(sequences, kolkata).nextTicketCode(7L)).isEqualTo("CRM-26-00001");
    }

    @Test
    @DisplayName("a project code that would produce an unparseable ticket code is refused")
    void refusesAMalformedProjectCode() {
        // Defence in depth. Stream B's Project Master validates against the same
        // contract pattern on the way in; this stops a row that predates that
        // validation, or arrives through an import, from minting bad IDs forever.
        when(sequences.findProjectCode(7L)).thenReturn(Optional.of("cr m"));
        when(sequences.allocateNextSequence(7L)).thenReturn(1L);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> generatorAt("2026-08-08T10:15:00Z").nextTicketCode(7L));
    }
}
