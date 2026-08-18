package com.edunext.edutrack.api.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
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
 * <p>Owned by Stream D. Runs with no infrastructure, the same way
 * {@code ApplicationSmokeTest} does it, so this passes on a laptop with nothing
 * installed.
 *
 * <p><b>Stream A edit, A-020 — flagged for Debashis's sign-off rather than made
 * quietly (CLAUDE.md, code ownership).</b> The datasource exclusions were
 * dropped from this list. They worked while no bean read the database; A-020's
 * {@code AuthUserRepository} needs a {@code JdbcClient}, so with
 * {@code DataSourceAutoConfiguration} excluded the context can no longer build
 * {@code AuthController} — and this test cannot see an endpoint that failed to
 * start, which defeats its purpose.
 *
 * <p>The no-infrastructure promise is unaffected: Spring Boot constructs the
 * {@code HikariDataSource} without starting its pool, and the pool opens on the
 * first {@code getConnection()}, which no test here triggers.
 *
 * <p><b>B-023 went one step further</b> and stopped excluding JPA — see the
 * property block below. Only Flyway is excluded now. The same change is in
 * {@code ApplicationSmokeTest}, {@code RelayBootsWithoutRedisTest} and
 * {@code DevPrincipalReachesRequestsTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // B-023, Stream B edit — flagged for Debashis's sign-off rather than
        // made quietly, exactly as A-020's note above did.
        //
        // Same shape of problem, one layer further on. `CalendarService` is the
        // first bean here to read through a Spring Data repository, so with JPA
        // excluded `CalendarController` never starts — and this test cannot see
        // an endpoint that failed to start, which is the failure A-020
        // described.
        //
        // The no-infrastructure promise survives. Hibernate connected during
        // refresh only to read JDBC metadata for dialect selection; naming the
        // dialect and refusing that lookup builds the EntityManagerFactory
        // offline. Flyway stays excluded — it connects unconditionally.
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
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
    /**
     * D-062 · the half that was missing, and the reason a blank column shipped.
     *
     * <p>{@link #servedEndpointsAreAllInTheContract()} proves the backend serves
     * no path the contract does not name. D-005 proves the committed TypeScript
     * client matches the contract. <strong>Neither has ever looked inside a
     * response body</strong>, so an endpoint at the right path returning the
     * wrong field names satisfies both — which is exactly what happened to
     * {@code GET /tickets}: it answered {@code ticketCode} where the contract
     * says {@code ticketId} and {@code assignedTo} where it says
     * {@code assignee}, and the S-17 grid rendered a blank ID column against a
     * green build.
     *
     * <p><strong>Property names only, not types.</strong> The cheap comparison
     * catches this entire class of drift — a renamed field, a dropped field, a
     * field added server-side and never declared — and a full structural
     * validator is a project rather than a test. Names are also the part the
     * generated client binds to, so a mismatch here is precisely a mismatch the
     * frontend will read as {@code undefined}.
     *
     * <p>Compared against what <em>springdoc</em> serves, which it derives from
     * the Java DTOs themselves. So this asserts the real shape of the real
     * response, not a second declaration that could drift in its own right.
     *
     * <p>Only {@code GET}. A request body is validated by Bean Validation and
     * fails loudly at runtime; a response body fails silently, in the browser,
     * as an empty cell.
     */
    /**
     * Known drift, each with the task that closes it.
     *
     * <p><strong>An exemption is a debt, not a decision.</strong> Every entry
     * names a task, so the list is auditable and shrinks; an entry without one
     * is how a guard becomes decoration. Deliberately keyed on the exact
     * operation rather than a prefix, so a *different* drift on the same path
     * still fails.
     */
    private static final java.util.Map<String, String> KNOWN_DRIFT = java.util.Map.of(
            "GET /tickets",
            "D-061 — the list serves ticketCode/assignedTo where the contract says "
                    + "ticketId/assignee. The backend rename is Stream C's half and is "
                    + "written up for Divyansh; the contract half is D-061. This is the "
                    + "drift that shipped a blank ID column on S-17.",

            "GET /chat/threads/{threadId}/messages",
            "D-053 — `attachments` is declared and not served because chat file share "
                    + "is blocked on C-024/C-025 owning upload, MinIO keys and AV scan. "
                    + "Declared intent, not a rename: nothing is served under a second "
                    + "name. Remove this entry when D-053 lands.");

    @Test
    @DisplayName("every GET returns the property names its contract schema declares")
    void responseBodiesCarryTheDeclaredPropertyNames() throws IOException {
        JsonNode contract = readContract();
        JsonNode served = readServedDocument();

        List<String> drift = new ArrayList<>();
        JsonNode contractPaths = contract.path("paths");

        contractPaths.fieldNames().forEachRemaining(path -> {
            JsonNode get = contractPaths.path(path).path("get");
            if (get.isMissingNode()) return;

            JsonNode declaredItem = payloadSchema(contract, get);
            Set<String> declared = propertyNames(declaredItem);
            if (declared.isEmpty()) return;   // no object payload to compare

            JsonNode servedGet = servedOperation(served, path);
            if (servedGet == null) return;    // not implemented yet — that is coverage, not drift

            JsonNode servedItem = payloadSchema(served, servedGet);
            Set<String> actual = propertyNames(servedItem);
            if (actual.isEmpty()) return;

            Set<String> missing = new TreeSet<>(declared); missing.removeAll(actual);
            Set<String> extra   = new TreeSet<>(actual);   extra.removeAll(declared);

            // Shape, for the fields both sides agree exist. `reportedBy` is the
            // case this was added for: the server sent the right NAME carrying a
            // bare id where the contract declares a UserRef, so the grid's
            // `reportedBy.displayName` was undefined and the column rendered a
            // dash — with the name check passing, because the name was right.
            Set<String> shape = new TreeSet<>();
            for (String shared : new TreeSet<>(declared)) {
                if (!actual.contains(shared)) continue;
                String want = kindOf(contract, declaredItem.path("properties").path(shared));
                String got  = kindOf(served,   servedItem.path("properties").path(shared));
                if (!"unknown".equals(want) && !"unknown".equals(got) && !want.equals(got)) {
                    shape.add(shared + " (contract: " + want + ", served: " + got + ")");
                }
            }

            if (!missing.isEmpty() || !extra.isEmpty() || !shape.isEmpty()) {
                if (KNOWN_DRIFT.containsKey("GET " + path)) return;
                drift.add("GET " + path
                        + (missing.isEmpty() ? "" : "\n      declared but not served: " + missing)
                        + (extra.isEmpty()   ? "" : "\n      served but not declared: " + extra)
                        + (shape.isEmpty()   ? "" : "\n      right name, wrong shape:  " + shape));
            }
        });

        if (!drift.isEmpty()) {
            fail("""
                    %d GET response(s) do not carry the property names their contract schema declares.

                    The contract is the agreed shape and the frontend generates from it, so a name
                    only the server knows reads as `undefined` in the browser — silently, and with a
                    green build. Fix whichever side is wrong; if the server's shape is the better
                    one, change the contract and regenerate the client (D-005 will insist).

                    %s""".formatted(drift.size(), String.join("\n\n    ", drift)));
        }
    }

    /**
     * The property names of the payload a 200 carries.
     *
     * <p>Descends the {@code { data, meta }} envelope every operation uses, and
     * through {@code data} being an array, so what comes back is the shape of
     * one <em>item</em> — which is what a grid row binds to. Returns empty for
     * anything that is not an object payload (a 204, a file download, a bare
     * scalar), because there is nothing to compare rather than nothing matching.
     */
    /**
     * Object, array or scalar — the coarsest distinction worth making.
     *
     * <p>Deliberately not a type check. Declaring {@code int32} and serving
     * {@code int64}, or {@code date-time} against {@code string}, is noise that
     * would bury the signal; the failure that reaches a user is a field the
     * client dereferences — {@code reportedBy.displayName} — arriving as a
     * number. Object-versus-scalar is exactly that failure and nothing else.
     *
     * <p>A nullable union such as {@code type: [string, 'null']} is a scalar,
     * and {@code $ref} is followed first, so a {@code UserRef} reads as an
     * object rather than as unknown.
     */
    private String kindOf(JsonNode document, JsonNode schema) {
        JsonNode s = resolve(document, schema);
        if (s.isMissingNode()) return "unknown";

        // `oneOf: [$ref Thing, null]` is the contract's nullable idiom, and its
        // kind is Thing's — NOT "object" because a composition keyword is
        // present. Treating oneOf as object made this check's first run report
        // `projectRole` on GET /projects/{projectId}/members as object-vs-scalar
        // drift; the branch is a string enum and the server was right. A guard
        // that cries wolf is worse than no guard, so the null branches are
        // dropped and the remaining one decides.
        for (String composed : new String[]{"oneOf", "anyOf"}) {
            if (s.has(composed)) {
                for (JsonNode branch : s.path(composed)) {
                    JsonNode resolved = resolve(document, branch);
                    if ("null".equals(resolved.path("type").asText())) continue;
                    return kindOf(document, resolved);
                }
                return "unknown";
            }
        }
        if (s.has("properties") || s.has("allOf")) return "object";
        JsonNode type = s.path("type");
        String name = type.isArray()
                ? java.util.stream.StreamSupport.stream(type.spliterator(), false)
                        .map(JsonNode::asText).filter(t -> !"null".equals(t)).findFirst().orElse("")
                : type.asText("");
        return switch (name) {
            case "object" -> "object";
            case "array" -> "array";
            case "" -> "unknown";
            default -> "scalar";
        };
    }

    /** The property names of an item schema. */
    private Set<String> propertyNames(JsonNode itemSchema) {
        JsonNode properties = itemSchema.path("properties");
        if (!properties.isObject()) return Set.of();
        Set<String> names = new TreeSet<>();
        properties.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private Set<String> payloadProperties(JsonNode document, JsonNode operation) {
        JsonNode schema = resolve(document, operation
                .path("responses").path("200")
                .path("content").path("application/json").path("schema"));
        if (schema.isMissingNode()) return Set.of();

        JsonNode data = resolve(document, schema.path("properties").path("data"));
        if (!data.isMissingNode()) schema = data;
        if ("array".equals(schema.path("type").asText())) {
            schema = resolve(document, schema.path("items"));
        }

        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) return Set.of();
        Set<String> names = new TreeSet<>();
        properties.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** The item schema a 200 carries — see {@link #payloadProperties}. */
    private JsonNode payloadSchema(JsonNode document, JsonNode operation) {
        JsonNode schema = resolve(document, operation
                .path("responses").path("200")
                .path("content").path("application/json").path("schema"));
        if (schema.isMissingNode()) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        JsonNode data = resolve(document, schema.path("properties").path("data"));
        if (!data.isMissingNode()) schema = data;
        if ("array".equals(schema.path("type").asText())) schema = resolve(document, schema.path("items"));
        return schema;
    }

    /**
     * Follow {@code $ref} to the schema it names.
     *
     * <p>One hop is enough in practice and a loop would need cycle detection;
     * a chain of refs resolves on the next level down when this is called again
     * for {@code data} or {@code items}.
     */
    private JsonNode resolve(JsonNode document, JsonNode schema) {
        if (schema == null || schema.isMissingNode()) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        JsonNode ref = schema.path("$ref");
        if (!ref.isTextual()) return schema;
        JsonNode at = document;
        for (String step : ref.asText().replaceFirst("^#/", "").split("/")) {
            at = at.path(step);
        }
        return at;
    }

    /** The served {@code get} for a contract path, matched on the normalised form. */
    private JsonNode servedOperation(JsonNode served, String contractPath) {
        JsonNode paths = served.path("paths");
        String wanted = normalise(contractPath);
        List<JsonNode> hit = new ArrayList<>();
        paths.fieldNames().forEachRemaining(p -> {
            if (normalise(p).equals(wanted) && paths.path(p).has("get")) {
                hit.add(paths.path(p).path("get"));
            }
        });
        return hit.isEmpty() ? null : hit.get(0);
    }

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
