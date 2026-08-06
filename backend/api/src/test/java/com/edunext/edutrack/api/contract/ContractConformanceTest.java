package com.edunext.edutrack.api.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The backend must not drift from the reviewed contract. D-005.
 *
 * <p>Two different kinds of drift, and only one of them is a failure.
 *
 * <p><b>Serving something the contract does not describe is a failure.</b> An
 * endpoint nobody reviewed is an endpoint with no agreed error shape, no
 * generated client, no permission-matrix entry and no scoping decision. It also
 * means the frontend cannot call it, because the client is generated from the
 * contract — so it is dead weight at best.
 *
 * <p><b>Not having implemented a contract endpoint yet is not a failure.</b>
 * Ninety-odd operations arrive over four months; failing the build for the ones
 * that have not been written yet would mean the check is red from now until
 * December, and a check that is always red is a check nobody reads. It is
 * reported as coverage instead, which turns the contract into a progress meter.
 *
 * <p>Owned by Stream D. Runs with no infrastructure — datasource, JPA and
 * Flyway autoconfiguration are excluded, the same way {@code ApplicationSmokeTest}
 * does it, so this passes on a laptop with nothing installed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
class ContractConformanceTest {

    /** Paths where a mutation verb must never appear. Blueprint §4A.5, PLAN.md §3.5. */
    private static final List<String> APPEND_ONLY =
            List.of("/history", "/effort-logs", "/audit-logs");

    private static final Set<String> MUTATING = Set.of("put", "patch", "delete");
    private static final Set<String> VERBS =
            Set.of("get", "post", "put", "patch", "delete", "head", "options");

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("every endpoint the backend serves is in the reviewed contract")
    void servedEndpointsAreAllInTheContract() throws IOException {
        Set<String> contract = operationsOf(readContract());
        Set<String> served = operationsOf(readServedDocument());

        Set<String> undocumented = new TreeSet<>(served);
        undocumented.removeAll(contract);

        if (!undocumented.isEmpty()) {
            fail("""
                    The backend serves %d operation(s) that are not in contracts/openapi.yaml:

                    %s

                    An endpoint nobody reviewed has no agreed error shape, no generated
                    client, no permission-matrix entry and no scoping decision. Add it to
                    the contract and get it reviewed, or remove it."""
                    .formatted(undocumented.size(), String.join("\n  ", undocumented)));
        }
    }

    @Test
    @DisplayName("no mutation verb is served on an append-only path")
    void appendOnlyPathsExposeNoMutation() throws IOException {
        JsonNode served = readServedDocument();
        JsonNode paths = served.path("paths");
        List<String> violations = new ArrayList<>();

        paths.fieldNames().forEachRemaining(path -> {
            if (APPEND_ONLY.stream().noneMatch(path::endsWith)) return;
            paths.path(path).fieldNames().forEachRemaining(verb -> {
                if (MUTATING.contains(verb.toLowerCase())
                        || (verb.equalsIgnoreCase("post") && !path.endsWith("/effort"))) {
                    violations.add(verb.toUpperCase() + " " + path);
                }
            });
        });

        assertThat(violations)
                .as("""
                        A mutation route on an append-only path. This is not an oversight to \
                        fix later — a correction is a new compensating entry with isCorrection \
                        and correctsEntryId, and the database rejects mutation independently \
                        through triggers and grants. If a task appears to need this, the design \
                        is wrong; raise it.""")
                .isEmpty();
    }

    @Test
    @DisplayName("contract coverage is reported, not enforced")
    void reportsHowMuchOfTheContractIsImplemented() throws IOException {
        Set<String> contract = operationsOf(readContract());
        Set<String> served = operationsOf(readServedDocument());

        Set<String> implemented = new TreeSet<>(contract);
        implemented.retainAll(served);

        // Grouped by tag prefix so the report says which areas are still empty,
        // which is more useful than a single percentage.
        TreeMap<String, int[]> byArea = new TreeMap<>();
        for (String op : contract) {
            String area = area(op);
            int[] counts = byArea.computeIfAbsent(area, k -> new int[2]);
            counts[1]++;
            if (implemented.contains(op)) counts[0]++;
        }

        StringBuilder report = new StringBuilder("\nContract coverage — %d of %d operations implemented (%.0f%%)\n"
                .formatted(implemented.size(), contract.size(),
                        contract.isEmpty() ? 0.0 : 100.0 * implemented.size() / contract.size()));
        byArea.forEach((areaName, counts) ->
                report.append("  %-22s %2d / %2d%n".formatted(areaName, counts[0], counts[1])));
        System.out.println(report);

        // Deliberately no assertion on the number. The contract is a four-month
        // plan; asserting a threshold here would only ever be adjusted to match
        // reality, which makes it noise.
        assertThat(contract).as("the contract itself must parse and be non-empty").isNotEmpty();
    }

    // ── reading the two documents ───────────────────────────────────────────

    /** The reviewed contract, found by walking up to the repository root. */
    private JsonNode readContract() throws IOException {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParentFile()) {
            File candidate = new File(dir, "contracts/openapi.yaml");
            if (candidate.isFile()) {
                return new ObjectMapper(new YAMLFactory()).readTree(candidate);
            }
        }
        throw new IllegalStateException(
                "contracts/openapi.yaml not found above " + new File("").getAbsolutePath());
    }

    /**
     * What springdoc actually serves.
     *
     * <p>{@code OpenApiConfig} declares a {@code GroupedOpenApi}, so the document
     * lives at {@code /v3/api-docs/edutrack}; the bare path returns the group
     * listing. Both are tried so this keeps working if the grouping is removed.
     */
    private JsonNode readServedDocument() throws IOException {
        for (String path : List.of("/v3/api-docs/edutrack", "/v3/api-docs")) {
            String body = rest.getForObject(path, String.class);
            if (body == null || body.isBlank()) continue;
            JsonNode doc = new ObjectMapper().readTree(body);
            if (doc.has("paths")) return doc;
        }
        throw new IllegalStateException(
                "springdoc served no OpenAPI document. If A-032's security chain is in place, "
                        + "it must permit /v3/api-docs/** and /swagger-ui/** — otherwise this "
                        + "fails with a 401 that looks nothing like an auth problem.");
    }

    /** `GET /tickets/{ticketId}/full` — method and path, normalised. */
    private Set<String> operationsOf(JsonNode document) {
        Set<String> out = new LinkedHashSet<>();
        JsonNode paths = document.path("paths");
        paths.fieldNames().forEachRemaining(path ->
                paths.path(path).fieldNames().forEachRemaining(verb -> {
                    if (VERBS.contains(verb.toLowerCase())) {
                        out.add(verb.toUpperCase() + " " + normalise(path));
                    }
                }));
        return out;
    }

    /**
     * springdoc serves paths including the {@code /api/v1} prefix; the contract
     * carries it in {@code servers}. Compare like with like, and treat
     * {@code {id}} and {@code {ticketId}} as the same position — a rename is a
     * documentation change, not drift.
     */
    private String normalise(String path) {
        String p = path.startsWith("/api/v1") ? path.substring("/api/v1".length()) : path;
        return p.replaceAll("\\{[^}]+}", "{}");
    }

    private String area(String operation) {
        String path = operation.substring(operation.indexOf(' ') + 1);
        int slash = path.indexOf('/', 1);
        return slash > 0 ? path.substring(1, slash) : path.substring(1);
    }
}
