package com.edunext.edutrack.common.canonical;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-041 · the golden files, and the reason PLAN.md §3.7 asks for them.
 *
 * <p>Every other test in this package asserts a <i>property</i> — that keys come
 * out sorted, that a decimal loses its trailing zeros. Properties cannot catch
 * the failure that actually matters here, because a change to the canonical form
 * changes the property tests and the output together and they stay green. What
 * breaks is every hash already written to the database: A-044's verifier
 * recomputes them under the new rules, they no longer match, and it reports the
 * entire audit log as tampered.
 *
 * <p>These files are the fixed point that makes such a change impossible to land
 * quietly. They are bytes, checked in, produced by a version of this serialiser
 * that we have decided is correct.
 *
 * <h2>Two locks, on purpose</h2>
 *
 * <p>Each case asserts twice: the output equals the checked-in file, <b>and</b>
 * its SHA-256 equals a hex literal in this source. The second is not redundant.
 * The obvious way to make a golden-file test pass is to regenerate the golden
 * file, and it is obvious precisely to someone who has not realised what the
 * test is protecting — the diff looks like an update, not like invalidating
 * every stored hash. Regenerating leaves the digest red, in a file whose next
 * line explains why.
 *
 * <p>So: <b>a failure here is not a test to fix.</b> It means the bytes the
 * chain is computed over have moved. Either the change is wrong, or it is right
 * and needs a payload version and a migration path for the rows already hashed
 * under the old rules. Both digest and file are updated together, deliberately,
 * with that decision written down.
 *
 * <h2>What these payloads are and are not</h2>
 *
 * <p>They are shaped like rows of the three protected tables because a realistic
 * shape exercises realistic types — a {@code DECIMAL(5,2)}, a
 * {@code DATETIME(6)}, a nullable column, a {@code remarks} field with a
 * non-ASCII character in it. They are <b>not</b> the payload contract. Which
 * columns A-042 actually hashes is A-042's to define in {@code domain}, where
 * the entities are; changing that column set does not belong here and will not
 * break these files.
 */
class CanonicalJsonGoldenFileTest {

    @Test
    @DisplayName("a ticket_history row")
    void history() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticket_id", 4711L);
        payload.put("cycle_no", (short) 2);
        payload.put("event_type", "STATUS_CHANGED");
        payload.put("field_name", "status");
        payload.put("old_value", "IN_PROGRESS");
        payload.put("new_value", "IN_QA");
        payload.put("actor_id", 88L);
        payload.put("actor_type", "USER");
        // An em dash, a quote and a newline: the three things a remark picks up
        // from a human being, and the three an escaping table has to get right.
        payload.put("remarks", "Handed off to QA — \"ready\"\nsee note");
        payload.put("is_correction", false);
        payload.put("corrects_entry_id", null);

        assertCanonical("ticket-history",
                "9430620ea0472af33658d095be0620d01df5e74ba355be60ecbc922c31e75e38", payload);
    }

    @Test
    @DisplayName("a ticket_effort_logs row — hours arrive as 8.00 and hash as 8")
    void effortLog() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticket_id", 4711L);
        payload.put("cycle_no", (short) 2);
        payload.put("stage_code", "DEV");
        payload.put("iteration_no", (short) 1);
        payload.put("user_id", 88L);
        payload.put("work_date", LocalDate.of(2026, 8, 14));
        // DECIMAL(5,2). Whoever wrote the row may have built this from "8";
        // MySQL hands the verifier back "8.00". Both must hash the same.
        payload.put("hours", new BigDecimal("8.00"));
        payload.put("note", null);
        payload.put("is_correction", false);
        payload.put("corrects_entry_id", null);

        assertCanonical("effort-log",
                "443fd22b3004596f7ecac6d25b93f0debc5eb34f4ee2e99bb4798f3338f326f6", payload);
    }

    @Test
    @DisplayName("an open ticket_stage_transitions hop")
    void stageTransition() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticket_id", 4711L);
        payload.put("cycle_no", (short) 2);
        payload.put("iteration_no", (short) 1);
        payload.put("seq_no", 7);
        payload.put("from_stage", "DEV");
        payload.put("to_stage", "QA");
        payload.put("from_user_id", 88L);
        payload.put("to_user_id", 91L);
        payload.put("action_code", "HANDOFF");
        payload.put("handoff_note", "Build 2.14 on staging");
        payload.put("reason", null);
        // A whole second, which is the case that catches a trimming formatter:
        // it must still write six zeros.
        payload.put("entered_at", Instant.parse("2026-08-14T09:30:00Z"));
        payload.put("exited_at", null);
        payload.put("duration_mins", null);
        payload.put("is_current", true);

        assertCanonical("stage-transition",
                "971908c2fedd8f2a4230cb34b4ceb1eb3c058684c5a6314eeb73035ff36bec24", payload);
    }

    @Test
    @DisplayName("every formatting trap in one payload")
    void edgeCases() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", 1);
        nested.put("a", Map.of("inner", true));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("astral", "🎫 ticket");
        payload.put("big_integer", new BigInteger("170141183460469231731687303715884105727"));
        payload.put("control_chars", "\b\f\n\r\t" + (char) 0x00 + (char) 0x1f);
        payload.put("date", LocalDate.of(2026, 1, 5));
        payload.put("decimal_padded", new BigDecimal("8.00"));
        payload.put("decimal_scientific", new BigDecimal("1E+3"));
        payload.put("decimal_zero", new BigDecimal("0.00"));
        payload.put("delete_char", "a" + (char) 0x7f + "b");
        payload.put("empty_list", List.of());
        payload.put("empty_object", Map.of());
        payload.put("empty_string", "");
        payload.put("epoch", Instant.EPOCH);
        payload.put("escaped", "quote \" backslash \\ slash /");
        payload.put("list_order", List.of(3, 1, 2));
        payload.put("micros", Instant.parse("2026-08-14T09:30:00.123456Z"));
        payload.put("negative", -42);
        payload.put("nested", nested);
        payload.put("whole_second", Instant.parse("2026-08-14T09:30:00Z"));

        assertCanonical("edge-cases",
                "9334dc0cbe2283696936dcd12b4f7dadae08bdb03f3629aa05cf3c6f403f97da", payload);
    }

    // ------------------------------------------------------------------

    /**
     * The bytes, then the digest of the bytes. See the class javadoc for why
     * both.
     *
     * <p>Compared as {@code byte[]} rather than {@code String} because the
     * charset is part of what is being pinned — a UTF-16 comparison would pass
     * happily on a JVM whose default encoding turns the em dash into a question
     * mark, which is exactly the row the verifier would then fail on.
     */
    private static void assertCanonical(String goldenName, String expectedSha256,
                                        Map<String, ?> payload) {
        byte[] actual = CanonicalJson.bytes(payload);
        byte[] golden = readGolden(goldenName);

        assertThat(new String(actual, StandardCharsets.UTF_8))
                .as("canonical form of %s — if this differs, every row already hashed under the "
                        + "old rules stops verifying", goldenName)
                .isEqualTo(new String(golden, StandardCharsets.UTF_8));
        assertThat(actual)
                .as("byte-for-byte, not merely character-for-character")
                .isEqualTo(golden);
        assertThat(sha256(actual))
                .as("SHA-256 of the canonical form. Regenerating %s.json does not move this "
                        + "literal — if you are here because the golden file was updated, read "
                        + "the class javadoc first", goldenName)
                .isEqualTo(expectedSha256);
    }

    private static byte[] readGolden(String name) {
        String resource = "/canonical/" + name + ".json";
        try (InputStream in = CanonicalJsonGoldenFileTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("golden file %s is missing", resource).isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + resource, e);
        }
    }

    private static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
