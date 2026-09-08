package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentDtos;
import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentScanner;
import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * B-107 · client documents against real MySQL.
 *
 * <p>What earns a container, given that {@code ObClientAttachmentServiceTest}
 * covers every decision made in Java:
 *
 * <ol>
 *   <li><b>A-102's four CHECK constraints.</b> {@code ck_ob_attachments_one_owner},
 *       {@code ck_ob_attachments_uploader} and {@code ck_ob_attachments_deleted}
 *       are the reason the migration chose a column per owner over an
 *       unconstrained {@code owner_type}/{@code owner_id} pair. A service that
 *       writes rows the columns would have refused is a service whose guarantee
 *       is a convention — so the insert and the tombstone are both run through
 *       the real table.</li>
 *   <li><b>That the tombstone really keeps the row.</b> "Removed by
 *       deactivation, never by DELETE" is the migration's own instruction and
 *       the whole reason OB-05 can render "file removed by X on date". Only an
 *       end-to-end removal can show the row still resolves afterwards.</li>
 *   <li><b>The scan sealing a row it did not insert.</b> The AV verdict lands on
 *       a different thread in a different transaction, which is exactly the
 *       arrangement a mock cannot represent — and the moment a
 *       {@code downloadUrl} appears is the moment the file becomes readable.</li>
 *   <li><b>That an attachment id under another client is invisible.</b> The
 *       ids are drawn from a sequence shared with every step's and sign-off's
 *       file, so this is the query that has to be right rather than the
 *       comment.</li>
 * </ol>
 *
 * <p>{@link AttachmentStorage} and {@link AttachmentScanner} are mocked — a
 * MinIO container would prove S3AttachmentStorage rather than anything about
 * this feature, and C-025's own {@code AttachmentStorageSecurityTest} already
 * owns that ground. What is real here is the table.
 *
 * <p>Fixtures use usernames and product codes no seed migration will claim, on
 * {@code ObClientsIT}'s own precedent. Nothing is torn down — one suffix per
 * test, {@code ObContactsIT}'s reasoning.
 */
@SpringBootTest
@Testcontainers
class ObClientAttachmentsIT {

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

    private static final LocalDate BOARDED = LocalDate.of(2026, 9, 7);

    /** A one-page PDF's magic bytes, which is all {@code AttachmentSniffer} reads. */
    private static final byte[] PDF = "%PDF-1.7\nmock document body\n%%EOF".getBytes(StandardCharsets.UTF_8);

    @Autowired
    ObClientWriteService clientWrites;

    @Autowired
    ObClientAttachmentService attachments;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AttachmentStorage storage;

    @MockitoBean
    AttachmentScanner scanner;

    private static final AtomicInteger RUN = new AtomicInteger();

    private long ayush;
    private long colleague;
    private long clientId;
    private long otherClientId;
    private ObClientScope admin;
    private ObClientScope sales;

    @BeforeEach
    void seed() {
        int run = RUN.incrementAndGet();

        ayush = insertUser("it_obatt_a_" + run);
        colleague = insertUser("it_obatt_b_" + run);
        admin = new ObClientScope(ObClientScope.OB_ADMIN, ayush);
        sales = new ObClientScope(ObClientScope.OB_SALES, colleague);

        long product = insertProduct("ITATT_A_" + run, "IT Att Product A " + run);
        insertTemplate(product, "IT Att Onboarding A " + run);

        clientId = boardClient("IT Att Client " + run, product, run, "one");
        otherClientId = boardClient("Another IT Att Client " + run, product, run, "two");

        when(storage.signedDownloadUrl(any(), anyString(), anyString(), any()))
                .thenReturn(URI.create("https://minio.local/signed"));
    }

    // ── the insert the CHECK constraints have to accept ─────────────────────

    /**
     * The one owner arm, the one uploader arm, and PENDING with no URL.
     *
     * <p>Read back through {@code information_schema}-adjacent SQL rather than
     * through the service's own view, because what is under test is the row the
     * column constraints accepted rather than the shape the assembler produced.
     */
    @Test
    @DisplayName("an upload lands one owner column, one uploader column, and nothing readable")
    void anUploadSatisfiesTheOwnerAndUploaderChecks() {
        ObAttachmentDtos.ObAttachmentView view =
                attachments.upload(admin, clientId, null, "msa.pdf", PDF);

        assertThat(view.scanStatus()).isEqualTo("PENDING");
        assertThat(view.downloadUrl()).isNull();
        assertThat(view.kind()).isEqualTo("SUBMISSION");
        assertThat(view.isDeleted()).isFalse();

        Map<String, Object> row = rowOf(view.id());
        assertThat(row.get("ob_client_id")).isEqualTo(clientId);
        assertThat(row.get("step_id")).isNull();
        assertThat(row.get("signoff_id")).isNull();
        assertThat(row.get("prereq_template_task_id")).isNull();
        assertThat(row.get("uploaded_by_type")).isEqualTo("STAFF");
        assertThat(row.get("uploaded_by_user")).isEqualTo(ayush);
        assertThat(row.get("uploaded_by_contact")).isNull();
        // The sniffed type, and the namespaced key. Both are what the bucket and
        // the browser are told, so both are worth reading back from the column.
        assertThat(row.get("content_type")).isEqualTo("application/pdf");
        assertThat((String) row.get("storage_key")).startsWith("onboarding/clients/" + clientId + "/");
        // The column A-102 put there for re-upload detection: 64 hex characters,
        // over the stored bytes.
        assertThat((String) row.get("content_sha256")).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(row.get("scanned_at")).isNull();
    }

    /**
     * The category boundary the database cannot draw — its CHECK constrains
     * which owner column is set and says nothing about which kinds go with which
     * owner, so the service is the only thing standing between a client document
     * and an {@code EVIDENCE} row B-116's reader could never find.
     */
    @Test
    @DisplayName("refuses a kind belonging to another owner arm, and writes nothing")
    void refusesAForeignKind() {
        int before = count("SELECT COUNT(*) FROM ob_attachments");

        assertThatThrownBy(() -> attachments.upload(admin, clientId, "EVIDENCE", "x.pdf", PDF))
                .isInstanceOf(ObClientValidationException.class);

        assertThat(count("SELECT COUNT(*) FROM ob_attachments")).isEqualTo(before);
    }

    // ── the scan, which is what makes a file readable ───────────────────────

    /**
     * The moment a {@code downloadUrl} appears is the moment the file becomes
     * readable, and it is reached by a different thread in a different
     * transaction. Driven here by sealing the column the way the scan task does,
     * so the assertion is about the read path rather than about the executor.
     */
    @Test
    @DisplayName("a CLEAN row grows a download URL; a PENDING one never has")
    void onlyACleanRowIsReadable() {
        long pending = attachments.upload(admin, clientId, null, "pending.pdf", PDF).id();
        long clean = attachments.upload(admin, clientId, null, "clean.pdf", PDF).id();
        seal(clean);

        Map<Long, ObAttachmentDtos.ObAttachmentView> byId = listed();

        assertThat(byId.get(pending).downloadUrl()).isNull();
        assertThat(byId.get(clean).downloadUrl()).isNotNull();
    }

    /**
     * PENDING and INFECTED rows are returned rather than hidden: hiding them
     * would make a scan delay indistinguishable from a failed upload and would
     * leave somebody re-attaching the same file.
     */
    @Test
    @DisplayName("an INFECTED row is listed, and is not downloadable")
    void infectedRowsAreListedAndUnreadable() {
        long id = attachments.upload(admin, clientId, null, "infected.pdf", PDF).id();
        jdbc.update("UPDATE ob_attachments SET scan_status = 'INFECTED', scanned_at = NOW(6) "
                + "WHERE id = ?", id);

        ObAttachmentDtos.ObAttachmentView view = listed().get(id);

        assertThat(view).isNotNull();
        assertThat(view.scanStatus()).isEqualTo("INFECTED");
        assertThat(view.downloadUrl()).isNull();
    }

    // ── removal, which never removes the row ────────────────────────────────

    /**
     * A-102's instruction, exercised rather than quoted: "removed by
     * deactivation, never by DELETE — a file referenced by a sign-off or a
     * completed step is evidence, and the row has to keep resolving".
     */
    @Test
    @DisplayName("removal keeps the row, stamps both tombstone columns, and drops the URL")
    void removalKeepsTheRow() {
        long id = attachments.upload(admin, clientId, null, "wrong.pdf", PDF).id();
        seal(id);

        attachments.delete(admin, clientId, id);

        Map<String, Object> row = rowOf(id);
        assertThat(row).isNotNull();
        // ck_ob_attachments_deleted refuses a stamp without an actor, so this
        // pair is the column's rule and not only the service's.
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("deleted_by")).isEqualTo(ayush);
        // And the file stops being readable the moment it is tombstoned, even
        // though the scan said CLEAN.
        assertThat(count("SELECT COUNT(*) FROM ob_attachments WHERE id = " + id)).isEqualTo(1);
    }

    /**
     * The uploader fixing their own mistake promptly leaves nothing behind;
     * anybody else always does. Both halves in one test, because the difference
     * is the rule.
     */
    @Test
    @DisplayName("the uploader's own prompt removal is silent; somebody else's is not")
    void tombstoneVisibilityFollowsWhoRemovedIt() {
        long mine = attachments.upload(admin, clientId, null, "mine.pdf", PDF).id();
        long theirs = attachments.upload(sales, clientId, null, "theirs.pdf", PDF).id();

        attachments.delete(admin, clientId, mine);
        attachments.delete(admin, clientId, theirs);

        Map<Long, ObAttachmentDtos.ObAttachmentView> byId = listed();

        assertThat(byId).doesNotContainKey(mine);
        assertThat(byId.get(theirs).isDeleted()).isTrue();
        assertThat(byId.get(theirs).deletedBy()).isNotNull();
        assertThat(byId.get(theirs).downloadUrl()).isNull();
    }

    @Test
    @DisplayName("a second removal changes nothing, including who removed it")
    void removalIsIdempotent() {
        long id = attachments.upload(sales, clientId, null, "twice.pdf", PDF).id();
        attachments.delete(admin, clientId, id);
        Object firstStamp = rowOf(id).get("deleted_at");

        attachments.delete(admin, clientId, id);

        assertThat(rowOf(id).get("deleted_at")).isEqualTo(firstStamp);
        assertThat(rowOf(id).get("deleted_by")).isEqualTo(ayush);
    }

    /**
     * Sales may file a document and may not remove a colleague's. 403 rather
     * than 404, because the caller is looking at the row in a listing they just
     * fetched.
     */
    @Test
    @DisplayName("Sales cannot remove a document somebody else filed")
    void salesCannotRemoveAnothersDocument() {
        long id = attachments.upload(admin, clientId, null, "admins.pdf", PDF).id();

        assertThatThrownBy(() -> attachments.delete(sales, clientId, id))
                .isInstanceOf(ObClientAttachmentRemovalNotPermittedException.class);

        assertThat(rowOf(id).get("deleted_at")).isNull();
    }

    // ── the ids are shared with the whole module ────────────────────────────

    /**
     * {@code ob_attachments} ids are drawn from a sequence shared with every
     * journey step's and sign-off's file, so this is the query that has to be
     * right rather than the comment above it.
     */
    @Test
    @DisplayName("an attachment under another client is invisible from this one")
    void anotherClientsAttachmentIsInvisible() {
        long theirs = attachments.upload(admin, otherClientId, null, "theirs.pdf", PDF).id();

        assertThat(listed()).doesNotContainKey(theirs);
        assertThatThrownBy(() -> attachments.delete(admin, clientId, theirs))
                .isInstanceOf(ObClientAttachmentNotFoundException.class);

        // And the row is untouched — a 404 must not have been a delete that
        // happened to answer badly.
        assertThat(rowOf(theirs).get("deleted_at")).isNull();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Map<Long, ObAttachmentDtos.ObAttachmentView> listed() {
        return attachments.list(admin, clientId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ObAttachmentDtos.ObAttachmentView::id, view -> view));
    }

    /** What {@code ObAttachmentScanTask} does on a CLEAN verdict, without the executor. */
    private void seal(long attachmentId) {
        jdbc.update("UPDATE ob_attachments SET scan_status = 'CLEAN', scanned_at = NOW(6) "
                + "WHERE id = ?", attachmentId);
    }

    private Map<String, Object> rowOf(long attachmentId) {
        return jdbc.queryForMap("SELECT * FROM ob_attachments WHERE id = ?", attachmentId);
    }

    private long boardClient(String name, long product, int run, String suffix) {
        // Created BY the Sales user, deliberately: ObClientScope reduces Sales
        // to the clients they created, so a fixture boarded by anybody else
        // would make every Sales assertion below a 404 about visibility rather
        // than the thing it is trying to say.
        return clientWrites.create(admin, colleague,
                new ObClientDtos.ObClientCreateRequest(
                        name, null, BOARDED, null, null, null, null,
                        List.of(new ObClientDtos.ObContactWriteRequest(
                                "Founding SPOC", "Principal",
                                "attfounder" + suffix + run + "@example.com",
                                "+911111111111", false, null, true)),
                        List.of(new ObClientDtos.ObApplicationWriteRequest(
                                product, "ANNUAL", 10, BOARDED, BOARDED.plusYears(1))),
                        List.of(),
                        // acknowledgeSimilarNames, on ObApplicationsIT's reason:
                        // these fixture names are exactly what B-102's fuzzy name
                        // guard exists to stop, and that is its own subject.
                        false, true))
                .id();
    }

    private long insertUser(String username) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", username, roleId);
        return idOfLastInsert();
    }

    private long insertProduct(String code, String name) {
        jdbc.update("INSERT INTO ob_products (code, name, is_active) VALUES (?, ?, 1)", code, name);
        return idOfLastInsert();
    }

    private void insertTemplate(long productId, String name) {
        jdbc.update("""
                INSERT INTO ob_journey_templates (product_id, name, version, is_active, sequence)
                VALUES (?, ?, 1, 1, 0)
                """, productId, name);
        long templateId = idOfLastInsert();
        jdbc.update("""
                INSERT INTO ob_journey_template_steps (template_id, sequence, name, tat_days)
                VALUES (?, 1, 'Kickoff', 2), (?, 2, 'Configuration', 3)
                """, templateId, templateId);
    }

    private long idOfLastInsert() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
