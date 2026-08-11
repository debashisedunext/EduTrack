package com.edunext.edutrack.api.feature.imports;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * B-030 · the dry run. <b>This class cannot write to the database.</b>
 *
 * <p>Not "does not" — cannot. It holds no repository, no {@code EntityManager}
 * and no {@link ImportSchemaDefinition#upsert} call path; the only schema method
 * it touches is the read-only existence probe. Blueprint §4B.3 makes step 4's
 * guarantee absolute ("nothing is written yet"), and a guarantee that depends on
 * nobody later adding a convenient save here is not one. The dependency simply
 * is not available to add.
 *
 * <p>Schema-agnostic throughout: it knows fields, verdicts and natural keys, and
 * nothing about clients or resources. B-038 adds a registration and this file
 * does not change.
 *
 * <h2>Order of judgement</h2>
 * <ol>
 *   <li><b>Content</b> — required, length, type, enum domain, custom rules. A
 *       row that fails here is {@link ImportVerdict#REJECTED} and takes no
 *       further part: it is not compared for duplicates and its key is not
 *       probed, because a row that will not be written cannot collide with
 *       anything.
 *   <li><b>The file against itself</b> — first occurrence of a natural key
 *       wins, later ones are {@link ImportVerdict#DUPLICATE_IN_FILE} naming the
 *       winner, exactly as blueprint §4B.3's table shows ("Row 2 wins").
 *   <li><b>The file against the table</b> — one batched probe decides
 *       {@link ImportVerdict#WILL_UPDATE} versus {@link ImportVerdict#WILL_CREATE}.
 * </ol>
 */
@Component
public class ImportValidationEngine {

    /**
     * @param rows already mapped to field names by {@link ImportMapping}
     * @return the preview, and no side effects of any kind
     */
    public ImportPreview validate(ImportSchemaDefinition schema, List<ImportRow> rows) {
        List<ImportField> fields = schema.fields();
        String keyField = schema.naturalKey().name();

        // Pass 1 — content, and the file against itself. Both in one walk
        // because duplicate detection is order-dependent and a second pass
        // would have to reconstruct the order it depended on.
        List<ImportRowVerdict> verdicts = new ArrayList<>(rows.size());
        Map<String, Integer> firstRowForKey = new HashMap<>();
        Set<String> keysToProbe = new LinkedHashSet<>();

        for (ImportRow row : rows) {
            String rejection = firstRejection(row, fields);
            if (rejection != null) {
                verdicts.add(ImportRowVerdict.of(row, ImportVerdict.REJECTED, rejection));
                continue;
            }

            String key = schema.normaliseKey(row.get(keyField));
            Integer winner = firstRowForKey.putIfAbsent(key, row.rowNumber());
            if (winner != null) {
                verdicts.add(ImportRowVerdict.of(row, ImportVerdict.DUPLICATE_IN_FILE,
                        "Row " + winner + " wins"));
                continue;
            }

            keysToProbe.add(key);
            // Placeholder verdict, settled by pass 2. Held in the list rather
            // than in a side collection so the output keeps the file's row
            // order without a sort — the user is reading it against their
            // spreadsheet.
            verdicts.add(ImportRowVerdict.of(row, ImportVerdict.WILL_CREATE));
        }

        if (keysToProbe.isEmpty()) {
            return ImportPreview.of(verdicts);
        }

        // Pass 2 — one query for up to 5,000 keys. Asked per row this is 5,000
        // round trips per dry run, and the dry run is the step users repeat.
        Set<String> existing = schema.findExisting(keysToProbe);

        List<ImportRowVerdict> settled = new ArrayList<>(verdicts.size());
        for (ImportRowVerdict verdict : verdicts) {
            if (verdict.verdict() != ImportVerdict.WILL_CREATE) {
                settled.add(verdict);
                continue;
            }
            String key = schema.normaliseKey(verdict.values().get(keyField));
            settled.add(existing.contains(key)
                    ? new ImportRowVerdict(verdict.rowNumber(), ImportVerdict.WILL_UPDATE,
                            null, verdict.values())
                    : verdict);
        }
        return ImportPreview.of(settled);
    }

    /**
     * The first thing wrong with a row, or {@code null}.
     *
     * <p>First, not all — blueprint §4B.3's Message column is one line per row
     * ("Code required", "Invalid email"). A row with six blank required fields
     * reported as six reasons pushes the other rejections off the screen, and
     * the user fixes one thing and re-uploads regardless.
     *
     * <p>Fields are checked in template order, so the reason a user is given is
     * the leftmost problem in the row they are looking at.
     */
    private static String firstRejection(ImportRow row, List<ImportField> fields) {
        for (ImportField field : fields) {
            String value = row.get(field.name());

            if (value == null) {
                if (field.required()) {
                    return field.header() + " required";
                }
                // Absent and optional: every validator below expects a present,
                // non-blank value (see FieldValidator), so there is nothing
                // left to ask about this cell.
                continue;
            }

            if (field.maxLength() > 0) {
                Optional<String> tooLong = FieldValidators.maxLength(field.maxLength()).validate(value);
                if (tooLong.isPresent()) {
                    return field.header() + ": " + tooLong.get();
                }
            }

            if (field.type() == ImportFieldType.ENUM) {
                // Applied from the declaration rather than declared per field,
                // so a dropdown cannot exist without the check that gives it
                // meaning — see FieldValidators#oneOf.
                Optional<String> notAllowed =
                        FieldValidators.oneOf(field.allowedValues()).validate(value);
                if (notAllowed.isPresent()) {
                    return field.header() + ": " + notAllowed.get();
                }
            }

            for (FieldValidator validator : field.validators()) {
                Optional<String> reason = validator.validate(value);
                if (reason.isPresent()) {
                    return field.header() + ": " + reason.get();
                }
            }
        }
        return null;
    }
}
