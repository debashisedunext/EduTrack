package com.edunext.edutrack.api.feature.tickets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** C-011 · the rendering rules of {@link TicketCode}, with no database in sight. */
class TicketCodeTest {

    @Test
    @DisplayName("renders the blueprint's own example, CRM-26-00347")
    void rendersTheCanonicalExample() {
        assertThat(TicketCode.format("CRM", 2026, 347)).isEqualTo("CRM-26-00347");
    }

    @Test
    @DisplayName("the sequence does not reset in January — 347 is followed by 348, not 1")
    void sequenceDoesNotResetAtYearRollover() {
        // PLAN.md §3.2. ticket_seq is per project and carries across years; the
        // year digits are descriptive. If someone ever "fixes" this to restart at
        // 00001, CRM-27-00001 collides with nothing today and with everything the
        // following January.
        assertThat(TicketCode.format("CRM", 2026, 347)).isEqualTo("CRM-26-00347");
        assertThat(TicketCode.format("CRM", 2027, 348)).isEqualTo("CRM-27-00348");
    }

    @Test
    @DisplayName("five digits is a minimum width, not a modulo")
    void sequenceBeyondFiveDigitsGrowsRatherThanWrapping() {
        assertThat(TicketCode.format("CRM", 2030, 99_999)).isEqualTo("CRM-30-99999");
        assertThat(TicketCode.format("CRM", 2030, 100_000)).isEqualTo("CRM-30-100000");
        assertThat(TicketCode.format("CRM", 2030, 1_234_567)).isEqualTo("CRM-30-1234567");
    }

    /**
     * Read from {@code contracts/openapi.yaml} rather than copied into a literal.
     *
     * <p>A copied pattern proves the two agreed on the day it was pasted. This
     * proves they still agree — the contract types the {@code ticketId}
     * <b>path parameter</b>, so a narrowing there does not break creation, it
     * breaks every attachment, comment and history call for tickets already
     * issued. That is a change nobody would connect back to this class.
     */
    private static Pattern contractTicketIdPattern() throws IOException {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParentFile()) {
            File candidate = new File(dir, "contracts/openapi.yaml");
            if (candidate.isFile()) {
                JsonNode spec = new ObjectMapper(new YAMLFactory()).readTree(candidate);
                String pattern = spec.path("components").path("schemas")
                        .path("TicketId").path("pattern").asText();
                assertThat(pattern).as("contracts/openapi.yaml TicketId.pattern").isNotEmpty();
                return Pattern.compile(pattern);
            }
        }
        throw new IllegalStateException(
                "contracts/openapi.yaml not found above " + new File("").getAbsolutePath());
    }

    @Test
    @DisplayName("every code this class can emit satisfies the contract's TicketId pattern")
    void everyEmittedCodeMatchesTheContract() throws IOException {
        Pattern contract = contractTicketIdPattern();

        // Spanning the interesting boundaries: shortest and longest legal project
        // code, first and last five-digit sequence, and the six- and seven-digit
        // ones a long-lived project reaches. The counter never resets, so those
        // last two are a matter of time, not of hypothesis.
        List<String> codes = List.of(
                TicketCode.format("CR", 2026, 1),
                TicketCode.format("CRM", 2026, 347),
                TicketCode.format("A123456789", 2026, 1),
                TicketCode.format("CRM", 2030, 99_999),
                TicketCode.format("CRM", 2030, 100_000),
                TicketCode.format("CRM", 2030, 1_234_567));

        assertThat(codes).allSatisfy(code ->
                assertThat(contract.matcher(code).matches())
                        .as("contract rejects issued ticket code %s", code)
                        .isTrue());
    }

    @Test
    @DisplayName("the year is padded too — year 2005 is -05-, never -5-")
    void yearIsTwoDigits() {
        assertThat(TicketCode.format("CRM", 2005, 1)).isEqualTo("CRM-05-00001");
        assertThat(TicketCode.format("CRM", 2100, 1)).isEqualTo("CRM-00-00001");
    }

    @Test
    @DisplayName("a ten-character project code still fits VARCHAR(30)")
    void longestLegalProjectCodeFits() {
        String code = TicketCode.format("A123456789", 2026, 1);
        assertThat(code).isEqualTo("A123456789-26-00001");
        assertThat(code.length()).isLessThanOrEqualTo(30);
    }

    @ParameterizedTest
    @ValueSource(strings = {"crm", "C", "1CRM", "CR-M", "CRM PROJ", "ABCDEFGHIJK", "CRM_1"})
    @DisplayName("a project code the contract would have rejected is rejected here too")
    void rejectsProjectCodesOutsideTheContractPattern(String projectCode) {
        // ^[A-Z][A-Z0-9]{1,9}$ — contracts/openapi.yaml, ProjectCreateRequest.
        assertThatThrownBy(() -> TicketCode.format(projectCode, 2026, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project_code");
    }

    @Test
    @DisplayName("a null project code is rejected, not rendered as \"null-26-00001\"")
    void rejectsNullProjectCode() {
        assertThatThrownBy(() -> TicketCode.format(null, 2026, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sequence 0 is refused — it is what a misrouted LAST_INSERT_ID() returns")
    void rejectsNonPositiveSequence() {
        // The failure this guard exists for: two statements landing on different
        // pooled connections. MySQL answers 0 on a session that has inserted
        // nothing, and CRM-26-00000 would be minted twice before anyone noticed.
        assertThatThrownBy(() -> TicketCode.format("CRM", 2026, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> TicketCode.format("CRM", 2026, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a year outside the two-digit-safe range is refused rather than truncated")
    void rejectsImplausibleYear() {
        assertThatThrownBy(() -> TicketCode.format("CRM", 1999, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TicketCode.format("CRM", 3000, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
