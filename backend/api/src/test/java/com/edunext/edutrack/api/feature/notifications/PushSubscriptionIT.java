package com.edunext.edutrack.api.feature.notifications;

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

import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-045 · who has agreed to be interrupted, against real MySQL.
 *
 * <p>The interesting behaviour here is not storage, it is <em>ownership</em>:
 * an endpoint identifies a browser rather than an account, and the rules that
 * follow from that are the ones worth pinning down.
 */
@SpringBootTest
@Testcontainers
class PushSubscriptionIT {

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

    /** A real 65-byte uncompressed P-256 point and a real 16-byte secret. */
    private static final String P256DH = base64Url(new byte[65]);
    private static final String AUTH = base64Url(new byte[16]);
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc123";

    @Autowired
    PushSubscriptionService push;

    @Autowired
    JdbcTemplate jdbc;

    private long ravi;
    private long meera;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM push_subscriptions");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_push_%'");
        ravi = insertUser("it_push_ravi");
        meera = insertUser("it_push_meera");
    }

    // ------------------------------------------------------------ the basics

    @Test
    @DisplayName("a browser subscribes and the row is stored against its user")
    void aBrowserSubscribes() {
        assertThat(subscribe(ravi, ENDPOINT, "Firefox on Ubuntu")).isEmpty();

        assertThat(ownerOf(ENDPOINT)).isEqualTo(ravi);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("subscribing twice from the same browser is one subscription, not two")
    void resubscribingIsIdempotent() {
        subscribe(ravi, ENDPOINT, "Firefox");
        subscribe(ravi, ENDPOINT, "Firefox");

        // The endpoint is the browser. A second POST is the same browser saying
        // the same thing — which is why this endpoint answers 204 and takes no
        // idempotency key rather than minting a resource each time.
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("one person on two devices has two subscriptions")
    void twoDevicesAreTwoRows() {
        subscribe(ravi, ENDPOINT, "Laptop");
        subscribe(ravi, ENDPOINT + "-phone", "Phone");

        assertThat(rowCount()).isEqualTo(2);
    }

    // --------------------------------------------------------- whose browser

    @Test
    @DisplayName("a second user on the same browser takes the subscription over")
    void resubscribingOnASharedBrowserMovesIt() {
        subscribe(ravi, ENDPOINT, "Shared desk machine");

        subscribe(meera, ENDPOINT, "Shared desk machine");

        // This is the whole reason the unique key is the endpoint alone. Leaving
        // Ravi's row in place would push his ticket alerts — title and all —
        // onto the screen Meera is now sitting at.
        assertThat(ownerOf(ENDPOINT)).isEqualTo(meera);
        assertThat(rowCount()).as("moved, not duplicated").isEqualTo(1);
    }

    @Test
    @DisplayName("unsubscribing somebody else's browser does nothing")
    void youCannotUnsubscribeAnotherUsersBrowser() {
        subscribe(ravi, ENDPOINT, "Ravi's laptop");

        push.unsubscribe(meera, ENDPOINT);

        assertThat(ownerOf(ENDPOINT))
                .as("scoped to the caller, so guessing an endpoint achieves nothing")
                .isEqualTo(ravi);
    }

    @Test
    @DisplayName("unsubscribing an endpoint that was never here is not an error")
    void unsubscribingAnUnknownEndpointIsQuiet() {
        push.unsubscribe(ravi, "https://fcm.googleapis.com/fcm/send/never-seen");

        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("deleting the user takes their subscriptions with them")
    void subscriptionsDieWithTheAccount() {
        subscribe(ravi, ENDPOINT, "Laptop");

        jdbc.update("DELETE FROM users WHERE id = ?", ravi);

        // A per-device identifier that outlived the account it belonged to
        // would be a record nobody has a reason to keep.
        assertThat(rowCount()).isZero();
    }

    // ------------------------------------------------------------ validation

    @Test
    @DisplayName("a p256dh of the wrong length is rejected at the door")
    void aMalformedPublicKeyIsRejected() {
        // Decodes fine, wrong size. Accepting it would store a subscription that
        // fails inside the ECDH at send time, where the only symptom is a push
        // that never arrives — indistinguishable from a browser that went away.
        Optional<String> failure = push.subscribe(ravi, request(ENDPOINT, base64Url(new byte[64]), AUTH, null));

        assertThat(failure).contains("keys.p256dh");
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("an auth secret that is not 16 bytes is rejected")
    void aMalformedAuthSecretIsRejected() {
        assertThat(push.subscribe(ravi, request(ENDPOINT, P256DH, base64Url(new byte[12]), null)))
                .contains("keys.auth");
    }

    @Test
    @DisplayName("something that is not base64url at all is rejected, not thrown")
    void garbageIsRejectedNotThrown() {
        assertThat(push.subscribe(ravi, request(ENDPOINT, "not base64!!", AUTH, null)))
                .contains("keys.p256dh");
    }

    @Test
    @DisplayName("a missing endpoint is rejected")
    void anEmptyEndpointIsRejected() {
        assertThat(push.subscribe(ravi, request("  ", P256DH, AUTH, null))).contains("endpoint");
    }

    @Test
    @DisplayName("an overlong user agent is truncated rather than refused")
    void anOverlongUserAgentIsTruncated() {
        // The label exists so somebody can recognise their own laptop. Denying
        // push to whichever browser is most verbose about itself would be a
        // poor trade for a display string.
        String verbose = "M".repeat(400);

        assertThat(subscribe(ravi, ENDPOINT, verbose)).isEmpty();
        assertThat(userAgentOf(ENDPOINT)).hasSize(255);
    }

    // --------------------------------------------------------------- the key

    @Test
    @DisplayName("with no VAPID pair configured, there is no key to serve")
    void anUnconfiguredDeploymentHasNoKey() {
        // The test profile sets none, which is also the state of a fresh
        // developer machine — and the endpoint answers 404 rather than an empty
        // string a browser would happily subscribe with and then hear nothing.
        assertThat(push.publicKey()).isEmpty();
    }

    // ------------------------------------------------------------- helpers

    private Optional<String> subscribe(long userId, String endpoint, String userAgent) {
        return push.subscribe(userId, request(endpoint, P256DH, AUTH, userAgent));
    }

    private static PushDtos.PushSubscriptionRequest request(
            String endpoint, String p256dh, String auth, String userAgent) {
        return new PushDtos.PushSubscriptionRequest(
                endpoint, new PushDtos.PushSubscriptionRequest.Keys(p256dh, auth), userAgent);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private long insertUser(String username) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", username, roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long ownerOf(String endpoint) {
        Long id = jdbc.queryForObject(
                "SELECT user_id FROM push_subscriptions WHERE endpoint = ?", Long.class, endpoint);
        return id == null ? 0 : id;
    }

    private String userAgentOf(String endpoint) {
        return jdbc.queryForObject(
                "SELECT user_agent FROM push_subscriptions WHERE endpoint = ?", String.class, endpoint);
    }

    private int rowCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM push_subscriptions", Integer.class);
        return n == null ? 0 : n;
    }
}
