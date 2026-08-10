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

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM notification_preferences");
        jdbc.update("DELETE FROM email_log");
        ravi = insertUser("ravi.pref");
    }

    // ------------------------------------------------------- the default

    @Test
    @DisplayName("somebody who has never opened the screen receives everything")
    void absenceMeansEnabled() {
        assertThat(preferences.allows(ravi, "COMMENT_ADDED", NotificationChannel.EMAIL)).isTrue();
        assertThat(preferences.allows(ravi, "COMMENT_ADDED", NotificationChannel.IN_APP)).isTrue();
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
        service.save(ravi, request("COMMENT_ADDED", false, null));

        PreferenceDtos.PreferenceRow row = rowFor("COMMENT_ADDED");
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
                new PreferenceDtos.PreferenceUpdate("COMMENT_ADDED", null, false),
                new PreferenceDtos.PreferenceUpdate("NOT_AN_EVENT", null, false))));

        // Validated up front for exactly this: half a save is worse than none,
        // because the user cannot tell which half.
        assertThat(countOverrides("COMMENT_ADDED", NotificationChannel.EMAIL)).isZero();
    }

    @Test
    void preferencesAreYourOwn() {
        long meera = insertUser("meera.pref");
        service.save(ravi, request("COMMENT_ADDED", null, false));

        assertThat(preferences.allows(meera, "COMMENT_ADDED", NotificationChannel.EMAIL)).isTrue();
    }

    // ------------------------------------------------------------- helpers

    private static PreferenceDtos.PreferenceUpdateRequest request(
            String eventKey, Boolean inApp, Boolean email) {
        return new PreferenceDtos.PreferenceUpdateRequest(
                List.of(new PreferenceDtos.PreferenceUpdate(eventKey, inApp, email)));
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

    private long insertUser(String username) {
        jdbc.update("""
                INSERT INTO users (username, email, full_name, password_hash, role_id, is_active)
                SELECT ?, ?, ?, 'x', MIN(id), 1 FROM roles
                """, username, username + "@edunext.test", username);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }
}
