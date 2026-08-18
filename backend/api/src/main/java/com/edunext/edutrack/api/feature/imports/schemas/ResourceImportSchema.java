package com.edunext.edutrack.api.feature.imports.schemas;

import com.edunext.edutrack.api.feature.imports.FieldValidator;
import com.edunext.edutrack.api.feature.imports.FieldValidators;
import com.edunext.edutrack.api.feature.imports.ImportField;
import com.edunext.edutrack.api.feature.imports.ImportFieldType;
import com.edunext.edutrack.api.feature.imports.ImportReversal;
import com.edunext.edutrack.api.feature.imports.ImportRow;
import com.edunext.edutrack.api.feature.imports.ImportSchemaDefinition;
import com.edunext.edutrack.api.feature.masters.resources.TemporaryPasswords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * B-038 · the resource master, registered — screen S-07, blueprint §4B.3.
 *
 * <p><b>This file is what B-030 promised.</b> §4B.3 closes with "the same wizard
 * pattern is reused for the resource master bulk import — build it once,
 * register two schemas", and CLAUDE.md restates it as a stop rule. Nothing under
 * {@code feature/imports} changed to make this work: no route, no migration to
 * the engine's own tables, no edit to the registry, no branch anywhere that asks
 * which entity is being imported. The contract already declared {@code users} in
 * its {@code ImportSchema} enum and {@code RESOURCE} on {@code ImportBatch.entity},
 * so the wire did not move either — seven assertions across six test classes
 * existed whose only claim was that this path answered 404, each carrying a note
 * to delete itself the day this {@code @Component} landed. All seven failed on
 * the first run of the suite after it did.
 *
 * <p>What it costs is this file, the queries beside it, and one column on
 * {@code users} so a run can be taken back.
 *
 * <h2>The fields a spreadsheet cannot carry</h2>
 *
 * <p><b>Reporting manager is absent, and it is the omission worth arguing
 * about.</b> Unlike the client registration's account manager — which a
 * spreadsheet could only name in prose — an employee code identifies a manager
 * exactly, so a column would resolve reliably. It is still refused, because
 * B-012's cycle rule is one of this stream's red rules and must hold <em>at any
 * depth</em>: A→B→C→A is as broken as A→A, and the database trigger only ever
 * caught the second. A file can name a manager three rows below the person
 * reporting to them, so honouring the column correctly means ordering the whole
 * file topologically and re-checking the chain as each row lands — a whole-file
 * operation the row-at-a-time SPI has no hook for, and one where getting it
 * wrong writes a cycle into the reporting line rather than rejecting a row.
 * Reporting lines are set on S-08 after the import, where the check already
 * works and where one wrong answer is one screen away from being seen.
 *
 * <p><b>Projects are absent</b> for the reason the client registration leaves
 * contacts out: memberships are a child table with facts of their own, and a
 * column of comma-separated project codes is a second import flow wearing a
 * cell's clothes.
 *
 * <p><b>Password is not a column and never will be.</b> Accounts are created
 * with a generated one-time password and {@code must_change_password} set. It is
 * hashed and discarded — five thousand temporary passwords cannot be handed back
 * through a progress bar, so a bulk-created resource reaches their account
 * through the ordinary forgotten-password route. Accepting passwords from a
 * spreadsheet would put credentials in a file that gets emailed around, which is
 * not a trade worth making for a first login.
 *
 * <h2>A known cost, stated rather than discovered</h2>
 *
 * <p>Hashing is Argon2id at §10.3's parameters — 64 MiB and three passes, by
 * design, because the whole point of the algorithm is to be expensive. That is
 * roughly a tenth of a second <em>per created row</em>, so a full 5,000-row
 * onboarding spends several minutes hashing credentials nobody will ever use. It
 * is a background job with a progress bar behind an Admin-only route, so this is
 * slow rather than broken, and an import of the size anybody actually runs is
 * unaffected.
 *
 * <p>The obvious optimisations were both refused, and it is worth saying why.
 * Hashing one password for the whole run and reusing it is cheap and puts a
 * shared credential in the identity table. Writing an unusable placeholder
 * instead of a hash is cheaper still and is arguably <em>safer</em> — nothing
 * could ever match it — but it gives {@code password_hash} a second meaning on
 * a table Stream A owns, which every reader of that column would then have to
 * know about. Neither is a change to make on a masters branch for a cost this
 * bounded; if 5,000-row onboarding becomes real it is a conversation with
 * Stream A, not a shortcut taken here.
 *
 * <h2>What the dry run cannot catch, stated rather than discovered</h2>
 *
 * <p>{@code users} has three unique indexes — {@code emp_code}, {@code username}
 * and {@code email} — and only one of them can be the natural key. The engine's
 * duplicate-in-file detection and its create-versus-update probe both work on
 * the natural key alone, so a file carrying two different employee codes with
 * the same email address passes step 4 and the second row is refused by
 * {@code uq_users_email} at write time. That is handled rather than hidden: the
 * row is counted rejected and lands in B-036's error report like any other. It
 * is the honest limit of a per-row preview, and widening the SPI to "declare
 * your other unique constraints" would be a generalisation built for one caller.
 */
@Component
public class ResourceImportSchema implements ImportSchemaDefinition {

    private static final Logger log = LoggerFactory.getLogger(ResourceImportSchema.class);

    private final ResourceImportRepository resources;
    private final PasswordEncoder passwordEncoder;

    ResourceImportSchema(ResourceImportRepository resources, PasswordEncoder passwordEncoder) {
        this.resources = resources;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * {@code users}, not {@code resources}.
     *
     * <p>The task is titled "resource bulk import" and the screen is the Resource
     * Master, but the contract settled on {@code users} and the generated
     * TypeScript client has been able to call this path since D-001. The URL is
     * the thing that has to be right; the SPI's own javadoc calls this out by
     * name.
     */
    @Override
    public String key() {
        return "users";
    }

    @Override
    public String entityCode() {
        return "RESOURCE";
    }

    @Override
    public ImportField naturalKey() {
        return EMPLOYEE_CODE;
    }

    @Override
    public List<ImportField> fields() {
        return FIELDS;
    }

    // ------------------------------------------------------------------
    // The declarations
    // ------------------------------------------------------------------

    /**
     * The upsert key, and the reason it is this column rather than the other two
     * unique ones.
     *
     * <p>An employee code is the identity HR already keeps and the one a
     * spreadsheet arrives keyed on. A username is issued by this system, so a
     * file of new joiners does not have one to match against; an email address is
     * the field most likely to be <em>corrected</em> by an import, and a natural
     * key that changes is a natural key that creates a duplicate instead of
     * updating.
     *
     * <p><b>Deliberately not {@code FieldValidators.alphanumeric()}</b>, despite
     * that method's own javadoc predicting this registration would be its user —
     * "B-038's resource registration has fields — an employee code — where
     * letters and digits really is the constraint". It does not: S-08 accepts any
     * non-blank string up to 20 characters, and {@code EDU-0142} is a code an
     * organisation genuinely issues. This is B-028's defect exactly, and it is
     * worse here than it was there. Because this is the upsert key, a rejected
     * value is not a message somebody reads and fixes: it is a person the import
     * silently declines to update, in a run that otherwise reports success.
     */
    private static final ImportField EMPLOYEE_CODE =
            ImportField.required("employeeCode", "Employee Code")
                    .maxLength(20)
                    .example("EDU-0142");

    /**
     * The six role codes, matching {@code ResourceWriteRequest.role}'s pattern
     * character for character — and note it is {@code SUPPORT}, not the
     * {@code SUPPORT_DESK} the roles table was originally seeded with and
     * {@code V20260807_1030} renamed.
     *
     * <p><b>Static rather than read from {@code roles}.</b> A dynamic domain is
     * the more correct answer in principle — S-11 can create a role, and a
     * hardcoded list would refuse one — and it cannot be had here:
     * {@code ImportSchemaRegistry} calls {@link #fields()} during its own
     * construction to refuse a malformed registration at boot, and three
     * {@code @SpringBootTest}s on this path deliberately run with Flyway excluded
     * and no database at all. Querying from {@code fields()} would turn a context
     * refresh into a database round trip and break them.
     *
     * <p>The cost is bounded and visible: a custom role has to be added here
     * before a spreadsheet can name it. The six that ship are the ones §2's
     * permission matrix is written for, the ones {@code ScopeResolver} switches
     * on, and the ones every role filter in the product already lists.
     */
    private static final List<String> ROLE_CODES =
            List.of("ADMIN", "PM", "DEVELOPER", "QA", "DEPLOYMENT", "SUPPORT");

    /**
     * Blueprint §7.4's S-08 form, minus the fields argued away in the class
     * comment, in the order the form groups them: identity, then access, then
     * org, then work.
     *
     * <p>Every rule below restates one the S-08 form already applies, and that
     * repetition is the point rather than an oversight: B-028's lesson is that
     * two ways in must not answer the same question differently, and a form that
     * accepts a value the importer rejects means an import refusing a row
     * describing a resource this system itself created. Where a rule is generic
     * it comes from {@code FieldValidators}; where it belongs to this entity it
     * is a lambda here, which is where the engine isolation guard says entity
     * knowledge goes.
     */
    private static final List<ImportField> FIELDS = List.of(
            EMPLOYEE_CODE,
            ImportField.required("fullName", "Full Name").maxLength(120).example("Asha Menon"),
            ImportField.required("username", "Username").maxLength(50).example("asha.menon")
                    // @Size(min = 3) on the form. Stated here so a two-character
                    // username is refused by the preview rather than by MySQL
                    // three hundred rows into the commit.
                    .validate(minLength(3)),
            ImportField.required("email", "Email").maxLength(150)
                    .type(ImportFieldType.EMAIL).example("asha.menon@edunext.example")
                    .validate(FieldValidators.email()),
            ImportField.required("role", "Role")
                    .oneOf(ROLE_CODES.toArray(String[]::new))
                    .example("DEVELOPER"),
            ImportField.optional("mobile", "Mobile").maxLength(20).example("+91 98200 11223")
                    .validate(mobile()),
            // Optional with a default rather than required, on the client
            // registration's argument: a file of new joiners is a file of active
            // people, and making every row restate that is how a mandatory
            // column gets filled in with noise.
            ImportField.optional("status", "Status").oneOf("ACTIVE", "INACTIVE").example("ACTIVE"),
            ImportField.optional("department", "Department").maxLength(80).example("Engineering"),
            ImportField.optional("designation", "Designation").maxLength(80)
                    .example("Senior Engineer"),
            ImportField.optional("location", "Location").maxLength(120).example("Pune"),
            ImportField.optional("dateOfJoining", "Date of Joining")
                    .type(ImportFieldType.DATE).example("2026-04-01")
                    .validate(FieldValidators.isoDate()),
            // TEXT rather than a new DECIMAL member of ImportFieldType. That enum
            // is documented as three answers to three concrete questions and
            // explicitly says "anything more specific than these belongs in a
            // FieldValidator on the field" — and adding a type is an engine
            // change on a task whose whole claim is that the engine does not
            // change.
            ImportField.optional("dailyCapacityHrs", "Daily Capacity (hrs)").maxLength(5)
                    .example("8").validate(dailyCapacity()),
            ImportField.optional("timezone", "Timezone").maxLength(50).example("Asia/Kolkata"),
            ImportField.optional("weeklyOff", "Weekly Off").maxLength(20).example("6, 7")
                    .validate(weeklyOff()),
            ImportField.optional("skills", "Skills").maxLength(400)
                    .example("Java, React, MySQL").validate(skills()));

    // ------------------------------------------------------------------
    // Field rules that belong to this entity
    // ------------------------------------------------------------------

    private static FieldValidator minLength(int min) {
        return value -> value.length() >= min
                ? Optional.empty()
                : Optional.of("Must be at least " + min + " characters");
    }

    /** {@code ResourceWriteRequest.mobile}'s pattern, and its message. */
    private static FieldValidator mobile() {
        return value -> MOBILE.matcher(value).matches()
                ? Optional.empty()
                : Optional.of("Must be 6-20 characters of digits, spaces, brackets or dashes");
    }

    private static final java.util.regex.Pattern MOBILE =
            java.util.regex.Pattern.compile("^[0-9+][0-9 ()-]{5,19}$");

    /**
     * Half an hour to twenty-four, matching the form's {@code @DecimalMin} and
     * {@code @DecimalMax}.
     *
     * <p>Excel hands a numeric cell over as {@code 8.0}, which parses; a cell
     * somebody typed "8 hrs" into does not, and saying so in the preview is the
     * difference between a rejected row and a failed batch. <b>Every SLA figure
     * in the product divides by this number</b> through B-024's working-hours
     * service, so a zero imported from a spreadsheet is not a cosmetic error.
     */
    private static FieldValidator dailyCapacity() {
        return value -> {
            BigDecimal hours;
            try {
                hours = new BigDecimal(value.trim());
            } catch (NumberFormatException notANumber) {
                return Optional.of("Not a number of hours");
            }
            if (hours.compareTo(MIN_CAPACITY) < 0) {
                return Optional.of("Daily capacity must be at least half an hour");
            }
            if (hours.compareTo(MAX_CAPACITY) > 0) {
                return Optional.of("A day has 24 hours");
            }
            return Optional.empty();
        };
    }

    private static final BigDecimal MIN_CAPACITY = new BigDecimal("0.5");
    private static final BigDecimal MAX_CAPACITY = new BigDecimal("24.0");

    /**
     * ISO-8601 day numbers as a spreadsheet can write them — {@code 6, 7} for a
     * Saturday-Sunday weekend.
     *
     * <p><b>1=Mon … 7=Sun, and a {@code 0} is refused rather than tolerated.</b>
     * B-023's note records what one extra day-numbering convention cost: a
     * contract saying "ISO" while constraining to 0–6 made Sunday a working day
     * and every weekend-spanning SLA short by a day. A spreadsheet column is a
     * new way for a {@code 0} to arrive, one row at a time and in the
     * harder-to-notice place, so it is refused at the same place the column's own
     * {@code ck_users_weekly_off} would refuse it — except here the user is told
     * which row, before anything is written.
     *
     * <p>Six days maximum, also matching the CHECK: a seven-day weekend sends
     * {@code addWorkingHours} looking for a working day it can never find.
     */
    private static FieldValidator weeklyOff() {
        return value -> {
            List<Integer> days;
            try {
                days = parseDays(value);
            } catch (NumberFormatException notADay) {
                return Optional.of("Days are numbers, 1=Mon … 7=Sun — for example 6, 7");
            }
            if (days.stream().anyMatch(d -> d < 1 || d > 7)) {
                return Optional.of("Days are ISO 1=Mon … 7=Sun; 0 is not a day");
            }
            if (days.stream().distinct().count() > 6) {
                return Optional.of("A week needs at least one working day");
            }
            return Optional.empty();
        };
    }

    /** Thirty tags, sixty characters each — {@code ck_users_skills}'s own limits. */
    private static FieldValidator skills() {
        return value -> {
            List<String> tags = parseTags(value);
            if (tags.size() > 30) {
                return Optional.of("30 skills is plenty");
            }
            if (tags.stream().anyMatch(tag -> tag.length() > 60)) {
                return Optional.of("A skill is at most 60 characters");
            }
            return Optional.empty();
        };
    }

    // ------------------------------------------------------------------
    // The dry run
    // ------------------------------------------------------------------

    /**
     * The resources these employee codes name, as the import sees them.
     *
     * <p>One query for the whole file, and the values as well as the keys —
     * B-034's step-4 preview names the fields an update would change, and
     * {@code ImportSchemaDefinition#findExisting} explains why "will update" on
     * its own is not enough to approve.
     *
     * <p>Read-only, and nothing escapes the transaction but strings.
     */
    @Override
    public Map<String, Map<String, String>> findExisting(Set<String> naturalKeyValues) {
        return resources.currentValues(naturalKeyValues);
    }

    // ------------------------------------------------------------------
    // The commit
    // ------------------------------------------------------------------

    /**
     * Insert or update, on {@code emp_code}, never a second row.
     *
     * <p><b>Only fields present in the row are written.</b> An unmapped column,
     * or a cell left blank on an update, leaves the stored value alone rather
     * than nulling it — the difference between a spreadsheet that corrects six
     * mobile numbers and one that erases every department in the organisation.
     * The preview cannot tell the user which they are about to get, so it has to
     * be the safe one. Clearing a field is S-08's job, where it is one person at
     * a time and visible.
     *
     * <p><b>{@code import_batch_id} is stamped on insert only</b>, which is what
     * makes the reversal below safe to offer. It is bound inside
     * {@code ResourceImportRepository.insert} and is not a column this method can
     * put in an update's list.
     */
    @Override
    @Transactional
    public void upsert(ImportRow row, Long importBatchId) {
        String empCode = row.get("employeeCode");
        Map<String, Object> columns = new LinkedHashMap<>();

        set(row, "fullName", "full_name", columns);
        set(row, "username", "username", columns);
        set(row, "email", "email", columns);
        set(row, "mobile", "mobile", columns);
        set(row, "department", "department", columns);
        set(row, "designation", "designation", columns);
        set(row, "location", "location", columns);
        set(row, "timezone", "timezone", columns);
        map(row, "role", "role_id", columns, this::roleId);
        map(row, "status", "is_active", columns, status -> "ACTIVE".equalsIgnoreCase(status.trim()));
        map(row, "dateOfJoining", "date_of_joining", columns, LocalDate::parse);
        map(row, "dailyCapacityHrs", "daily_capacity_hrs", columns,
                hrs -> new BigDecimal(hrs.trim()));
        map(row, "weeklyOff", "weekly_off", columns,
                days -> resources.writeJson(parseDays(days).stream().distinct().sorted().toList()));
        map(row, "skills", "skills", columns, tags -> resources.writeJson(parseTags(tags)));

        Optional<Long> existing = resources.findIdByEmpCode(empCode);
        if (existing.isPresent()) {
            resources.update(existing.get(), columns);
            return;
        }

        columns.put("emp_code", empCode);
        // Generated, hashed, and not returned to anybody — see the class comment
        // and TemporaryPasswords. The account is reachable through
        // POST /auth/forgot-password, and must_change_password means the first
        // login ends in the same place S-08's flow does.
        columns.put("password_hash", passwordEncoder.encode(TemporaryPasswords.generate()));
        // Set rather than left to the column default, so the promise above is
        // visible in this file rather than in a DDL somebody would have to go
        // and read.
        columns.put("must_change_password", true);
        resources.insert(columns, importBatchId);
    }

    /**
     * The role id behind a code the ENUM has already accepted.
     *
     * <p>Throws rather than defaulting if the roles table does not carry it,
     * which {@code ImportCommitRunner} turns into one rejected row and a log
     * line. The only way to get here is a role deactivated or renamed between
     * this file being written and the import being run — and a resource silently
     * given somebody else's permissions because their role could not be resolved
     * is the worst available outcome of a bad spreadsheet.
     */
    private int roleId(String roleCode) {
        String code = roleCode.trim().toUpperCase(Locale.ROOT);
        return resources.findRoleId(code).orElseThrow(() -> new IllegalStateException(
                "no role with code " + code));
    }

    // ------------------------------------------------------------------
    // The reversal — B-037's seventh method
    // ------------------------------------------------------------------

    /**
     * B-037 · every resource this run <b>created</b>, deleted as a set.
     *
     * <p>This method is the whole of what B-038 pays for reversal. Which requests
     * are refused, in what order, and what the batch row records are all
     * {@code ImportReversalService}'s and were written once; what only a
     * registration can know is here. That split is why the history panel's
     * Reverse button works on a resource import without a line of new code
     * behind it.
     *
     * <h2>Created, never merely updated</h2>
     *
     * <p>A resource the run edited carries some earlier batch's id, or none, so
     * they are not in this list and are not touched. A spreadsheet that onboarded
     * 12 people and corrected 400 phone numbers reverses to 12 deleted, not to
     * 412 people removed from the organisation. There is no before image
     * anywhere, so the other 400 could not be restored even if they were in
     * scope — which is why the promise is worded "reversed as a set" rather than
     * "undone".
     *
     * <h2>Why this is more cautious than the client registration</h2>
     *
     * <p>{@code clients} has two inbound foreign keys worth reasoning about.
     * {@code users} has around forty, because a person is referenced by every
     * ticket they touched, every history entry they wrote and every comment they
     * left. Enumerating all of them here would be a list that goes stale the
     * first time another stream adds a column, and the failure mode of a stale
     * list is silent: the missing check reads as "nothing references them".
     *
     * <p>So the pre-checks cover only the three cases that both <em>happen</em>
     * and can be described in a sentence somebody can act on — tickets, direct
     * reports, managed projects — and the {@code catch} is what makes the
     * operation complete. Same two-layer shape the client registration uses, with
     * the balance shifted: there, the pre-check was expected to answer almost
     * every case; here it is the backstop that carries the tail.
     *
     * <h2>One resource, one transaction</h2>
     *
     * <p>The same shape {@code ImportCommitRunner} uses on the way in: a resource
     * that cannot be removed costs that resource and not the set. A single
     * transaction over 400 deletes would be rolled back whole by one person who
     * was assigned a ticket while the operator was reading the history panel,
     * turning the most ordinary partial case into total failure.
     */
    @Override
    public ImportReversal reverse(long batchId) {
        List<ResourceImportRepository.CreatedResource> created = resources.createdBy(batchId);
        if (created.isEmpty()) {
            return ImportReversal.none();
        }

        List<Long> ids = created.stream().map(ResourceImportRepository.CreatedResource::id).toList();
        Map<Long, Long> tickets = resources.ticketCounts(ids);
        Map<Long, Long> reports = resources.subordinateCounts(ids);
        Map<Long, Long> projects = resources.managedProjectCounts(ids);

        List<String> deleted = new ArrayList<>();
        List<ImportReversal.Retained> retained = new ArrayList<>();

        for (ResourceImportRepository.CreatedResource resource : created) {
            String reason = whyKept(resource.id(), tickets, reports, projects);
            if (reason != null) {
                retained.add(new ImportReversal.Retained(resource.empCode(), reason));
                continue;
            }
            try {
                resources.deleteResourceAndOwnedRows(resource.id());
                deleted.add(resource.empCode());
            } catch (DataIntegrityViolationException stillReferenced) {
                // The expected path for anything the three counts above do not
                // cover — a comment, an effort log, an attachment, a chat
                // message. Deliberately vague on the wire and specific in the
                // log: the exception carries a constraint name, a table name and
                // sometimes the SQL, and this text is read on a screen and
                // pasted into email.
                log.warn("Import batch {} — resource {} could not be deleted: {}",
                        batchId, resource.empCode(), stillReferenced.toString());
                retained.add(new ImportReversal.Retained(resource.empCode(),
                        "Kept — work recorded since the import still refers to this resource."
                                + " The detail is in the server log for import #" + batchId + "."));
            }
        }

        return new ImportReversal(List.copyOf(deleted), List.copyOf(retained));
    }

    /**
     * Why this resource is being kept, or null to delete them.
     *
     * <p>Tickets first because it is the overwhelmingly common answer and the
     * most specific — an operator can go and look at them. Direct reports and
     * managed projects follow, in that order, because a reporting line is the
     * more surprising of the two to find on somebody imported by mistake.
     */
    private static String whyKept(long userId, Map<Long, Long> tickets,
                                  Map<Long, Long> reports, Map<Long, Long> projects) {
        Long ticketCount = tickets.get(userId);
        if (ticketCount != null && ticketCount > 0) {
            return ticketCount == 1
                    ? "Kept — 1 ticket names this resource as its reporter or assignee."
                    : "Kept — " + ticketCount + " tickets name this resource as their reporter"
                            + " or assignee.";
        }
        Long reportCount = reports.get(userId);
        if (reportCount != null && reportCount > 0) {
            return reportCount == 1
                    ? "Kept — 1 resource reports to this one."
                    : "Kept — " + reportCount + " resources report to this one.";
        }
        Long projectCount = projects.get(userId);
        if (projectCount != null && projectCount > 0) {
            return projectCount == 1
                    ? "Kept — this resource manages 1 project."
                    : "Kept — this resource manages " + projectCount + " projects.";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Copies a cell straight across, or leaves the column out if it is absent. */
    private static void set(ImportRow row, String field, String column,
                            Map<String, Object> columns) {
        map(row, field, column, columns, value -> value);
    }

    /**
     * Copies a cell through a conversion, or leaves the column out if it is
     * absent.
     *
     * <p><b>Absent means absent.</b> {@code ImportRow} already collapses a
     * missing cell, an empty one and a whitespace-only one into "not present",
     * and this is the single place that turns "not present" into "do not write
     * that column" — which is what makes the blank-cell rule in {@link #upsert}
     * true of every field rather than of the ones somebody remembered.
     *
     * <p>The conversions are safe to run without catching, because the row
     * reached commit and so has already been through the field's validators. If
     * that stops being true this should throw rather than swallow: a date
     * silently dropped from a joining record is not a smaller problem than one
     * rejected row.
     */
    private static <T> void map(ImportRow row, String field, String column,
                                Map<String, Object> columns, Function<String, T> convert) {
        String value = row.get(field);
        if (value != null) {
            columns.put(column, convert.apply(value));
        }
    }

    /** {@code "6, 7"} or {@code "6;7"} as day numbers. Blank entries are not days. */
    private static List<Integer> parseDays(String value) {
        return java.util.Arrays.stream(value.split("[,;/\\s]+"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(Integer::parseInt)
                .toList();
    }

    /**
     * {@code "Java, React, MySQL"} as tags — trimmed, blank-free and
     * de-duplicated, with the order the file gave them.
     *
     * <p>Semicolons too, because a spreadsheet exported from a locale that uses
     * the comma as a decimal separator is full of them, and the alternative is a
     * single skill named "Java; React; MySQL".
     */
    private static List<String> parseTags(String value) {
        return List.copyOf(new java.util.LinkedHashSet<>(
                java.util.Arrays.stream(value.split("[,;]"))
                        .map(String::trim)
                        .filter(tag -> !tag.isEmpty())
                        .toList()));
    }
}
