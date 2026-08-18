package com.edunext.edutrack.api.feature.imports.schemas;

import com.edunext.edutrack.api.feature.imports.ImportField;
import com.edunext.edutrack.api.feature.imports.ImportFieldType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-038 · <b>the declarations, and the promise that they are all there is.</b>
 *
 * <p>Everything the resource import does that is worth asserting without a
 * database is in {@link ResourceImportSchema#fields()} — which columns exist,
 * which are required, and what each one refuses. That is what a registration
 * being "a list of columns plus two short methods" means in practice, and it is
 * why this file is longer than the one it tests.
 *
 * <p><b>The rules here are not this task's own.</b> Every one of them restates a
 * rule the S-08 form already applies, and the reason to pin them twice is B-028:
 * the client registration and the S-33 form spent two releases disagreeing about
 * what a valid client code was, and because that column is the upsert key the
 * disagreement did not surface as an error message — it surfaced as clients the
 * import quietly declined to update. The equivalent here would be an employee
 * the organisation cannot bulk-update, so the drift is asserted rather than
 * hoped for.
 *
 * <p>Both collaborators are null: nothing on this path touches the repository or
 * the password encoder, exactly as the client registration's validation test
 * passes nulls for the same reason.
 */
class ResourceImportSchemaTest {

    private static final ResourceImportSchema SCHEMA = new ResourceImportSchema(null, null);

    private static ImportField field(String name) {
        return SCHEMA.fields().stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the resource registration no longer declares a " + name + " column"));
    }

    /** The first reason a value is refused, or empty if every validator accepts it. */
    private static Optional<String> refuse(String name, String value) {
        return field(name).validators().stream()
                .map(v -> v.validate(value))
                .flatMap(Optional::stream)
                .findFirst();
    }

    // ── the registration's identity ─────────────────────────────────────────

    /**
     * The URL segment is {@code users}, and the stored discriminator is
     * {@code RESOURCE}.
     *
     * <p>They are deliberately different things and this is the one file that
     * says so about this registration. The key is what the contract's
     * {@code ImportSchema} enum constrains and what the generated TypeScript
     * client has called since D-001; the entity code is what
     * {@code import_batches.entity} stores and what B-037's reversal resolves a
     * historical run through. Collapsing them would mean renaming a live URL to
     * fix a column, or rewriting stored rows to fix a URL.
     */
    @Test
    @DisplayName("registered under the contract's own path segment, stored under its own code")
    void keyAndEntityCodeAreBothCorrectAndNotTheSame() {
        assertThat(SCHEMA.key())
                .as("contracts/openapi.yaml constrains {schema} to [clients, users]")
                .isEqualTo("users");
        assertThat(SCHEMA.entityCode()).isEqualTo("RESOURCE");
        assertThat(SCHEMA.key()).isNotEqualTo(SCHEMA.entityCode());
    }

    /**
     * The natural key is the employee code, and it is one of the declared fields
     * and required.
     *
     * <p>{@code ImportSchemaRegistry} refuses a registration where either is
     * untrue, so this would fail the context refresh rather than a test — but it
     * would fail it at boot in every environment at once, and the sentence a
     * failed context refresh produces is not "the employee code stopped being
     * required".
     */
    @Test
    void theNaturalKeyIsTheEmployeeCode() {
        assertThat(SCHEMA.naturalKey().name()).isEqualTo("employeeCode");
        assertThat(SCHEMA.fields()).contains(SCHEMA.naturalKey());
        assertThat(SCHEMA.naturalKey().required()).isTrue();
    }

    // ── what a spreadsheet may and may not carry ────────────────────────────

    /**
     * The four columns without which a row cannot become an account, and no
     * others.
     *
     * <p>{@code users} has exactly these four as {@code NOT NULL} with no usable
     * default — plus {@code password_hash}, which is generated. Everything else
     * is optional on purpose: blueprint §4B.3's step-4 preview exists so a file
     * can be partial and the user can see what that costs, and a required column
     * nobody has data for is a column that gets filled in with noise.
     */
    @Test
    @DisplayName("four required columns — the ones an account cannot exist without")
    void onlyTheColumnsAnAccountNeedsAreRequired() {
        assertThat(SCHEMA.fields().stream().filter(ImportField::required).map(ImportField::name))
                .containsExactlyInAnyOrder("employeeCode", "fullName", "username", "email", "role");
    }

    /**
     * <b>Reporting manager is absent, and this is the assertion that keeps it
     * absent.</b>
     *
     * <p>It is the field somebody will add, because unlike the client
     * registration's account manager an employee code identifies a manager
     * exactly. B-012's cycle rule is one of this stream's red rules and holds at
     * <em>any</em> depth — A→B→C→A, not merely A→A, which is all the database
     * trigger ever caught. A spreadsheet can name a manager three rows below the
     * person reporting to them, so honouring the column means ordering the file
     * topologically and re-checking the whole chain as each row lands. The
     * row-at-a-time SPI has no hook for that, and the failure mode is not a
     * rejected row: it is a cycle written into the reporting line.
     *
     * <p>Password is absent for a different reason and the same weight — a
     * credential in a file that gets emailed around. Projects, because
     * memberships are a child table with facts of their own.
     */
    @ParameterizedTest
    @ValueSource(strings = {"reportingManagerId", "reportingManager", "reportingManagerCode",
            "password", "passwordHash", "projects", "projectIds"})
    @DisplayName("the fields a spreadsheet must not carry are not declared")
    void theExcludedFieldsStayExcluded(String name) {
        assertThat(SCHEMA.fields().stream().map(ImportField::name))
                .doesNotContain(name);
    }

    /** Every declared field maps to a column that exists, named as S-08 names it. */
    @Test
    void theDeclaredColumnsAreTheOnesS08Shows() {
        assertThat(SCHEMA.fields().stream().map(ImportField::name)).containsExactly(
                "employeeCode", "fullName", "username", "email", "role", "mobile", "status",
                "department", "designation", "location", "dateOfJoining", "dailyCapacityHrs",
                "timezone", "weeklyOff", "skills");
    }

    // ── the rules, one per column ───────────────────────────────────────────

    @Nested
    @DisplayName("employee code — the upsert key")
    class EmployeeCode {

        /**
         * <b>B-028's defect, not repeated.</b>
         *
         * <p>{@code FieldValidators.alphanumeric()}'s own javadoc names this
         * registration as its expected user — "B-038's resource registration has
         * fields — an employee code — where letters and digits really is the
         * constraint". It does not, and putting it here would have been the
         * client-code mistake a second time in the place it costs most: this is
         * the upsert key, so a refused value is not a message somebody reads and
         * fixes, it is an employee an otherwise-successful import silently
         * declines to update.
         *
         * <p>S-08 accepts any non-blank string up to 20 characters. So does this.
         */
        @ParameterizedTest
        @ValueSource(strings = {"EDU0142", "EDU-0142", "EDU_0142", "edu-0142", "0142", "E.142",
                "EDU/0142"})
        @DisplayName("every code the S-08 form accepts is importable")
        void everyCodeTheFormAcceptsIsImportable(String code) {
            assertThat(refuse("employeeCode", code)).isEmpty();
        }

        @Test
        @DisplayName("bounded by the column, not by the contract's optimism")
        void isBoundedByTheColumn() {
            // VARCHAR(20). Checked by the engine before the commit so a
            // truncation surfaces as one previewed rejection rather than as a
            // failed batch three hundred rows in.
            assertThat(field("employeeCode").maxLength()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("role")
    class Role {

        /**
         * <b>The same six codes {@code ResourceWriteRequest.role} accepts, in the
         * same spelling.</b>
         *
         * <p>{@code SUPPORT}, not the {@code SUPPORT_DESK} the roles table was
         * originally seeded with — {@code V20260807_1030} renamed it precisely
         * because everything else in the system already called it {@code SUPPORT}
         * and the generated Zod client rejected the other. An import declaring
         * the pre-rename vocabulary would resolve to no role at all and fail
         * every row at write time, after the preview had approved them.
         */
        @Test
        void offersExactlyTheRolesTheFormOffers() {
            assertThat(field("role").allowedValues()).containsExactly(
                    "ADMIN", "PM", "DEVELOPER", "QA", "DEPLOYMENT", "SUPPORT");
        }

        /**
         * An {@code ENUM}, so the template writes a data-validation dropdown from
         * the same declaration the dry run checks against — which is what makes
         * "the template cannot offer a value the import rejects" true rather than
         * maintained.
         */
        @Test
        void isAnEnumSoTheTemplateGetsADropdown() {
            assertThat(field("role").type()).isEqualTo(ImportFieldType.ENUM);
        }

        /**
         * The declared vocabulary matches the pattern on the form's own DTO,
         * read out of the class rather than restated here.
         *
         * <p>A restatement is a third copy, and B-028's lesson is that copies of
         * a vocabulary drift silently. This fails if somebody adds a role to the
         * form and not to the import — which is the direction the drift actually
         * goes, because the form is the one people edit.
         */
        @Test
        @DisplayName("agrees with the S-08 request DTO's own pattern")
        void agreesWithTheFormsPattern() throws Exception {
            Class<?> writeRequest = Class.forName(
                    "com.edunext.edutrack.api.feature.masters.resources.ResourceDtos$ResourceWriteRequest");
            Field role = writeRequest.getDeclaredField("role");
            jakarta.validation.constraints.Pattern pattern =
                    role.getAnnotation(jakarta.validation.constraints.Pattern.class);

            assertThat(pattern)
                    .as("the S-08 request no longer constrains role; this test is now blind")
                    .isNotNull();
            assertThat(Set.of(pattern.regexp().split("\\|")))
                    .isEqualTo(Set.copyOf(field("role").allowedValues()));
        }
    }

    @Nested
    @DisplayName("mobile")
    class Mobile {

        @ParameterizedTest
        @ValueSource(strings = {"+91 98200 11223", "9820011223", "+44 (0) 20-7946-0958"})
        void acceptsWhatTheFormAccepts(String value) {
            assertThat(refuse("mobile", value)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"98200", "ring me", "+91 98200 11223 ext 4"})
        void refusesWhatTheFormRefuses(String value) {
            assertThat(refuse("mobile", value)).isPresent();
        }
    }

    @Nested
    @DisplayName("daily capacity")
    class DailyCapacity {

        /**
         * Excel hands a numeric cell over as {@code 8.0}, and that has to parse.
         *
         * <p>The field is TEXT rather than a new {@code DECIMAL} member of
         * {@code ImportFieldType}, because that enum is documented as three
         * answers to three concrete questions and says anything more specific
         * belongs in a validator — and adding a type would be an engine change on
         * a task whose whole claim is that the engine does not change.
         */
        @ParameterizedTest
        @ValueSource(strings = {"8", "8.0", "8.00", "0.5", "24", "24.0", " 7.5 "})
        void acceptsTheNumbersAWorkbookProduces(String value) {
            assertThat(refuse("dailyCapacityHrs", value)).isEmpty();
        }

        /**
         * Zero is the one worth naming.
         *
         * <p>Every SLA and utilisation figure in the product divides by this
         * number through B-024's working-hours service, so a zero imported from a
         * spreadsheet is not a cosmetic error — and a whole file of them arrives
         * as one blank column somebody mapped by mistake.
         */
        @ParameterizedTest
        @ValueSource(strings = {"0", "0.25", "25", "-8", "8 hrs", "eight"})
        void refusesWhatTheFormWouldRefuse(String value) {
            assertThat(refuse("dailyCapacityHrs", value)).isPresent();
        }
    }

    @Nested
    @DisplayName("weekly off")
    class WeeklyOff {

        @ParameterizedTest
        @ValueSource(strings = {"6, 7", "6,7", "7", "6 7", "1,2,3,4,5,6"})
        void acceptsIsoDayNumbers(String value) {
            assertThat(refuse("weeklyOff", value)).isEmpty();
        }

        /**
         * <b>{@code 0} is refused, and B-023's note is why it is asserted rather
         * than left to the CHECK.</b>
         *
         * <p>One extra day-numbering convention already cost this project a
         * defect: the contract said "ISO" while constraining to 0–6 and the mock
         * sent {@code [0, 6]}, which read by a backend using {@code DayOfWeek}
         * makes Sunday a working day and every weekend-spanning SLA short by a
         * day. A spreadsheet column is a new way for a {@code 0} to arrive — one
         * row at a time, in the harder-to-notice place. The column's own
         * {@code ck_users_weekly_off} would refuse it, mid-commit, as one more
         * anonymous write failure; this refuses it in the preview, with the row
         * number.
         */
        @ParameterizedTest
        @ValueSource(strings = {"0", "0, 6", "8", "-1", "Sat"})
        void refusesAnythingThatIsNotAnIsoDay(String value) {
            assertThat(refuse("weeklyOff", value)).isPresent();
        }

        /** A seven-day weekend sends {@code addWorkingHours} looking for a day it never finds. */
        @Test
        void refusesAWeekWithNoWorkingDay() {
            assertThat(refuse("weeklyOff", "1,2,3,4,5,6,7")).isPresent();
        }
    }

    @Nested
    @DisplayName("skills")
    class Skills {

        @Test
        void acceptsACommaSeparatedList() {
            assertThat(refuse("skills", "Java, React, MySQL")).isEmpty();
        }

        /** {@code ck_users_skills}'s own limits, refused before MySQL has to. */
        @Test
        void refusesMoreThanTheColumnHolds() {
            String thirtyOne = String.join(",",
                    java.util.stream.IntStream.rangeClosed(1, 31).mapToObj(i -> "skill" + i).toList());
            assertThat(refuse("skills", thirtyOne)).isPresent();
            assertThat(refuse("skills", "x".repeat(61))).isPresent();
        }
    }

    @Nested
    @DisplayName("username and email")
    class Identity {

        /** {@code @Size(min = 3)} on the form, so a two-character name is refused here too. */
        @Test
        void aUsernameIsAtLeastThreeCharacters() {
            assertThat(refuse("username", "ab")).isPresent();
            assertThat(refuse("username", "abc")).isEmpty();
        }

        /**
         * The shared {@code FieldValidators.email()}, not a second opinion.
         *
         * <p>Its javadoc predicted this exact reuse: "generic, and rightly here:
         * B-038's resource registration validates addresses on the same rule".
         * The alternative is the S-33 defect again — two ways in disagreeing
         * about whether {@code asha@edunext} is an address.
         */
        @Test
        void anEmailIsCheckedByTheSharedRule() {
            assertThat(refuse("email", "asha.menon@edunext.example")).isEmpty();
            assertThat(refuse("email", "asha.menon@edunext")).isPresent();
            assertThat(field("email").type()).isEqualTo(ImportFieldType.EMAIL);
        }
    }

    // ── the template ────────────────────────────────────────────────────────

    /**
     * Every column carries an example, and every example passes its own column's
     * rules.
     *
     * <p>B-031 puts these in the template's one filled row, and §4B.3 says a
     * worked example is what makes the difference to how many rows come back
     * rejected. An example the import would itself refuse is worse than none —
     * it teaches the wrong format to everybody who copies the row.
     */
    @Test
    @DisplayName("the template's example row would pass its own dry run")
    void everyExampleSatisfiesItsOwnField() {
        List<ImportField> withoutExamples = SCHEMA.fields().stream()
                .filter(f -> f.example() == null || f.example().isBlank())
                .toList();
        assertThat(withoutExamples)
                .as("a column with no example is a column the template cannot demonstrate")
                .isEmpty();

        for (ImportField declared : SCHEMA.fields()) {
            assertThat(refuse(declared.name(), declared.example()))
                    .as("%s's example %s is refused by its own validators",
                            declared.name(), declared.example())
                    .isEmpty();
            if (declared.maxLength() > 0) {
                assertThat(declared.example().length()).isLessThanOrEqualTo(declared.maxLength());
            }
            if (declared.type() == ImportFieldType.ENUM) {
                assertThat(declared.allowedValues()).contains(declared.example());
            }
        }
    }
}
