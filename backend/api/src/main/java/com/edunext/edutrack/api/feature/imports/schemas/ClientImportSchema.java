package com.edunext.edutrack.api.feature.imports.schemas;

import com.edunext.edutrack.api.feature.imports.FieldValidators;
import com.edunext.edutrack.api.feature.imports.ImportField;
import com.edunext.edutrack.api.feature.imports.ImportFieldType;
import com.edunext.edutrack.api.feature.imports.ImportReversal;
import com.edunext.edutrack.api.feature.imports.ImportRow;
import com.edunext.edutrack.api.feature.imports.ImportSchemaDefinition;
import com.edunext.edutrack.domain.clients.Client;
import com.edunext.edutrack.domain.clients.ClientCodeFormat;
import com.edunext.edutrack.domain.clients.ClientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * B-030 · the client master, registered — screen S-34, blueprint §4B.3.
 *
 * <p><b>This file is the deliverable's evidence.</b> It is a list of columns and
 * two short methods, and it is the entirety of what "clients can be
 * bulk-imported" costs. Nothing here parses a workbook, matches a header,
 * detects a duplicate, builds a preview or writes an error report — all of that
 * is in the engine beside it and none of it mentions clients. B-038 adds the
 * resource registration as a comparable file and the engine does not change.
 *
 * <p>If a future task finds itself adding a branch to the engine "for clients",
 * the branch belongs here instead.
 */
@Component
public class ClientImportSchema implements ImportSchemaDefinition {

    private static final Logger log = LoggerFactory.getLogger(ClientImportSchema.class);

    private final ClientRepository clients;
    private final ClientImportReferences references;

    ClientImportSchema(ClientRepository clients, ClientImportReferences references) {
        this.clients = clients;
        this.references = references;
    }

    /** Matches the contract's {@code ImportSchema} enum — the generated client already calls this path. */
    @Override
    public String key() {
        return "clients";
    }

    @Override
    public String entityCode() {
        return "CLIENT";
    }

    @Override
    public ImportField naturalKey() {
        return CLIENT_CODE;
    }

    /**
     * Blueprint §4B.3 names four rules — "client code unique and alphanumeric;
     * name required; email format; country against an ISO list" — and every one
     * of them is a declaration below rather than a line of code.
     *
     * <p><b>Account manager and SLA policy are deliberately absent.</b> Both are
     * foreign keys, and a spreadsheet can only carry a person's name or a policy
     * label. A column that resolves when the spelling happens to match and
     * silently leaves the field null when it does not is worse than no column:
     * the user sees 412 clients created and no indication that 90 of them have
     * no account manager. They are assigned on S-33 after import, and get a
     * template column when there is something to resolve names against.
     */
    @Override
    public List<ImportField> fields() {
        return FIELDS;
    }

    private static final ImportField CLIENT_CODE =
            ImportField.required("clientCode", "Client Code")
                    .maxLength(20)
                    .example("ACME")
                    // B-028 · the client master's own rule, not a stricter one.
                    //
                    // `FieldValidators.alphanumeric()` was here and refused
                    // `ACME-IN` — a code S-33 issues and every report displays.
                    // Because this field is B-035's upsert key, that refusal is
                    // not a message somebody reads and fixes: the row is
                    // rejected, so the client it names is silently *not*
                    // updated by an import that otherwise succeeded.
                    //
                    // Stated as a lambda over ClientCodeFormat rather than as a
                    // `FieldValidators.clientCode()`, and that is the guard's
                    // call rather than a preference:
                    // ImportEngineIsolationTest failed the first attempt,
                    // because a client-shaped rule on the shared engine's front
                    // door is the first half of a second import flow. Entity
                    // knowledge belongs in the registration — this file.
                    .validate(value -> ClientCodeFormat.isValid(value)
                            ? java.util.Optional.empty()
                            : java.util.Optional.of(ClientCodeFormat.SENTENCE));

    private static final List<ImportField> FIELDS = List.of(
            CLIENT_CODE,
            ImportField.required("name", "Name").maxLength(150).example("Acme Corporation"),
            ImportField.optional("shortName", "Short Name").maxLength(60).example("Acme"),
            ImportField.optional("industry", "Industry").maxLength(80).example("Manufacturing"),
            // Optional with a default rather than required: a file of new
            // clients is a file of active clients, and making every row restate
            // that is how a mandatory column gets filled in with noise.
            ImportField.optional("status", "Status").oneOf("ACTIVE", "INACTIVE").example("ACTIVE"),
            ImportField.optional("supportPlan", "Support Plan")
                    .oneOf("BASIC", "STANDARD", "PREMIUM").example("STANDARD"),
            ImportField.optional("primaryEmail", "Primary Email").maxLength(150)
                    .type(ImportFieldType.EMAIL).example("accounts@acme.example")
                    .validate(FieldValidators.email()),
            ImportField.optional("supportEmail", "Support Email").maxLength(150)
                    .type(ImportFieldType.EMAIL).example("support@acme.example")
                    .validate(FieldValidators.email()),
            ImportField.optional("phone", "Phone").maxLength(30).example("+91 22 4000 1000"),
            ImportField.optional("websiteDomain", "Website Domain").maxLength(120)
                    .example("acme.example"),
            ImportField.optional("addressLine1", "Address Line 1").maxLength(150)
                    .example("14 Marine Drive"),
            ImportField.optional("addressLine2", "Address Line 2").maxLength(150),
            ImportField.optional("city", "City").maxLength(80).example("Mumbai"),
            ImportField.optional("state", "State").maxLength(80).example("Maharashtra"),
            ImportField.optional("country", "Country").maxLength(80).example("India")
                    .validate(FieldValidators.isoCountry()),
            ImportField.optional("postalCode", "Postal Code").maxLength(20).example("400020"),
            // Presentation only — see Client#timezone. It decides how this
            // client's SLA windows are rendered, never how anything is stored.
            ImportField.optional("timezone", "Timezone").maxLength(50).example("Asia/Kolkata"),
            ImportField.optional("contractStart", "Contract Start")
                    .type(ImportFieldType.DATE).example("2026-04-01")
                    .validate(FieldValidators.isoDate()),
            ImportField.optional("contractEnd", "Contract End")
                    .type(ImportFieldType.DATE).example("2027-03-31")
                    .validate(FieldValidators.isoDate()),
            ImportField.optional("notes", "Notes").example("Renewal due Q1"));

    /**
     * The clients these codes name, as the import sees them.
     *
     * <p>One query for the whole file, and the values as well as the keys —
     * B-034's step-4 preview names the fields an update would change, and
     * {@link ImportSchemaDefinition#findExisting} explains why "will update" on
     * its own is not enough to approve.
     *
     * <p><b>Only the columns this registration declares</b>, formatted the way
     * the import reads them. A stored null is left out rather than mapped to an
     * empty string, so an incoming value against an empty field reads as a
     * change — which it is — and matches {@link ImportRow}'s one representation
     * of missing. {@code accountManagerId} and {@code slaPolicyId} are not here
     * for the same reason they are not in {@link #fields()}: a spreadsheet
     * cannot carry them, so nothing can change them.
     *
     * <p>Read-only, and nothing escapes the transaction but strings.
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Map<String, String>> findExisting(Set<String> naturalKeyValues) {
        Map<String, Map<String, String>> existing = new LinkedHashMap<>();
        for (Client stored : clients.findByClientCodeIn(naturalKeyValues)) {
            // Back to the engine's normalised form: MySQL matched these
            // case-insensitively through the ci collation and returned them in
            // whatever case they were stored in, which will not always be the
            // case the file used.
            existing.put(normaliseKey(stored.getClientCode()), currentValues(stored));
        }
        return existing;
    }

    /**
     * One stored client, projected onto this schema's field names.
     *
     * <p>Deliberately parallel to {@link #upsert} — every {@code set(...)} there
     * has a {@code put(...)} here, and a column added to one without the other
     * is a field the preview silently never reports as changing. The two lists
     * being adjacent is what makes that visible in review.
     */
    private static Map<String, String> currentValues(Client client) {
        Map<String, String> values = new LinkedHashMap<>();
        put(values, "name", client.getName());
        put(values, "shortName", client.getShortName());
        put(values, "industry", client.getIndustry());
        put(values, "status", client.getStatus());
        put(values, "supportPlan", client.getSupportPlan());
        put(values, "primaryEmail", client.getPrimaryEmail());
        put(values, "supportEmail", client.getSupportEmail());
        put(values, "phone", client.getPhone());
        put(values, "websiteDomain", client.getWebsiteDomain());
        put(values, "addressLine1", client.getAddressLine1());
        put(values, "addressLine2", client.getAddressLine2());
        put(values, "city", client.getCity());
        put(values, "state", client.getState());
        put(values, "country", client.getCountry());
        put(values, "postalCode", client.getPostalCode());
        put(values, "timezone", client.getTimezone());
        put(values, "notes", client.getNotes());
        // ISO, because that is what the DATE column's validator accepts and so
        // what the uploaded cell has been normalised to by the time it is
        // compared. `toString()` on a LocalDate is ISO-8601 by definition.
        put(values, "contractStart", asText(client.getContractStart()));
        put(values, "contractEnd", asText(client.getContractEnd()));
        return values;
    }

    private static void put(Map<String, String> values, String field, String value) {
        if (value != null && !value.isBlank()) {
            values.put(field, value);
        }
    }

    private static String asText(LocalDate date) {
        return date == null ? null : date.toString();
    }

    /**
     * Insert or update, on {@code client_code}, never a second row.
     *
     * <p><b>Only fields present in the row are written.</b> An unmapped column,
     * or a cell the user left blank on an update, leaves the stored value alone
     * rather than nulling it. This is the difference between a spreadsheet that
     * corrects six phone numbers and a spreadsheet that erases every address in
     * the account — and the user cannot tell which they are about to get from
     * the preview, so it has to be the safe one.
     *
     * <p>Clearing a field is therefore not something the importer can do. That
     * is S-33's job, where it is one client at a time and visible.
     */
    @Override
    @Transactional
    public void upsert(ImportRow row, Long importBatchId) {
        String code = row.get("clientCode");
        Client client = clients.findByClientCode(code).orElseGet(() -> {
            Client fresh = new Client();
            fresh.setClientCode(code);
            // Stamped on insert only. An update must not re-attribute a client
            // to the batch that last touched it, or B-037's reversal would
            // delete rows that a later import merely edited.
            fresh.setImportBatchId(importBatchId);
            return fresh;
        });

        set(row, "name", client::setName);
        set(row, "shortName", client::setShortName);
        set(row, "industry", client::setIndustry);
        set(row, "status", client::setStatus);
        set(row, "supportPlan", client::setSupportPlan);
        set(row, "primaryEmail", client::setPrimaryEmail);
        set(row, "supportEmail", client::setSupportEmail);
        set(row, "phone", client::setPhone);
        set(row, "websiteDomain", client::setWebsiteDomain);
        set(row, "addressLine1", client::setAddressLine1);
        set(row, "addressLine2", client::setAddressLine2);
        set(row, "city", client::setCity);
        set(row, "state", client::setState);
        set(row, "country", client::setCountry);
        set(row, "postalCode", client::setPostalCode);
        set(row, "timezone", client::setTimezone);
        set(row, "notes", client::setNotes);
        setDate(row, "contractStart", client::setContractStart);
        setDate(row, "contractEnd", client::setContractEnd);

        clients.save(client);
    }

    /**
     * B-037 · every client this run <b>created</b>, deleted as a set.
     *
     * <p>Blueprint §4B.3's closing validation rule, and §17's mitigation for
     * "Client Excel import silently corrupts the master". The identification is
     * {@code clients.import_batch_id}, stamped by {@link #upsert} on insert only;
     * this is the reversal it was stamped for, and it is the first reader of
     * {@code ClientRepository.findByImportBatchId} and of
     * {@code ix_clients_import_batch}.
     *
     * <h2>Created, never merely updated</h2>
     *
     * <p>A client the run edited carries some earlier batch's id, or none, so it
     * is not in this list and is not touched. That is the property that makes the
     * whole operation safe to offer: a spreadsheet that created 12 clients and
     * corrected the phone numbers of 400 reverses to "12 deleted", not to 412
     * clients removed from the master. There is no before image anywhere, so the
     * 400 could not be restored even if they were in scope — which is the
     * honest reason the promise is worded "reversed as a set" rather than
     * "undone".
     *
     * <h2>One client, one transaction</h2>
     *
     * <p>The same shape {@code ImportCommitRunner} uses on the way in, for the
     * same reason: a row that cannot be removed costs that row and not the set.
     * A single transaction over 400 deletes would be rolled back whole by one
     * client that acquired a ticket while the operator was reading the history
     * panel — turning the most ordinary partial case into total failure.
     *
     * <p>The contacts and the client go in one transaction together, though.
     * Those two are not independent: a client whose contacts were removed and
     * whose own delete then failed is a client that has silently lost data with
     * nothing recording that it happened.
     *
     * <h2>Two mechanisms for "cannot delete", on purpose</h2>
     *
     * <p>{@link ClientImportReferences#ticketCounts} answers the case that
     * actually occurs and can say something useful about it — a count the
     * operator can go and look at. The {@code catch} below is the backstop for
     * anything else that references {@code clients}, including a foreign key a
     * future stream adds that this file cannot know about. Only the first can
     * produce a good sentence; only the second is complete.
     */
    @Override
    public ImportReversal reverse(long batchId) {
        List<Client> created = clients.findByImportBatchId(batchId);
        if (created.isEmpty()) {
            return ImportReversal.none();
        }

        Map<Long, Long> ticketCounts =
                references.ticketCounts(created.stream().map(Client::getId).toList());

        List<String> deleted = new ArrayList<>();
        List<ImportReversal.Retained> retained = new ArrayList<>();

        for (Client client : created) {
            Long tickets = ticketCounts.get(client.getId());
            if (tickets != null && tickets > 0) {
                retained.add(new ImportReversal.Retained(client.getClientCode(),
                        tickets == 1
                                ? "Kept — 1 ticket has been raised against this client since the import."
                                : "Kept — " + tickets + " tickets have been raised against this client"
                                        + " since the import."));
                continue;
            }

            try {
                references.deleteClientAndContacts(client.getId());
                deleted.add(client.getClientCode());
            } catch (DataIntegrityViolationException stillReferenced) {
                // The backstop, not the expected path. Deliberately vague on the
                // wire and specific in the log — B-036 made the same call about
                // the error report's Reason column, and for the same reason: the
                // exception carries a constraint name, a table name and sometimes
                // the SQL, and this text is read on a screen and pasted into
                // email.
                log.warn("Import batch {} — client {} could not be deleted: {}",
                        batchId, client.getClientCode(), stillReferenced.toString());
                retained.add(new ImportReversal.Retained(client.getClientCode(),
                        "Kept — something created since the import still refers to this client."
                                + " The detail is in the server log for import #" + batchId + "."));
            }
        }

        return new ImportReversal(List.copyOf(deleted), List.copyOf(retained));
    }

    private static void set(ImportRow row, String field, Consumer<String> setter) {
        String value = row.get(field);
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * Safe to parse without catching: the row reached commit, which means the
     * dry run already ran {@link FieldValidators#isoDate()} over this cell. If
     * that stops being true this should throw rather than swallow — a date
     * silently dropped from a contract is not a smaller problem than a failed
     * batch.
     */
    private static void setDate(ImportRow row, String field, Consumer<LocalDate> setter) {
        String value = row.get(field);
        if (value != null) {
            setter.accept(LocalDate.parse(value));
        }
    }
}
