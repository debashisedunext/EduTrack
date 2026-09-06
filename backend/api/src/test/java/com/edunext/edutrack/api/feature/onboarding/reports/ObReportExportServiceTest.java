package com.edunext.edutrack.api.feature.onboarding.reports;

import com.edunext.edutrack.api.feature.reports.ReportDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * B-122 · the one seam where this module's report shape meets the shared export
 * engine.
 *
 * <p>Worth its own test because the failure mode is bad out of proportion to
 * the code: an unmapped column type throws inside
 * {@code ExportDelivery.writeTo}, by which point the status line has gone and
 * the user has a truncated file that looks complete.
 */
class ObReportExportServiceTest {

    /**
     * Exhaustive over the enum by construction — a seventh column type added to
     * {@link ObReportDtos.ColumnType} fails this test rather than reaching a
     * caller mid-download.
     */
    @ParameterizedTest
    @EnumSource(ObReportDtos.ColumnType.class)
    void everyColumnTypeHasAnEngineCounterpart(ObReportDtos.ColumnType type) {
        assertThatCode(() -> ObReportExportService.translate(
                List.of(new ObReportDtos.Column("k", "Label", type)))).doesNotThrowAnyException();
    }

    /**
     * The five that match by name keep their meaning, so a spreadsheet
     * right-aligns what the screen right-aligns and writes hours as hours.
     */
    @Test
    void theFiveSharedTypesMapOneForOne() {
        assertThat(engineTypes(ObReportDtos.ColumnType.STRING, ObReportDtos.ColumnType.NUMBER,
                ObReportDtos.ColumnType.PERCENT, ObReportDtos.ColumnType.DURATION,
                ObReportDtos.ColumnType.DATE))
                .containsExactly(ReportDtos.ColumnType.STRING, ReportDtos.ColumnType.NUMBER,
                        ReportDtos.ColumnType.PERCENT, ReportDtos.ColumnType.DURATION,
                        ReportDtos.ColumnType.DATE);
    }

    /**
     * The only mapping that is not one-for-one, and it is the right one: a
     * health chip is a coloured rendering of a word, and a spreadsheet cell
     * holds the word.
     */
    @Test
    @DisplayName("a rag column exports as the word the chip is coloured from")
    void ragBecomesAString() {
        assertThat(engineTypes(ObReportDtos.ColumnType.RAG))
                .containsExactly(ReportDtos.ColumnType.STRING);
    }

    @Test
    void keysAndLabelsSurviveTheTranslation() {
        ReportDtos.Column translated = ObReportExportService.translate(List.of(
                new ObReportDtos.Column("rag", "Health", ObReportDtos.ColumnType.RAG))).get(0);

        assertThat(translated.key()).isEqualTo("rag");
        assertThat(translated.label()).isEqualTo("Health");
    }

    /**
     * No column in this module links anywhere, so the engine's two link fields
     * stay null. A column carrying a link kind with nothing to key on renders
     * as a dead anchor.
     */
    @Test
    void noColumnCarriesALinkIntoTheEngine() {
        ReportDtos.Column translated = ObReportExportService.translate(List.of(
                new ObReportDtos.Column("client", "Client",
                        ObReportDtos.ColumnType.STRING))).get(0);

        assertThat(translated.linkTo()).isNull();
        assertThat(translated.linkIdKey()).isNull();
    }

    private static List<ReportDtos.ColumnType> engineTypes(ObReportDtos.ColumnType... types) {
        return ObReportExportService.translate(
                        java.util.Arrays.stream(types)
                                .map(type -> new ObReportDtos.Column("k", "Label", type))
                                .toList())
                .stream().map(ReportDtos.Column::type).toList();
    }
}
