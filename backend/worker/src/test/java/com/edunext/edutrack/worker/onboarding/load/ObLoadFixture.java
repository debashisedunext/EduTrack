package com.edunext.edutrack.worker.onboarding.load;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * C-117 · the onboarding scanners' load fixture — 500 active journeys and
 * 4,000 open steps, seeded once per container.
 *
 * <h2>Why a shared class rather than a helper on each IT</h2>
 *
 * <p>{@code ObTatScanner} and {@code ObEscalationScanner} are both
 * package-private, so a load pass over each has to live in that scanner's own
 * package and there is no single class that can drive both. The two ITs
 * ({@code ObTatScannerLoadIT}, {@code ObEscalationScannerLoadIT}) are
 * therefore separate; what they must not have separately is <em>the shape of
 * the load</em>, because the whole claim of this task is that both scanners
 * were measured against the same population. That population is defined once,
 * here.
 *
 * <h2>The shape, and why each part of it is there</h2>
 *
 * <p>500 active journeys × 8 open steps = the 4,000 the backlog names. All
 * eight are open — none {@code DONE}, none {@code SKIPPED} — so the table the
 * scanners sweep is at its worst realistic size rather than padded with rows
 * their {@code WHERE} discards for free.
 *
 * <p>Per journey, by sequence:
 *
 * <pre>
 *   0  IN_PROGRESS       overdue always                   → 500 candidates
 *   1  IN_PROGRESS       overdue on every 2nd journey     → 250 candidates
 *   2  IN_PROGRESS       due in the future
 *   3  BLOCKED           overdue on every 4th journey     → 125 candidates
 *   4  BLOCKED           due in the future
 *   5  WAITING_ON_CLIENT due_at already past, clock paused → never a candidate
 *   6  PENDING           no due date yet
 *   7  PENDING           no due date yet
 * </pre>
 *
 * <p>{@link #OVERDUE_STEPS} = 875 is deliberately not a multiple of the
 * scanners' 500-per-pass cap: a backlog that divided evenly would never
 * exercise the partial final pass, which is the one a drain-rate assertion can
 * actually get wrong.
 *
 * <p>Sequence 5 is the load-scale restatement of {@code ObTatScannerIT}'s
 * "§5.7: WAITING_ON_CLIENT has a paused clock and is never flagged" — 500 rows
 * that look overdue to a careless query. A filter that regressed would show up
 * here as 1,375 candidates rather than 875, which is why both ITs assert an
 * exact count and not merely a duration.
 *
 * <h2>The excluded journeys are part of the load, not decoration</h2>
 *
 * <p>{@link #EXCLUDED_JOURNEYS} carry {@link #EXCLUDED_OVERDUE_STEPS} steps
 * that are overdue in every respect the step row itself records, but whose
 * journey or client is not running — archived, completed, gate still
 * {@code LOCKED}, client {@code ON_HOLD}. {@code ObTatRepository}'s own
 * javadoc calls those filters settled; this is where "settled" gets checked
 * against a table big enough for the optimiser to change its mind about the
 * join order.
 */
public final class ObLoadFixture {

    /** The backlog's own numbers: "500 active journeys, 4,000 open steps". */
    public static final int ACTIVE_JOURNEYS = 500;
    public static final int STEPS_PER_JOURNEY = 8;
    public static final int OPEN_STEPS = ACTIVE_JOURNEYS * STEPS_PER_JOURNEY;

    /** 500 + 250 + 125 — see the table above. */
    public static final int OVERDUE_STEPS =
            ACTIVE_JOURNEYS + ACTIVE_JOURNEYS / 2 + ACTIVE_JOURNEYS / 4;

    /** Paused clocks with a due date already behind them. Never candidates. */
    public static final int PAUSED_PAST_DUE_STEPS = ACTIVE_JOURNEYS;

    /** Of the 875, the ones {@link #markRecentlyBreached} moves to a fresh breach. */
    public static final int RECENTLY_BREACHED_STEPS = ACTIVE_JOURNEYS / 4;

    public static final int EXCLUDED_JOURNEYS = 24;
    public static final int EXCLUDED_STEPS_PER_JOURNEY = 2;
    public static final int EXCLUDED_OVERDUE_STEPS = EXCLUDED_JOURNEYS * EXCLUDED_STEPS_PER_JOURNEY;

    /** Both scanners cap one pass at 500 candidates ({@code MAX_PER_PASS}). */
    public static final int CAP_PER_PASS = 500;

    private static final int PRODUCTS = 10;
    private static final int MANAGERS = 20;
    private static final int OWNERS = 200;

    /** Rows per round trip. Seeding time is not what this test measures. */
    private static final int BATCH = 500;

    private ObLoadFixture() {
    }

    /**
     * @param journeyIds the 500 active journeys, in insertion order
     * @param obAdminId  the single live {@code OB_ADMIN} grant — L3's recipient
     */
    public record Seeded(List<Long> journeyIds, long obAdminId) {
    }

    /**
     * Seed the whole population — roughly 5,000 rows, in a handful of batched
     * INSERTs rather than 5,000 round trips.
     */
    public static Seeded seed(JdbcTemplate jdbc, Instant now) {
        long roleId = scalar(jdbc, "SELECT id FROM roles ORDER BY id LIMIT 1");

        long obAdmin = insertUsers(jdbc, roleId, "load-adm", 1, null).get(0);
        jdbc.update("""
                INSERT INTO user_module_access (user_id, module, module_role, granted_at)
                VALUES (?, 'ONBOARDING', 'OB_ADMIN', ?)
                """, obAdmin, Timestamp.from(now.minusSeconds(86_400)));

        List<Long> managers = insertUsers(jdbc, roleId, "load-mgr", MANAGERS, null);
        List<Long> owners = insertOwners(jdbc, roleId, managers);

        List<Long> products = insertProducts(jdbc);
        List<Long> templates = insertTemplates(jdbc, products);

        List<Long> clients = insertClients(jdbc, "Load Client", ACTIVE_JOURNEYS, "ONBOARDING");
        List<Long> journeys = insertJourneys(jdbc, clients, products, templates, now, JourneyKind.ACTIVE);
        insertActiveSteps(jdbc, journeys, owners, now);

        insertExcluded(jdbc, products, templates, owners, now);

        // The optimiser plans from statistics, and a table that has just been
        // bulk-loaded has none worth the name. Without this the first measured
        // pass is measuring a stale plan rather than the query.
        jdbc.execute("ANALYZE TABLE ob_journey_steps");
        jdbc.execute("ANALYZE TABLE ob_journeys");
        jdbc.execute("ANALYZE TABLE ob_clients");

        return new Seeded(journeys, obAdmin);
    }

    /**
     * Flip every overdue active step to breached, as though the TAT scanner
     * had already been round — the escalation ladder's own starting state.
     *
     * <p>A direct {@code UPDATE} rather than a call to {@code ObTatScanner}:
     * the escalation load pass measures the escalation sweep, and running 875
     * breach transactions to arrange its fixture would fold the other
     * scanner's cost into this one's timings.
     *
     * <p>{@code now} and {@code breachedAt} are separate arguments and must
     * stay so: which steps are overdue is decided against the scanner's "now",
     * while the flag records a moment further back. Deciding it against
     * {@code breachedAt} instead would silently mark only the steps that were
     * already overdue three days ago — a smaller set than {@link
     * #OVERDUE_STEPS}, and a load pass quietly measuring a lighter load.
     *
     * @return how many steps were marked
     */
    public static int markOverdueAsBreached(JdbcTemplate jdbc, Instant now, Instant breachedAt) {
        return jdbc.update("""
                UPDATE ob_journey_steps s
                  JOIN ob_journeys j ON j.id = s.journey_id
                  JOIN ob_clients  c ON c.id = j.ob_client_id
                   SET s.tat_breached_at = ?
                 WHERE s.status IN ('IN_PROGRESS', 'BLOCKED')
                   AND s.due_at IS NOT NULL
                   AND s.due_at < ?
                   AND j.archived_at  IS NULL
                   AND j.completed_at IS NULL
                   AND j.gate_status   = 'OPEN'
                   AND c.overall_status = 'ONBOARDING'
                """, Timestamp.from(breachedAt), Timestamp.from(now));
    }

    /**
     * Move the 125 overdue {@code BLOCKED} steps' breach to a few minutes ago,
     * leaving the other 750 breached days ago.
     *
     * <p>The escalation ladder's L2 and L3 are gated on working hours elapsed
     * <em>since the breach</em>, and a fixture where every breach is equally
     * old would prove only that the gate is open. These 125 are the cohort
     * that must climb to L1 and stop there — the load-scale statement of
     * {@code ObEscalationScannerIT}'s "L2 does not fire before its threshold".
     *
     * @return how many steps were moved
     */
    public static int markRecentlyBreached(JdbcTemplate jdbc, Instant breachedAt) {
        return jdbc.update("""
                UPDATE ob_journey_steps
                   SET tat_breached_at = ?
                 WHERE status = 'BLOCKED'
                   AND sequence = 4
                   AND tat_breached_at IS NOT NULL
                """, Timestamp.from(breachedAt));
    }

    /** Undo the flags so a second measured pass starts from the same place. */
    public static int clearBreachFlags(JdbcTemplate jdbc) {
        return jdbc.update(
                "UPDATE ob_journey_steps SET tat_breached_at = NULL WHERE tat_breached_at IS NOT NULL");
    }

    // ------------------------------------------------------------- seeding

    private enum JourneyKind { ACTIVE, ARCHIVED, COMPLETED, GATE_LOCKED, CLIENT_ON_HOLD }

    private static void insertExcluded(JdbcTemplate jdbc, List<Long> products, List<Long> templates,
                                       List<Long> owners, Instant now) {
        JourneyKind[] kinds = {
                JourneyKind.ARCHIVED, JourneyKind.COMPLETED,
                JourneyKind.GATE_LOCKED, JourneyKind.CLIENT_ON_HOLD};
        int per = EXCLUDED_JOURNEYS / kinds.length;

        for (JourneyKind kind : kinds) {
            String status = kind == JourneyKind.CLIENT_ON_HOLD ? "ON_HOLD" : "ONBOARDING";
            List<Long> clients = insertClients(jdbc, "Load Excluded " + kind, per, status);
            List<Long> journeys = insertJourneys(jdbc, clients, products, templates, now, kind);
            insertExcludedSteps(jdbc, journeys, owners, now);
        }
    }

    private static List<Long> insertOwners(JdbcTemplate jdbc, long roleId, List<Long> managers) {
        List<Object[]> rows = new ArrayList<>(OWNERS);
        for (int i = 0; i < OWNERS; i++) {
            String name = "load-own-" + i;
            rows.add(new Object[] {
                    name, name, name + "@edunext.test", name, roleId, managers.get(i % managers.size())});
        }
        jdbc.batchUpdate(INSERT_USER, rows, BATCH, ObLoadFixture::bind);
        return ids(jdbc, "SELECT id FROM users WHERE username LIKE ? ORDER BY id", "load-own-%");
    }

    private static final String INSERT_USER = """
            INSERT INTO users (emp_code, username, email, password_hash, full_name,
                               role_id, reporting_manager_id)
            VALUES (?, ?, ?, 'not-a-real-hash', ?, ?, ?)
            """;

    private static List<Long> insertUsers(JdbcTemplate jdbc, long roleId, String prefix,
                                          int count, Long managerId) {
        List<Object[]> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = prefix + "-" + i;
            rows.add(new Object[] {name, name, name + "@edunext.test", name, roleId, managerId});
        }
        jdbc.batchUpdate(INSERT_USER, rows, BATCH, ObLoadFixture::bind);
        return ids(jdbc, "SELECT id FROM users WHERE username LIKE ? ORDER BY id", prefix + "-%");
    }

    private static List<Long> insertProducts(JdbcTemplate jdbc) {
        List<Object[]> rows = new ArrayList<>(PRODUCTS);
        for (int i = 0; i < PRODUCTS; i++) {
            rows.add(new Object[] {"LOAD" + i, "Load Product " + i});
        }
        jdbc.batchUpdate("INSERT INTO ob_products (code, name) VALUES (?, ?)", rows, BATCH, ObLoadFixture::bind);
        return ids(jdbc, "SELECT id FROM ob_products WHERE code LIKE ? ORDER BY id", "LOAD%");
    }

    private static List<Long> insertTemplates(JdbcTemplate jdbc, List<Long> products) {
        List<Object[]> rows = new ArrayList<>(products.size());
        for (long product : products) {
            rows.add(new Object[] {product, "Load Template " + product});
        }
        jdbc.batchUpdate("INSERT INTO ob_journey_templates (product_id, name) VALUES (?, ?)",
                rows, BATCH, ObLoadFixture::bind);
        return ids(jdbc, "SELECT id FROM ob_journey_templates WHERE name LIKE ? ORDER BY id",
                "Load Template%");
    }

    private static List<Long> insertClients(JdbcTemplate jdbc, String prefix, int count, String status) {
        List<Object[]> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new Object[] {
                    prefix + " " + i,
                    status,
                    "ON_HOLD".equals(status) ? "Parked for the load fixture" : null});
        }
        jdbc.batchUpdate("""
                INSERT INTO ob_clients (name, onboarding_date, overall_status, status_reason)
                VALUES (?, '2026-08-01', ?, ?)
                """, rows, BATCH, ObLoadFixture::bind);
        return ids(jdbc, "SELECT id FROM ob_clients WHERE name LIKE ? ORDER BY id", prefix + " %");
    }

    /**
     * One live journey per client, so {@code uq_ob_journeys_client_product}
     * holds whichever product the client draws.
     */
    private static List<Long> insertJourneys(JdbcTemplate jdbc, List<Long> clients, List<Long> products,
                                             List<Long> templates, Instant now, JourneyKind kind) {
        Instant started = now.minusSeconds(30L * 86_400);

        List<Object[]> applications = new ArrayList<>(clients.size());
        List<Object[]> journeys = new ArrayList<>(clients.size());
        for (int i = 0; i < clients.size(); i++) {
            int slot = i % products.size();
            applications.add(new Object[] {clients.get(i), products.get(slot)});

            boolean locked = kind == JourneyKind.GATE_LOCKED;
            journeys.add(new Object[] {
                    clients.get(i), products.get(slot), templates.get(slot),
                    locked ? "LOCKED" : "OPEN",
                    // ck_ob_journeys_gate_opened_at: an OPEN gate has a moment it opened at.
                    locked ? null : Timestamp.from(started),
                    Timestamp.from(started),
                    kind == JourneyKind.COMPLETED ? Timestamp.from(now.minusSeconds(86_400)) : null,
                    kind == JourneyKind.ARCHIVED ? Timestamp.from(now.minusSeconds(86_400)) : null});
        }

        jdbc.batchUpdate("INSERT INTO ob_client_applications (ob_client_id, product_id) VALUES (?, ?)",
                applications, BATCH, ObLoadFixture::bind);
        jdbc.batchUpdate("""
                INSERT INTO ob_journeys (ob_client_id, product_id, template_id, gate_status,
                                         gate_opened_at, started_at, completed_at, archived_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, journeys, BATCH, ObLoadFixture::bind);

        return ids(jdbc, "SELECT id FROM ob_journeys WHERE ob_client_id IN ("
                + placeholders(clients) + ") ORDER BY id", clients.toArray());
    }

    private static void insertActiveSteps(JdbcTemplate jdbc, List<Long> journeys,
                                          List<Long> owners, Instant now) {
        Instant longAgo = now.minusSeconds(10L * 86_400);
        List<Object[]> rows = new ArrayList<>(journeys.size() * STEPS_PER_JOURNEY);

        for (int j = 0; j < journeys.size(); j++) {
            for (int seq = 0; seq < STEPS_PER_JOURNEY; seq++) {
                String status = statusFor(seq);
                Instant dueAt = dueAtFor(seq, isOverdue(j, seq), j, now);
                int slot = j * STEPS_PER_JOURNEY + seq;

                rows.add(new Object[] {
                        journeys.get(j), seq + 1, "Load step " + seq,
                        owners.get(slot % owners.size()),
                        owners.get((slot + 1) % owners.size()),
                        status,
                        // ck_ob_journey_steps_blocked_reason: BLOCKED needs one.
                        "BLOCKED".equals(status) ? "CLIENT_DEPENDENCY" : null,
                        dueAt == null ? null : Timestamp.from(dueAt),
                        Timestamp.from(longAgo), Timestamp.from(longAgo)});
            }
        }
        insertSteps(jdbc, rows);
    }

    /** Overdue in every respect the scanner reads on the step row itself. */
    private static void insertExcludedSteps(JdbcTemplate jdbc, List<Long> journeys,
                                            List<Long> owners, Instant now) {
        Instant longAgo = now.minusSeconds(10L * 86_400);
        List<Object[]> rows = new ArrayList<>(journeys.size() * EXCLUDED_STEPS_PER_JOURNEY);

        for (int j = 0; j < journeys.size(); j++) {
            for (int seq = 0; seq < EXCLUDED_STEPS_PER_JOURNEY; seq++) {
                long owner = owners.get((j + seq) % owners.size());
                rows.add(new Object[] {
                        journeys.get(j), seq + 1, "Excluded step " + seq, owner, owner,
                        "IN_PROGRESS", null,
                        Timestamp.from(now.minusSeconds(7_200)),
                        Timestamp.from(longAgo), Timestamp.from(longAgo)});
            }
        }
        insertSteps(jdbc, rows);
    }

    private static void insertSteps(JdbcTemplate jdbc, List<Object[]> rows) {
        jdbc.batchUpdate("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, owner_user_id,
                                              backup_owner_user_id, status, blocked_reason_code,
                                              due_at, started_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows, BATCH, ObLoadFixture::bind);
    }

    private static String statusFor(int seq) {
        return switch (seq) {
            case 0, 1, 2 -> "IN_PROGRESS";
            case 3, 4 -> "BLOCKED";
            case 5 -> "WAITING_ON_CLIENT";
            default -> "PENDING";
        };
    }

    private static boolean isOverdue(int journeyIndex, int seq) {
        return switch (seq) {
            case 0 -> true;
            case 1 -> journeyIndex % 2 == 0;
            case 3 -> journeyIndex % 4 == 0;
            default -> false;
        };
    }

    /**
     * Overdue steps are spread across a fortnight rather than stacked on one
     * instant: the candidate query orders by {@code due_at}, and 875 rows
     * sharing a single value would let that sort disappear into a tie.
     */
    private static Instant dueAtFor(int seq, boolean overdue, int journeyIndex, Instant now) {
        if (seq >= 6) {
            return null;                             // PENDING — not started, nothing promised yet
        }
        if (seq == 5) {
            return now.minusSeconds(3L * 86_400);    // paused, and past its frozen date
        }
        return overdue
                ? now.minusSeconds(3_600L + (journeyIndex % 14) * 86_400L)
                : now.plusSeconds(2L * 86_400);
    }

    // ------------------------------------------------------------ plumbing

    private static void bind(java.sql.PreparedStatement ps, Object[] row) throws java.sql.SQLException {
        for (int i = 0; i < row.length; i++) {
            ps.setObject(i + 1, row[i]);
        }
    }

    private static List<Long> ids(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.queryForList(sql, Long.class, args);
    }

    private static long scalar(JdbcTemplate jdbc, String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        if (value == null) {
            throw new IllegalStateException("no row for: " + sql);
        }
        return value;
    }

    private static String placeholders(List<Long> values) {
        return String.join(",", Collections.nCopies(values.size(), "?"));
    }
}
