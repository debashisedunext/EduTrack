package com.edunext.edutrack.common.canonical;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A-041 · the properties the hash chain depends on, and every refusal.
 *
 * <p>{@link CanonicalJsonGoldenFileTest} pins the exact bytes. This suite pins
 * the reasons they are those bytes — the cases where two callers holding the
 * same row would otherwise produce two different strings.
 *
 * <p>Each rejection is tested for the message as well as the type. The message
 * is the whole value of these exceptions: they fire inside an append, in code
 * the author was not thinking about, and "no canonical form" without the
 * sentence that follows sends someone to read this class instead of fixing
 * their call in ten seconds.
 */
class CanonicalJsonTest {

    @Nested
    @DisplayName("key order")
    class KeyOrder {

        /**
         * The property that matters most, stated as directly as it can be: the
         * journal builds its payload with a {@code LinkedHashMap} in column
         * order and the verifier builds one from a {@code ResultSet} in
         * whatever order it read, and the two must be the same string. Nothing
         * about either call site looks wrong when they are not.
         */
        @Test
        @DisplayName("the map's own iteration order cannot reach the output")
        void isIndependentOfInsertionOrder() {
            Map<String, Object> forwards = new LinkedHashMap<>();
            forwards.put("actor_id", 88L);
            forwards.put("event_type", "ASSIGNED");
            forwards.put("ticket_id", 4711L);

            Map<String, Object> backwards = new LinkedHashMap<>();
            backwards.put("ticket_id", 4711L);
            backwards.put("event_type", "ASSIGNED");
            backwards.put("actor_id", 88L);

            Map<String, Object> hashed = new HashMap<>(forwards);

            Map<String, Object> reverseSorted = new TreeMap<>(Comparator.reverseOrder());
            reverseSorted.putAll(forwards);

            String expected =
                    "{\"actor_id\":88,\"event_type\":\"ASSIGNED\",\"ticket_id\":4711}";
            assertThat(CanonicalJson.serialise(forwards)).isEqualTo(expected);
            assertThat(CanonicalJson.serialise(backwards)).isEqualTo(expected);
            assertThat(CanonicalJson.serialise(hashed)).isEqualTo(expected);
            assertThat(CanonicalJson.serialise(reverseSorted)).isEqualTo(expected);
        }

        @Test
        @DisplayName("nested objects are sorted too")
        void sortsRecursively() {
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("b", 2);
            inner.put("a", 1);

            assertThat(CanonicalJson.serialise(Map.of("outer", inner)))
                    .isEqualTo("{\"outer\":{\"a\":1,\"b\":2}}");
        }

        @Test
        @DisplayName("a list keeps its order, because in a list the order is the meaning")
        void doesNotSortLists() {
            assertThat(CanonicalJson.serialise(Map.of("stages", List.of("DEV", "QA", "UAT"))))
                    .isEqualTo("{\"stages\":[\"DEV\",\"QA\",\"UAT\"]}");
        }

        @Test
        @DisplayName("an empty payload is {} rather than an error")
        void allowsAnEmptyObject() {
            assertThat(CanonicalJson.serialise(Map.of())).isEqualTo("{}");
        }
    }

    @Nested
    @DisplayName("nulls")
    class Nulls {

        /**
         * Dropping nulls is the default in a good many JSON configurations and
         * it is a collision: two payloads that differ by a whole column hash
         * identically, so a column silently disappearing from the payload
         * builder is invisible to the verifier.
         */
        @Test
        @DisplayName("a null column is written, not omitted")
        void writesNullsExplicitly() {
            Map<String, Object> withNull = new LinkedHashMap<>();
            withNull.put("field_name", null);
            withNull.put("ticket_id", 4711L);

            assertThat(CanonicalJson.serialise(withNull))
                    .isEqualTo("{\"field_name\":null,\"ticket_id\":4711}");
            assertThat(CanonicalJson.serialise(withNull))
                    .isNotEqualTo(CanonicalJson.serialise(Map.of("ticket_id", 4711L)));
        }

        @Test
        @DisplayName("a null inside a list is written too")
        void writesNullsInLists() {
            List<Object> values = new ArrayList<>();
            values.add("DEV");
            values.add(null);

            assertThat(CanonicalJson.serialise(Map.of("stages", values)))
                    .isEqualTo("{\"stages\":[\"DEV\",null]}");
        }

        @Test
        @DisplayName("a null payload is refused")
        void refusesANullPayload() {
            assertThatExceptionOfType(CanonicalJsonException.class)
                    .isThrownBy(() -> CanonicalJson.serialise(null))
                    .withMessageContaining("a payload is required");
        }

        @Test
        @DisplayName("a null key is refused")
        void refusesANullKey() {
            Map<String, Object> payload = new HashMap<>();
            payload.put(null, 1);

            assertThatExceptionOfType(CanonicalJsonException.class)
                    .isThrownBy(() -> CanonicalJson.serialise(payload))
                    .withMessageContaining("cannot be null");
        }

        @Test
        @DisplayName("a non-String key in a nested map is refused rather than toString()'d")
        void refusesANonStringKey() {
            assertThatExceptionOfType(CanonicalJsonException.class)
                    .isThrownBy(() -> CanonicalJson.serialise(Map.of("nested", Map.of(1, "one"))))
                    .withMessageContaining("must be a String");
        }
    }

    @Nested
    @DisplayName("timestamps")
    class Timestamps {

        /**
         * Three instants that every trimming ISO writer renders at three
         * different widths — {@code …:00Z}, {@code …:00.100Z},
         * {@code …:00.123456Z} — and that MySQL stores in one.
         */
        @Test
        @DisplayName("always exactly six fractional digits, always Z")
        void writesDatetime6() {
            assertThat(CanonicalJson.serialise(Map.of("t", Instant.parse("2026-08-14T09:30:00Z"))))
                    .isEqualTo("{\"t\":\"2026-08-14T09:30:00.000000Z\"}");
            assertThat(CanonicalJson.serialise(Map.of("t", Instant.parse("2026-08-14T09:30:00.1Z"))))
                    .isEqualTo("{\"t\":\"2026-08-14T09:30:00.100000Z\"}");
            assertThat(CanonicalJson.serialise(
                    Map.of("t", Instant.parse("2026-08-14T09:30:00.123456Z"))))
                    .isEqualTo("{\"t\":\"2026-08-14T09:30:00.123456Z\"}");
        }

        /**
         * The same moment written two ways. Under a trimming formatter these
         * are {@code .1} and {@code .100000} — equal instants, different
         * hashes, and only one of them survives a round trip through
         * {@code DATETIME(6)}.
         */
        @Test
        @DisplayName("the same instant expressed at different precisions hashes the same")
        void isIndifferentToHowTheInstantWasBuilt() {
            String fromString = CanonicalJson.serialise(
                    Map.of("t", Instant.parse("2026-08-14T09:30:00.100Z")));
            String fromMillis = CanonicalJson.serialise(
                    Map.of("t", Instant.ofEpochMilli(Instant.parse("2026-08-14T09:30:00.100Z")
                            .toEpochMilli())));

            assertThat(fromString).isEqualTo(fromMillis);
        }

        /**
         * See {@code CanonicalJson#writeInstant}: truncating here would be the
         * friendly-looking option and would not work, because MySQL rounds
         * rather than truncates and the stored value could still differ from
         * the hashed one.
         */
        @Test
        @DisplayName("sub-microsecond precision is refused, with the fix in the message")
        void refusesNanosecondPrecision() {
            Map<String, Object> payload = Map.of("t", Instant.parse("2026-08-14T09:30:00.123456789Z"));

            assertThatExceptionOfType(CanonicalJsonException.class)
                    .isThrownBy(() -> CanonicalJson.serialise(payload))
                    .withMessageContaining("sub-microsecond")
                    .withMessageContaining("truncatedTo")
                    .withMessageContaining("MySQL rounds");
        }

        @Test
        @DisplayName("a truncated instant is accepted, which is what the message asks for")
        void acceptsATruncatedInstant() {
            Instant truncated = Instant.parse("2026-08-14T09:30:00.123456789Z")
                    .truncatedTo(java.time.temporal.ChronoUnit.MICROS);

            assertThat(CanonicalJson.serialise(Map.of("t", truncated)))
                    .isEqualTo("{\"t\":\"2026-08-14T09:30:00.123456Z\"}");
        }

        @Test
        @DisplayName("a LocalDate is ISO, and a single-digit month keeps its zero")
        void writesIsoDates() {
            assertThat(CanonicalJson.serialise(Map.of("work_date", LocalDate.of(2026, 1, 5))))
                    .isEqualTo("{\"work_date\":\"2026-01-05\"}");
        }
    }

    @Nested
    @DisplayName("numbers")
    class Numbers {

        /**
         * {@code hours} is {@code DECIMAL(5,2)}. Whoever writes the row may
         * hand us {@code new BigDecimal("8")}; the verifier reads {@code 8.00}
         * back out of MySQL. Same number, and — without stripping — two hashes.
         */
        @Test
        @DisplayName("a decimal hashes by value, not by the scale it happens to carry")
        void ignoresDecimalScale() {
            String expected = "{\"hours\":8}";
            assertThat(CanonicalJson.serialise(Map.of("hours", new BigDecimal("8"))))
                    .isEqualTo(expected);
            assertThat(CanonicalJson.serialise(Map.of("hours", new BigDecimal("8.0"))))
                    .isEqualTo(expected);
            assertThat(CanonicalJson.serialise(Map.of("hours", new BigDecimal("8.00"))))
                    .isEqualTo(expected);
            assertThat(CanonicalJson.serialise(Map.of("hours", new BigDecimal("8.000000"))))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("zero strips to 0 rather than 0.00")
        void stripsZero() {
            assertThat(CanonicalJson.serialise(Map.of("hours", new BigDecimal("0.00"))))
                    .isEqualTo("{\"hours\":0}");
        }

        /**
         * {@code stripTrailingZeros} on a large or small value leaves an
         * exponent behind, and {@code toString()} would then write
         * {@code 1E+3}. That is legal JSON and a second spelling of a number we
         * already have one spelling for.
         */
        @Test
        @DisplayName("no scientific notation survives")
        void writesPlainStrings() {
            assertThat(CanonicalJson.serialise(Map.of("n", new BigDecimal("1E+3"))))
                    .isEqualTo("{\"n\":1000}");
            assertThat(CanonicalJson.serialise(Map.of("n", new BigDecimal("0.0000001"))))
                    .isEqualTo("{\"n\":0.0000001}");
        }

        @Test
        @DisplayName("every integral type the protected tables use")
        void writesIntegers() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("a_byte", (byte) 7);
            payload.put("b_short", (short) 2);
            payload.put("c_int", -42);
            payload.put("d_long", 4711L);
            payload.put("e_big", new BigInteger("170141183460469231731687303715884105727"));

            assertThat(CanonicalJson.serialise(payload)).isEqualTo(
                    "{\"a_byte\":7,\"b_short\":2,\"c_int\":-42,\"d_long\":4711,"
                            + "\"e_big\":170141183460469231731687303715884105727}");
        }

        @Test
        @DisplayName("a double is refused — 0.1 + 0.2 is why")
        void refusesDouble() {
            assertThatExceptionOfType(CanonicalJsonException.class)
                    .isThrownBy(() -> CanonicalJson.serialise(Map.of("hours", 8.0d)))
                    .withMessageContaining("Double")
                    .withMessageContaining("BigDecimal");
        }

        @Test
        @DisplayName("a float is refused for the same reason")
        void refusesFloat() {
            assertThatExceptionOfType(CanonicalJsonException.class)
                    .isThrownBy(() -> CanonicalJson.serialise(Map.of("hours", 8.0f)))
                    .withMessageContaining("Float");
        }
    }

    @Nested
    @DisplayName("strings")
    class Strings {

        @Test
        @DisplayName("the escaping table, in one assertion")
        void escapesTheJsonMinimum() {
            String value = "q\"b\\s/" + "\b\f\n\r\t" + (char) 0x00 + (char) 0x1f + (char) 0x7f;

            assertThat(CanonicalJson.serialise(Map.of("v", value)))
                    // Slash and DEL are not escaped; the C0 controls without a
                    // two-character form use lowercase hex, per RFC 8785.
                    .isEqualTo("{\"v\":\"q\\\"b\\\\s/\\b\\f\\n\\r\\t\\u0000\\u001f\"}");
        }

        @Test
        @DisplayName("non-ASCII text is written literally and encoded as UTF-8")
        void doesNotEscapeNonAscii() {
            // Escaping non-ASCII as \\uXXXX is a common JSON-writer option and
            // an equally valid document — which is the problem. There has to be
            // one answer, and it is the shorter one.
            assertThat(CanonicalJson.serialise(Map.of("v", "café — 🎫")))
                    .isEqualTo("{\"v\":\"café — 🎫\"}");
            assertThat(CanonicalJson.bytes(Map.of("v", "🎫")))
                    .isEqualTo(("{\"v\":\"🎫\"}").getBytes(StandardCharsets.UTF_8));
        }

        /**
         * The one string failure that is silent rather than loud.
         * {@code String.getBytes} substitutes {@code ?} for an unpaired
         * surrogate, so two different truncated remarks encode to identical
         * bytes and hash the same — and MySQL replaces or rejects the text as
         * well, so the verifier reads back something other than what was
         * hashed.
         */
        @Test
        @DisplayName("half an emoji is refused rather than silently turned into ?")
        void refusesAnUnpairedSurrogate() {
            String truncatedMidEmoji = "note 🎫".substring(0, 6);
            assertThat(truncatedMidEmoji).hasSize(6);

            assertThatExceptionOfType(CanonicalJsonException.class)
                    .isThrownBy(() -> CanonicalJson.serialise(Map.of("remarks", truncatedMidEmoji)))
                    .withMessageContaining("unpaired surrogate")
                    .withMessageContaining("code points, not chars");
        }

        @Test
        @DisplayName("a lone low surrogate is refused too")
        void refusesALoneLowSurrogate() {
            assertThatExceptionOfType(CanonicalJsonException.class)
                    .isThrownBy(() -> CanonicalJson.serialise(
                            Map.of("remarks", String.valueOf('\udc00'))))
                    .withMessageContaining("unpaired surrogate");
        }

        @Test
        @DisplayName("a well-formed pair passes")
        void acceptsAPairedSurrogate() {
            assertThatCode(() -> CanonicalJson.serialise(Map.of("remarks", "🎫")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an empty string is a value, not a null")
        void writesTheEmptyString() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("a", "");
            payload.put("b", null);

            assertThat(CanonicalJson.serialise(payload)).isEqualTo("{\"a\":\"\",\"b\":null}");
        }
    }

    @Nested
    @DisplayName("the ambient environment cannot reach the output")
    class Ambient {

        /**
         * The classic version of this bug is a {@code String.format("%d")} or a
         * {@code SimpleDateFormat} picking up a default the developer never
         * set — and the symptom is not a crash but a hash that differs between
         * a laptop in one locale and a server in another, on rows containing
         * nothing unusual at all.
         *
         * <p>Mutating the JVM defaults is heavy-handed and it is the only way
         * to assert the absence of a dependency on them. Restored in a
         * {@code finally}; the suite does not run in parallel.
         */
        @Test
        @DisplayName("a hostile default locale and timezone change nothing")
        void ignoresDefaultLocaleAndTimeZone() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("t", Instant.parse("2026-08-14T09:30:00.123456Z"));
            payload.put("work_date", LocalDate.of(2026, 1, 5));
            payload.put("hours", new BigDecimal("8.50"));
            payload.put("n", 1234567L);

            String baseline = CanonicalJson.serialise(payload);

            Locale locale = Locale.getDefault();
            TimeZone zone = TimeZone.getDefault();
            try {
                // Eastern Arabic numerals, and a timezone at +05:30 so that a
                // formatter reading the default zone would move the date as
                // well as the time.
                Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"));
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));

                assertThat(CanonicalJson.serialise(payload)).isEqualTo(baseline);
            } finally {
                Locale.setDefault(locale);
                TimeZone.setDefault(zone);
            }

            assertThat(baseline).contains("2026-08-14T09:30:00.123456Z", "8.5", "1234567");
        }
    }

    @Nested
    @DisplayName("types with no canonical form")
    class Unsupported {

        private enum Stage { DEV }

        @Test
        @DisplayName("an enum is refused, and told to use name()")
        void refusesAnEnum() {
            assertThatExceptionOfType(CanonicalJsonException.class)
                    .isThrownBy(() -> CanonicalJson.serialise(Map.of("stage", Stage.DEV)))
                    .withMessageContaining("has no canonical form")
                    .withMessageContaining("name()");
        }

        @Test
        @DisplayName("an array is refused, and told to use a List")
        void refusesAnArray() {
            assertThatExceptionOfType(CanonicalJsonException.class)
                    .isThrownBy(() -> CanonicalJson.serialise(
                            Map.of("stages", new Object[]{"DEV", "QA"})))
                    .withMessageContaining("wrap it in a List");
        }

        /**
         * A {@code ZonedDateTime} is the near miss worth naming: it holds a
         * zone that the {@code DATETIME(6)} column does not, so two of them an
         * hour apart in wall-clock terms are the same stored row. Accepting it
         * would hash something the database cannot give back.
         */
        @Test
        @DisplayName("a temporal that carries a zone is refused rather than flattened")
        void refusesAZonedDateTime() {
            assertThatExceptionOfType(CanonicalJsonException.class)
                    .isThrownBy(() -> CanonicalJson.serialise(
                            Map.of("t", Instant.parse("2026-08-14T09:30:00Z")
                                    .atZone(java.time.ZoneId.of("Asia/Kolkata")))))
                    .withMessageContaining("has no canonical form");
        }

        /**
         * Without the depth guard this is a {@link StackOverflowError} thrown
         * from inside an open transaction, which is a far worse thing to debug
         * than a sentence.
         */
        @Test
        @DisplayName("a payload containing itself fails with a message, not a StackOverflowError")
        void refusesACycle() {
            Map<String, Object> cycle = new HashMap<>();
            cycle.put("self", cycle);

            assertThatExceptionOfType(CanonicalJsonException.class)
                    .isThrownBy(() -> CanonicalJson.serialise(cycle))
                    .withMessageContaining("containing itself");
        }
    }

    @Nested
    @DisplayName("bytes()")
    class Bytes {

        /**
         * The reason the overload exists: {@code serialise(p).getBytes()} takes
         * the platform default charset, which is UTF-8 on every machine this
         * project builds on and need not be on the one it runs on. The
         * resulting hashes differ only for rows containing a non-ASCII
         * character — a bug no test written in English would find.
         */
        @Test
        @DisplayName("is UTF-8, not the platform default")
        void encodesAsUtf8() {
            byte[] actual = CanonicalJson.bytes(Map.of("v", "é"));

            assertThat(actual).isEqualTo(new byte[]{
                    '{', '"', 'v', '"', ':', '"', (byte) 0xC3, (byte) 0xA9, '"', '}'});
        }

        @Test
        @DisplayName("agrees with serialise()")
        void agreesWithSerialise() {
            Map<String, Object> payload = Map.of("v", "café");

            assertThat(CanonicalJson.bytes(payload))
                    .isEqualTo(CanonicalJson.serialise(payload).getBytes(StandardCharsets.UTF_8));
        }
    }
}
