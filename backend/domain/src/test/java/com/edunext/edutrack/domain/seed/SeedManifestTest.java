package com.edunext.edutrack.domain.seed;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-008 · the seed manifest is the load order, not a description of it.
 *
 * <h2>Why a test and not just a document</h2>
 *
 * <p>TEAM-PLAN.md §7.3 asks for seeds "loaded in a fixed order by a manifest".
 * Flyway already runs migrations in a fixed order — ascending version. What it
 * does not do is make that order <em>reviewable</em>, and ordering defects never
 * live inside one file. They live between two, where nobody looks.
 *
 * <p>That is not theoretical here. {@code V20260807_1030} renamed a role code
 * and {@code V20260807_1100}, queued directly behind it, wrote the old value
 * back into thirteen rows. Both files read correctly on their own. Only the
 * queue was wrong, and only listing the queue showed it.
 *
 * <p>A manifest nobody updates is worse than none — it reads as authoritative
 * while being stale. So this asserts the register in {@code SEED-MANIFEST.md}
 * matches this directory exactly: every migration listed, nothing listed that
 * does not exist, in the order Flyway will run them. Adding a migration without
 * a manifest row fails the build.
 *
 * <p>Reflection over text files, so it runs in surefire on every build rather
 * than only where Docker exists. {@code SeedDataIT} is the other half — it
 * checks what the order actually <em>produced</em>.
 */
class SeedManifestTest {

    private static final String MANIFEST = "db/migration/SEED-MANIFEST.md";

    /** Same shape CI's migration-guard job enforces. */
    private static final Pattern MIGRATION_FILE =
            Pattern.compile("V\\d{8}_\\d{4}__[a-z0-9_]+\\.sql");

    /**
     * Only the fenced register is parsed. The manifest names migrations in its
     * prose too, and prose is not in load order — without the markers this
     * would fail on the correction-history table.
     */
    private static final Pattern REGISTER = Pattern.compile(
            "<!-- load-order:begin -->(.*?)<!-- load-order:end -->", Pattern.DOTALL);

    // ------------------------------------------------------------------
    // Completeness, both directions
    // ------------------------------------------------------------------

    /** The failure mode this whole file exists for: a migration nobody registered. */
    @Test
    void everyMigrationIsRegistered() {
        List<String> unregistered = new ArrayList<>(migrationsOnDisk());
        unregistered.removeAll(registeredInOrder());

        assertThat(unregistered)
                .as("migrations present in db/migration but missing from %s — "
                        + "add a row to its load-order table, in version order", MANIFEST)
                .isEmpty();
    }

    /** The mirror: a row surviving a rename or deletion, still claiming to be the order. */
    @Test
    void everyRegisteredFileExists() {
        List<String> phantom = new ArrayList<>(registeredInOrder());
        phantom.removeAll(migrationsOnDisk());

        assertThat(phantom)
                .as("%s lists migrations that are not in db/migration", MANIFEST)
                .isEmpty();
    }

    /**
     * A row in the wrong place is the defect that matters — the register is only
     * worth reading if its top-to-bottom is Flyway's first-to-last.
     *
     * <p>Flyway orders numerically by version; these names are fixed-width digits,
     * so lexicographic order is the same order.
     */
    @Test
    void theRegisterIsInTheOrderFlywayWillRunThem() {
        assertThat(registeredInOrder())
                .as("the load-order table in %s must be sorted by version", MANIFEST)
                .isSortedAccordingTo(Comparator.naturalOrder())
                .containsExactlyElementsOf(migrationsOnDisk());
    }

    /** Two rows for one file means one of them is being silently ignored by every reader. */
    @Test
    void noMigrationIsRegisteredTwice() {
        assertThat(registeredInOrder())
                .as("each migration appears exactly once in the %s register", MANIFEST)
                .doesNotHaveDuplicates();
    }

    /**
     * CI enforces this too, but only against a pull request's diff. This catches it
     * on the branch, before the push, and covers files CI has already accepted.
     */
    @Test
    void everyMigrationIsTimestampVersioned() {
        assertThat(migrationsOnDisk())
                .as("sequential V14__ numbering guarantees collisions between four "
                        + "developers — timestamps make them impossible")
                .allMatch(name -> MIGRATION_FILE.matcher(name).matches());
    }

    // ------------------------------------------------------------------
    // Sources of truth
    // ------------------------------------------------------------------

    /** What Flyway will actually run, in the order it will run it. */
    private static List<String> migrationsOnDisk() {
        try {
            Resource[] found = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/*.sql");

            List<String> names = Arrays.stream(found)
                    .map(Resource::getFilename)
                    .filter(java.util.Objects::nonNull)
                    .sorted()
                    .toList();

            // A resolver that silently matched nothing would make every
            // assertion above vacuously true.
            assertThat(names)
                    .as("the migration directory was found on the classpath")
                    .isNotEmpty();
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException("could not scan db/migration", e);
        }
    }

    /** What the manifest claims that order is. */
    private static List<String> registeredInOrder() {
        Matcher block = REGISTER.matcher(manifestText());
        assertThat(block.find())
                .as("%s must fence its load-order table with the "
                        + "<!-- load-order:begin --> and <!-- load-order:end --> "
                        + "markers this test parses", MANIFEST)
                .isTrue();

        List<String> registered = new ArrayList<>();
        Matcher rows = MIGRATION_FILE.matcher(block.group(1));
        while (rows.find()) {
            registered.add(rows.group());
        }
        return registered;
    }

    private static String manifestText() {
        try (var in = new ClassPathResource(MANIFEST).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    MANIFEST + " is missing — it is the register every migration registers in", e);
        }
    }
}
