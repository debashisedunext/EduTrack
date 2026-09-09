package com.edunext.edutrack.api.feature.onboarding.signoff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-118 · the handover note's field mapping, on
 * {@code ObSignoffCertificateRendererTest}'s own shape one class over —
 * {@link #rows} is asserted directly and {@link #render} only has to prove a
 * well-formed document, since nothing in this codebase reads a PDF back to
 * check its text.
 */
class ObGoLiveHandoverRendererTest {

    private static final Instant LIVE_AT = Instant.parse("2026-09-09T11:20:00Z");

    private final ObGoLiveHandoverRenderer renderer = new ObGoLiveHandoverRenderer();

    private static ObGoLiveHandoverReader.Data fullData() {
        return new ObGoLiveHandoverReader.Data(
                "Acme Corporation",
                LocalDate.parse("2026-08-01"),
                LIVE_AT,
                List.of("Payroll Onboarding", "Compliance Suite"),
                List.of(
                        new ObGoLiveHandoverReader.Contact(
                                "Priya Raman", "Head of Ops", "priya@client.example", "+91 99999 00000", true),
                        new ObGoLiveHandoverReader.Contact(
                                "Arjun Mehta", null, "arjun@client.example", null, false)));
    }

    @Nested
    @DisplayName("rows — the field mapping")
    class Rows {

        @Test
        @DisplayName("carries the client, onboarding date, live date and every product")
        void carriesTheHeaderFacts() {
            List<String[]> rows = renderer.rows(fullData());

            assertThat(rows).contains(
                    new String[]{"Client", "Acme Corporation"},
                    new String[]{"Onboarding started", "01 Aug 2026"},
                    new String[]{"Live from", "09 Sep 2026, 11:20:00 UTC"},
                    new String[]{"Products / journeys", "Payroll Onboarding, Compliance Suite"});
        }

        @Test
        @DisplayName("prints the primary contact distinctly from the rest")
        void distinguishesThePrimaryContact() {
            List<String[]> rows = renderer.rows(fullData());

            assertThat(rows).contains(new String[]{
                    "Primary contact", "Priya Raman — Head of Ops, priya@client.example, +91 99999 00000"});
            assertThat(rows).contains(new String[]{"Contact", "Arjun Mehta, arjun@client.example"});
        }

        @Test
        @DisplayName("no contacts prints a dash rather than an empty document")
        void noContactsPrintsADash() {
            ObGoLiveHandoverReader.Data noContacts = new ObGoLiveHandoverReader.Data(
                    "Acme Corporation", LocalDate.parse("2026-08-01"), LIVE_AT,
                    List.of("Payroll Onboarding"), List.of());

            assertThat(renderer.rows(noContacts)).contains(new String[]{"Contacts", "—"});
        }

        @Test
        @DisplayName("the sparsest possible data prints dashes rather than nulls")
        void missingFactsPrintDashes() {
            ObGoLiveHandoverReader.Data sparse = new ObGoLiveHandoverReader.Data(
                    null, null, null, List.of(), List.of());

            List<String[]> rows = renderer.rows(sparse);

            assertThat(rows).contains(
                    new String[]{"Client", "—"},
                    new String[]{"Onboarding started", "—"},
                    new String[]{"Live from", "—"},
                    new String[]{"Products / journeys", "—"},
                    new String[]{"Contacts", "—"});
        }
    }

    @Nested
    @DisplayName("render — a well-formed PDF")
    class Render {

        @Test
        @DisplayName("produces a real PDF, starting with the magic bytes")
        void producesAValidPdf() {
            byte[] bytes = renderer.render(fullData());

            assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
            assertThat(bytes.length).isGreaterThan(500);
        }

        @Test
        @DisplayName("renders the sparsest possible data without throwing")
        void rendersTheSparsestData() {
            ObGoLiveHandoverReader.Data sparse = new ObGoLiveHandoverReader.Data(
                    null, null, null, List.of(), List.of());

            byte[] bytes = renderer.render(sparse);

            assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        }
    }
}
