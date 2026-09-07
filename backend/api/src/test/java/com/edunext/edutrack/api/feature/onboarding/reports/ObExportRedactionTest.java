package com.edunext.edutrack.api.feature.onboarding.reports;

import com.edunext.edutrack.api.feature.reports.ReportDtos;
import com.edunext.edutrack.api.feature.reports.export.ExportDelivery;
import com.edunext.edutrack.api.feature.reports.export.ExportRows;
import com.edunext.edutrack.api.feature.reports.export.ReportExporter;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.module.ModuleAccessGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * B-123 · "an export that ignores the masking rule is the masking rule not
 * existing", tested as three separate claims.
 *
 * <p>They are separable and all three are needed. The policy could mask
 * correctly and never be called; it could be called and the file be written
 * from some other copy of the rows; and both could be right while a seventh
 * report adds an unclassified {@code pan} column that neither notices. So:
 * what redaction does, that the export actually carries what it produced, and
 * that a column which needs classifying cannot avoid it.
 *
 * <p>No database. The rule is about values in transit, and a container would
 * only add rows to mask.
 */
class ObExportRedactionTest {

    private static final String RAW = "ABCDE1234F";

    /** {@code PanFormat}'s rule: the last four characters, the rest bulleted. */
    private static final String MASKED = "••••••234F";

    private static ObReportDtos.Column pan() {
        return new ObReportDtos.Column("pan", "PAN",
                ObReportDtos.ColumnType.STRING, ObReportSensitivity.PAN);
    }

    private static ObReportDtos.Column money() {
        return new ObReportDtos.Column("amount", "Amount",
                ObReportDtos.ColumnType.NUMBER, ObReportSensitivity.MONEY);
    }

    private static ObReportDtos.Column client() {
        return new ObReportDtos.Column("client", "Client", ObReportDtos.ColumnType.STRING);
    }

    // ── what redaction does ─────────────────────────────────────────────────

    @Nested
    @DisplayName("the policy")
    class Policy {

        @Test
        @DisplayName("a PAN column keeps its last four characters and nothing else")
        void panIsMaskedToTheLastFour() {
            ObReportRunner.Result out = ObExportRedaction.apply(new ObReportRunner.Result(
                    List.of(client(), pan()),
                    List.of(Map.of("client", "Acme Pvt Ltd", "pan", RAW))));

            assertThat(out.rows()).singleElement().satisfies(row -> {
                assertThat(row.get("pan")).isEqualTo(MASKED);
                assertThat(row.get("client")).isEqualTo("Acme Pvt Ltd");
            });
        }

        /**
         * The whole value, not a mask. A masked amount still discloses its
         * order of magnitude, which for a contract value is most of what the
         * number was worth.
         */
        @Test
        void aMoneyColumnIsRemovedRatherThanMasked() {
            ObReportRunner.Result out = ObExportRedaction.apply(new ObReportRunner.Result(
                    List.of(money()),
                    List.of(Map.of("amount", 125000L))));

            assertThat(out.rows()).singleElement()
                    .satisfies(row -> assertThat(row.get("amount")).isNull());
        }

        /**
         * The identity is the assertion. Every report in this package is
         * ordinary throughout, so rebuilding each row map to change nothing
         * would be a real cost on a surface that already defends a calendar
         * call per row.
         */
        @Test
        @DisplayName("a report with nothing to redact is handed back untouched, not copied")
        void anOrdinaryReportIsTheSameInstance() {
            ObReportRunner.Result in = new ObReportRunner.Result(
                    List.of(client()), List.of(Map.of("client", "Acme")));

            assertThat(ObExportRedaction.apply(in)).isSameAs(in);
        }

        /**
         * A client with no PAN recorded stays absent rather than becoming a row
         * of bullets — {@code PanFormat} makes the same call, and for the same
         * reason: bullets claim a value is held and withheld.
         */
        @Test
        void aNullStaysNull() {
            Map<String, Object> row = new HashMap<>();
            row.put("pan", null);

            ObReportRunner.Result out = ObExportRedaction.apply(
                    new ObReportRunner.Result(List.of(pan()), List.of(row)));

            assertThat(out.rows()).singleElement()
                    .satisfies(r -> assertThat(r.get("pan")).isNull());
        }

        /**
         * "The runner did not produce this cell" and "this cell was withheld"
         * are different claims, and only one of them is true here.
         */
        @Test
        @DisplayName("a key the row never carried is not invented as a null")
        void anAbsentKeyStaysAbsent() {
            ObReportRunner.Result out = ObExportRedaction.apply(new ObReportRunner.Result(
                    List.of(client(), pan()), List.of(Map.of("client", "Acme"))));

            assertThat(out.rows()).singleElement()
                    .satisfies(row -> assertThat(row).doesNotContainKey("pan"));
        }

        /**
         * A runner is entitled to hand back {@code Map.of(…)}, which throws on
         * {@code put}. Redacting in place would fail on the one report that
         * most needed it.
         */
        @Test
        void anImmutableRowIsRedactedRatherThanMutated() {
            Map<String, Object> immutable = Map.of("pan", RAW);

            assertThatCode(() -> ObExportRedaction.apply(
                    new ObReportRunner.Result(List.of(pan()), List.of(immutable))))
                    .doesNotThrowAnyException();

            assertThat(immutable).containsEntry("pan", RAW);
        }

        /**
         * A PAN arriving as something other than a String is already a defect.
         * The safe reading of an unexpected type in a column classified as
         * identity data is to mask what it prints as, not to pass it through.
         */
        @Test
        void aNonStringValueInAPanColumnIsStillMasked() {
            ObReportRunner.Result out = ObExportRedaction.apply(new ObReportRunner.Result(
                    List.of(pan()), List.of(Map.of("pan", new StringBuilder(RAW)))));

            assertThat(out.rows()).singleElement()
                    .satisfies(row -> assertThat(row.get("pan")).isEqualTo(MASKED));
        }
    }

    // ── that the export carries it ──────────────────────────────────────────

    /**
     * The claim B-123 is actually about. The policy being right is not the same
     * as the downloaded file being right, and the gap between them is where
     * this would fail silently: a second query on the export path, a cached
     * copy of the rows, an exporter handed the runner's output instead of the
     * service's.
     *
     * <p>Run through the real {@link ObReportService} and the real
     * {@code ObReportExportService}, with only the runner and the exporter
     * faked — the runner because a PAN column has to come from somewhere and no
     * built report has one, the exporter because the CSV and XLSX writers are
     * deliberately package-private in another feature and what matters here is
     * the values they are handed, not their quoting.
     */
    @Nested
    @DisplayName("the export path")
    class ExportPath {

        private final PanRunner runner = new PanRunner();
        private final ObReportService service = new ObReportService(List.of(runner),
                Clock.fixed(Instant.parse("2026-09-07T09:00:00Z"), ZoneOffset.UTC));
        private final CapturingExporter exporter = new CapturingExporter();

        @Test
        @DisplayName("the file is written from redacted rows, for an OB_ADMIN too")
        void theExportedRowsCarryTheMaskAndNotThePan() throws Exception {
            writeExport("OB_ADMIN");

            assertThat(exporter.rows).singleElement()
                    .satisfies(row -> assertThat(row.get("pan")).isEqualTo(MASKED));
        }

        /**
         * Stated as its own case because it is the decision this task made
         * rather than inherited. The on-screen rule names OB_ADMIN and
         * OB_MANAGER as the roles that may see a PAN — but A-113 made the
         * unmasked value a reveal operation that audits one row per
         * disclosure, and a five-hundred-row export cannot produce five hundred
         * of those. An unmasked column here would be the one bulk read of PAN
         * in the product with no trail behind it.
         */
        @ParameterizedTest
        @ValueSource(strings = {"OB_ADMIN", "OB_MANAGER", "OB_VIEWER", "OB_SALES", "OB_STEP_OWNER"})
        @DisplayName("no module role exports an unmasked PAN")
        void noRoleGetsTheRawValue(String moduleRole) throws Exception {
            writeExport(moduleRole);

            assertThat(exporter.rows).isNotEmpty();
            assertThat(exporter.rows).noneSatisfy(
                    row -> assertThat(row.values()).contains(RAW));
        }

        /**
         * The JSON the viewer draws goes through the same {@code run()}, so the
         * page cannot be less safe than the spreadsheet downloaded from it —
         * which is what redacting in the exporter alone would have produced.
         */
        @Test
        void theJsonBehindTheViewerIsRedactedToo() {
            ObReportService.Rendered rendered = render("OB_ADMIN");

            assertThat(rendered.report().rows()).singleElement()
                    .satisfies(row -> assertThat(row.get("pan")).isEqualTo(MASKED));
        }

        /**
         * The ETag is hashed from the rows. Taking it before redaction would
         * pin a validator to a payload nobody is ever sent — and would be the
         * seam where a later role-varying rule quietly handed one caller
         * another's file out of a cache.
         */
        @Test
        @DisplayName("the validator is computed over what the caller receives")
        void theEtagIsHashedFromTheRedactedRows() {
            ObReportService.Rendered rendered = render("OB_ADMIN");

            assertThat(rendered.etag()).isEqualTo(ObReportService.etagOf(
                    PanRunner.KEY, ObReportScope.of(caller("OB_ADMIN")),
                    LocalDate.parse("2026-06-09"), LocalDate.parse("2026-09-07"),
                    null, new ObReportFilters(null, null, null),
                    rendered.report().rows()));
        }

        private void writeExport(String moduleRole) throws Exception {
            new ObReportExportService(new ExportDelivery(List.of(exporter)))
                    .writeTo(new MockHttpServletResponse(), ReportExporter.Format.CSV,
                            PanRunner.KEY, render(moduleRole));
        }

        private ObReportService.Rendered render(String moduleRole) {
            return service.run(caller(moduleRole), PanRunner.KEY,
                    null, null, null, null, null, null).orElseThrow();
        }
    }

    // ── that a column cannot avoid being classified ─────────────────────────

    /**
     * The part that has to survive this task. A policy applied to columns
     * nobody classified protects nothing, and the writer who adds the seventh
     * report will not have read any of the above.
     */
    @Nested
    @DisplayName("the classification guard")
    class Guard {

        @ParameterizedTest
        @ValueSource(strings = {"pan", "clientPan", "amount", "totalAmount",
                "invoice", "paymentStatus", "price", "fee"})
        @DisplayName("a sensitive-looking column cannot be declared ORDINARY")
        void aSensitiveNameIsRefused(String key) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ObReportDtos.Column(
                            key, "Label", ObReportDtos.ColumnType.STRING))
                    .withMessageContaining("B-123");
        }

        /** The label is checked too — the file the caller opens shows the label. */
        @Test
        void aSensitiveLabelOverAnInnocentKeyIsRefusedAsWell() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ObReportDtos.Column(
                            "holderId", "PAN", ObReportDtos.ColumnType.STRING));
        }

        @Test
        @DisplayName("classifying it is what makes it constructible")
        void aClassifiedColumnIsAccepted() {
            assertThatCode(ObExportRedactionTest::pan).doesNotThrowAnyException();
            assertThatCode(ObExportRedactionTest::money).doesNotThrowAnyException();
        }

        /**
         * Substring matching was the obvious first version of
         * {@code looksSensitive} and would have thrown on half this module's
         * vocabulary — {@code company} and {@code span} both contain "pan". A
         * guard that fires on "Sales person / company" is a guard somebody
         * turns off.
         */
        @ParameterizedTest
        @ValueSource(strings = {"company", "companyName", "expand", "span", "panel", "japan"})
        @DisplayName("a word that merely contains 'pan' is not sensitive")
        void substringsAreNotMatched(String key) {
            assertThatCode(() -> new ObReportDtos.Column(
                    key, "Label", ObReportDtos.ColumnType.STRING)).doesNotThrowAnyException();
        }

        /**
         * A sample of what the six built reports actually declare. If any of
         * them ever grows a column this guard would refuse, it fails here as a
         * sentence rather than as a constructor throwing inside a request.
         */
        @Test
        void theColumnsTheBuiltReportsDeclareAreAlreadyAcceptable() {
            assertThatCode(() -> {
                new ObReportDtos.Column("salesPerson", "Sales person", ObReportDtos.ColumnType.STRING);
                new ObReportDtos.Column("client", "Client", ObReportDtos.ColumnType.STRING);
                new ObReportDtos.Column("contactEmail", "Email", ObReportDtos.ColumnType.STRING);
                new ObReportDtos.Column("onTimePct", "On time", ObReportDtos.ColumnType.PERCENT);
            }).doesNotThrowAnyException();
        }
    }

    private static CallerIdentity caller(String moduleRole) {
        return new CallerIdentity(7L, "SUPPORT", List.of(),
                List.of(ModuleAccessGuard.ONBOARDING),
                Map.of(ModuleAccessGuard.ONBOARDING, moduleRole));
    }

    /** A report with a PAN column, which no built one has and the rule needs. */
    private static final class PanRunner implements ObReportRunner {

        private static final String KEY = JourneyFunnelRunner.KEY;

        @Override
        public String key() {
            return KEY;
        }

        @Override
        public Result run(ObReportScope scope, LocalDate from, LocalDate to, Instant now,
                          Long ownerSubject, ObReportFilters filters) {
            return new Result(List.of(client(), pan()),
                    List.of(Map.of("client", "Acme Pvt Ltd", "pan", RAW)));
        }
    }

    /** Records what the engine handed the writer, which is the question here. */
    private static final class CapturingExporter implements ReportExporter {

        private final List<Map<String, Object>> rows = new ArrayList<>();

        @Override
        public Format format() {
            return Format.CSV;
        }

        @Override
        public void write(OutputStream out, String reportTitle, String appliedScope,
                          List<ReportDtos.Column> columns, ExportRows exportRows) {
            exportRows.forEach(rows::add);
        }
    }
}
