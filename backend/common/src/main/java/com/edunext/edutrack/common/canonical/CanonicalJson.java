package com.edunext.edutrack.common.canonical;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A-041 · the byte-exact JSON form the hash chain is computed over.
 *
 * <p>PLAN.md §3.7 defines the chain as
 * {@code row_hash = SHA256(prev_hash ‖ canonical_json(payload))}. Two different
 * programs compute that expression: A-042's journal, in {@code domain}, at the
 * moment of the append; and A-044's nightly verifier, in {@code worker}, months
 * later, from a row it has read back out of MySQL. If those two byte sequences
 * differ anywhere — one space, one trailing zero, one key in a different place —
 * the verifier reports a hash mismatch, and <b>a false alarm is indistinguishable
 * from a real one</b>. Somebody then spends a day proving that a tamper alert on
 * the audit log was our own formatting.
 *
 * <p>So this class is not a JSON writer that happens to be tidy. It is the
 * <i>definition</i> of those bytes, and every rule below exists because some
 * ordinary, reasonable-looking code path would otherwise produce a different
 * string for the same row.
 *
 * <h2>The rules</h2>
 *
 * <ol>
 *   <li><b>Keys are sorted</b> lexicographically by UTF-16 code unit
 *       ({@link String#compareTo}), at every level. This is RFC 8785's rule and
 *       the reason is that it needs no maintenance: a hand-written column order
 *       is a second list to keep in step with the schema, and when it falls
 *       behind nothing complains — the hashes simply change.</li>
 *   <li><b>No insignificant whitespace.</b> {@code {"a":1,"b":2}} and nothing
 *       else.</li>
 *   <li><b>Nulls are written, never dropped.</b> Omitting them would give
 *       {@code {"a":null,"b":1}} and {@code {"b":1}} the same hash, so a column
 *       that vanished from the payload builder would be undetectable.</li>
 *   <li><b>Timestamps are exactly six fractional digits and always {@code Z}</b>
 *       — {@code 2026-08-14T09:30:00.000000Z}. The columns are
 *       {@code DATETIME(6)} (PLAN.md §3.1) and the usual ISO writers, Jackson's
 *       included, trim trailing zeros: {@code .100000} and {@code .1} are the
 *       same instant and two different hashes.</li>
 *   <li><b>Decimals are stripped of trailing zeros.</b> {@code hours} is
 *       {@code DECIMAL(5,2)}; Java hands us {@code 8}, MySQL hands the verifier
 *       back {@code 8.00}. {@link BigDecimal#toPlainString()} also keeps
 *       {@code 1E+3} out of the output.</li>
 *   <li><b>Everything else is refused</b> rather than guessed at — see
 *       {@link #writeValue}.</li>
 * </ol>
 *
 * <h2>Why this is hand-written and not an {@code ObjectMapper}</h2>
 *
 * <p>{@code common} already has Jackson, and Jackson can be configured most of
 * the way here ({@code ORDER_MAP_ENTRIES_BY_KEYS}, a custom
 * {@code InstantSerializer}, and so on). The part that cannot be configured is
 * the part that matters: which characters its {@code CharacterEscapes} table
 * escapes, and whether that table is the same in the next minor version. A
 * canonical form whose definition lives in a third party's defaults is not
 * canonical — it is canonical until somebody bumps a dependency, and then every
 * hash written before the bump fails verification with no code change to blame.
 * The value space here is ten types and the escaping table is twenty lines, so
 * it is written out below where a diff can be seen.
 *
 * <h2>What this class is not</h2>
 *
 * <p>It does not build the payload. Turning a {@code TicketHistory} into a map
 * of columns belongs to A-042, in {@code domain}, because {@code common} cannot
 * see the entities and should not — it is on the classpath of every module
 * precisely because it depends on none of them. That single payload builder is
 * then what A-044's verifier reuses; two builders, one per side, is the same
 * failure this class exists to prevent, one level up.
 *
 * <p>Two things for whoever writes that builder:
 *
 * <ul>
 *   <li><b>A database-generated column cannot be in the payload.</b>
 *       {@code created_at} and {@code logged_at} are
 *       {@code @Generated(event = INSERT)} — at hash time they are null, and by
 *       verification time they are a timestamp. The same goes for {@code id},
 *       which is {@code AUTO_INCREMENT} and does not exist until the row does.
 *       Hashing any of them guarantees a mismatch on every row — the one
 *       failure that looks exactly like the whole table having been
 *       tampered with.</li>
 *   <li><b>Consider a version marker in the payload itself.</b> If the set of
 *       hashed columns ever changes, every row written before the change becomes
 *       unverifiable with no way to tell which rule applied. A key naming the
 *       payload revision lets the verifier pick.</li>
 * </ul>
 */
public final class CanonicalJson {

    /**
     * Exactly six fractional digits and a literal {@code Z}, matching
     * {@code DATETIME(6)}.
     *
     * <p>{@link DateTimeFormatterBuilder#appendInstant(int)} with a fixed digit
     * count is the reason this is a builder rather than a pattern string: it
     * pads and truncates to the requested width instead of emitting the
     * shortest form, and it takes no locale, so it cannot be bent by a default
     * that differs between a developer's laptop and the server.
     */
    private static final DateTimeFormatter TIMESTAMP =
            new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    /** {@code 2026-08-14}. Numeric ISO fields only, so no locale applies. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final int NANOS_PER_MICRO = 1_000;

    /**
     * A row is not thirty maps deep. This exists only so a payload that
     * accidentally contains itself fails with a sentence instead of a
     * {@link StackOverflowError} thrown halfway through an open transaction.
     */
    private static final int MAX_DEPTH = 32;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CanonicalJson() {
    }

    /**
     * The canonical JSON text for one row's payload.
     *
     * @param payload the hashed columns; keys are the column names
     * @throws CanonicalJsonException if the payload is null, has a null key, or
     *         holds a value with no canonical form
     */
    public static String serialise(Map<String, ?> payload) {
        if (payload == null) {
            throw new CanonicalJsonException("a payload is required");
        }
        StringBuilder out = new StringBuilder(256);
        writeObject(payload, out, 0);
        return out.toString();
    }

    /**
     * The same text as UTF-8 bytes, which is what {@code MessageDigest} wants.
     *
     * <p>This exists so that no call site writes {@code serialise(p).getBytes()}
     * — the no-argument overload uses the platform default charset, which is
     * UTF-8 on the build machines and need not be everywhere, and the resulting
     * hashes differ only for rows containing a non-ASCII character. That is a
     * bug that passes every test written in English.
     */
    public static byte[] bytes(Map<String, ?> payload) {
        return serialise(payload).getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Structure
    // ------------------------------------------------------------------

    private static void writeObject(Map<?, ?> map, StringBuilder out, int depth) {
        requireDepth(depth);

        List<String> keys = new ArrayList<>(map.size());
        for (Object key : map.keySet()) {
            if (key == null) {
                throw new CanonicalJsonException("a payload key cannot be null");
            }
            if (!(key instanceof String name)) {
                throw new CanonicalJsonException(
                        "a payload key must be a String, not " + key.getClass().getName()
                                + " (" + key + "). JSON has no other kind of key, and relying on "
                                + "toString() would make the canonical form depend on it.");
            }
            keys.add(name);
        }
        // UTF-16 code-unit order, per RFC 8785 §3.2.3. Every key here is an
        // ASCII column name, where this agrees with code-point order anyway;
        // the rule is stated so it stays true if one ever is not.
        keys.sort(Comparator.naturalOrder());

        out.append('{');
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            writeString(keys.get(i), out);
            out.append(':');
            writeValue(map.get(keys.get(i)), out, depth + 1);
        }
        out.append('}');
    }

    private static void writeArray(Collection<?> values, StringBuilder out, int depth) {
        requireDepth(depth);
        out.append('[');
        boolean first = true;
        for (Object value : values) {
            if (!first) {
                out.append(',');
            }
            first = false;
            // Not sorted: in an object the key carries the meaning and the order
            // does not, but in an array the order *is* the meaning.
            writeValue(value, out, depth + 1);
        }
        out.append(']');
    }

    // ------------------------------------------------------------------
    // Values
    // ------------------------------------------------------------------

    /**
     * The closed list of types that have a canonical form.
     *
     * <p>Closed on purpose. The alternative — falling back to
     * {@code toString()} for anything unrecognised — would accept an enum, a
     * {@code UUID}, a {@code ZonedDateTime} and an entity alike, and produce a
     * hash over whatever that type's author happened to write. It would keep
     * working right up until someone adds a field to a {@code toString()} for
     * a log line, at which point every row hashed with it stops verifying.
     */
    private static void writeValue(Object value, StringBuilder out, int depth) {
        switch (value) {
            case null -> out.append("null");
            case String s -> writeString(s, out);
            case Boolean b -> out.append(b ? "true" : "false");
            case Instant instant -> writeInstant(instant, out);
            case LocalDate date -> writeString(DATE.format(date), out);
            case BigDecimal decimal -> out.append(decimal.stripTrailingZeros().toPlainString());
            // Byte, Short, Integer, Long, BigInteger — every integral type these
            // three tables use. toString() on each is locale-independent and
            // exact, which is the whole requirement.
            case Byte b -> out.append(b.toString());
            case Short s -> out.append(s.toString());
            case Integer i -> out.append(i.toString());
            case Long l -> out.append(l.toString());
            case BigInteger i -> out.append(i.toString());
            case Map<?, ?> nested -> writeObject(nested, out, depth);
            case Collection<?> nested -> writeArray(nested, out, depth);
            case Double _ -> throw floatingPoint("Double");
            case Float _ -> throw floatingPoint("Float");
            case Object[] _ -> throw new CanonicalJsonException(
                    "an array has no canonical form here — wrap it in a List. Arrays compare and "
                            + "print by identity, so accepting one invites a payload that hashes "
                            + "differently on every run.");
            default -> throw new CanonicalJsonException(
                    value.getClass().getName() + " has no canonical form. Convert it at the call "
                            + "site to something that does — an enum to name(), a UUID or an "
                            + "identifier to its String, a temporal to Instant or LocalDate — so "
                            + "that the conversion is visible in a diff rather than inherited from "
                            + "whatever that type's toString() says this year.");
        }
    }

    /**
     * <b>Sub-microsecond precision is refused, not truncated.</b>
     *
     * <p>Truncating here would look like the helpful thing and would not work.
     * The column is {@code DATETIME(6)} and MySQL 8 <i>rounds</i> a finer value
     * on insert rather than truncating it, so a payload hashed over
     * {@code .1234565} would be stored as {@code .123457} and the verifier,
     * reading the stored value back, would recompute a different hash. Silent
     * truncation converts a loud failure at the append into exactly the false
     * tamper alert this class exists to prevent.
     *
     * <p>Only the caller can fix it, because only the caller writes the row: the
     * value that goes into the hash and the value that goes into the
     * {@code INSERT} have to be the same object, truncated once, before either.
     * Hence a rejection that names the fix.
     */
    private static void writeInstant(Instant instant, StringBuilder out) {
        if (instant.getNano() % NANOS_PER_MICRO != 0) {
            throw new CanonicalJsonException(
                    instant + " carries sub-microsecond precision, which DATETIME(6) cannot store. "
                            + "Truncate before you hash *and* store — instant.truncatedTo("
                            + "ChronoUnit.MICROS) — so the row and its hash are computed from the "
                            + "same value. Truncating here instead would not help: MySQL rounds "
                            + "rather than truncates, so the stored timestamp could still differ "
                            + "from the hashed one by a microsecond and the nightly verifier would "
                            + "report it as tampering.");
        }
        out.append('"');
        TIMESTAMP.formatTo(instant, out);
        out.append('"');
    }

    private static CanonicalJsonException floatingPoint(String type) {
        return new CanonicalJsonException(
                "a " + type + " has no canonical form. Binary floating point has no exact decimal "
                        + "representation and no agreed shortest one, so 0.1 + 0.2 hashes "
                        + "differently from 0.3 while comparing equal to it in a report. None of "
                        + "the protected tables has such a column — hours is DECIMAL(5,2) — so use "
                        + "BigDecimal.");
    }

    // ------------------------------------------------------------------
    // Strings
    // ------------------------------------------------------------------

    /**
     * The escaping table, written out rather than delegated.
     *
     * <p>Minimal escaping, per RFC 8785 §3.2.2.2: quote and backslash, the five
     * two-character control escapes, {@code &#92;u00xx} in <b>lowercase</b> hex
     * for every other C0 control, and every remaining character — {@code /},
     * {@code DEL}, and all of Unicode — written literally, to be encoded as
     * UTF-8 by {@link #bytes}.
     *
     * <p>(The escape above is written as an HTML entity because a {@code &#92;u}
     * sequence is processed by the Java lexer <i>inside comments too</i>, and an
     * invalid one will not compile.)
     */
    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
                    } else {
                        if (Character.isSurrogate(c)) {
                            i = requirePairedSurrogate(value, i, length);
                            out.append(c).append(value.charAt(i));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * An unpaired surrogate is refused because the alternative is a silent
     * collision.
     *
     * <p>A Java {@code String} can hold half of an astral character — the usual
     * way is truncating a {@code remarks} field to fit a column and cutting an
     * emoji in two. {@link String#getBytes(java.nio.charset.Charset)} does not
     * fail on one; it substitutes {@code ?}. So two different remarks, each
     * truncated mid-character, encode to identical bytes and hash the same, and
     * MySQL rejects or replaces the text as well — meaning the verifier reads
     * back something other than what was hashed. Every part of that is invisible
     * until it is a tamper alert.
     *
     * @return the index of the low surrogate, so the caller can consume the pair
     */
    private static int requirePairedSurrogate(String value, int index, int length) {
        if (Character.isHighSurrogate(value.charAt(index))
                && index + 1 < length
                && Character.isLowSurrogate(value.charAt(index + 1))) {
            return index + 1;
        }
        throw new CanonicalJsonException(
                "the string contains an unpaired surrogate at index " + index + ", which is not a "
                        + "character and cannot be encoded as UTF-8. It is almost always a value "
                        + "truncated through the middle of an emoji or other astral character — "
                        + "truncate on code points, not chars.");
    }

    private static void requireDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new CanonicalJsonException(
                    "the payload nests more than " + MAX_DEPTH + " levels deep, which a database "
                            + "row does not — it is almost certainly a structure containing "
                            + "itself.");
        }
    }
}
