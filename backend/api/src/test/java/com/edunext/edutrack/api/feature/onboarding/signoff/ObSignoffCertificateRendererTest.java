package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-116 · the field mapping is asserted directly on {@link #rows}
 * ({@code ReportExporterTest}'s own PDF cases check only the {@code %PDF-}
 * magic bytes and a length floor — nothing in this codebase reads a PDF back
 * to check its text), and {@link #render} only has to prove it still produces
 * a well-formed document.
 */
class ObSignoffCertificateRendererTest {

    private static final Instant SIGNED_AT = Instant.parse("2026-09-09T11:20:00Z");

    private final ObSignoffCertificateRenderer renderer = new ObSignoffCertificateRenderer();

    private static ObSignoffCertificateRenderer.Data stepData() {
        return new ObSignoffCertificateRenderer.Data(
                77L,
                ObSignoffKind.STEP,
                "Acme Corporation",
                "Payroll Onboarding",
                "Data Migration Sign-off",
                "Priya Raman",
                SIGNED_AT,
                "203.0.113.9",
                "Mozilla/5.0",
                "Email",
                "Happy, subject to the March invoice.");
    }

    @Nested
    @DisplayName("rows — the field mapping")
    class Rows {

        @Test
        @DisplayName("carries name, timestamp, IP, user agent and OTP channel")
        void carriesTheFourFactsAndTheChannel() {
            List<String[]> rows = renderer.rows(stepData());

            assertThat(rows).extracting(row -> row[0]).contains(
                    "Accepted by", "Accepted at", "IP address", "User agent", "OTP verified via");
            assertThat(rows).contains(
                    new String[]{"Accepted by", "Priya Raman"},
                    new String[]{"IP address", "203.0.113.9"},
                    new String[]{"User agent", "Mozilla/5.0"},
                    new String[]{"OTP verified via", "Email"});
        }

        @Test
        @DisplayName("formats the timestamp in UTC, explicitly")
        void formatsTheTimestampInUtc() {
            List<String[]> rows = renderer.rows(stepData());

            assertThat(rows).contains(new String[]{"Accepted at", "09 Sep 2026, 11:20:00 UTC"});
        }

        @Test
        @DisplayName("names the client and the service for a STEP sign-off")
        void identifiesTheStepContext() {
            List<String[]> rows = renderer.rows(stepData());

            assertThat(rows).contains(
                    new String[]{"Client", "Acme Corporation"},
                    new String[]{"Service", "Data Migration Sign-off"},
                    new String[]{"Product / journey", "Payroll Onboarding"});
        }

        @Test
        @DisplayName("names the milestone as Go-Live for a GO_LIVE sign-off, never a step title")
        void identifiesTheGoLiveContext() {
            ObSignoffCertificateRenderer.Data goLive = new ObSignoffCertificateRenderer.Data(
                    88L, ObSignoffKind.GO_LIVE, "Acme Corporation", "Payroll Onboarding",
                    null, "Priya Raman", SIGNED_AT, "203.0.113.9", "Mozilla/5.0", "Email", null);

            List<String[]> rows = renderer.rows(goLive);

            assertThat(rows).contains(new String[]{"Milestone", "Go-Live"});
            assertThat(rows).extracting(row -> row[0]).doesNotContain("Service");
        }

        @Test
        @DisplayName("a missing optional fact prints an em dash rather than an empty cell or a null")
        void missingFactsPrintADash() {
            ObSignoffCertificateRenderer.Data sparse = new ObSignoffCertificateRenderer.Data(
                    99L, ObSignoffKind.STEP, null, null, null, null, null, null, null, null, null);

            List<String[]> rows = renderer.rows(sparse);

            assertThat(rows).contains(
                    new String[]{"Client", "—"},
                    new String[]{"Service", "—"},
                    new String[]{"Accepted by", "—"},
                    new String[]{"Accepted at", "—"},
                    new String[]{"IP address", "—"},
                    new String[]{"User agent", "—"},
                    new String[]{"OTP verified via", "—"});
        }

        @Test
        @DisplayName("an absent note is left off the certificate rather than printed blank")
        void absentNoteIsOmitted() {
            ObSignoffCertificateRenderer.Data noNote = new ObSignoffCertificateRenderer.Data(
                    77L, ObSignoffKind.STEP, "Acme Corporation", null, "Data Migration", "Priya Raman",
                    SIGNED_AT, "203.0.113.9", "Mozilla/5.0", "Email", "   ");

            assertThat(renderer.rows(noNote)).extracting(row -> row[0]).doesNotContain("Note");
        }

        @Test
        @DisplayName("a present note is printed")
        void presentNoteIsPrinted() {
            List<String[]> rows = renderer.rows(stepData());

            assertThat(rows).contains(new String[]{"Note", "Happy, subject to the March invoice."});
        }
    }

    @Nested
    @DisplayName("render — a well-formed PDF")
    class Render {

        @Test
        @DisplayName("produces a real PDF, starting with the magic bytes")
        void producesAValidPdf() {
            byte[] bytes = renderer.render(stepData());

            assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
            assertThat(bytes.length).isGreaterThan(500);
        }

        @Test
        @DisplayName("renders even the sparsest possible sign-off without throwing")
        void rendersTheSparsestData() {
            ObSignoffCertificateRenderer.Data sparse = new ObSignoffCertificateRenderer.Data(
                    1L, ObSignoffKind.GO_LIVE, null, null, null, null, null, null, null, null, null);

            byte[] bytes = renderer.render(sparse);

            assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        }
    }
}
