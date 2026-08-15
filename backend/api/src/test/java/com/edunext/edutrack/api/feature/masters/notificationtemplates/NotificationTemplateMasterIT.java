package com.edunext.edutrack.api.feature.masters.notificationtemplates;

import com.edunext.edutrack.domain.notifications.MergeTag;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-022 · S-15 against a real MySQL.
 *
 * <p>{@link NotificationTemplateServiceTest} proves the decisions against mocks.
 * This proves the half a mock cannot, and the part worth a Docker container is
 * the seed: {@code V20260815_1100} writes about fifty rows of wording, and every
 * {@code {{tag}}} in every one of them has to be in {@link MergeTag} or the
 * renderer will print braces into a client-facing mail. Nothing else checks
 * that, because the migration is data rather than code.
 *
 * <p>It also proves that the seed covers <em>every</em> event this build can
 * raise. An event with no template is an event whose wording lives in Java, and
 * ending that is the entire reason the table exists — so a producer Stream D
 * adds without a template here should fail this test rather than fail silently
 * at 3 a.m.
 */
@SpringBootTest
@Testcontainers
class NotificationTemplateMasterIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_notification_template_it")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00",
                    "--sql-mode=ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,"
                            + "ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION",
                    "--log-bin-trust-function-creators=1")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("useSSL", "false")
            .withUrlParam("connectionTimeZone", "UTC");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    @Autowired
    NotificationTemplateService service;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * Rows this test created are removed, and the seeded ones are restored to
     * their migration values.
     *
     * <p>Unlike {@link com.edunext.edutrack.api.feature.masters.priorities.PriorityMasterIT},
     * throwaway rows <em>are</em> possible here — a {@code PUSH} template on a
     * seeded event is a legitimate create — so only those are deleted. What is
     * restored is {@code is_active}, because a test that switches an optional
     * template off would otherwise leave the next test reading a different
     * world. An order-dependent suite passes locally and fails in CI on the day
     * somebody adds a test above it.
     */
    @BeforeEach
    @AfterEach
    void restoreTheSeed() {
        jdbc.update("DELETE FROM notification_templates WHERE channel = 'PUSH'");
        jdbc.update("UPDATE notification_templates SET is_active = 1");
    }

    // ------------------------------------------------------------------
    // what the migration actually seeded
    // ------------------------------------------------------------------

    /**
     * The assertion this container exists for.
     *
     * <p>The seed is fifty rows of prose in a {@code .sql} file. A typo in one of
     * them — {@code {{ticketId}}} for {@code {{ticket_id}}} — compiles, applies,
     * and is discovered by a customer reading a mail with braces in it. The
     * service refuses one on the write path; nothing refuses one on the seed
     * path, so this does.
     */
    @Test
    @DisplayName("every merge tag in every seeded template resolves")
    void seededTemplatesUseOnlyKnownMergeTags() {
        for (NotificationTemplateDtos.TemplateView template : service.list()) {
            assertThat(MergeTag.unknownIn(template.subjectTemplate()))
                    .as("subject of %s/%s", template.eventCode(), template.channel())
                    .isEmpty();
            assertThat(MergeTag.unknownIn(template.bodyTemplate()))
                    .as("body of %s/%s", template.eventCode(), template.channel())
                    .isEmpty();
        }
    }

    /**
     * An event with no template is an event whose wording lives in Java, which is
     * what this table exists to end. A producer added without a template here
     * should fail on a build machine rather than at 3 a.m.
     */
    @Test
    @DisplayName("every event this build can raise has at least one template")
    void everyEventHasATemplate() {
        Set<String> withTemplates = service.list().stream()
                .map(NotificationTemplateDtos.TemplateView::eventCode)
                .collect(java.util.stream.Collectors.toSet());

        for (NotificationEvent event : NotificationEvent.values()) {
            assertThat(withTemplates)
                    .as("%s fires with no wording anywhere — add a row to the seed", event)
                    .contains(event.name());
        }
    }

    /**
     * Every §4B.6 mail marked ❌ never optional has to <em>exist</em> before the
     * rule protecting it means anything. A mandatory event with no email template
     * is a mail that cannot be switched off because it was never switched on.
     *
     * <p><b>Two events in mandatory categories send no mail at all, and both are
     * §11 reading correctly rather than a gap in the seed.</b>
     * {@code isMandatoryMail()} is deliberately stated over the <em>category</em>
     * — that is what keeps it in step with D-036, and what makes a new
     * escalation event mandatory the moment it is declared — so it necessarily
     * says true for a handful of events whose §11 row has a dash in the Email
     * column. D-055 wrote that down for {@code STATUS_REQUEST_ANSWERED} and
     * called it moot: an event that sends nothing cannot have its mail silenced.
     *
     * <p>Running this assertion over the whole enum found the second one.
     * {@code TICKET_REASSIGNED_AWAY} is an {@code ASSIGNMENT} — being taken off a
     * ticket does change who is responsible — and §11 gives it a bell entry and
     * no mail, which is right: the person is being told about work leaving them,
     * not arriving. Seeding an email for it to satisfy a category rule would be
     * inventing a mail the blueprint says not to send.
     *
     * <p>Checked exhaustively rather than skipped by name, so a third one is a
     * failure here and a decision, not a silent omission.
     */
    @Test
    @DisplayName("every mandatory event that §11 gives a mail has one, and it is locked")
    void mandatoryEventsHaveALockedEmailTemplate() {
        Set<NotificationEvent> noMailInBlueprint = Set.of(
                NotificationEvent.STATUS_REQUEST_ANSWERED,
                NotificationEvent.TICKET_REASSIGNED_AWAY);

        for (NotificationEvent event : NotificationEvent.values()) {
            if (!event.isMandatoryMail() || noMailInBlueprint.contains(event)) {
                continue;
            }
            assertThat(service.list())
                    .as("%s has no EMAIL template to lock", event)
                    .anySatisfy(template -> {
                        assertThat(template.eventCode()).isEqualTo(event.name());
                        assertThat(template.channel()).isEqualTo("EMAIL");
                        assertThat(template.isMandatory()).isTrue();
                    });
        }

        // And the two exceptions really are mail-less, rather than the list
        // being a way to excuse a seed that forgot them.
        for (NotificationEvent event : noMailInBlueprint) {
            assertThat(service.list())
                    .as("%s is listed as having no mail in §11 but the seed gave it one", event)
                    .noneSatisfy(template -> {
                        assertThat(template.eventCode()).isEqualTo(event.name());
                        assertThat(template.channel()).isEqualTo("EMAIL");
                    });
        }
    }

    /**
     * The column is the vocabulary's only storage, and a stored token the enum
     * cannot parse is silently dropped at send time — so a seeded typo would
     * mean a mail quietly reaching one fewer person than intended.
     */
    @Test
    @DisplayName("every seeded recipient parses, and no template has an empty list")
    void seededRecipientsAllResolve() {
        List<String> raw = jdbc.queryForList(
                "SELECT recipients FROM notification_templates", String.class);

        assertThat(raw).isNotEmpty().allSatisfy(stored -> {
            assertThat(stored).isNotBlank();
            assertThat(com.edunext.edutrack.domain.notifications.NotificationRecipient.parse(stored))
                    .as("recipients column '%s' has a token this build cannot resolve", stored)
                    .hasSize(stored.split(",").length);
        });
    }

    /**
     * A-007's comment predicted {@code POPUP|BELL|EMAIL}. The seed writes
     * {@code IN_APP} and {@code EMAIL}, and this pins it — the migration's
     * section 2 carries the argument, and a future seed restoring the old
     * vocabulary from the comment would put rows in the table that the renderer
     * can never find.
     */
    @Test
    @DisplayName("the seeded channels are D-042's, not A-007's column comment")
    void seededChannelsAreTheOnesThatRun() {
        assertThat(jdbc.queryForList(
                "SELECT DISTINCT channel FROM notification_templates", String.class))
                .containsExactlyInAnyOrder("IN_APP", "EMAIL");
    }

    /** Every in-app row leaves the subject null — a bell entry has a title. */
    @Test
    @DisplayName("in-app templates carry no subject and email templates all carry one")
    void subjectsMatchTheirChannel() {
        assertThat(service.list()).allSatisfy(template -> {
            if ("EMAIL".equals(template.channel())) {
                assertThat(template.subjectTemplate())
                        .as("%s email has no subject", template.eventCode()).isNotBlank();
            } else {
                assertThat(template.subjectTemplate())
                        .as("%s in-app carries a subject", template.eventCode()).isNull();
            }
        });
    }

    /** The one event deliberately without an email row — §4B.6's loop. */
    @Test
    @DisplayName("a failed-mail alert is not itself a mail")
    void mailDeliveryFailureIsInAppOnly() {
        assertThat(service.list())
                .filteredOn(t -> "MAIL_DELIVERY_FAILED".equals(t.eventCode()))
                .extracting(NotificationTemplateDtos.TemplateView::channel)
                .containsExactly("IN_APP");
    }

    // ------------------------------------------------------------------
    // the schema agrees with the service
    // ------------------------------------------------------------------

    /**
     * The service checks for a duplicate pair before inserting so the caller
     * gets a 409 rather than a constraint violation surfacing as a 500. This
     * proves the constraint is really there, so that check is a courtesy rather
     * than the only thing standing between the table and two templates for one
     * pair.
     */
    @Test
    @DisplayName("uq_notification_templates agrees with the service's own check")
    void uniqueKeyIsReallyThere() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO notification_templates "
                        + "(event_code, channel, recipients, subject_template, body_template) "
                        + "VALUES ('TICKET_ASSIGNED', 'EMAIL', 'ASSIGNEE', 'dup', 'dup')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * {@code recipients} is {@code NOT NULL} and the migration drops the default
     * it needed to add the column, so a row that names nobody cannot be inserted
     * by anything — service or not.
     *
     * <p>Asserted on {@link DataAccessException} and the message rather than on
     * {@code DataIntegrityViolationException}: MySQL reports a missing default as
     * error 1364, which Spring's exception translator does not map to the
     * integrity-violation branch and hands back as an
     * {@code UncategorizedSQLException}. Pinning the narrower class would be
     * asserting how Spring classifies the error, when what this test is about is
     * that the database refuses the insert and says which column.
     */
    @Test
    @DisplayName("recipients is NOT NULL with no default to fall back on")
    void recipientsHasNoDefault() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO notification_templates (event_code, channel, body_template) "
                        + "VALUES ('TICKET_ASSIGNED', 'PUSH', 'no recipients')"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("recipients");
    }

    // ------------------------------------------------------------------
    // the rules, end to end
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a push template can be added to a seeded event, and only once")
    void pushTemplateCanBeAddedOnce() {
        NotificationTemplateDtos.TemplateView created =
                service.create(new NotificationTemplateDtos.TemplateWrite(
                        "HANDOFF_RECEIVED", "PUSH", List.of("STAGE_OWNER"),
                        "Handed to you at {{stage}}", "{{ticket_id}} — {{ticket_title}}", null));

        assertThat(created.channel()).isEqualTo("PUSH");
        assertThat(created.isMandatory())
                .as("push is never mandatory — §7.7 gives the guarantee to mail")
                .isFalse();

        assertThatThrownBy(() -> service.create(new NotificationTemplateDtos.TemplateWrite(
                "HANDOFF_RECEIVED", "PUSH", List.of("STAGE_OWNER"), "again", "again", null)))
                .isInstanceOf(NotificationTemplateService.DuplicateTemplateException.class);
    }

    @Test
    @DisplayName("the seeded assignment mail cannot be switched off, and survives the attempt")
    void seededMandatoryMailSurvivesTheAttempt() {
        NotificationTemplateDtos.TemplateView mail = service.list().stream()
                .filter(t -> "TICKET_ASSIGNED".equals(t.eventCode())
                        && "EMAIL".equals(t.channel()))
                .findFirst().orElseThrow();

        assertThatThrownBy(() -> service.update(mail.id(),
                new NotificationTemplateDtos.TemplatePatch(
                        null, null, null, null, null, false)))
                .isInstanceOf(NotificationTemplateService.MandatoryTemplateException.class);

        assertThat(service.find(mail.id())).get()
                .extracting(NotificationTemplateDtos.TemplateView::isActive).isEqualTo(true);
    }

    @Test
    @DisplayName("an optional template really does switch off and back on")
    void optionalTemplateSwitchesOffAndBackOn() {
        NotificationTemplateDtos.TemplateView digest = service.list().stream()
                .filter(t -> "DAILY_DIGEST".equals(t.eventCode()))
                .findFirst().orElseThrow();

        service.update(digest.id(), new NotificationTemplateDtos.TemplatePatch(
                null, null, null, null, null, false));
        assertThat(service.find(digest.id())).get()
                .extracting(NotificationTemplateDtos.TemplateView::isActive).isEqualTo(false);

        service.update(digest.id(), new NotificationTemplateDtos.TemplatePatch(
                null, null, null, null, null, true));
        assertThat(service.find(digest.id())).get()
                .extracting(NotificationTemplateDtos.TemplateView::isActive).isEqualTo(true);
    }

    /**
     * The property S-15 actually needs: every event's rows arrive <b>contiguous</b>,
     * so the grid can group by walking the list once.
     *
     * <p><b>Asserted as contiguity rather than as a sorted list of
     * {@code "EVENT/CHANNEL"} strings</b>, which is what this test did first and
     * which failed on correct data. Joining the two keys with a separator is not
     * the same comparison as {@code ORDER BY event_code, channel}: {@code /} is
     * {@code 0x2F} and sorts before {@code _} in Java's {@code String}, so
     * {@code STATUS_REQUEST_ANSWERED/IN_APP} lands after
     * {@code STATUS_REQUESTED/EMAIL} on a joined string and before it on the two
     * columns. The database was right and the assertion was wrong — and a
     * separator-joined key would also have made the test depend on
     * {@code utf8mb4_0900_ai_ci} agreeing with Java about punctuation, which is
     * not something this screen relies on.
     */
    @Test
    @DisplayName("every event's channels arrive together, so the grid can group in one pass")
    void listIsGroupedForTheGrid() {
        List<String> events = service.list().stream()
                .map(NotificationTemplateDtos.TemplateView::eventCode)
                .toList();

        Set<String> closed = new java.util.HashSet<>();
        String current = null;
        for (String event : events) {
            if (event.equals(current)) {
                continue;
            }
            assertThat(closed)
                    .as("%s appears again after another event — the grid would render it twice",
                            event)
                    .doesNotContain(event);
            if (current != null) {
                closed.add(current);
            }
            current = event;
        }
    }
}
