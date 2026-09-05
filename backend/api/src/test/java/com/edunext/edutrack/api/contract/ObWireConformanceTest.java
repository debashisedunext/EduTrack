package com.edunext.edutrack.api.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-119 · do the onboarding DTOs serialise into the shapes the contract
 * describes?
 *
 * <h2>Why this exists, and why now</h2>
 *
 * <p>{@code TicketWireConformanceTest} is the template, and its javadoc records
 * what phase 1 paid for not having it: three bugs shipped on 21 Aug 2026 with
 * one cause between them — <b>the MSW mock follows the contract, the server does
 * not, and nothing compared them.</b> The frontend is built against the mock so
 * its tests pass; the backend is tested against its own DTOs so its tests pass;
 * the disagreement is discovered by a person clicking a screen. One of the three
 * took the whole detail page blank, because {@code reportedBy} arrived as a bare
 * id where the contract said {@code UserRef} and the client dereferenced a
 * property of it.
 *
 * <p><b>This one lands before the screens do.</b> That is the entire difference
 * and the reason A-119 sits in week 2 of the module rather than after OB-05.
 * A ratchet added after the fact can only ever record the divergences that have
 * already shipped; added first, it fails on the day a field is written, when the
 * fix is one line and nobody has built on top of it yet.
 *
 * <h2>Why this scans instead of listing</h2>
 *
 * <p>The ticket test hand-builds one sample of one record. That works for one
 * DTO and does not survive fifteen: a hand-written sample list is a second place
 * to remember, and the field it is missing is exactly the field nobody checked.
 * Two facts about this module make scanning the better shape:
 *
 * <ul>
 *   <li>Its DTO holders are <b>package-private</b> ({@code
 *       ObJourneyStepLifecycleDtos}, {@code ObJourneyTemplateDtos}), so this
 *       test could not import them even if it wanted to. Making them public to
 *       suit a test would widen Stream C's API for Stream A's convenience.</li>
 *   <li>Most of the module's DTOs <b>do not exist yet</b>. A-120, A-124 and
 *       A-125 each add several. A scanner covers them on the day they are
 *       written without anybody remembering this file exists — which is the
 *       only way a ratchet keeps tension across four streams.</li>
 * </ul>
 *
 * <p>So: ArchUnit enumerates every record under the onboarding feature package,
 * a record is matched to a contract schema <b>by simple name</b>, and a fully
 * populated instance is built reflectively and serialised. A record with no
 * same-named schema is skipped — request bodies, internal projections and
 * envelope types have no contract shape of their own to disagree with.
 *
 * <h2>What it asserts, and what it deliberately does not</h2>
 *
 * <p><b>1. A field the contract types as an object must not serialise as a
 * scalar.</b> The {@code reportedBy} shape exactly, and the one that takes a
 * screen down rather than degrading. The onboarding contract has 57 schemas
 * carrying at least one object-typed field — {@code product → ObProductRef},
 * {@code salesPerson → UserRef}, {@code actor → UserRef}, {@code sentToContact
 * → ObContact} — and today's three DTO holders touch none of them. That is not
 * a reason to skip the check; it is the reason to write it now, because A-120's
 * sign-off DTOs and A-125's client DTOs are where those fields land.
 *
 * <p><b>2. Every contract field must be emitted</b>, minus {@link #KNOWN_GAPS}.
 *
 * <p>It does <b>not</b> assert the reverse — a server field absent from the
 * contract is not failed here. Additive fields are how a DTO grows before its
 * contract slice is written, and {@code ContractConformanceTest} already fails
 * an operation the contract does not describe.
 *
 * <p><b>{@code KNOWN_GAPS} is allowed to shrink and never to grow.</b> That is
 * what makes this a ratchet rather than an excuse.
 */
class ObWireConformanceTest {

    private static final String FEATURE_PACKAGE = "com.edunext.edutrack.api.feature.onboarding";

    /**
     * Contract fields a matched DTO does not emit today, as {@code
     * Schema.field}.
     *
     * <p>Every entry is a real divergence and carries its reason. The rule the
     * ticket test set and this one keeps: <b>do not add an entry silently.</b>
     * The list is only useful while it is short, explained and shrinking, and an
     * unexplained line is indistinguishable from a bug somebody parked.
     *
     * <p><b>All five are optional in the contract</b> — none is in a {@code
     * required} list — so each is a deliberate omission rather than a bug this
     * list is parking. That is worth stating, because the ticket test's
     * equivalent list is mostly the other kind, and the two should not be read
     * the same way.
     *
     * <pre>
     *   ObJourneyStepDetail.elapsedHours
     *       Needs C-105's ob_step_clock_events, which no migration has created
     *       yet — ObJourneyStepDetail's own javadoc says exactly this, next to
     *       the same reasoning for `rag`. A zero here would be a guess standing
     *       in for a number, which is worse than the field being absent.
     *
     *   Meta.totalCount
     *       Not emitted by PageMeta, and deliberately: it is nullable in the
     *       schema because cursor pagination does not count. CLAUDE.md's "never
     *       live COUNT(*)" is the same decision one layer down. This gap is
     *       system-wide rather than the onboarding module's, and it leaves this
     *       list only if PageMeta itself changes.
     *
     *   UserRef.avatarUrl
     *   UserRef.handle
     *   UserRef.role
     *       The local UserRef in ObJourneyStepLifecycleDtos is (id,
     *       displayName) — exactly the contract's required pair. The other
     *       three are optional and two are nullable.
     *
     *       `role` is the one worth watching. It is a RoleCode, and a screen
     *       that renders an owner's role chip from it gets undefined rather
     *       than a wrong value — the softer half of the reportedBy failure,
     *       and softer only because nothing dereferences a property of a
     *       string. If OB-06 ever draws that chip, this entry is the reason it
     *       is blank, and the fix belongs in Stream C's DTO rather than here.
     * </pre>
     *
     * <p>Entries leave this list by being fixed. That is the only way one should
     * ever leave it.
     */
    private static final Set<String> KNOWN_GAPS = Set.of(
            "ObJourneyStepDetail.elapsedHours",
            "Meta.totalCount",
            "UserRef.avatarUrl",
            "UserRef.handle",
            "UserRef.role");

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    // ── the checks ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a field the contract types as an object never serialises as a scalar")
    void objectFieldsAreNotEmittedAsScalars() {
        Set<String> offenders = new TreeSet<>();
        matchedRecords().forEach((schema, type) ->
                offenders.addAll(scalarsWhereContractSaysObject(schema, serialise(type))));

        assertThat(offenders)
                .as("""
                        A field the contract types as an object is being emitted as a scalar.

                        The client dereferences a property of it, so this does not degrade \
                        gracefully — it throws in render. There is no ErrorBoundary in the \
                        frontend, so the screen goes blank rather than showing an error.

                        This is what `reportedBy` did on the ticket detail page until 21 Aug: \
                        the contract said UserRef, the server sent the row id, the MSW mock \
                        sent a correct UserRef, and every test on both sides passed.""")
                .isEmpty();
    }

    @Test
    @DisplayName("every contract field is emitted, or is a recorded and explained gap")
    void theKnownGapListIsARatchet() {
        Set<String> missing = new TreeSet<>();
        matchedRecords().forEach((schema, type) -> {
            JsonNode emitted = serialise(type);
            contractProperties(schema).keySet().forEach(field -> {
                if (!emitted.has(field)) {
                    missing.add(schema + "." + field);
                }
            });
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

        Set<String> stale = new TreeSet<>(KNOWN_GAPS);
        stale.removeAll(missing);
        assertThat(stale)
                .as("""
                        These fields are on KNOWN_GAPS but the DTO now emits them — which is \
                        good news and a stale list. Remove them from KNOWN_GAPS so the ratchet \
                        keeps its tension.""")
                .isEmpty();
    }

    /**
     * Does the check above actually detect anything?
     *
     * <p>{@code PermissionMatrix} records that its own first draft was found to
     * be asserting nothing, by deliberately mis-stating a row and watching the
     * suite stay green. {@code TicketWireConformanceTest} keeps the same
     * discipline by feeding its detector the exact payload that shipped broken.
     * There is no shipped-broken onboarding payload yet — which is the point of
     * landing this early — so the equivalent here is synthetic and asserts the
     * same thing: an object-typed field flattened to a bare id must be caught.
     *
     * <p>Without this, a refactor that quietly stopped resolving {@code $ref}s
     * would leave a test that passes on everything.
     */
    @Test
    @DisplayName("the check rejects an object-typed field flattened to a bare id")
    void theCheckWouldCatchTheReportedByShape() {
        String schema = anySchemaWithAnObjectField();
        Map.Entry<String, String> field = objectFields(schema).entrySet().iterator().next();

        ObjectNode broken = JSON.createObjectNode();
        broken.put(field.getKey(), 1);

        assertThat(scalarsWhereContractSaysObject(schema, broken))
                .as("the flattened-to-an-id payload must be rejected, or this suite proves nothing")
                .containsExactly(schema + "." + field.getKey()
                        + " → contract " + field.getValue() + ", server emits number");
    }

    // ── what is under test ────────────────────────────────────────────────

    /**
     * Every record under the onboarding feature package that shares its simple
     * name with a contract schema, as {@code schemaName -> record class}.
     *
     * <p>Matching by name is the whole convention, and it is the same one
     * springdoc uses to name a served component from a Java simple name — which
     * {@code ObJourneyTemplateDtos.ObJourneyTemplateResponse} already documents
     * the sharp edge of. A record whose name matches nothing is not a failure:
     * request bodies ({@code AddStepRequest}) and internal projections have no
     * contract shape of their own.
     */
    private static Map<String, Class<?>> matchedRecords() {
        JavaClasses scanned = new ClassFileImporter().importPackages(FEATURE_PACKAGE);
        Map<String, Class<?>> matched = new LinkedHashMap<>();
        for (JavaClass candidate : scanned) {
            if (!candidate.isRecord() || candidate.getSimpleName().isEmpty()) {
                continue;
            }
            String name = candidate.getSimpleName();
            if (schema(name) == null) {
                continue;
            }
            matched.put(name, candidate.reflect());
        }
        return matched;
    }

    private static JsonNode serialise(Class<?> record) {
        return JSON.valueToTree(sample(record, 0));
    }

    /**
     * A fully populated instance, built through the canonical constructor.
     *
     * <p><b>Every component is non-null</b>, deliberately: an absent value
     * cannot hide a wrong shape, and a null would make the object-vs-scalar
     * check pass on a field it never examined. This is the reflective form of
     * what {@code TicketWireConformanceTest.sample()}'s javadoc asks for by
     * hand — "every field populated, so an absent value cannot hide a wrong
     * shape".
     *
     * <p>{@code setAccessible} is what lets this reach Stream C's
     * package-private records without widening them. The depth guard stops a
     * self-referential record from recursing forever; beyond it, null is
     * correct rather than convenient, because a cycle has no honest sample.
     */
    private static Object sample(Class<?> record, int depth) {
        try {
            RecordComponent[] components = record.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                args[i] = value(components[i].getType(), components[i].getGenericType(), depth);
            }
            Constructor<?> canonical = record.getDeclaredConstructor(types);
            canonical.setAccessible(true);
            return canonical.newInstance(args);
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(
                    "could not build a sample of " + record.getName()
                            + " — if it is not a record with a canonical constructor, "
                            + "it should not have matched a contract schema", impossible);
        }
    }

    private static Object value(Class<?> type, Type generic, int depth) {
        if (depth > 4) {
            return null;
        }
        if (type == long.class || type == Long.class) return 1L;
        if (type == int.class || type == Integer.class) return 1;
        if (type == boolean.class || type == Boolean.class) return Boolean.FALSE;
        if (type == double.class || type == Double.class) return 1.0d;
        if (type == String.class) return "sample";
        if (type == BigDecimal.class) return BigDecimal.ONE;
        if (type == Instant.class) return Instant.parse("2026-09-05T09:00:00Z");
        if (type == LocalDate.class) return LocalDate.parse("2026-09-05");
        if (type.isEnum()) return type.getEnumConstants()[0];
        if (List.class.isAssignableFrom(type)) {
            Class<?> element = elementType(generic);
            List<Object> one = new ArrayList<>();
            if (element != null) {
                one.add(value(element, element, depth + 1));
            }
            return List.copyOf(one);
        }
        if (type.isRecord()) return sample(type, depth + 1);
        return null;
    }

    private static Class<?> elementType(Type generic) {
        if (generic instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
            return element;
        }
        return null;
    }

    // ── the contract ──────────────────────────────────────────────────────

    /**
     * Fields the named schema types as an <em>object</em> that this payload
     * emits as something else.
     *
     * <p>{@code $ref}s to string schemas are skipped deliberately, as in the
     * ticket test: {@code ObJourneyStepStatus}, {@code ObRag} and {@code
     * ObStepClockState} are all refs to strings, and a string serialising as a
     * string is not a defect.
     */
    private static Set<String> scalarsWhereContractSaysObject(String schema, JsonNode emitted) {
        Set<String> offenders = new TreeSet<>();
        objectFields(schema).forEach((field, refName) -> {
            JsonNode value = emitted.get(field);
            if (value != null && !value.isNull() && !value.isObject()) {
                offenders.add(schema + "." + field + " → contract " + refName
                        + ", server emits " + value.getNodeType().toString().toLowerCase());
            }
        });
        return offenders;
    }

    /** {@code field -> referenced schema name}, for fields the contract types as an object. */
    private static Map<String, String> objectFields(String schema) {
        Map<String, String> objects = new LinkedHashMap<>();
        contractProperties(schema).forEach((field, declared) -> {
            JsonNode ref = declared.get("$ref");
            if (ref == null) {
                return;
            }
            String refName = ref.asText().replaceAll(".*/", "");
            JsonNode target = schema(refName);
            if (target != null && isObject(target)) {
                objects.put(field, refName);
            }
        });
        return objects;
    }

    private static boolean isObject(JsonNode schema) {
        return "object".equals(schema.path("type").asText())
                || schema.has("allOf")
                || (schema.has("properties") && !schema.has("type"));
    }

    /**
     * A schema's properties with its {@code allOf} parts folded in.
     *
     * <p>The module leans on {@code allOf} heavily — {@code ObJourneyStepDetail}
     * is {@code ObJourneyStepView} is {@code ObJourneyStep} — and reading only
     * the outermost level would silently check three fields of a twenty-three
     * field shape.
     */
    private static Map<String, JsonNode> contractProperties(String name) {
        Map<String, JsonNode> properties = new LinkedHashMap<>();
        collectProperties(name, properties, new TreeSet<>());
        return properties;
    }

    private static void collectProperties(String name, Map<String, JsonNode> into, Set<String> seen) {
        if (!seen.add(name)) {
            return;
        }
        JsonNode node = schema(name);
        if (node == null) {
            return;
        }
        node.path("allOf").forEach(part -> {
            JsonNode ref = part.get("$ref");
            if (ref != null) {
                collectProperties(ref.asText().replaceAll(".*/", ""), into, seen);
            } else {
                part.path("properties").fields()
                        .forEachRemaining(e -> into.put(e.getKey(), e.getValue()));
            }
        });
        node.path("properties").fields().forEachRemaining(e -> into.put(e.getKey(), e.getValue()));
    }

    private static String anySchemaWithAnObjectField() {
        return contractSchemas().fieldNames().next() == null ? null : firstObjectCarrier();
    }

    private static String firstObjectCarrier() {
        List<String> names = new ArrayList<>();
        contractSchemas().fieldNames().forEachRemaining(names::add);
        return names.stream()
                .filter(n -> n.startsWith("Ob"))
                .filter(n -> !objectFields(n).isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "the onboarding contract declares no object-typed field — "
                                + "if that is genuinely true, this check has nothing to prove "
                                + "and should be deleted rather than left passing vacuously"));
    }

    private static JsonNode contractSchemas() {
        return cached().path("components").path("schemas");
    }

    private static JsonNode schema(String name) {
        JsonNode node = contractSchemas().get(name);
        return node == null || node.isMissingNode() ? null : node;
    }

    private static JsonNode cached;

    private static JsonNode cached() {
        try {
            if (cached == null) {
                cached = readContract();
            }
            return cached;
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
}
