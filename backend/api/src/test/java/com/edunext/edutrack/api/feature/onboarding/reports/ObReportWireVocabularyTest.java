package com.edunext.edutrack.api.feature.onboarding.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-122 · do OB-10's enums spell their values the way the contract does?
 *
 * <h2>Why this is not covered by the A-119 ratchet</h2>
 *
 * <p>{@code ObWireConformanceTest} scans the onboarding feature package for
 * <b>records</b> whose simple name matches a contract schema, and compares the
 * shape they serialise into. That catches a missing field and a scalar sent
 * where an object was declared. It cannot catch an enum spelled wrongly,
 * because the enum is not a record and its value is a leaf the shape check
 * treats as a string either way.
 *
 * <p>Which is exactly the mistake this file was written after making. The first
 * version of {@link ObReportFilterKind} emitted {@code productId},
 * {@code obClientId} and {@code ownerUserId} — the names of the query
 * parameters each control sets — where A-118 had written {@code product},
 * {@code client} and {@code owner}. Everything compiled, every other test
 * passed, and the failure would have been a filter bar that silently rendered
 * no controls at all: the viewer switches on these tokens, and three of five
 * would have matched nothing.
 *
 * <p>So the contract is read and compared value for value. A vocabulary the
 * client switches on is the one kind of string where "close enough" is
 * indistinguishable from broken.
 */
class ObReportWireVocabularyTest {

    @Test
    @DisplayName("ObReportCategory matches the contract, value for value")
    void categoriesMatchTheContract() {
        assertThat(wireValues(ObReportCategory.values(), Enum::name))
                .containsExactlyInAnyOrderElementsOf(contractEnum("ObReportCategory"));
    }

    /**
     * The five filter tokens. Order is not asserted — a client switching on a
     * value does not care where it sits in the list — but membership is exact
     * in both directions, so a token declared here and absent from the contract
     * fails as loudly as the reverse.
     */
    @Test
    void filterKindsMatchTheContract() {
        assertThat(wireValues(ObReportFilterKind.values(), ObReportFilterKind::wire))
                .containsExactlyInAnyOrderElementsOf(contractEnum("ObReportFilterKind"));
    }

    @Test
    void columnTypesMatchTheContract() {
        JsonNode type = schema("ObReportResponse")
                .path("properties").path("data")
                .path("properties").path("columns")
                .path("items").path("properties").path("type").path("enum");

        assertThat(wireValues(ObReportDtos.ColumnType.values(), ObReportDtos.ColumnType::wire))
                .containsExactlyInAnyOrderElementsOf(values(type));
    }

    /**
     * {@code chart} is a nullable enum in the contract, so its list carries a
     * {@code null} that no Java constant corresponds to — the absence of a
     * chart is {@code null} on the field, not a sixth value.
     */
    @Test
    void chartsMatchTheContractWithoutInventingAConstantForNull() {
        JsonNode chart = schema("ObReportDescriptor")
                .path("properties").path("chart").path("enum");

        assertThat(wireValues(ObReportDtos.Chart.values(), ObReportDtos.Chart::wire))
                .containsExactlyInAnyOrderElementsOf(
                        values(chart).stream().filter(value -> value != null).toList());
        assertThat(values(chart)).contains((String) null);
    }

    /**
     * The two export formats this route accepts. PDF is deliberately absent —
     * the module's only PDF is B-116's archived sign-off certificate, which is
     * a legal record rather than a rendering of a filtered grid — so the
     * contract's enum is the authority on what {@code ObReportController} may
     * allow, and a third value appearing there without a decision fails here.
     */
    @Test
    void theExportFormatsAreTheTwoTheContractDeclares() {
        JsonNode export = contract()
                .path("paths").path("/onboarding/reports/{reportKey}").path("get")
                .path("parameters");

        List<String> declared = Stream.of(export)
                .flatMap(node -> node.valueStream())
                .filter(parameter -> "export".equals(parameter.path("name").asText()))
                .flatMap(parameter -> values(parameter.path("schema").path("enum")).stream())
                .toList();

        assertThat(declared).containsExactlyInAnyOrder("xlsx", "csv");
    }

    private static <E extends Enum<E>> List<String> wireValues(
            E[] constants, java.util.function.Function<E, String> wire) {
        return Stream.of(constants).map(wire).toList();
    }

    private static List<String> contractEnum(String schemaName) {
        return values(schema(schemaName).path("enum"));
    }

    private static List<String> values(JsonNode enumNode) {
        assertThat(enumNode.isArray())
                .as("the contract declares this as an enum array")
                .isTrue();
        return enumNode.valueStream()
                .map(node -> node.isNull() ? null : node.asText())
                .toList();
    }

    private static JsonNode schema(String name) {
        JsonNode node = contract().path("components").path("schemas").path(name);
        assertThat(node.isMissingNode())
                .as("contracts/openapi.yaml declares %s", name)
                .isFalse();
        return node;
    }

    private static JsonNode cached;

    /** {@code ContractConformanceTest.readContract}'s idiom, verbatim. */
    private static JsonNode contract() {
        if (cached != null) {
            return cached;
        }
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParentFile()) {
            File candidate = new File(dir, "contracts/openapi.yaml");
            if (candidate.isFile()) {
                try {
                    cached = new ObjectMapper(new YAMLFactory()).readTree(candidate);
                    return cached;
                } catch (IOException unreadable) {
                    throw new IllegalStateException(unreadable);
                }
            }
        }
        throw new IllegalStateException(
                "contracts/openapi.yaml not found above " + new File("").getAbsolutePath());
    }
}
