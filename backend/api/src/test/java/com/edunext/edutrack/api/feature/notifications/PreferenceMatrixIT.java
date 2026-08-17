package com.edunext.edutrack.api.feature.notifications;

import com.edunext.edutrack.domain.notifications.NotificationChannel;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationPreferences;
import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-042 · the preference matrix · D-036 · and the mail it cannot silence.
 *
 * <p>Asserted through the send path rather than only through the service,
 * because the guarantee is not "the screen refuses to save it" — it is that a
 * breach mail goes out even when a row says otherwise. Rows are written
 * directly here to prove exactly that: a value the API would never accept must
 * still not silence the mail.
 */
@Testcontainers
@SpringBootTest
class PreferenceMatrixIT {

    /** The same container settings as {@code NotificationCentreIT} — the
     * baseline migration defines triggers, which MySQL refuses without
     * {@code log-bin-trust-function-creators}. */
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_it")
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
    JdbcTemplate jdbc;

    @Autowired
    NotificationPreferences preferences;

    @Autowired
    PreferenceService service;

    @Autowired
    OutboxEnqueuer outbox;

    private long ravi;

    /**
     * Users are not deleted between tests — notifications and email_log point
     * at them — so each test gets its own rather than colliding on the unique
     * username.
     */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM notification_preferences");
        jdbc.update("DELETE FROM email_log");
        ravi = insertUser("ravi.pref." + SEQ.incrementAndGet());
    }

    // ------------------------------------------------------- the default

    @Test
    @DisplayName("somebody who has never opened the screen receives everything")
    void absenceMeansEnabled() {
        assertThat(preferences.allows(ravi, "COMMENT_ADDED", NotificationChannel.EMAIL)).isTrue();
        // D-040 changed which event demonstrates this on IN_APP, not the rule.
        // It has to be one §11 gives a popup at all: COMMENT_ADDED has a dash in
        // that column, so there is no toast for an absent preference to leave
        // enabled, and asserting it here was asserting the wrong thing.
        assertThat(preferences.allows(ravi, "MENTIONED", NotificationChannel.IN_APP)).isTrue();
    }

    @Test
    @DisplayName("§11's dash beats an absent preference — there is no toast to enable")
    void anEventWithNoPopupIsNotToasted() {
        // Absence still means enabled; this is the prior question. D-042 asks
        // "did the user switch it off", and the answer is no — but the event has
        // no in-app popup in the first place, and a default cannot conjure a
        // channel the blueprint did not give it.
        assertThat(NotificationEvent.COMMENT_ADDED.popsUp()).isFalse();
        assertThat(preferences.allows(ravi, "COMMENT_ADDED", NotificationChannel.IN_APP)).isFalse();

        // The bell is not a preference and is not affected — S-26 still holds
        // every comment. Only the interrupt is refused.
    }

    @Test
    @DisplayName("the screen shows that switch off, rather than lying about it")
    void theMatrixDoesNotOfferAToastThatCannotHappen() {
        // The mirror of the mandatory-mail row, which always reads as on
        // whatever the table says. A switch shown on that the send path ignores
        // is the same defect pointing the other way.
        assertThat(rowFor("COMMENT_ADDED").inApp()).isFalse();
        assertThat(rowFor("MENTIONED").inApp()).isTrue();
    }

    @Test
    @DisplayName("turning that switch on is discarded, not stored")
    void enablingAnImpossibleToastIsDiscarded() {
        // Discarded rather than rejected, exactly as a locked mail is: the
        // client posts back the grid it was shown, and failing the whole save
        // over a switch the user could not usefully have moved punishes them for
        // our screen. What matters is that no row is written claiming a
        // preference the send path cannot honour.
        assertThat(service.save(ravi, request("COMMENT_ADDED", true, null)))
                .isEqualTo(PreferenceService.SaveOutcome.SAVED);

        assertThat(preferences.overridesFor(ravi))
                .as("no IN_APP row was stored for an event with no popup")
                .noneMatch(o -> o.eventCode().equals("COMMENT_ADDED")
                        && o.channel().equals(NotificationChannel.IN_APP.name()));
        assertThat(preferences.allows(ravi, "COMMENT_ADDED", NotificationChannel.IN_APP)).isFalse();
    }

    @Test
    @DisplayName("an event this build does not know is still deliverable")
    void unknownCodesAreNotSilenced() {
        // A producer from a newer deploy must not be silenced by an older
        // reader's vocabulary — the failure mode of guessing wrong is a missed
        // alert, which is the risk §17 exists to close.
        assertThat(preferences.allows(ravi, "SOMETHING_FROM_THE_FUTURE", NotificationChannel.EMAIL))
                .isTrue();
    }

    // ------------------------------------------------------- opting out

    @Test
    void turningOffAnOptionalMailStopsItBeingQueued() {
        service.save(ravi, request("COMMENT_ADDED", null, false));

        assertThat(enqueue("COMMENT_ADDED")).isEmpty();
    }

    @Test
    void whatIsStillOnIsStillQueued() {
        service.save(ravi, request("COMMENT_ADDED", null, false));

        // The suppression is per event, not a blanket mute.
        assertThat(enqueue("ATTACHMENT_ADDED")).isPresent();
    }

    @Test
    @DisplayName("a suppressed mail never reaches email_log at all")
    void suppressionHappensBeforeTheQueue() {
        service.save(ravi, request("COMMENT_ADDED", null, false));
        enqueue("COMMENT_ADDED");

        // Queueing it and dropping it at send would leave a row D-033 reads as
        // "we tried", and §17 wants that log to answer "did they get it".
        assertThat(countEmailLog()).isZero();
    }

    // ------------------------------------------------------- D-036

    @Test
    @DisplayName("a breach mail cannot be switched off through the API")
    void theApiDiscardsAnAttemptToDisableAMandatoryMail() {
        PreferenceService.SaveOutcome outcome =
                service.save(ravi, request("SLA_BREACHED", null, false));

        // Discarded, not rejected: the client is usually posting back the row
        // it was showing.
        assertThat(outcome).isEqualTo(PreferenceService.SaveOutcome.SAVED);
        assertThat(preferences.allows(ravi, "SLA_BREACHED", NotificationChannel.EMAIL)).isTrue();
        assertThat(countOverrides("SLA_BREACHED", NotificationChannel.EMAIL)).isZero();
    }

    @Test
    @DisplayName("a stored row saying otherwise still cannot silence a breach mail")
    void theRuleIsEnforcedOnTheSendPathNotTheScreen() {
        // Written straight to the table — a value the API would never accept,
        // standing in for an older build, a direct database edit or a bug.
        jdbc.update("""
                INSERT INTO notification_preferences (user_id, event_code, channel, enabled)
                VALUES (?, 'SLA_BREACHED', 'EMAIL', 0)
                """, ravi);

        assertThat(preferences.allows(ravi, "SLA_BREACHED", NotificationChannel.EMAIL)).isTrue();
        assertThat(enqueue("SLA_BREACHED")).isPresent();
    }

    @Test
    @DisplayName("the in-app toast for a mandatory event is still a preference")
    void theLockIsOnMailOnly() {
        service.save(ravi, request("SLA_BREACHED", false, null));

        // §7.7 gives the guarantee to mail. Quieting the popup while keeping
        // the mail is a real choice, and the bell entry is written regardless.
        assertThat(preferences.allows(ravi, "SLA_BREACHED", NotificationChannel.IN_APP)).isFalse();
        assertThat(preferences.allows(ravi, "SLA_BREACHED", NotificationChannel.EMAIL)).isTrue();
    }

    // ------------------------------------------------------- the screen

    @Test
    @DisplayName("the matrix lists every event, not just the overridden ones")
    void theWholeCatalogueIsReturned() {
        assertThat(service.matrixFor(ravi).data())
                .hasSize(NotificationEvent.values().length);
    }

    @Test
    void aLockedRowAlwaysReadsAsOn() {
        jdbc.update("""
                INSERT INTO notification_preferences (user_id, event_code, channel, enabled)
                VALUES (?, 'SLA_BREACHED', 'EMAIL', 0)
                """, ravi);

        PreferenceDtos.PreferenceRow row = rowFor("SLA_BREACHED");

        // The screen must never show a switch as off that the send path ignores.
        assertThat(row.email()).isTrue();
        assertThat(row.emailLocked()).isTrue();
    }

    @Test
    void savingOneChannelLeavesTheOtherAlone() {
        // MENTIONED since D-040, for the reason given in
        // channelsDoNotDragEachOtherAround: an event §11 gives no popup reads
        // false whether or not the save did anything.
        service.save(ravi, request("MENTIONED", false, null));

        PreferenceDtos.PreferenceRow row = rowFor("MENTIONED");
        assertThat(row.inApp()).isFalse();
        assertThat(row.email()).isTrue();
    }

    @Test
    void savingTwiceUpdatesRatherThanDuplicating() {
        service.save(ravi, request("COMMENT_ADDED", null, false));
        service.save(ravi, request("COMMENT_ADDED", null, true));

        assertThat(countOverrides("COMMENT_ADDED", NotificationChannel.EMAIL)).isEqualTo(1);
        assertThat(rowFor("COMMENT_ADDED").email()).isTrue();
    }

    @Test
    @DisplayName("an unknown event key is rejected rather than silently dropped")
    void anUnknownEventIsABadRequest() {
        assertThat(service.save(ravi, request("NOT_AN_EVENT", null, false)))
                .isEqualTo(PreferenceService.SaveOutcome.UNKNOWN_EVENT);
    }

    @Test
    @DisplayName("a batch containing one bad key writes none of it")
    void aRejectedBatchIsNotPartiallyApplied() {
        service.save(ravi, new PreferenceDtos.PreferenceUpdateRequest(List.of(
                new PreferenceDtos.PreferenceUpdate("COMMENT_ADDED", null, false, null),
                new PreferenceDtos.PreferenceUpdate("NOT_AN_EVENT", null, false, null))));

        // Validated up front for exactly this: half a save is worse than none,
        // because the user cannot tell which half.
        assertThat(countOverrides("COMMENT_ADDED", NotificationChannel.EMAIL)).isZero();
    }

    @Test
    void preferencesAreYourOwn() {
        long meera = insertUser("meera.pref." + SEQ.incrementAndGet());
        service.save(ravi, request("COMMENT_ADDED", null, false));

        assertThat(preferences.allows(meera, "COMMENT_ADDED", NotificationChannel.EMAIL)).isTrue();
    }

    // ------------------------------------------------- D-045 · the push channel

    @Test
    @DisplayName("push defaults to on, like every other channel — the table holds deviations")
    void pushDefaultsToOn() {
        assertThat(rowFor("TICKET_ASSIGNED").push()).isTrue();
        assertThat(preferences.allows(ravi, "TICKET_ASSIGNED", NotificationChannel.PUSH)).isTrue();
    }

    @Test
    @DisplayName("switching push off is honoured on the send path")
    void pushCanBeSwitchedOff() {
        service.save(ravi, request("TICKET_ASSIGNED", null, null, false));

        assertThat(rowFor("TICKET_ASSIGNED").push()).isFalse();
        assertThat(preferences.allows(ravi, "TICKET_ASSIGNED", NotificationChannel.PUSH)).isFalse();
    }

    @Test
    @DisplayName("no push is mandatory, not even an escalation's")
    void noPushIsLocked() {
        // D-036 locks mail for assignments and escalations. It deliberately does
        // not reach here: §7.7 gives the guarantee to mail because push depends
        // on a permission the user can revoke in their own browser settings
        // without telling us, and a channel we cannot promise must not be
        // presented as one they cannot switch off.
        service.save(ravi, request("SLA_BREACHED", null, null, false));

        assertThat(rowFor("SLA_BREACHED").push()).isFalse();
        assertThat(preferences.allows(ravi, "SLA_BREACHED", NotificationChannel.PUSH)).isFalse();
        assertThat(rowFor("SLA_BREACHED").email())
                .as("the mail it cannot switch off is still on — that is the actual promise")
                .isTrue();
    }

    @Test
    @DisplayName("the three channels are independent")
    void channelsDoNotDragEachOtherAround() {
        // MENTIONED rather than COMMENT_ADDED since D-040: §11 gives the latter
        // no in-app popup, so its toast reads off whatever is saved, and this
        // test would have gone on passing while proving nothing about the
        // channel it names.
        service.save(ravi, request("MENTIONED", false, null, null));

        // Somebody who silenced the toast has not asked to lose push as well.
        assertThat(rowFor("MENTIONED").inApp()).isFalse();
        assertThat(rowFor("MENTIONED").push()).isTrue();
        assertThat(rowFor("MENTIONED").email()).isTrue();
    }

    @Test
    @DisplayName("an omitted push field leaves the stored value alone")
    void omittingPushChangesNothing() {
        service.save(ravi, request("COMMENT_ADDED", null, null, false));
        service.save(ravi, request("COMMENT_ADDED", true, null));

        assertThat(rowFor("COMMENT_ADDED").push())
                .as("a screen saving one switch must not silently reset the others")
                .isFalse();
    }

    // ------------------------------------------------------------- helpers

    private static PreferenceDtos.PreferenceUpdateRequest request(
            String eventKey, Boolean inApp, Boolean email) {
        return request(eventKey, inApp, email, null);
    }

    private static PreferenceDtos.PreferenceUpdateRequest request(
            String eventKey, Boolean inApp, Boolean email, Boolean push) {
        return new PreferenceDtos.PreferenceUpdateRequest(
                List.of(new PreferenceDtos.PreferenceUpdate(eventKey, inApp, email, push)));
    }

    private PreferenceDtos.PreferenceRow rowFor(String eventKey) {
        return service.matrixFor(ravi).data().stream()
                .filter(r -> r.eventKey().equals(eventKey))
                .findFirst()
                .orElseThrow();
    }

    private OptionalLong enqueue(String eventCode) {
        return outbox.enqueue(new NewMail(null, eventCode, null, ravi, "ravi@edunext.test", "x"));
    }

    private int countEmailLog() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM email_log", Integer.class);
        return count == null ? 0 : count;
    }

    private int countOverrides(String eventCode, NotificationChannel channel) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_preferences
                 WHERE user_id = ? AND event_code = ? AND channel = ?
                """, Integer.class, ravi, eventCode, channel.name());
        return count == null ? 0 : count;
    }

    /** The same shape {@code NotificationCentreIT} uses — emp_code is required. */
    private long insertUser(String username) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", username, roleId);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }
}
