package com.edunext.edutrack.api.security.permission;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-033 · the Java catalogue and the database must agree, and this is what makes
 * a disagreement a build failure.
 *
 * <h2>Why the drift matters more than it looks</h2>
 *
 * <p>{@link Permissions} and {@link RolePermissions} are mirrors of
 * {@code role_permissions}, and a mirror nobody checks is worse than no mirror:
 * it reads as authoritative while being stale. Both directions of drift are
 * silent in the dangerous way.
 *
 * <ul>
 *   <li><b>A code in Java that is not in the database</b> produces
 *       {@code hasAuthority('ticket.approve')} — an authority no token can ever
 *       carry, so the route denies all six roles for ever. In review it reads
 *       like a working check; as an Admin, whose smoke test is the one everybody
 *       runs, it reads like a bug in the screen.</li>
 *   <li><b>A code in the database that is not in Java</b> is a capability S-09
 *       can grant and no route consults — an administrator ticking a box that
 *       does nothing.</li>
 *   <li><b>A grant that differs</b> means {@code dev-noauth} and production
 *       disagree about authorisation, and the local environment is the trusted
 *       one because it is the one people try things in.</li>
 * </ul>
 *
 * <p>Text parsing, not a database — so it runs in surefire on every build rather
 * than only where Docker exists, which is the same trade {@code SeedManifestTest}
 * makes for the load order. {@code SeedDataIT} covers what the migrations
 * actually produced when run.
 *
 * <h2>Every migration, not just the seed</h2>
 *
 * <p>The whole directory is scanned rather than {@code V20260806_0900} alone.
 * Migrations are append-only by policy — corrections are new files — so the
 * current truth is the seed plus everything after it, and
 * {@code V20260807_1030} is already an example: it renamed
 * {@code SUPPORT_DESK} to {@code SUPPORT} and its own header names A-033 as one
 * of the three things that rename was protecting. Reading only the seed would
 * have this test asserting a role code that no longer exists.
 *
 * <p>The parser understands the three statement shapes the migrations use
 * today. A future migration written differently — a grant inserted by id rather
 * than by code, say — will not be seen, and the assertion will fail rather than
 * quietly pass. That is the intended direction: extend the parser, do not
 * loosen the check.
 */
class PermissionCatalogTest {

    private static final String MIGRATIONS = "classpath*:db/migration/V*.sql";

    /** {@code ('resource.manage', 'Manage resources…', 'admin', '…')} — the code is the first column. */
    private static final Pattern PERMISSION_ROW =
            Pattern.compile("^\\s*\\('([a-z0-9_.]+)'\\s*,", Pattern.MULTILINE);

    /**
     * Both grant shapes in one pattern: the block's opening
     * {@code SELECT 'ADMIN' AS role_code, 'resource.manage' AS permission_code}
     * and the {@code UNION ALL SELECT 'ADMIN', 'project.manage'} rows after it.
     */
    private static final Pattern GRANT_ROW = Pattern.compile(
            "SELECT\\s+'([A-Z_]+)'(?:\\s+AS\\s+role_code)?\\s*,\\s*'([a-z0-9_.]+)'");

    /** {@code V20260807_1030}'s correction, and any future one like it. */
    private static final Pattern ROLE_RENAME = Pattern.compile(
            "UPDATE\\s+roles\\s+SET\\s+code\\s*=\\s*'([A-Z_]+)'\\s+WHERE\\s+code\\s*=\\s*'([A-Z_]+)'",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PERMISSIONS_INSERT =
            Pattern.compile("INSERT\\s+INTO\\s+permissions\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern ROLE_PERMISSIONS_INSERT =
            Pattern.compile("INSERT\\s+INTO\\s+role_permissions\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void permissionsConstantsMatchTheMigrations() {
        assertThat(Permissions.ALL)
                .as("Permissions.ALL must be exactly the codes seeded into the `permissions` "
                        + "table. A code here and not there can never be satisfied by a token; "
                        + "a code there and not here is a capability no route consults.")
                .containsExactlyInAnyOrderElementsOf(seededPermissionCodes());
    }

    @Test
    void everyConstantIsUsedByTheMatrixOrDocumentedAsUngranted() {
        Set<String> granted = new TreeSet<>();
        RolePermissions.matrix().values().forEach(granted::addAll);

        Set<String> ungranted = new TreeSet<>(Permissions.ALL);
        ungranted.removeAll(granted);

        // Blueprint §2: "Edit / delete history or ribbon — ❌ (nobody can)".
        // Exactly one code is expected to have no grants, and asserting *which*
        // one is the point: a second ungranted code means somebody dropped a
        // role's grants without noticing, and a zero-length list here would mean
        // history.edit_delete had acquired one.
        assertThat(ungranted)
                .as("history.edit_delete is granted to nobody by design — CLAUDE.md's "
                        + "append-only rule. Any other ungranted code is a mistake.")
                .containsExactly(Permissions.HISTORY_EDIT_DELETE);
    }

    @Test
    void roleMatrixMatchesTheMigrations() {
        Map<String, Set<String>> fromSql = seededGrants();

        assertThat(RolePermissions.matrix().keySet())
                .as("the six system roles of blueprint §2")
                .containsExactlyInAnyOrderElementsOf(fromSql.keySet());

        // Asserted per role rather than as one map comparison: a whole-map
        // failure prints two twenty-line structures and leaves the reader to
        // diff them, which is how a one-code difference gets waved through.
        fromSql.forEach((role, expected) -> assertThat(RolePermissions.of(role))
                .as("grants for %s, per the seed migration", role)
                .containsExactlyInAnyOrderElementsOf(expected));
    }

    @Test
    void theSupportRoleCodeIsTheCorrectedOne() {
        // Guards the specific defect V20260807_1030 was written to fix. If a
        // future edit resurrects SUPPORT_DESK, three things break at once — the
        // JWT role claim against the generated Zod enum, the ribbon's stage
        // owner match, and every role check here — and the failure would appear
        // in none of them obviously.
        assertThat(seededGrants()).containsKey(RolePermissions.SUPPORT);
        assertThat(seededGrants()).doesNotContainKey("SUPPORT_DESK");
    }

    // ------------------------------------------------------------------
    // parsing
    // ------------------------------------------------------------------

    private static Set<String> seededPermissionCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (String sql : migrationsInLoadOrder()) {
            for (String statement : statementsStartingWith(sql, PERMISSIONS_INSERT)) {
                Matcher row = PERMISSION_ROW.matcher(statement);
                while (row.find()) {
                    codes.add(row.group(1));
                }
            }
        }
        assertThat(codes)
                .as("no INSERT INTO permissions found — the parser has stopped "
                        + "matching the migrations rather than the migrations being empty")
                .isNotEmpty();
        return codes;
    }

    private static Map<String, Set<String>> seededGrants() {
        Map<String, Set<String>> grants = new LinkedHashMap<>();
        Map<String, String> renames = new LinkedHashMap<>();

        for (String sql : migrationsInLoadOrder()) {
            for (String statement : statementsStartingWith(sql, ROLE_PERMISSIONS_INSERT)) {
                Matcher row = GRANT_ROW.matcher(statement);
                while (row.find()) {
                    grants.computeIfAbsent(row.group(1), any -> new LinkedHashSet<>())
                            .add(row.group(2));
                }
            }
            Matcher rename = ROLE_RENAME.matcher(sql);
            while (rename.find()) {
                renames.put(rename.group(2), rename.group(1));
            }
        }

        // Applied after collection, in file order, because a rename migration
        // runs after the seed it corrects — reading the grants under the old
        // code and then moving them is what Flyway actually did.
        renames.forEach((from, to) -> {
            Set<String> moved = grants.remove(from);
            if (moved != null) {
                grants.computeIfAbsent(to, any -> new LinkedHashSet<>()).addAll(moved);
            }
        });

        assertThat(grants)
                .as("no INSERT INTO role_permissions found — see the parser note in the javadoc")
                .isNotEmpty();
        return grants;
    }

    /**
     * The statements in {@code sql} whose first line matches {@code opening},
     * each collected up to the line that ends it.
     *
     * <p><b>Line-oriented rather than a {@code (.*?);} regex, and that is not a
     * style choice.</b> The first version of this parser bounded a statement at
     * the next semicolon and read exactly two of the eighteen permissions,
     * because {@code project.manage}'s description is
     * "Create/edit projects; map resources to a project." — a semicolon inside a
     * string literal. The test still passed its other three cases and reported a
     * catalogue mismatch that did not exist, which is the failure mode a drift
     * test can least afford: it cost more to disbelieve than the drift would
     * have. Every row in these migrations ends its line with {@code ),} or
     * {@code );}, so end-of-statement is "a line whose last character is a
     * semicolon" and a semicolon mid-string cannot be mistaken for one.
     */
    private static List<String> statementsStartingWith(String sql, Pattern opening) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = null;

        for (String line : sql.split("\\R")) {
            if (current == null) {
                if (opening.matcher(line).find()) {
                    current = new StringBuilder(line).append('\n');
                }
                continue;
            }
            current.append(line).append('\n');
            if (line.stripTrailing().endsWith(";")) {
                statements.add(current.toString());
                current = null;
            }
        }
        // An unterminated statement is a truncated file, not something to
        // salvage — dropping it would under-report codes and read as drift.
        assertThat(current)
                .as("unterminated SQL statement while scanning for %s", opening)
                .isNull();
        return statements;
    }

    /** Ascending filename, which is the order Flyway applies them in. */
    private static List<String> migrationsInLoadOrder() {
        try {
            Resource[] found = new PathMatchingResourcePatternResolver().getResources(MIGRATIONS);
            List<Resource> ordered = new ArrayList<>(List.of(found));
            ordered.sort(Comparator.comparing(resource -> String.valueOf(resource.getFilename())));

            List<String> contents = new ArrayList<>();
            for (Resource resource : ordered) {
                try (var in = resource.getInputStream()) {
                    contents.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            return contents;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "could not read " + MIGRATIONS + " — the domain module's resources "
                            + "should be on the api test classpath", e);
        }
    }
}
