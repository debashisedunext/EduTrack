package com.edunext.edutrack.api.feature.fixtures.onboarding;

import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.ApplicationSpec;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.ClientSpec;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.CommunicationSpec;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.ContactSpec;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.EscalationSpec;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.ItemAnswerSpec;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.JourneySpec;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.ProductSpec;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.StepSpec;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.TemplateSpec;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.UserSpec;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureSchedule.JourneySchedule;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureSchedule.StepSchedule;
import com.edunext.edutrack.domain.masters.WorkingCalendar;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * B-101 · writes the onboarding fixture corpus.
 *
 * <p>Everything the corpus <em>is</em> lives in {@link OnboardingFixtureData};
 * everything about <em>where it goes</em> lives here. It seeds four products,
 * three journey template versions with their steps, Task List items and
 * document checklists, eight clients with SPOCs, purchases and requirements,
 * eleven journeys across them, every step under those journeys with its Task
 * List answers, the clock events that produced their elapsed time, the
 * communications timeline, the journey history and one open client escalation.
 *
 * <h2>Why this writes SQL rather than entities</h2>
 *
 * <p>There is no JPA mapping for a single {@code ob_*} table on {@code develop},
 * and there deliberately is not: A-101 to A-106 landed six migrations and no
 * Java at all. The entities are being written right now by the features that
 * own them — C-101 has {@code domain/onboarding/ObJourneyTemplate*} in flight
 * and B-102 will bring the client aggregate. A fixture that declared its own
 * would either duplicate both or block on both, and the duplicate is the worse
 * outcome: two mappings of one table that drift is exactly the failure the
 * append-only rules exist to prevent elsewhere.
 *
 * <p>So this writes columns. It costs some verbosity and buys the corpus the
 * ability to land <em>before</em> the feature packages rather than after them,
 * which is the entire reason B-101 sits at the front of OB1 — the same argument
 * B-007 made in phase 1, where the corpus is what let D test the SLA scanner
 * and C test the ribbon before either existed.
 *
 * <h2>The hash chain is left NULL, on purpose</h2>
 *
 * <p>{@code ob_step_history} is hash-chained, and every row this class writes
 * leaves {@code prev_hash} and {@code row_hash} NULL. {@code ChainDigest} is
 * generic enough to call, but a chain is only as good as agreement on
 * <em>what is hashed</em>: {@code ChainPayloads} defines that for the ticket
 * journal, the onboarding equivalent has not been written, and a fixture that
 * invented one would hand A-123's verifier rows that fail to verify against
 * whatever Stream A actually ships. {@code ChainDigest}'s own javadoc names
 * both-NULL as the recognised "unchained row" state, so this is a state the
 * design already accounts for rather than a hole. Same call B-007 made, same
 * backfill owed once the onboarding journal lands.
 *
 * <h2>Local runs need A-109's grants first</h2>
 *
 * <p>{@code docker/grants/apply-app-grants.sql} names no {@code ob_*} table —
 * A-109 adds them. Against Testcontainers this is invisible (the test connects
 * as root), but a developer running {@code local,fixtures} against the docker
 * MySQL will see a permission error naming a table that plainly exists until
 * A-109 lands and {@code make grants} is run.
 */
@Component
@Profile("fixtures")
public class OnboardingFixture {

    /** Emp-code prefix, so the corpus's own users are one {@code LIKE} away. */
    private static final String EMP_CODE_PREFIX = "B101-";

    /** A password nobody logs in with — {@code dev-noauth} stands in. Mirrors B-007's. */
    private static final String FIXTURE_PASSWORD = "Fixture#B101-2026";

    /** Where a step's day begins and a communication defaults to, in the calendar's zone. */
    private static final LocalTime STEP_START_TIME = LocalTime.of(10, 0);

    /** Where a completed step's day ends. Before {@code work_day_end}, so it is inside the window. */
    private static final LocalTime STEP_FINISH_TIME = LocalTime.of(17, 0);

    private final JdbcTemplate jdbc;
    private final WorkingHoursService workingHours;
    private final WorkingCalendarRepository calendars;
    private final PasswordEncoder passwordEncoder;

    OnboardingFixture(JdbcTemplate jdbc, WorkingHoursService workingHours,
                      WorkingCalendarRepository calendars, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.workingHours = workingHours;
        this.calendars = calendars;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * {@code true} once the ERP product exists — the idempotency check the
     * loader runs before writing anything.
     *
     * <p>Deliberately separate from B-007's. The two corpora share a database
     * and a profile but not a lifetime: a developer who loaded tickets last week
     * and pulls this branch today needs the onboarding half to load without the
     * ticket half being reloaded on top of itself.
     */
    @Transactional(readOnly = true)
    public boolean alreadyLoaded() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ob_products WHERE code = ?", Integer.class,
                OnboardingFixtureData.PRODUCTS.get(0).code());
        return count != null && count > 0;
    }

    /** Write the whole corpus. One transaction — a half-loaded corpus is worse than none. */
    @Transactional
    public void load() {
        WorkingCalendar calendar = calendars.getCalendar();
        ZoneId zone = calendar.zone();
        LocalDate anchor = LocalDate.now(zone);

        Map<String, Long> userIds = createUsers();
        Map<String, Long> productIds = createProducts(userIds);
        Map<String, TemplateRefs> templates = createTemplates(productIds, userIds);

        for (ClientSpec client : OnboardingFixtureData.CLIENTS) {
            createClient(client, userIds, productIds, templates, zone, anchor);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Users, products, templates
    // ══════════════════════════════════════════════════════════════════════

    /**
     * The seven new demo users, plus the shared one.
     *
     * <p>Argon2id is expensive per call and none of these users has a distinct
     * password, so the hash is computed once and shared — B-007's own reasoning,
     * and the reason a fixture that creates users at all does not cost seconds.
     */
    private Map<String, Long> createUsers() {
        Map<String, Long> roleIds = new LinkedHashMap<>();
        Map<String, Long> byKey = new LinkedHashMap<>();
        String sharedHash = passwordEncoder.encode(FIXTURE_PASSWORD);

        int index = 0;
        for (UserSpec spec : OnboardingFixtureData.USERS) {
            index++;
            Long roleId = roleIds.computeIfAbsent(spec.platformRoleCode(), this::requireRoleId);
            long id = insert("""
                    INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id,
                                       department, designation, must_change_password, is_active)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 1)
                    """,
                    EMP_CODE_PREFIX + String.format(Locale.ROOT, "%03d", index),
                    spec.username(),
                    spec.username() + "@edunext.example",
                    sharedHash,
                    spec.fullName(),
                    roleId,
                    spec.department(),
                    OnboardingFixtureData.obRoleLabel(spec.obRole()));
            byKey.put(spec.key(), id);
        }

        byKey.put(OnboardingFixtureData.SHARED_USER_KEY, requireSharedUserId());
        return byKey;
    }

    private Long requireRoleId(String code) {
        try {
            return jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", Long.class, code);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException(
                    "role '" + code + "' is missing — V20260806_0900's seed did not run before the "
                            + "onboarding fixture. The six onboarding roles are A-109's; until they "
                            + "exist this corpus parks its users on the phase-1 codes.", e);
        }
    }

    private Long requireSharedUserId() {
        try {
            return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class,
                    OnboardingFixtureData.SHARED_USERNAME);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException(
                    "user '" + OnboardingFixtureData.SHARED_USERNAME + "' is missing. The onboarding "
                            + "corpus reuses B-007's row for the prototype's " + OnboardingFixtureData.SHARED_USER_KEY
                            + " rather than creating a second person of the same name, so the ticket "
                            + "corpus has to be loaded first. Both run from the same 'fixtures' profile.", e);
        }
    }

    private Map<String, Long> createProducts(Map<String, Long> userIds) {
        Map<String, Long> byKey = new LinkedHashMap<>();
        Long admin = userIds.get("u1");
        for (ProductSpec spec : OnboardingFixtureData.PRODUCTS) {
            long id = insert("""
                    INSERT INTO ob_products (code, name, is_active, created_by) VALUES (?, ?, 1, ?)
                    """, spec.code(), spec.name(), admin);
            byKey.put(spec.key(), id);
        }
        return byKey;
    }

    /** A template version's id plus the ids of the steps under it, in sequence. */
    private record TemplateRefs(long templateId, List<StepSpec> specs, List<Long> stepIds) {
    }

    private Map<String, TemplateRefs> createTemplates(Map<String, Long> productIds, Map<String, Long> userIds) {
        Map<String, TemplateRefs> byKey = new LinkedHashMap<>();
        Long admin = userIds.get("u1");
        Instant publishedAt = Instant.now();

        for (TemplateSpec spec : OnboardingFixtureData.TEMPLATES) {
            Long dependsOn = spec.dependsOnTemplateKey() == null
                    ? null
                    : byKey.get(spec.dependsOnTemplateKey()).templateId();
            long templateId = insert("""
                    INSERT INTO ob_journey_templates (product_id, name, version, is_active, sequence,
                                                      depends_on_template_id, published_by, published_at,
                                                      created_by)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    productIds.get(spec.productKey()), spec.name(), spec.version(), spec.active() ? 1 : 0,
                    spec.sequence(), dependsOn, admin, Timestamp.from(publishedAt), admin);

            List<Long> stepIds = new ArrayList<>();
            for (int i = 0; i < spec.steps().size(); i++) {
                StepSpec step = spec.steps().get(i);
                // The dependency is an earlier step of the same template, so it
                // is already inserted and has an id. fk_ob_journey_template_steps
                // _depends_on is composite on (template_id, id) and would refuse
                // anything else.
                Long dependsOnStepId = step.dependsOnIndex() == null ? null : stepIds.get(step.dependsOnIndex());
                long stepId = insert("""
                        INSERT INTO ob_journey_template_steps (template_id, sequence, name, tat_days,
                                                               owner_user_id, requires_signoff,
                                                               depends_on_step_id)
                             VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        templateId, i + 1, step.name(), step.tatDays(), userIds.get(step.ownerKey()),
                        step.requiresSignoff() ? 1 : 0, dependsOnStepId);
                stepIds.add(stepId);

                for (int k = 0; k < step.items().size(); k++) {
                    insert("""
                            INSERT INTO ob_journey_template_step_items (step_id, sequence, label)
                                 VALUES (?, ?, ?)
                            """, stepId, k + 1, step.items().get(k));
                }
                for (int k = 0; k < step.requiredDocs().size(); k++) {
                    insert("""
                            INSERT INTO ob_journey_template_step_docs (step_id, sequence, label, is_required)
                                 VALUES (?, ?, ?, 1)
                            """, stepId, k + 1, step.requiredDocs().get(k));
                }
            }

            byKey.put(spec.key(), new TemplateRefs(templateId, spec.steps(), List.copyOf(stepIds)));
        }
        return byKey;
    }

    // ══════════════════════════════════════════════════════════════════════
    // One client
    // ══════════════════════════════════════════════════════════════════════

    private void createClient(ClientSpec spec, Map<String, Long> userIds, Map<String, Long> productIds,
                              Map<String, TemplateRefs> templates, ZoneId zone, LocalDate anchor) {
        LocalDate onboardingDate = dateFor(spec.onboardSerial(), anchor);
        Instant liveAt = spec.liveSerial() == null
                ? null
                : instantFor(spec.liveSerial(), STEP_FINISH_TIME, zone, anchor);

        // pan_ciphertext and pan_blind_index stay NULL until A-113 — see
        // OnboardingFixtureData's class javadoc.
        long clientId = insert("""
                INSERT INTO ob_clients (name, description, onboarding_date, address, sales_person_id,
                                        license_type, overall_status, live_at, created_by)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                spec.name(), spec.description(), java.sql.Date.valueOf(onboardingDate), spec.address(),
                userIds.get(spec.salesUserKey()), spec.licenseType(), spec.status(),
                liveAt == null ? null : Timestamp.from(liveAt), userIds.get(spec.createdByUserKey()));

        Map<String, Long> contactIds = createContacts(spec, clientId);
        createApplications(spec, clientId, productIds, onboardingDate);
        createRequirements(spec, clientId, userIds);

        Map<String, Long> journeyIds = new LinkedHashMap<>();
        for (JourneySpec journey : spec.journeys()) {
            createJourney(spec, journey, clientId, productIds, templates, userIds, contactIds,
                    journeyIds, zone, anchor);
        }
    }

    /** SPOCs. One primary per client, which {@code is_primary_key} enforces. */
    private Map<String, Long> createContacts(ClientSpec spec, long clientId) {
        Map<String, Long> byName = new LinkedHashMap<>();
        for (ContactSpec contact : spec.contacts()) {
            long id = insert("""
                    INSERT INTO ob_client_contacts (ob_client_id, name, email, phone, whatsapp_opt_in,
                                                    is_primary, is_active)
                         VALUES (?, ?, ?, ?, ?, ?, 1)
                    """,
                    clientId, contact.name(), contact.email(), contact.phone(),
                    contact.whatsappOptIn() ? 1 : 0, contact.primary() ? 1 : 0);
            byName.put(contact.name(), id);
        }
        return byName;
    }

    /**
     * What the client bought.
     *
     * <p><b>The licence window is derived, not transcribed.</b> The prototype
     * carries no per-application dates; B-104 calls that pair "the renewal
     * anchor" and a corpus of NULLs would let a renewals query pass while
     * answering nothing. The term comes from the client's own licence label —
     * three years where it says so, one otherwise — starting on the onboarding
     * date.
     */
    private void createApplications(ClientSpec spec, long clientId, Map<String, Long> productIds,
                                    LocalDate onboardingDate) {
        int years = spec.licenseType().contains("3-year") ? 3 : 1;
        LocalDate licenseEnd = onboardingDate.plusYears(years).minusDays(1);
        for (ApplicationSpec application : spec.applications()) {
            insert("""
                    INSERT INTO ob_client_applications (ob_client_id, product_id, license_type, units,
                                                        license_start, license_end)
                         VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    clientId, productIds.get(application.productKey()), application.licenseType(),
                    application.units(), java.sql.Date.valueOf(onboardingDate),
                    java.sql.Date.valueOf(licenseEnd));
        }
    }

    /**
     * The prototype's single {@code requirements} string, as one structured row.
     *
     * <p>B-106 splits requirements into rows plus rich text; one row is what the
     * prototype actually has, and inventing three from one sentence would be
     * fabricating the very thing B-106 is meant to capture from a real client.
     */
    private void createRequirements(ClientSpec spec, long clientId, Map<String, Long> userIds) {
        insert("""
                INSERT INTO ob_client_requirements (ob_client_id, sequence, body, created_by)
                     VALUES (?, 1, ?, ?)
                """, clientId, spec.requirements(), userIds.get(spec.createdByUserKey()));
    }

    // ══════════════════════════════════════════════════════════════════════
    // One journey
    // ══════════════════════════════════════════════════════════════════════

    private void createJourney(ClientSpec client, JourneySpec spec, long clientId,
                               Map<String, Long> productIds, Map<String, TemplateRefs> templates,
                               Map<String, Long> userIds, Map<String, Long> contactIds,
                               Map<String, Long> journeyIdsByTemplateKey, ZoneId zone, LocalDate anchor) {
        TemplateRefs template = templates.get(spec.templateKey());
        TemplateSpec templateSpec = OnboardingFixtureData.TEMPLATES.stream()
                .filter(t -> t.key().equals(spec.templateKey()))
                .findFirst()
                .orElseThrow();
        JourneySchedule schedule = OnboardingFixtureSchedule.walk(template.specs(), spec);

        Instant startedAt = schedule.startSerial() == null
                ? null
                : instantFor(schedule.startSerial(), STEP_START_TIME, zone, anchor);
        Instant completedAt = schedule.completedSerial() == null
                ? null
                : instantFor(schedule.completedSerial(), STEP_FINISH_TIME, zone, anchor);
        Long heldBy = spec.heldByTemplateKey() == null
                ? null
                : journeyIdsByTemplateKey.get(spec.heldByTemplateKey());

        // ck_ob_journeys_gate_opened_at: an OPEN gate has a moment it opened at.
        // A held journey's gate is open — what holds it is the service ahead of
        // it, not its prerequisites — so it opened on the client's onboarding
        // date even though no step has started.
        Instant gateOpenedAt = null;
        if (spec.gateOpen()) {
            gateOpenedAt = startedAt != null
                    ? startedAt
                    : instantFor(spec.startSerial(), STEP_START_TIME, zone, anchor);
        }

        long journeyId = insert("""
                INSERT INTO ob_journeys (ob_client_id, product_id, template_id, gate_status, gate_opened_at,
                                         gate_opened_by, held_by_journey_id, started_at, completed_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                clientId, productIds.get(templateSpec.productKey()), template.templateId(),
                spec.gateOpen() ? "OPEN" : "LOCKED",
                gateOpenedAt == null ? null : Timestamp.from(gateOpenedAt),
                spec.gateOpen() ? userIds.get(client.createdByUserKey()) : null,
                heldBy,
                startedAt == null ? null : Timestamp.from(startedAt),
                completedAt == null ? null : Timestamp.from(completedAt));
        journeyIdsByTemplateKey.put(spec.templateKey(), journeyId);

        if (spec.gateOpen()) {
            appendHistory(journeyId, null, clientId, "GATE_OPENED", null, null, null,
                    userIds.get(client.createdByUserKey()),
                    "Prerequisites cleared — journey gate opened.", gateOpenedAt);
        }

        List<Long> stepIds = createSteps(client, spec, template, schedule, journeyId, clientId, userIds,
                zone, anchor);
        createCommunications(client, spec, template, journeyId, clientId, stepIds, userIds, contactIds,
                zone, anchor);
        createEscalations(client, spec, journeyId, clientId, stepIds, contactIds, zone, anchor);
    }

    /** Every step of one journey, with its Task List, clock events and history. */
    private List<Long> createSteps(ClientSpec client, JourneySpec spec, TemplateRefs template,
                                   JourneySchedule schedule, long journeyId, long clientId,
                                   Map<String, Long> userIds, ZoneId zone, LocalDate anchor) {
        List<Long> stepIds = new ArrayList<>();
        List<Instant> finishedByIndex = new ArrayList<>();

        for (int i = 0; i < template.specs().size(); i++) {
            StepSpec stepSpec = template.specs().get(i);
            StepSchedule step = schedule.steps().get(i);
            Long ownerId = userIds.get(stepSpec.ownerKey());

            Instant startedAt = step.startSerial() == null
                    ? null
                    : instantFor(step.startSerial(), STEP_START_TIME, zone, anchor);
            Instant finishedAt = step.finishSerial() == null
                    ? null
                    : instantFor(step.finishSerial(), STEP_FINISH_TIME, zone, anchor);

            // The walk works in whole days; these are moments. Where the clamp at
            // today collapses a step and its predecessor onto the same date, a
            // 10:00 start would land in front of a 17:00 finish and the corpus
            // would contain a step that began before the one it waits for
            // completed — which is not a state the module can produce, so nothing
            // downstream should ever have to read it.
            Instant predecessorFinish = stepSpec.dependsOnIndex() == null
                    ? null
                    : finishedByIndex.get(stepSpec.dependsOnIndex());
            if (startedAt != null && predecessorFinish != null && startedAt.isBefore(predecessorFinish)) {
                startedAt = predecessorFinish;
                if (finishedAt != null && finishedAt.isBefore(startedAt)) {
                    finishedAt = startedAt;
                }
            }
            finishedByIndex.add(finishedAt);

            Instant dueAt = startedAt == null ? null : dueAt(startedAt, stepSpec.tatDays(), zone);

            boolean blocked = "BLOCKED".equals(step.status());
            Long dependsOnStepId = stepSpec.dependsOnIndex() == null
                    ? null
                    : stepIds.get(stepSpec.dependsOnIndex());

            long stepId = insert("""
                    INSERT INTO ob_journey_steps (journey_id, template_step_id, sequence, name, tat_days,
                                                  owner_user_id, requires_signoff, depends_on_step_id,
                                                  status, blocked_reason_code, blocked_note,
                                                  started_at, finished_at, due_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    journeyId, template.stepIds().get(i), i + 1, stepSpec.name(), stepSpec.tatDays(),
                    ownerId, stepSpec.requiresSignoff() ? 1 : 0, dependsOnStepId, step.status(),
                    // ck_ob_journey_steps_blocked_reason: a BLOCKED step always
                    // says why. The prototype's block is a sentence, so the code
                    // beside it is the category that sentence describes.
                    blocked ? "CLIENT_DEPENDENCY" : null,
                    blocked ? spec.blockedNote() : null,
                    startedAt == null ? null : Timestamp.from(startedAt),
                    finishedAt == null ? null : Timestamp.from(finishedAt),
                    dueAt == null ? null : Timestamp.from(dueAt));
            stepIds.add(stepId);

            createStepItems(client, spec, stepSpec, step, stepId, i, ownerId, finishedAt, startedAt);
            createClockEvents(client, spec, step, stepId, journeyId, ownerId, startedAt, finishedAt, i,
                    zone, anchor);
            createStepHistory(client, spec, step, stepSpec, stepId, journeyId, clientId, ownerId,
                    startedAt, finishedAt, i, zone, anchor);
        }

        return List.copyOf(stepIds);
    }

    /**
     * The Task List answers.
     *
     * <p>The default is the prototype's: everything on a completed step is True,
     * the first item of the in-flight step is True, the rest unanswered. The
     * overrides in {@link OnboardingFixtureData#ITEM_ANSWERS} are what make the
     * corpus interesting — a False with its mandatory remark, which is the shape
     * {@code ck_ob_journey_step_items_remark} refuses to store without one.
     */
    private void createStepItems(ClientSpec client, JourneySpec journey, StepSpec stepSpec,
                                 StepSchedule step, long stepId, int stepIndex, Long ownerId,
                                 Instant finishedAt, Instant startedAt) {
        for (int k = 0; k < stepSpec.items().size(); k++) {
            Boolean answer = defaultAnswer(step, k);
            String remark = null;

            Optional<ItemAnswerSpec> override = findOverride(client, journey, stepIndex, k);
            if (override.isPresent()) {
                answer = override.get().answer();
                remark = override.get().remark();
            }

            Instant answeredAt = answer == null ? null : (finishedAt != null ? finishedAt : startedAt);
            insert("""
                    INSERT INTO ob_journey_step_items (step_id, sequence, label, answer, remark,
                                                       answered_by, answered_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    stepId, k + 1, stepSpec.items().get(k),
                    answer == null ? null : (answer ? 1 : 0), remark,
                    answer == null ? null : ownerId,
                    answeredAt == null ? null : Timestamp.from(answeredAt));
        }
    }

    private Boolean defaultAnswer(StepSchedule step, int itemIndex) {
        if (step.done()) {
            return Boolean.TRUE;
        }
        return step.inFlight() && itemIndex == 0 ? Boolean.TRUE : null;
    }

    private Optional<ItemAnswerSpec> findOverride(ClientSpec client, JourneySpec journey,
                                                  int stepIndex, int itemIndex) {
        return OnboardingFixtureData.ITEM_ANSWERS.stream()
                .filter(o -> o.clientKey().equals(client.key())
                        && o.templateKey().equals(journey.templateKey())
                        && o.stepIndex() == stepIndex
                        && o.itemIndex() == itemIndex)
                .findFirst();
    }

    // ══════════════════════════════════════════════════════════════════════
    // The clock
    // ══════════════════════════════════════════════════════════════════════

    /**
     * The pause/resume rows behind every TAT figure.
     *
     * <p>{@code PHASE-2-BUILD-PLAN.md} §3 item 5 is explicit that "the build must
     * not copy the mock" here: the prototype expresses waiting-on-client as a
     * status flip and the plan requires clock events, because a TAT recorded
     * without them can never be recomputed. That applies to the corpus first —
     * a fixture whose steps have statuses but no clock gives D's scanner and
     * C-120's roll-up nothing to read.
     *
     * <p>So: a step that started has a {@code STARTED}; a step that finished has
     * a {@code STOPPED}; the waiting step has a {@code PAUSED} attributed to the
     * client, at the moment its own internal note says the clock stopped.
     * <b>A blocked step keeps running</b> — plan §5.7 stops the clock for
     * waiting-on-client and nothing else, which is the distinction that makes
     * Trinity's blocked step breach and Bluebell's waiting step not.
     */
    private void createClockEvents(ClientSpec client, JourneySpec journey, StepSchedule step, long stepId,
                                   long journeyId, Long ownerId, Instant startedAt, Instant finishedAt,
                                   int stepIndex, ZoneId zone, LocalDate anchor) {
        if (startedAt == null) {
            return;
        }
        insertClockEvent(stepId, journeyId, "STARTED", null, "INTERNAL", startedAt, ownerId,
                "Step activated.");

        if ("WAITING_ON_CLIENT".equals(step.status())) {
            Instant pausedAt = pauseMoment(client, journey, stepIndex, startedAt, zone, anchor);
            insertClockEvent(stepId, journeyId, "PAUSED", "WAITING_ON_CLIENT", "CLIENT", pausedAt, ownerId,
                    "Waiting on client — clock paused.");
        }

        if (finishedAt != null) {
            insertClockEvent(stepId, journeyId, "STOPPED", null, "INTERNAL", finishedAt, ownerId,
                    "Step completed.");
        }
    }

    /**
     * When the clock stopped.
     *
     * <p>Taken from the step's own last communication, because that is where the
     * prototype records it — Bluebell's "Clock paused — waiting on client for
     * signed requirement sheet" is an internal note with a timestamp. Reading it
     * from there keeps the timeline and the clock telling the same story instead
     * of two that happen to be near each other.
     */
    private Instant pauseMoment(ClientSpec client, JourneySpec journey, int stepIndex, Instant startedAt,
                                ZoneId zone, LocalDate anchor) {
        return OnboardingFixtureData.COMMUNICATIONS.stream()
                .filter(c -> c.clientKey().equals(client.key())
                        && c.templateKey().equals(journey.templateKey())
                        && c.stepIndex() == stepIndex)
                .reduce((first, second) -> second)
                .map(c -> instantFor(c.daySerial(), LocalTime.MIDNIGHT.plusMinutes(c.minuteOfDay()), zone, anchor))
                .orElse(startedAt.plus(java.time.Duration.ofDays(1)));
    }

    private void insertClockEvent(long stepId, long journeyId, String eventType, String pauseReason,
                                  String attributedTo, Instant occurredAt, Long actorId, String note) {
        insert("""
                INSERT INTO ob_step_clock_events (step_id, journey_id, event_type, pause_reason,
                                                  attributed_to, occurred_at, actor_id, actor_type, note)
                     VALUES (?, ?, ?, ?, ?, ?, ?, 'USER', ?)
                """, stepId, journeyId, eventType, pauseReason, attributedTo,
                Timestamp.from(occurredAt), actorId, note);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Communications, history, escalations
    // ══════════════════════════════════════════════════════════════════════

    private void createCommunications(ClientSpec client, JourneySpec journey, TemplateRefs template,
                                      long journeyId, long clientId, List<Long> stepIds,
                                      Map<String, Long> userIds, Map<String, Long> contactIds,
                                      ZoneId zone, LocalDate anchor) {
        Long primaryContactId = primaryContactId(client, contactIds);

        for (CommunicationSpec comm : OnboardingFixtureData.COMMUNICATIONS) {
            if (!comm.clientKey().equals(client.key()) || !comm.templateKey().equals(journey.templateKey())) {
                continue;
            }
            Instant occurredAt = instantFor(comm.daySerial(),
                    LocalTime.MIDNIGHT.plusMinutes(comm.minuteOfDay()), zone, anchor);
            // ck_ob_comms_author: exactly one author column, matching the type.
            Long authorUserId = "STAFF".equals(comm.authorType()) ? userIds.get(comm.authorKey()) : null;
            Long authorContactId = "CLIENT".equals(comm.authorType()) ? primaryContactId : null;

            insert("""
                    INSERT INTO ob_step_communications (step_id, journey_id, ob_client_id, entry_type, body,
                                                        author_type, author_user_id, author_contact_id,
                                                        is_client_visible, occurred_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    stepIds.get(comm.stepIndex()), journeyId, clientId, comm.entryType(), comm.body(),
                    comm.authorType(), authorUserId, authorContactId,
                    comm.clientVisible() ? 1 : 0, Timestamp.from(occurredAt));
        }
    }

    /**
     * The history entries for one step.
     *
     * <p>Enough to make a timeline, not a reconstruction of a write path that
     * does not exist yet: activation, the state it reached, and completion. The
     * events an audit reader looks for on these steps — why a step is blocked,
     * when a client sign-off was asked for — are the ones written explicitly.
     */
    private void createStepHistory(ClientSpec client, JourneySpec journey, StepSchedule step,
                                   StepSpec stepSpec, long stepId, long journeyId, long clientId,
                                   Long ownerId, Instant startedAt, Instant finishedAt, int stepIndex,
                                   ZoneId zone, LocalDate anchor) {
        if (startedAt == null) {
            return;
        }
        appendHistory(journeyId, stepId, clientId, "STEP_ACTIVATED", "status", null, "IN_PROGRESS",
                ownerId, null, startedAt);

        if (step.done() && finishedAt != null) {
            appendHistory(journeyId, stepId, clientId, "COMPLETED", "status", "IN_PROGRESS", "DONE",
                    ownerId, null, finishedAt);
            return;
        }
        if ("BLOCKED".equals(step.status())) {
            appendHistory(journeyId, stepId, clientId, "BLOCKED", "status", "IN_PROGRESS", "BLOCKED",
                    ownerId, journey.blockedNote(), startedAt);
        }
        if ("WAITING_ON_CLIENT".equals(step.status())) {
            Instant pausedAt = pauseMoment(client, journey, stepIndex, startedAt, zone, anchor);
            appendHistory(journeyId, stepId, clientId, "WAITING_ON_CLIENT", "status", "IN_PROGRESS",
                    "WAITING_ON_CLIENT", ownerId, "Clock paused — awaiting the client.", pausedAt);
        }
        if (step.inFlight() && journey.signoffRequested() && stepSpec.requiresSignoff()) {
            appendHistory(journeyId, stepId, clientId, "SIGNOFF_REQUESTED", null, null, null,
                    ownerId, "Sign-off link sent to the primary SPOC.", startedAt);
        }
    }

    /**
     * One {@code ob_step_history} row.
     *
     * <p>{@code prev_hash} and {@code row_hash} are left NULL — see the class
     * javadoc. Everything else is a real column: this is the table A-123 will
     * mutation-test, and its triggers already refuse an update or a delete, so
     * the corpus writes each row once and correctly or not at all.
     */
    private void appendHistory(long journeyId, Long stepId, long clientId, String eventType,
                               String fieldName, String oldValue, String newValue, Long actorId,
                               String remarks, Instant createdAt) {
        insert("""
                INSERT INTO ob_step_history (journey_id, step_id, ob_client_id, event_type, field_name,
                                             old_value, new_value, actor_id, actor_type, remarks, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'USER', ?, ?)
                """, journeyId, stepId, clientId, eventType, fieldName, oldValue, newValue, actorId,
                remarks, Timestamp.from(createdAt));
    }

    private void createEscalations(ClientSpec client, JourneySpec journey, long journeyId, long clientId,
                                   List<Long> stepIds, Map<String, Long> contactIds, ZoneId zone,
                                   LocalDate anchor) {
        for (EscalationSpec escalation : OnboardingFixtureData.ESCALATIONS) {
            if (!escalation.clientKey().equals(client.key())
                    || !escalation.templateKey().equals(journey.templateKey())) {
                continue;
            }
            Instant raisedAt = instantFor(escalation.daySerial(),
                    LocalTime.MIDNIGHT.plusMinutes(escalation.minuteOfDay()), zone, anchor);
            // Left open — resolved_by and resolved_at move together or not at
            // all, and an open escalation is what OB-02's card exists to count.
            insert("""
                    INSERT INTO ob_client_escalations (ob_client_id, journey_id, step_id,
                                                       raised_by_contact_id, comment, raised_at)
                         VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    clientId, journeyId, stepIds.get(escalation.stepIndex()),
                    primaryContactId(client, contactIds), escalation.comment(), Timestamp.from(raisedAt));
        }
    }

    private Long primaryContactId(ClientSpec client, Map<String, Long> contactIds) {
        return client.contacts().stream()
                .filter(ContactSpec::primary)
                .findFirst()
                .map(c -> contactIds.get(c.name()))
                .orElseThrow(() -> new IllegalStateException(
                        "client '" + client.name() + "' has no primary contact — a client needs one "
                                + "before it can be selected anywhere, and the portal has no other "
                                + "principal to attribute a client-authored row to"));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Dates
    // ══════════════════════════════════════════════════════════════════════

    /** Serial {@value OnboardingFixtureData#TODAY_SERIAL} is the load date. See {@link OnboardingFixtureData}. */
    private LocalDate dateFor(int serial, LocalDate anchor) {
        return anchor.minusDays((long) OnboardingFixtureData.TODAY_SERIAL - serial);
    }

    private Instant instantFor(int serial, LocalTime time, ZoneId zone, LocalDate anchor) {
        return dateFor(serial, anchor).atTime(time).atZone(zone).toInstant();
    }

    /**
     * Where a step's TAT lands, through the working calendar.
     *
     * <p>{@code tat_days} is a budget in <b>working</b> days, so this walks the
     * calendar rather than adding to a date — a step that starts on Friday with
     * a two-day TAT is due Tuesday, and a corpus that made it due Sunday would
     * hand the SLA scanner a breach that the working calendar says never
     * happened. CLAUDE.md names this as the requirement most commonly missed.
     *
     * <p>The due moment is the end of the working day, which is the sense in
     * which a whole-day budget is spent.
     */
    private Instant dueAt(Instant startedAt, int tatDays, ZoneId zone) {
        LocalDate date = LocalDate.ofInstant(startedAt, zone);
        for (int i = 0; i < tatDays; i++) {
            date = workingHours.nextWorkingDay(date);
        }
        return date.atTime(calendars.getCalendar().getWorkDayEnd()).atZone(zone).toInstant();
    }

    // ══════════════════════════════════════════════════════════════════════
    // JDBC
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Insert one row and return its generated id.
     *
     * <p>A {@code null} argument is bound as {@link Types#NULL} rather than left
     * to the driver to guess: every nullable column here is either a foreign key
     * or a timestamp, and MySQL Connector/J refuses an untyped null on both.
     */
    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement =
                    connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) {
                    statement.setNull(i + 1, Types.NULL);
                } else {
                    statement.setObject(i + 1, args[i]);
                }
            }
            return statement;
        }, keys);

        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("no generated key returned for: " + sql);
        }
        return key.longValue();
    }
}
