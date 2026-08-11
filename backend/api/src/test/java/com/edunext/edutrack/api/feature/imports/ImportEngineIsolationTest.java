package com.edunext.edutrack.api.feature.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-030 · <b>the rule CLAUDE.md states in prose, enforced.</b>
 *
 * <p>"Build it once, register two schemas… if you find yourself writing a second
 * import flow, stop." A stop rule that lives only in a document is obeyed until
 * a Thursday afternoon when a client-shaped special case is genuinely the
 * shortest path — and by then it is a one-line diff nobody reviews as an
 * architectural change. These tests fail that diff.
 *
 * <h2>Why this reads source instead of bytecode</h2>
 *
 * <p>This wanted to be four ArchUnit rules. ArchUnit is already a declared test
 * dependency, and it was written that way first. It cannot work on this project:
 *
 * <pre>
 *   INFO  PluginLoader        -- Detected Java version 25.0.4
 *   WARN  ClassFileProcessor  -- Couldn't import class … RefreshRotationService$Rotation.class
 *   java.lang.IllegalArgumentException: Unsupported class file major version 69
 * </pre>
 *
 * <p>ArchUnit 1.3.0 bundles an ASM that stops at major version 68; PLAN.md §D-10
 * compiles this project at {@code release 25}, which is 69. ArchUnit does not
 * fail on a class it cannot read — it logs and skips. Every class is skipped,
 * every rule then "passes" having examined nothing, and the build goes green
 * reporting that the architecture is enforced. That is strictly worse than no
 * test.
 *
 * <p>Nobody had hit it because this would have been the repository's first
 * ArchUnit test. Fixing it properly is a version bump in {@code backend/pom.xml},
 * which is Stream A's build file, so it is raised rather than taken.
 *
 * <h2>What this catches, and what it does not</h2>
 *
 * <p>Source scanning catches an {@code import} of a forbidden package and a
 * fully-qualified reference in a method body — between them, every ordinary way
 * a dependency gets written. It cannot see reflection or a class named in a
 * string, which is not how anyone accidentally writes a second importer.
 *
 * <p>Unlike the ArchUnit version, it cannot pass vacuously:
 * {@link #theScanActuallyFindsTheEngineSource()} fails if the file set is empty.
 */
class ImportEngineIsolationTest {

    private static final Path ENGINE_SOURCE =
            Path.of("src/main/java/com/edunext/edutrack/api/feature/imports");
    private static final Path REGISTRATION_SOURCE = ENGINE_SOURCE.resolve("schemas");

    /**
     * Business entities the engine must not know about. {@code domain.imports}
     * is deliberately absent — {@code ImportBatch} is the engine's own record of
     * a run, not somebody else's entity.
     */
    private static final Map<String, String> FORBIDDEN_IN_ENGINE = Map.of(
            "com.edunext.edutrack.domain.clients", "the client master",
            "com.edunext.edutrack.domain.identity", "users and projects",
            "com.edunext.edutrack.domain.tickets", "tickets");

    /** Nothing the dry run may reach, because reaching any of it means it can write. */
    private static final List<String> PERSISTENCE = List.of(
            "org.springframework.data",
            "org.springframework.jdbc",
            "jakarta.persistence");

    @Test
    @DisplayName("the scan is actually looking at files — an empty scan passes every rule")
    void theScanActuallyFindsTheEngineSource() {
        // The exact failure that made the ArchUnit version of this file
        // worthless. A guard that cannot fail is a green tick claiming a rule
        // is enforced when nothing is being examined.
        assertThat(engineSources()).isNotEmpty();
        assertThat(engineSources()).anyMatch(p -> p.endsWith("ImportValidationEngine.java"));
        assertThat(registrationSources()).isNotEmpty();
    }

    @Test
    @DisplayName("the engine knows nothing about clients — that belongs in a registration")
    void theEngineDoesNotDependOnAnyBusinessEntity() {
        for (Path source : engineSources()) {
            String body = read(source);
            FORBIDDEN_IN_ENGINE.forEach((forbiddenPackage, what) ->
                    assertThat(body)
                            .as("""
                                    %s references %s (%s).

                                    The import engine is written once and registered twice \
                                    (B-030, blueprint §4B.3) — a dependency here is the first \
                                    half of a second implementation. Put the entity-specific \
                                    behaviour in an ImportSchemaDefinition under .schemas, \
                                    where ClientImportSchema already lives and where B-038's \
                                    resource registration will go.""",
                                    source.getFileName(), forbiddenPackage, what)
                            .doesNotContain(forbiddenPackage));
        }
    }

    @Test
    @DisplayName("the dry run holds no repository — step 4 cannot write even by accident")
    void theValidationEngineCannotReachThePersistenceLayer() {
        // Blueprint §4B.3 makes step 4's guarantee absolute: "nothing is written
        // yet". A guarantee resting on nobody adding a convenient save() is not
        // a guarantee, so the dependency is not available to add.
        String body = read(ENGINE_SOURCE.resolve("ImportValidationEngine.java"));

        for (String persistence : PERSISTENCE) {
            assertThat(body)
                    .as("""
                            ImportValidationEngine references %s.

                            The step-4 dry run writes nothing. It reaches the database only \
                            through ImportSchemaDefinition#findExisting, which is read-only \
                            by contract.""", persistence)
                    .doesNotContain(persistence);
        }
    }

    @Test
    @DisplayName("registrations do not call each other — two schemas, not a hierarchy")
    void registrationsAreIndependent() {
        // A registration reusing another one is how "clients but slightly
        // different" gets built: a second implementation wearing the registry's
        // clothes. Anything two registrations need is engine behaviour.
        Pattern otherRegistration = Pattern.compile("\\b(\\w+)ImportSchema\\b");

        for (Path source : registrationSources()) {
            String self = source.getFileName().toString().replace(".java", "");
            otherRegistration.matcher(read(source)).results()
                    .map(match -> match.group())
                    .filter(referenced -> !referenced.equals(self))
                    .forEach(referenced -> assertThat(referenced)
                            .as("%s references another registration, %s", self, referenced)
                            .isEqualTo(self));
        }
    }

    @Test
    @DisplayName("every registration lives under .schemas, so the rules above see it")
    void registrationsAreWhereTheyAreExpected() {
        // An ImportSchemaDefinition implemented outside .schemas would sit in
        // the engine package and be caught by the dependency rule — but with a
        // confusing message. This says the real thing first.
        for (Path source : engineSources()) {
            assertThat(read(source))
                    .as("%s implements ImportSchemaDefinition but is not under .schemas",
                            source.getFileName())
                    .doesNotContain("implements ImportSchemaDefinition");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Engine sources only — {@code .schemas} is where entity knowledge is allowed. */
    private static List<Path> engineSources() {
        return javaFilesUnder(ENGINE_SOURCE).stream()
                .filter(p -> !p.startsWith(REGISTRATION_SOURCE))
                .toList();
    }

    private static List<Path> registrationSources() {
        return javaFilesUnder(REGISTRATION_SOURCE);
    }

    private static List<Path> javaFilesUnder(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            // Surefire's working directory is the module root. If that ever
            // stops being true this must fail, not quietly scan nothing.
            throw new UncheckedIOException("Could not read " + root.toAbsolutePath(), e);
        }
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + source.toAbsolutePath(), e);
        }
    }
}
