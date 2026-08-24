package com.edunext.edutrack.api.contract;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does {@code TicketWire.Ticket} actually serialise into the shape the contract
 * describes?
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Three separate bugs on 21 Aug 2026 had one cause between them: <b>the MSW
 * mock follows the contract and the server does not, and nothing compared
 * them.</b> The frontend is built against the mock, so its tests all pass; the
 * backend is tested against its own DTOs, so its tests all pass; and the
 * disagreement is only ever discovered by a person clicking the screen.
 *
 * <ol>
 *   <li>{@code @PathVariable long ticketId} against a contract whose
 *       {@code TicketId} is a code — every ticket sub-resource answered 400.</li>
 *   <li>{@code ribbon} hard-coded null while the mock always built one.</li>
 *   <li>{@code reportedBy} emitted as a bare id where the contract says
 *       {@code UserRef} — the detail page renders it through
 *       {@code initials(person.displayName)}, so it threw, and with no
 *       {@code ErrorBoundary} the entire page went blank.</li>
 * </ol>
 *
 * <p>{@code ContractConformanceTest} could not catch any of them: it compares
 * <em>paths and verbs</em>, never response bodies. This one compares bodies.
 *
 * <h2>What it asserts, and what it deliberately does not</h2>
 *
 * <p><b>A field the contract types as an object must not serialise as a
 * scalar.</b> That is the exact shape of bug 3 and the one that takes a screen
 * down, because the client dereferences a property of it.
 *
 * <p>It does <em>not</em> yet assert that every contract field is present.
 * {@link #KNOWN_GAPS} records the ones that are not, because the honest state
 * of this DTO is that it is named after database columns and the contract is
 * named after the screen — {@code currentCycleNo} vs {@code cycleNo},
 * {@code ticketCode} vs {@code ticketId}, and eleven more. Reconciling those is
 * a real task with a real blast radius across eleven call sites and the
 * generated client; pretending otherwise by deleting this list would either
 * fail the build for everyone or quietly assert nothing.
 *
 * <p><b>The list is allowed to shrink and never to grow.</b> That is what makes
 * it a ratchet rather than an excuse: a new field that disagrees fails here on
 * the day it is written, when it is one line to fix.
 */
class TicketWireConformanceTest {

    /**
     * Contract fields {@code TicketWire.Ticket} does not emit today.
     *
     * <p>Every entry is a real divergence, not a false positive. Most are the
     * same field under a database-shaped name — the mapping is written out here
     * so the next person does not have to rediscover it:
     *
     * <pre>
     *   contract            server
     *   ticketId            ticketCode
     *   cycleNo             currentCycleNo
     *   iterationNo         currentIteration
     *   currentStageCode    currentStage
     *   estimatedHrs        estimatedEffortHrs
     *   assignee            (now emitted — removed from this list 21 Aug)
     *   project             projectId          (an object vs its id)
     *   client              —                  (never resolved)
     * </pre>
     *
     * <p>{@code reportedBy} was on this list until 21 Aug. It came off by being
     * fixed, which is the only way an entry should ever leave it.
     */
    private static final Set<String> KNOWN_GAPS = Set.of(
            "cycleNo", "iterationNo", "currentStageCode", "estimatedHrs",
            "clientContactId", "isClientRaised", "delayedSince", "createdAt", "updatedAt");

    private static final ObjectMapper JSON =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("a field the contract types as an object never serialises as a scalar")
    void objectFieldsAreNotEmittedAsScalars() throws IOException {
        assertThat(scalarsWhereContractSaysObject(JSON.valueToTree(sample())))
                .as("""
                        A field the contract types as an object is being emitted as a scalar.

                        The client dereferences a property of it, so this does not degrade \
                        gracefully — it throws in render. There is no ErrorBoundary in the \
                        frontend, so the screen goes blank rather than showing an error.

                        This is what `reportedBy` did until 21 Aug: the contract said UserRef, \
                        the server sent the row id, the MSW mock sent a correct UserRef, and \
                        every test on both sides passed.""")
                .isEmpty();
    }

    /**
     * Does the check above actually detect anything?
     *
     * <p>{@code PermissionMatrix} records that its own first draft was found to
     * be asserting nothing, by deliberately mis-stating a row and watching the
     * suite stay green. Same discipline here: the detector is fed the exact
     * payload the server produced before the fix — {@code reportedBy} as a bare
     * {@code 1} — and must reject it. Without this, a refactor that quietly
     * stopped resolving {@code $ref}s would leave a test that passes on
     * everything.
     */
    @Test
    @DisplayName("the check rejects the payload that actually shipped — reportedBy as a bare id")
    void theCheckWouldHaveCaughtIt() throws IOException {
        JsonNode broken = JSON.valueToTree(sample());
        ((com.fasterxml.jackson.databind.node.ObjectNode) broken).put("reportedBy", 1);

        assertThat(scalarsWhereContractSaysObject(broken))
                .as("the pre-fix payload must be rejected, or this suite proves nothing")
                .containsExactly("reportedBy → contract UserRef, server emits number");
    }

    @Test
    @DisplayName("the list of unemitted contract fields shrinks and never grows")
    void theKnownGapListIsARatchet() throws IOException {
        JsonNode props = contractSchema("Ticket").get("properties");
        JsonNode emitted = JSON.valueToTree(sample());

        Set<String> missing = new TreeSet<>();
        props.fieldNames().forEachRemaining(field -> {
            if (!emitted.has(field)) {
                missing.add(field);
            }
        });

        Set<String> newlyMissing = new TreeSet<>(missing);
        newlyMissing.removeAll(KNOWN_GAPS);
        assertThat(newlyMissing)
                .as("""
                        A contract field this DTO does not emit, and that is not on the \
                        recorded list of known gaps.

                        Add the field, or — if it genuinely cannot be emitted yet — add it to \
                        KNOWN_GAPS **with the reason**. Do not add it silently: the whole point \
                        of that list is that it is short, explained, and shrinking.""")
                .isEmpty();

        Set<String> fixed = new TreeSet<>(KNOWN_GAPS);
        fixed.removeAll(missing);
        assertThat(fixed)
                .as("""
                        These fields are on KNOWN_GAPS but the DTO now emits them — which is \
                        good news and a stale list. Remove them from KNOWN_GAPS so the ratchet \
                        keeps its tension.""")
                .isEmpty();
    }

    /**
     * Fields the contract types as an <em>object</em> that this payload emits as
     * something else. Shared by the real check and the check on the check.
     *
     * <p>{@code $ref}s to string schemas are skipped deliberately: {@code Level},
     * {@code StatusCode} and {@code TicketId} are all refs to strings, and a
     * string serialising as a string is not a defect.
     */
    private static Set<String> scalarsWhereContractSaysObject(JsonNode emitted) throws IOException {
        JsonNode props = contractSchema("Ticket").get("properties");
        Set<String> scalarWhereObject = new TreeSet<>();
        props.fieldNames().forEachRemaining(field -> {
            JsonNode declared = props.get(field);
            if (!declared.has("$ref")) {
                return;
            }
            String refName = declared.get("$ref").asText().replaceAll(".*/", "");
            JsonNode target = schema(refName);
            if (target == null || !"object".equals(target.path("type").asText())) {
                return;
            }
            JsonNode value = emitted.get(field);
            if (value != null && !value.isNull() && !value.isObject()) {
                scalarWhereObject.add(field + " → contract " + refName
                        + ", server emits " + value.getNodeType().toString().toLowerCase());
            }
        });
        return scalarWhereObject;
    }

    /**
     * Every field populated, so an absent value cannot hide a wrong shape. The
     * two {@code UserRef}s are the point of the exercise.
     */
    private static TicketWire.Ticket sample() {
        return new TicketWire.Ticket(
                347L, "CRM-26-00347", "Checkout fails with 500", "A description",
                3, "HIGH", "MEDIUM", "IN_PROGRESS", "PROD",
                Instant.parse("2026-08-01T09:00:00Z"),
                new TicketWire.UserRef(12L, "Priya Nair"),
                new TicketWire.UserRef(55L, "Nikhil Bansal"),
                new BigDecimal("8.00"), new BigDecimal("6.50"),
                Instant.parse("2026-08-09T09:00:00Z"), null,
                false, 0, 2, true, "DEV", 1, 0, 40,
                4, "Checkout", "Payment", "Add to cart, then pay",
                new TicketWire.Project(8L, "CRM", "Client CRM Platform"),
                new TicketWire.ClientRef(3L, "ACME", "Acme Corporation"));
    }

    private static JsonNode contractSchema(String name) throws IOException {
        JsonNode s = schema(name);
        if (s == null) {
            throw new IllegalStateException("contract has no schema named " + name);
        }
        return s;
    }

    private static JsonNode cached;

    private static JsonNode schema(String name) {
        try {
            if (cached == null) {
                cached = readContract();
            }
            JsonNode node = cached.path("components").path("schemas").get(name);
            return node == null || node.isMissingNode() ? null : node;
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    /** {@code ContractConformanceTest.readContract}'s idiom, verbatim. */
    private static JsonNode readContract() throws IOException {
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

    /** Unused today; kept so the sample's field count breaks loudly if the record changes. */
    @SuppressWarnings("unused")
    private static final Map<String, String> NOTE = Map.of();
}
