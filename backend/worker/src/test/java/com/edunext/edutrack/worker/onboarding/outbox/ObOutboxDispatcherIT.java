package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.mail.EmailSuppressions;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotification;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import com.edunext.edutrack.worker.WorkerApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-110 · the onboarding outbox, proven against a real MySQL 8.4.
 *
 * <p>{@code SKIP LOCKED}, the generated dedupe column, lease expiry and
 * rollback atomicity are behaviours of the database, so a mocked repository
 * would assert nothing that matters. The scheduler is switched off and each
 * test drives {@link ObOutboxDispatcher#pollOnce()} by hand with a clock it
 * controls — otherwise the poller drains rows out from under the assertions.
 *
 * <p>Two adapters are in the registry these tests drive: the shipped
 * {@link EmailChannelAdapter} over the logging transport, and a scripted one on
 * {@code IN_APP} that the failure tests steer. <b>The registry is assembled in
 * {@link #reset()} rather than autowired</b> — B-112 ships a real
 * {@link InAppChannelAdapter} and {@link ChannelAdapterRegistry} refuses two
 * adapters for one channel, so the scripted one can no longer be a bean. See
 * the field for why the fix is a hand-built registry and not an excluded bean.
 */
@Testcontainers
@SpringBootTest(classes = WorkerApplication.class)
@Import(ObOutboxDispatcherIT.TestAdapters.class)
class ObOutboxDispatcherIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_ob_outbox_it")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00",
                    "--log-bin-trust-function-creators=1")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("useSSL", "false")
            .withUrlParam("connectionTimeZone", "UTC");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // The worker leaves migration to api in production; the test has no
        // api, so it builds the schema itself from the same migrations.
        registry.add("spring.flyway.enabled", () -> true);
        // Every background schedule off, for the reason OutboxWorkerIT gives:
        // a scheduler writing rows underneath a test that controls its own
        // fixtures is a race, and each of these has cost a re-run before.
        registry.add("edutrack.ob-outbox.enabled", () -> false);
        registry.add("edutrack.outbox.enabled", () -> false);
        registry.add("edutrack.stats.enabled", () -> "false");
        registry.add("edutrack.sla.initial-delay", () -> "PT24H");
    }

    /**
     * One clock for the enqueuer and the dispatcher, so "due now" means the
     * same instant on both sides and the tests can move time rather than
     * sleep through a backoff.
     */
    @TestConfiguration
    static class TestAdapters {

        static final MutableClock CLOCK = new MutableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        static final ScriptedAdapter IN_APP = new ScriptedAdapter();

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    ObOutboxRepository repository;

    @Autowired
    ObOutboxEnqueuer enqueuer;

    /**
     * The shipped EMAIL adapter, paired with the scripted one in the registry
     * {@link #reset()} builds.
     */
    @Autowired
    EmailChannelAdapter emailAdapter;

    /**
     * <b>Built here rather than autowired, since B-112.</b> The scripted
     * adapter used to be a {@code @Bean} on {@code IN_APP}, which was free
     * because nothing shipped one. B-112 ships {@link InAppChannelAdapter}, and
     * {@link ChannelAdapterRegistry} refuses two adapters for one channel at
     * startup — correctly; that rule is what stops a message going out twice on
     * two transports, and switching it off for a test would be removing the
     * guarantee to test around it.
     *
     * <p>Excluding the shipped bean from the context would have worked too, and
     * is worse: it makes the context under test differ from the real one in a
     * way nothing in the file says out loud. This registry is assembled from
     * beans that are all really there, and the dispatcher was already
     * hand-built from it two lines below for the same reason.
     */
    private ChannelAdapterRegistry adapters;

    @Autowired
    ObOutboxProperties shippedProperties;

    @Autowired
    ObDeliveryFailureNotifier failureNotifier;

    @Autowired
    EmailSuppressions suppressions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    private MutableClock clock;
    private ObOutboxDispatcher dispatcher;
    private long clientId;
    private long contactId;
    private long staffId;
    private long journeyId;
    private long stepId;

    @BeforeEach
    void reset() {
        // Children first. The outbox references users, contacts, clients,
        // journeys and steps; steps reference journeys; journeys reference
        // the client's purchase and a template; the template a product.
        jdbc.update("DELETE FROM notifications");
        // B-112. Before the outbox: an entry references the queue row it was
        // delivered from.
        jdbc.update("DELETE FROM ob_notifications");
        jdbc.update("DELETE FROM ob_notification_outbox");
        jdbc.update("DELETE FROM ob_journey_steps");
        jdbc.update("DELETE FROM ob_journeys");
        jdbc.update("DELETE FROM ob_client_applications");
        jdbc.update("DELETE FROM ob_journey_templates");
        jdbc.update("DELETE FROM ob_products");
        jdbc.update("DELETE FROM ob_client_contacts");
        jdbc.update("DELETE FROM ob_clients");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM email_suppressions");

        clock = TestAdapters.CLOCK;
        clock.set(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        TestAdapters.IN_APP.reset();

        adapters = new ChannelAdapterRegistry(List.of(emailAdapter, TestAdapters.IN_APP));
        dispatcher = new ObOutboxDispatcher(repository, adapters, shippedProperties, failureNotifier, clock);

        staffId = insertUser("asha", "PM");
        clientId = insertClient("Horizon Academy");
        contactId = insertContact(clientId, "Priya Menon", "priya@horizon.test");

        jdbc.update("INSERT INTO ob_products (code, name) VALUES ('LMS', 'Learning Management')");
        long productId = lastInsertId();
        jdbc.update("INSERT INTO ob_journey_templates (product_id, name) VALUES (?, 'Standard SaaS Onboarding')",
                productId);
        long templateId = lastInsertId();
        jdbc.update("INSERT INTO ob_client_applications (ob_client_id, product_id) VALUES (?, ?)",
                clientId, productId);
        jdbc.update("INSERT INTO ob_journeys (ob_client_id, product_id, template_id) VALUES (?, ?, ?)",
                clientId, productId, templateId);
        journeyId = lastInsertId();
        jdbc.update("INSERT INTO ob_journey_steps (journey_id, sequence, name) VALUES (?, 1, 'Data migration')",
                journeyId);
        stepId = lastInsertId();
    }

    // ── the happy path, both recipient populations ───────────────────────

    @Test
    void aStaffRecipientIsResolvedFromUsersAndSentOverTheLoggingTransport() {
        long id = enqueue(ObNotification.aboutClient("CLIENT_LOGIN_CREATED", ObChannel.EMAIL,
                new ObRecipient.Staff(staffId), clientId, Map.of("clientName", "Horizon Academy")));

        assertThat(dispatcher.pollOnce()).isEqualTo(1);

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("provider_message_id")).isEqualTo(LoggingObMailTransport.PROVIDER_ID_PREFIX + id);
        assertThat(instant(row, "sent_at")).isEqualTo(clock.instant());
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("last_error")).isNull();
        // Left the queue: the generated column releases the dedupe slot.
        assertThat(row.get("queued_dedupe_key")).isNull();
    }

    @Test
    void aClientContactIsResolvedFromItsRowAtClaimTime() {
        long id = enqueue(stepEvent("SIGNOFF_REQUESTED", ObChannel.IN_APP, new ObRecipient.Client(contactId)));
        // The address is corrected while the row waits; the send must use
        // the corrected one, which is why resolution happens at claim.
        jdbc.update("UPDATE ob_client_contacts SET email = 'priya.menon@horizon.test' WHERE id = ?", contactId);

        dispatcher.pollOnce();

        ObOutboxMessage seen = TestAdapters.IN_APP.lastSeen();
        assertThat(seen.id()).isEqualTo(id);
        assertThat(seen.recipient()).isEqualTo(new ObRecipient.Client(contactId));
        assertThat(seen.details().name()).isEqualTo("Priya Menon");
        assertThat(seen.details().email()).isEqualTo("priya.menon@horizon.test");
        assertThat(seen.details().active()).isTrue();
        assertThat(seen.payload()).containsEntry("stepTitle", "Data migration");
        assertThat(seen.obClientId()).isEqualTo(clientId);
        assertThat(seen.journeyId()).isEqualTo(journeyId);
        assertThat(seen.stepId()).isEqualTo(stepId);
        assertThat(row(id).get("status")).isEqualTo("SENT");
    }

    // ── dedupe: the generated column, exercised end to end ───────────────

    @Test
    void theSameEventIsQueuedOnceWhileItWaitsAndAgainOnceItHasLeft() {
        ObNotification reminder = stepEvent("TAT_REMINDER", ObChannel.EMAIL, new ObRecipient.Client(contactId));

        OptionalLong first = enqueuer.enqueue(reminder);
        OptionalLong duplicate = enqueuer.enqueue(reminder);
        assertThat(first).isPresent();
        assertThat(duplicate).isEmpty();
        assertThat(count()).isEqualTo(1);

        dispatcher.pollOnce();
        assertThat(row(first.getAsLong()).get("status")).isEqualTo("SENT");

        // A genuine repeat later — the second reminder — is allowed.
        OptionalLong later = enqueuer.enqueue(reminder);
        assertThat(later).isPresent();
        assertThat(later.getAsLong()).isNotEqualTo(first.getAsLong());
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void aRowInFlightStillHoldsItsDedupeSlot() {
        ObNotification reminder = stepEvent("TAT_REMINDER", ObChannel.EMAIL, new ObRecipient.Client(contactId));
        long id = enqueue(reminder);

        List<ObOutboxMessage> claimed = repository.claimBatch(
                EnumSet.of(ObChannel.EMAIL), 10, Duration.ofMinutes(2), clock.instant());
        assertThat(claimed).extracting(ObOutboxMessage::id).containsExactly(id);
        assertThat(row(id).get("status")).isEqualTo("SENDING");

        assertThat(enqueuer.enqueue(reminder)).isEmpty();
    }

    // ── claiming: due, leased, disjoint ──────────────────────────────────

    @Test
    void claimsOnlyRowsThatArePendingAndDue() {
        long due = enqueue(stepEvent("A", ObChannel.EMAIL, new ObRecipient.Staff(staffId)));
        long later = enqueue(stepEvent("B", ObChannel.EMAIL, new ObRecipient.Staff(staffId)));
        jdbc.update("UPDATE ob_notification_outbox SET next_attempt_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().plus(Duration.ofMinutes(10))), later);
        long failed = enqueue(stepEvent("C", ObChannel.EMAIL, new ObRecipient.Staff(staffId)));
        jdbc.update("UPDATE ob_notification_outbox SET status = 'FAILED' WHERE id = ?", failed);
        long cancelled = enqueue(stepEvent("D", ObChannel.EMAIL, new ObRecipient.Staff(staffId)));
        jdbc.update("UPDATE ob_notification_outbox SET status = 'CANCELLED' WHERE id = ?", cancelled);

        List<ObOutboxMessage> claimed = repository.claimBatch(
                EnumSet.of(ObChannel.EMAIL), 10, Duration.ofMinutes(2), clock.instant());

        assertThat(claimed).extracting(ObOutboxMessage::id).containsExactly(due);
        assertThat(row(due).get("status")).isEqualTo("SENDING");
        assertThat(instant(row(due), "next_attempt_at")).isEqualTo(clock.instant().plus(Duration.ofMinutes(2)));
    }

    @Test
    void aClaimedRowIsInvisibleToTheNextPollUntilItsLeaseLapses() {
        long id = enqueue(stepEvent("A", ObChannel.EMAIL, new ObRecipient.Staff(staffId)));
        Duration lease = Duration.ofMinutes(2);

        assertThat(repository.claimBatch(EnumSet.of(ObChannel.EMAIL), 10, lease, clock.instant())).hasSize(1);
        assertThat(repository.claimBatch(EnumSet.of(ObChannel.EMAIL), 10, lease, clock.instant())).isEmpty();
        assertThat(row(id).get("status")).isEqualTo("SENDING");

        // The worker that claimed it dies. The lease lapses, the next poll
        // reclaims the row and delivers it.
        clock.advance(lease.plusSeconds(1));
        assertThat(dispatcher.pollOnce()).isEqualTo(1);
        assertThat(row(id).get("status")).isEqualTo("SENT");
    }

    @Test
    void aLiveLeaseIsNotReclaimed() {
        long id = enqueue(stepEvent("A", ObChannel.EMAIL, new ObRecipient.Staff(staffId)));
        repository.claimBatch(EnumSet.of(ObChannel.EMAIL), 10, Duration.ofMinutes(2), clock.instant());

        clock.advance(Duration.ofMinutes(1));
        assertThat(repository.reclaimExpiredLeases(clock.instant())).isZero();
        assertThat(dispatcher.pollOnce()).isZero();
        assertThat(row(id).get("status")).isEqualTo("SENDING");
    }

    @Test
    void aLateStampFromAnExpiredLeaseCannotOverwriteTheReclaimedRow() {
        long id = enqueue(stepEvent("A", ObChannel.EMAIL, new ObRecipient.Staff(staffId)));
        repository.claimBatch(EnumSet.of(ObChannel.EMAIL), 10, Duration.ofMinutes(2), clock.instant());

        clock.advance(Duration.ofMinutes(3));
        dispatcher.pollOnce();                    // reclaimed and SENT by "another worker"
        assertThat(row(id).get("status")).isEqualTo("SENT");

        // The first worker wakes up and tries to stamp its own, stale, outcome.
        repository.markForRetry(id, 1, clock.instant(), "stale");
        repository.markFailed(id, 1, "stale", clock.instant());

        assertThat(row(id).get("status")).isEqualTo("SENT");
        assertThat(row(id).get("last_error")).isNull();
    }

    @Test
    void concurrentClaimsNeverOverlap() throws Exception {
        int rows = 40;
        for (int i = 0; i < rows; i++) {
            enqueue(stepEvent("EVT_" + i, ObChannel.EMAIL, new ObRecipient.Staff(staffId)));
        }

        int workers = 4;
        CountDownLatch start = new CountDownLatch(1);
        List<List<Long>> perWorker = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            for (int w = 0; w < workers; w++) {
                pool.submit(() -> {
                    start.await();
                    List<Long> mine = new ArrayList<>();
                    while (true) {
                        List<ObOutboxMessage> batch = repository.claimBatch(
                                EnumSet.of(ObChannel.EMAIL), 5, Duration.ofMinutes(2), clock.instant());
                        if (batch.isEmpty()) {
                            break;
                        }
                        batch.forEach(m -> mine.add(m.id()));
                    }
                    perWorker.add(mine);
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        Set<Long> union = new HashSet<>();
        int total = 0;
        for (List<Long> mine : perWorker) {
            total += mine.size();
            union.addAll(mine);
        }
        assertThat(total).isEqualTo(rows);
        assertThat(union).hasSize(rows);
    }

    // ── retry, exhaustion, permanent failure ─────────────────────────────

    @Test
    void aTransientFailureIsRescheduledWithExponentialBackoff() {
        TestAdapters.IN_APP.outcome = m -> new DeliveryOutcome.TransientFailure("bell offline");
        long id = enqueue(stepEvent("GATE_OPENED", ObChannel.IN_APP, new ObRecipient.Staff(staffId)));

        dispatcher.pollOnce();
        Map<String, Object> afterFirst = row(id);
        assertThat(afterFirst.get("status")).isEqualTo("PENDING");
        assertThat(afterFirst.get("attempts")).isEqualTo(1);
        assertThat(afterFirst.get("last_error")).isEqualTo("bell offline");
        assertThat(instant(afterFirst, "next_attempt_at"))
                .isEqualTo(clock.instant().plus(shippedProperties.backoffFor(1)));

        // Not due yet.
        assertThat(dispatcher.pollOnce()).isZero();

        clock.advance(shippedProperties.backoffFor(1));
        dispatcher.pollOnce();
        Map<String, Object> afterSecond = row(id);
        assertThat(afterSecond.get("attempts")).isEqualTo(2);
        assertThat(instant(afterSecond, "next_attempt_at"))
                .isEqualTo(clock.instant().plus(shippedProperties.backoffFor(2)));
        assertThat(shippedProperties.backoffFor(2)).isEqualTo(shippedProperties.backoffFor(1).multipliedBy(2));
    }

    @Test
    void retriesAreExhaustedIntoFailedAndTheStaffRecipientIsToldInApp() {
        TestAdapters.IN_APP.outcome = m -> new DeliveryOutcome.TransientFailure("bell offline");
        long id = enqueue(stepEvent("GATE_OPENED", ObChannel.IN_APP, new ObRecipient.Staff(staffId)));

        for (int attempt = 1; attempt <= shippedProperties.maxAttempts(); attempt++) {
            dispatcher.pollOnce();
            clock.advance(shippedProperties.backoffCap());
        }

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("attempts")).isEqualTo(shippedProperties.maxAttempts());
        assertThat(row.get("failed_at")).isNotNull();
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("queued_dedupe_key")).isNull();
        assertThat(TestAdapters.IN_APP.calls.get()).isEqualTo(shippedProperties.maxAttempts());

        List<Map<String, Object>> bell = notifications();
        assertThat(bell).hasSize(1);
        assertThat(bell.getFirst().get("user_id")).isEqualTo(staffId);
        assertThat(bell.getFirst().get("event_code")).isEqualTo("MAIL_DELIVERY_FAILED");
        assertThat(bell.getFirst().get("title")).isEqualTo(ObDeliveryFailureNotifier.TITLE);
        assertThat(bell.getFirst().get("link_url")).isEqualTo("/onboarding/clients/" + clientId);
        assertThat((String) bell.getFirst().get("body")).contains("after 3 retries").contains("bell offline");

        // Nothing further happens to it.
        clock.advance(shippedProperties.backoffCap());
        assertThat(dispatcher.pollOnce()).isZero();
    }

    @Test
    void theShippedConfigurationIsThreeRetries() {
        assertThat(shippedProperties.maxAttempts()).isEqualTo(4);
    }

    @Test
    void aPermanentFailureForAClientContactFailsAtOnceAndTellsTheAdmins() {
        long admin = insertUser("root", "ADMIN");
        TestAdapters.IN_APP.outcome = m -> new DeliveryOutcome.PermanentFailure("no portal login");
        long id = enqueue(stepEvent("SIGNOFF_REQUESTED", ObChannel.IN_APP, new ObRecipient.Client(contactId)));

        dispatcher.pollOnce();

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("last_error")).isEqualTo("no portal login");
        assertThat(TestAdapters.IN_APP.calls.get()).isEqualTo(1);

        List<Map<String, Object>> bell = notifications();
        assertThat(bell).hasSize(1);
        assertThat(bell.getFirst().get("user_id")).isEqualTo(admin);
        assertThat((String) bell.getFirst().get("body"))
                .contains("priya@horizon.test").doesNotContain("retries");
    }

    @Test
    void anAdapterThatThrowsIsRetriedNotLost() {
        TestAdapters.IN_APP.outcome = m -> {
            throw new IllegalStateException("bug");
        };
        long id = enqueue(stepEvent("GATE_OPENED", ObChannel.IN_APP, new ObRecipient.Staff(staffId)));

        dispatcher.pollOnce();

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("last_error")).isEqualTo("IllegalStateException: bug");
    }

    // ── the channel-agnostic refusals ────────────────────────────────────

    @Test
    void aDeactivatedContactIsFailedWithoutTheAdapterBeingAsked() {
        long id = enqueue(stepEvent("TAT_REMINDER", ObChannel.IN_APP, new ObRecipient.Client(contactId)));
        jdbc.update("UPDATE ob_client_contacts SET is_active = 0 WHERE id = ?", contactId);

        dispatcher.pollOnce();

        assertThat(TestAdapters.IN_APP.calls.get()).isZero();
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat((String) row.get("last_error")).contains("inactive");
    }

    @Test
    void aDeactivatedUserIsFailedWithoutTheAdapterBeingAsked() {
        long id = enqueue(stepEvent("GATE_OPENED", ObChannel.IN_APP, new ObRecipient.Staff(staffId)));
        jdbc.update("UPDATE users SET is_active = 0 WHERE id = ?", staffId);

        dispatcher.pollOnce();

        assertThat(TestAdapters.IN_APP.calls.get()).isZero();
        assertThat(row(id).get("status")).isEqualTo("FAILED");
    }

    @Test
    void aSuppressedAddressIsNeverEmailed() {
        suppressions.suppress("priya@horizon.test", EmailSuppressions.SuppressionReason.BOUNCE, "550", null);
        long id = enqueue(stepEvent("TAT_REMINDER", ObChannel.EMAIL, new ObRecipient.Client(contactId)));

        dispatcher.pollOnce();

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("provider_message_id")).isNull();
        assertThat((String) row.get("last_error")).contains("suppressed");
    }

    // ── the deferral is not a one-way door ───────────────────────────────

    @Test
    void aRowForAChannelWithNoAdapterWaitsRatherThanFailing() {
        long id = enqueue(stepEvent("SIGNOFF_REQUESTED", ObChannel.WHATSAPP, new ObRecipient.Client(contactId)));

        assertThat(adapters.supported()).doesNotContain(ObChannel.WHATSAPP);
        assertThat(dispatcher.pollOnce()).isZero();
        clock.advance(Duration.ofDays(30));
        assertThat(dispatcher.pollOnce()).isZero();

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(repository.unroutableByChannel(adapters.supported())).containsEntry("WHATSAPP", 1);

        // The adapter arrives (D-101): one class, and the queue drains.
        ScriptedAdapter whatsapp = new ScriptedAdapter(ObChannel.WHATSAPP);
        ObOutboxDispatcher withWhatsapp = new ObOutboxDispatcher(repository,
                new ChannelAdapterRegistry(List.of(whatsapp)), shippedProperties, failureNotifier, clock);
        assertThat(withWhatsapp.pollOnce()).isEqualTo(1);
        assertThat(row(id).get("status")).isEqualTo("SENT");
        assertThat(whatsapp.lastSeen().details().whatsappOptIn()).isTrue();
    }

    // ── the transactional guarantee ──────────────────────────────────────

    @Test
    void enqueueRollsBackWithTheBusinessTransaction() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            enqueuer.enqueue(stepEvent("SIGNOFF_REQUESTED", ObChannel.EMAIL, new ObRecipient.Client(contactId)));
            throw new IllegalStateException("business rule failed after enqueue");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count()).isZero();
    }

    @Test
    void enqueueCommitsWithTheBusinessTransactionAndIsImmediatelyClaimable() {
        Long id = transactions.execute(status ->
                enqueuer.enqueue(stepEvent("SIGNOFF_REQUESTED", ObChannel.EMAIL, new ObRecipient.Client(contactId)))
                        .orElseThrow());

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("recipient_type")).isEqualTo("CLIENT");
        assertThat(row.get("recipient_contact_id")).isEqualTo(contactId);
        assertThat(row.get("recipient_user_id")).isNull();
        assertThat(row.get("dedupe_key")).isEqualTo("SIGNOFF_REQUESTED:EMAIL:step:" + stepId + ":contact:" + contactId);
        assertThat(row.get("journey_id")).isEqualTo(journeyId);
        assertThat(row.get("step_id")).isEqualTo(stepId);
        assertThat(instant(row, "next_attempt_at")).isEqualTo(clock.instant());
        assertThat(dispatcher.pollOnce()).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesARowNamingBothOrNeitherRecipient() {
        // Belt and braces: the sealed type makes this unreachable through the
        // enqueuer, and the CHECK makes it unreachable through anything else.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ob_notification_outbox
                       (event_key, channel, recipient_type, recipient_user_id, recipient_contact_id, dedupe_key)
                VALUES ('X', 'EMAIL', 'STAFF', ?, ?, 'both')
                """, staffId, contactId))
                .hasMessageContaining("ck_ob_outbox_recipient");
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private ObNotification stepEvent(String eventKey, ObChannel channel, ObRecipient recipient) {
        return ObNotification.aboutStep(eventKey, channel, recipient, clientId, journeyId, stepId,
                Map.of("stepTitle", "Data migration", "dueOn", "2026-09-10"));
    }

    private long enqueue(ObNotification notification) {
        return enqueuer.enqueue(notification).orElseThrow();
    }

    /** Roles are seeded by migration; users are not, so tests make their own. */
    private long insertUser(String username, String roleCode) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", Long.class, roleCode);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", username, roleId);
        return lastInsertId();
    }

    private long insertClient(String name) {
        jdbc.update("INSERT INTO ob_clients (name, onboarding_date) VALUES (?, CURRENT_DATE)", name);
        return lastInsertId();
    }

    private long insertContact(long client, String name, String email) {
        jdbc.update("""
                INSERT INTO ob_client_contacts (ob_client_id, name, email, phone, whatsapp_opt_in, is_primary)
                VALUES (?, ?, ?, '+91 98765 43210', 1, 1)
                """, client, name, email);
        return lastInsertId();
    }

    private long lastInsertId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM ob_notification_outbox WHERE id = ?", id);
    }

    private List<Map<String, Object>> notifications() {
        return jdbc.queryForList("SELECT * FROM notifications ORDER BY id");
    }

    private int count() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM ob_notification_outbox", Integer.class);
        return c == null ? 0 : c;
    }

    private static Instant instant(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.toInstant(ZoneOffset.UTC);
        }
        throw new AssertionError(column + " is a " + value.getClass());
    }

    /** An adapter the tests steer. Registered on IN_APP by default. */
    static final class ScriptedAdapter implements ObChannelAdapter {
        private final ObChannel channel;
        final AtomicInteger calls = new AtomicInteger();
        volatile Function<ObOutboxMessage, DeliveryOutcome> outcome = m -> new DeliveryOutcome.Sent("scripted");
        private volatile ObOutboxMessage last;

        ScriptedAdapter() {
            this(ObChannel.IN_APP);
        }

        ScriptedAdapter(ObChannel channel) {
            this.channel = channel;
        }

        void reset() {
            calls.set(0);
            outcome = m -> new DeliveryOutcome.Sent("scripted");
            last = null;
        }

        ObOutboxMessage lastSeen() {
            return last;
        }

        @Override
        public ObChannel channel() {
            return channel;
        }

        @Override
        public DeliveryOutcome deliver(ObOutboxMessage message) {
            calls.incrementAndGet();
            last = message;
            return outcome.apply(message);
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
