package com.edunext.edutrack.api.feature.masters.notificationtemplates;

import com.edunext.edutrack.domain.notifications.MergeTag;
import com.edunext.edutrack.domain.notifications.NotificationChannel;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationRecipient;
import com.edunext.edutrack.domain.notifications.NotificationTemplate;
import com.edunext.edutrack.domain.notifications.NotificationTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * B-022 · S-15, the Notification Template Master.
 *
 * <p>The table was created by A-007 and had never held a row. The entity, the
 * repository and {@code email_log.template_id} have all been sitting here since
 * the baseline with <b>no caller anywhere in the codebase</b> — the sixth
 * instance of "modelled, mocked or declared, never mounted" this stream has
 * found, after B-023's nine calendar operations, B-014's status patch, B-018's
 * two SLA operations, B-020's {@code listTaskTypes} and B-021's
 * {@code listPriorities}. Every mail the system sends today builds its subject
 * in Java and enqueues with {@code templateId = null}.
 *
 * <p>{@code V20260815_1100} seeds one row per (event, channel) pair blueprint
 * §11 ticks, so this screen opens onto content rather than onto an empty grid,
 * and D-010 has something to render.
 *
 * <h2>There is no delete</h2>
 *
 * <p>The same call B-020 made on a task type and B-021 on a level, for a sharper
 * reason than either: deleting a template does not orphan a reference, it
 * removes the <em>wording</em> for an event that still fires. The producer goes
 * on raising {@code HANDOFF_RECEIVED}; there is simply nothing to render it
 * with, and the failure appears in a mail that does not arrive rather than in an
 * error anybody sees. Deactivating is how a template goes away, and it is
 * refused outright on the mails §4B.6 marks unsuppressable.
 *
 * <h2>Five refusals, none of them enforced by the schema</h2>
 *
 * <ol>
 *   <li><b>The event and channel must exist</b> in {@link NotificationEvent} and
 *       {@link NotificationChannel}. Neither column has a {@code CHECK} — the
 *       migration explains why it deliberately did not add one — so this is
 *       where the vocabulary is actually closed.</li>
 *   <li><b>(event, channel) is the row's identity and is immutable.</b> See
 *       {@link #update}.</li>
 *   <li><b>Every merge tag must resolve</b> — see {@link #validateMergeTags}.</li>
 *   <li><b>At least one recipient</b>, and every one of them known.</li>
 *   <li><b>A mandatory mail cannot be switched off</b> — see
 *       {@link #guardMandatory}, which is the rule this screen exists to not
 *       break.</li>
 * </ol>
 */
@Service
public class NotificationTemplateService {

    private final NotificationTemplateRepository templates;

    NotificationTemplateService(NotificationTemplateRepository templates) {
        this.templates = templates;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * Every template, retired ones included — the shape B-020 and B-064 read
     * their masters in, and the opposite of B-021's narrow default.
     *
     * <p>The difference is that this route has no picker behind it. B-021 kept
     * retired levels out by default because two shipped Stream C screens read
     * that list unfiltered and would have put a retired level straight into the
     * create form. Nothing outside S-15 reads this one — the renderer will look
     * up a single (event, channel) pair, not the list — so the grid's need to
     * show a switched-off template is the only requirement there is.
     */
    @Transactional(readOnly = true)
    public List<NotificationTemplateDtos.TemplateView> list() {
        return templates.findAllByOrderByEventCodeAscChannelAsc().stream()
                .map(NotificationTemplateService::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<NotificationTemplateDtos.TemplateView> find(long templateId) {
        return templates.findById(templateId).map(NotificationTemplateService::toView);
    }

    /**
     * The four closed vocabularies, read off the enums rather than restated.
     *
     * <p>Serving them beats letting the screen hold its own copy: the copy is
     * what drifts, and it drifts in the direction where S-15 offers a merge tag
     * the renderer does not substitute. This is the {@code /masters/permissions}
     * pattern — reference data with no writes, because every value here exists
     * only because code resolves it.
     */
    public NotificationTemplateDtos.VocabularyView vocabulary() {
        List<NotificationTemplateDtos.EventOption> events =
                new ArrayList<>(NotificationEvent.values().length);
        for (NotificationEvent event : NotificationEvent.values()) {
            events.add(new NotificationTemplateDtos.EventOption(
                    event.name(), event.category().name(), event.isMandatoryMail()));
        }
        return new NotificationTemplateDtos.VocabularyView(
                events,
                java.util.Arrays.stream(NotificationChannel.values()).map(Enum::name).toList(),
                java.util.Arrays.stream(NotificationRecipient.values()).map(Enum::name).toList(),
                java.util.Arrays.stream(MergeTag.values()).map(MergeTag::tag).toList());
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * A template for an (event, channel) pair the seed did not cover — in
     * practice a {@code PUSH} one, since §11 has no push column and the
     * migration seeded none.
     *
     * <p>The duplicate check runs before the insert so that a second template
     * for the same pair is a 409 naming what already exists, rather than
     * {@code uq_notification_templates} surfacing as a constraint violation the
     * caller reads as a 500.
     */
    @Transactional
    public NotificationTemplateDtos.TemplateView create(
            NotificationTemplateDtos.TemplateWrite write) {

        NotificationEvent event = requireEvent(write.eventCode());
        NotificationChannel channel = requireChannel(write.channel());

        if (templates.existsByEventCodeAndChannel(event.name(), channel.name())) {
            throw new DuplicateTemplateException(
                    "A " + channel + " template for " + event + " already exists. An event has one "
                    + "template per channel — edit that one, or bring it back if it is switched "
                    + "off.");
        }

        List<NotificationRecipient> recipients = requireRecipients(write.recipients());
        String subject = blankToNull(write.subjectTemplate());
        requireSubjectWhenEmail(channel, subject);
        validateMergeTags(subject, write.bodyTemplate());

        boolean active = write.isActive() == null || write.isActive();
        if (!active) {
            guardMandatory(event, channel);
        }

        NotificationTemplate template = new NotificationTemplate();
        template.setEventCode(event.name());
        template.setChannel(channel.name());
        template.setRecipients(NotificationRecipient.join(recipients));
        template.setSubjectTemplate(subject);
        template.setBodyTemplate(write.bodyTemplate().trim());
        template.setActive(active);

        return toView(templates.save(template));
    }

    /**
     * Reword, re-target, switch off.
     *
     * <p><b>{@code eventCode} and {@code channel} are refused if changed, and
     * resending the stored values is a no-op</b> — S-15 submits the whole form on
     * every save, so any other reading makes every edit a 409. B-016's rule about
     * resending a project code, B-020's about a task type code and B-021's about
     * a level code, applied to a two-column identity.
     *
     * <p>Together those two columns <em>are</em> the row: {@code
     * uq_notification_templates} is over the pair, the renderer looks a template
     * up by the pair, and {@code email_log.template_id} rows already sent point
     * at this id. Re-pointing a template at another event would silently change
     * what those historical sends claim to have been rendered from — and would
     * be indistinguishable, afterwards, from having always been that.
     *
     * <p>The order below is deliberate: every rule runs before any field is
     * written, so a refused save leaves the row exactly as it was rather than
     * half-applied. The transaction would roll the row back anyway; the entity in
     * the persistence context would not.
     */
    @Transactional
    public Optional<NotificationTemplateDtos.TemplateView> update(
            long templateId, NotificationTemplateDtos.TemplatePatch patch) {

        Optional<NotificationTemplate> found = templates.findById(templateId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        NotificationTemplate template = found.get();

        if (patch.eventCode() != null
                && !patch.eventCode().trim().toUpperCase(Locale.ROOT)
                        .equals(template.getEventCode())) {
            throw new ImmutableTemplateIdentityException("eventCode",
                    template.getEventCode(), template.getChannel());
        }
        if (patch.channel() != null
                && !patch.channel().trim().toUpperCase(Locale.ROOT).equals(template.getChannel())) {
            throw new ImmutableTemplateIdentityException("channel",
                    template.getEventCode(), template.getChannel());
        }

        NotificationEvent event = requireEvent(template.getEventCode());
        NotificationChannel channel = requireChannel(template.getChannel());

        // The state the row will be in when the patch is done, computed before
        // anything is written — the same technique B-021 used to close the
        // half-applied-entity hole, and needed here because the subject rule and
        // the mandatory rule each read a field the other may be changing.
        String willBeSubject = patch.subjectTemplate() != null
                ? blankToNull(patch.subjectTemplate().orElse(null))
                : template.getSubjectTemplate();
        String willBeBody = patch.bodyTemplate() != null
                ? patch.bodyTemplate().trim()
                : template.getBodyTemplate();
        boolean willBeActive = patch.isActive() != null ? patch.isActive() : template.isActive();

        requireSubjectWhenEmail(channel, willBeSubject);
        validateMergeTags(willBeSubject, willBeBody);
        if (!willBeActive) {
            guardMandatory(event, channel);
        }

        if (patch.recipients() != null) {
            template.setRecipients(
                    NotificationRecipient.join(requireRecipients(patch.recipients())));
        }
        if (patch.bodyTemplate() != null) {
            if (willBeBody.isBlank()) {
                throw new TemplateValidationException("bodyTemplate",
                        "bodyTemplate cannot be blank. A template with no body renders an empty "
                        + "mail, which is worse than no mail — the recipient cannot tell whether "
                        + "something was meant to be there.");
            }
            template.setBodyTemplate(willBeBody);
        }
        if (patch.subjectTemplate() != null) {
            template.setSubjectTemplate(willBeSubject);
        }
        if (patch.isActive() != null) {
            template.setActive(patch.isActive());
        }

        return Optional.of(toView(templates.save(template)));
    }

    // ------------------------------------------------------------------
    // The rules
    // ------------------------------------------------------------------

    /**
     * The mails blueprint §4B.6 marks <b>❌ never optional</b> cannot be switched
     * off from this screen.
     *
     * <p>This is the rule S-15 exists to not break. "Per-event on/off" is in the
     * backlog line and in §4B.6's own description of the master, and taken
     * unqualified it hands an Admin a single click that silences, org-wide, the
     * mails that D-036 spent a whole method making unsilenceable per-user. A
     * ticket assignment that never mails is blueprint §1's headline behaviour
     * quietly gone, and nothing about the screen would say so — the toggle looks
     * like the twenty next to it.
     *
     * <p>The condition is {@link NotificationEvent#isMandatoryMail()} rather than
     * a list of event codes, so it stays exactly in step with the per-user rule
     * it is protecting: both are stated over the event's category, and an
     * escalation event added to the enum next month is covered by both the moment
     * it is declared.
     *
     * <p><b>Mail only, and that is D-036's line rather than a shortcut.</b> §7.7
     * calls mail "the guaranteed channel" and gives the guarantee to it; an
     * in-app toast only reaches somebody who is logged in and a push only reaches
     * a browser that is still subscribed, so neither was ever the thing making an
     * assignment impossible to miss. An Admin who wants a quieter interface can
     * switch the {@code IN_APP} template off and the mail still goes.
     *
     * <p><b>What this deliberately does not guard is the recipient list.</b>
     * Removing {@code ASSIGNEE} from {@code TICKET_ASSIGNED}'s mail would silence
     * it as effectively as the toggle, and it is still permitted — because §11's
     * "To" column is a sensible default rather than a law, and an organisation
     * that routes assignment mail through a shared desk address is doing
     * something legitimate that a frozen list would forbid. The list must be
     * non-empty; what it contains is the Admin's call. The asymmetry is
     * deliberate: one is a switch whose only meaning is "off", the other is a
     * configuration whose meaning is the whole feature.
     */
    private static void guardMandatory(NotificationEvent event, NotificationChannel channel) {
        if (channel != NotificationChannel.EMAIL || !event.isMandatoryMail()) {
            return;
        }
        throw new MandatoryTemplateException(
                "The " + event + " mail cannot be switched off. Blueprint §4B.6 marks assignment, "
                + "handoff, escalation and status-request mail as never optional, and D-036 already "
                + "prevents an individual user from muting it — switching the template off here "
                + "would silence it for everybody at once, which is the same outcome by a route "
                + "nobody would think to check. The in-app template for this event can be switched "
                + "off; mail is the channel §7.7 guarantees. To change what it says, edit it.");
    }

    /**
     * Every {@code {{tag}}} in the subject and body has to resolve.
     *
     * <p>Without this, {@code {{ticketId}}} for {@code {{ticket_id}}} saves
     * cleanly, renders as literal braces in a client-facing mail, and the first
     * person who could notice is the client. The Admin who typed it gets no
     * signal at any point — S-15's whole promise is that they can do this without
     * a developer, which also means without a developer reviewing it.
     *
     * <p>Subject and body are checked together and reported together, so a save
     * that misspells one tag in each is one round trip rather than two.
     */
    private static void validateMergeTags(String subject, String body) {
        Set<String> unknown = new LinkedHashSet<>(MergeTag.unknownIn(subject));
        unknown.addAll(MergeTag.unknownIn(body));
        if (!unknown.isEmpty()) {
            throw new UnknownMergeTagException(List.copyOf(unknown),
                    java.util.Arrays.stream(MergeTag.values()).map(MergeTag::tag).toList());
        }
    }

    /**
     * A mail with no subject line is unsendable, so {@code EMAIL} requires one.
     *
     * <p>The rule is not a {@code @NotBlank} because it depends on another field
     * in the same body. The other two channels do not require a subject and are
     * not refused one — a browser push has a title as well as a body (D-045), and
     * refusing the field would make this master unable to express something the
     * channel has.
     */
    private static void requireSubjectWhenEmail(NotificationChannel channel, String subject) {
        if (channel == NotificationChannel.EMAIL && (subject == null || subject.isBlank())) {
            throw new TemplateValidationException("subjectTemplate",
                    "An email template needs a subject line. OutboxEnqueuer prefixes the ticket "
                    + "code (D-031), so write what happened — 'Handed to you at {{stage}} by "
                    + "{{actor}}' — and not the code itself.");
        }
    }

    // ------------------------------------------------------------------
    // vocabulary checks
    // ------------------------------------------------------------------

    private static NotificationEvent requireEvent(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return NotificationEvent.of(code).orElseThrow(() -> new TemplateValidationException(
                "eventCode", "'" + code + "' is not an event this system raises. A template for an "
                + "event nothing fires is wording that can never be read, and it would sit in the "
                + "grid looking configured. The events are served by "
                + "GET /masters/notification-templates/vocabulary; adding one is a change to "
                + "NotificationEvent and to whatever would raise it, not to this screen."));
    }

    private static NotificationChannel requireChannel(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return NotificationChannel.of(code).orElseThrow(() -> new TemplateValidationException(
                "channel", "'" + code + "' is not a delivery channel. The three are IN_APP, EMAIL "
                + "and PUSH. Note that the bell is not one of them: it renders the same wording as "
                + "the in-app toast, from the IN_APP template."));
    }

    /**
     * At least one recipient, every one of them known.
     *
     * <p>Strict here and tolerant in {@link NotificationRecipient#parse}, the
     * split {@link NotificationEvent} states: a typo is refused at the moment the
     * person who made it is looking at the form, and a stored code a later deploy
     * has dropped is skipped at send time rather than losing the other
     * recipients with it.
     */
    private static List<NotificationRecipient> requireRecipients(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new TemplateValidationException("recipients",
                    "Name at least one recipient. A template with none is a row that looks "
                    + "configured and sends nothing — switch it off instead, which says so.");
        }
        List<NotificationRecipient> parsed = new ArrayList<>(raw.size());
        List<String> unknown = new ArrayList<>();
        for (String token : raw) {
            NotificationRecipient.of(token)
                    .ifPresentOrElse(parsed::add, () -> unknown.add(String.valueOf(token)));
        }
        if (!unknown.isEmpty()) {
            throw new TemplateValidationException("recipients",
                    "Not a recipient this system can resolve: " + String.join(", ", unknown)
                    + ". The list is served by GET /masters/notification-templates/vocabulary. "
                    + "Note that these are positions relative to a ticket rather than roles — "
                    + "'PROJECT_MANAGER' means the PM of this ticket's project, not everybody "
                    + "holding the PM role.");
        }
        return parsed;
    }

    // ------------------------------------------------------------------
    // mapping
    // ------------------------------------------------------------------

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static NotificationTemplateDtos.TemplateView toView(NotificationTemplate template) {
        // Tolerant, because this is a read: a row whose event or channel this
        // build no longer knows still has to render in the grid, carrying the
        // stored code so an Admin can see what it says and switch it off. The
        // category falls back to the stored code's absence rather than throwing.
        Optional<NotificationEvent> event = NotificationEvent.of(template.getEventCode());
        boolean mandatory = event.map(NotificationEvent::isMandatoryMail).orElse(false)
                && NotificationChannel.EMAIL.name().equals(template.getChannel());

        return new NotificationTemplateDtos.TemplateView(
                template.getId(),
                template.getEventCode(),
                event.map(e -> e.category().name()).orElse("OTHER"),
                template.getChannel(),
                NotificationRecipient.parse(template.getRecipients()).stream()
                        .map(Enum::name).toList(),
                template.getSubjectTemplate(),
                template.getBodyTemplate(),
                template.isActive(),
                mandatory);
    }

    // ------------------------------------------------------------------
    // Refusals — see NotificationTemplateExceptionHandler for the wire shapes
    // ------------------------------------------------------------------

    /** A second template for an (event, channel) pair that already has one. */
    static class DuplicateTemplateException extends RuntimeException {
        DuplicateTemplateException(String message) {
            super(message);
        }
    }

    /** Somebody tried to re-point a template at a different event or channel. */
    static class ImmutableTemplateIdentityException extends RuntimeException {
        private final String field;

        ImmutableTemplateIdentityException(String field, String eventCode, String channel) {
            super("A template's event and channel are its identity and cannot be changed. This one "
                  + "is the " + channel + " template for " + eventCode + ". Historical sends in "
                  + "email_log point at this row by id, so re-pointing it would change what those "
                  + "records claim to have been rendered from — and afterwards there would be no "
                  + "way to tell. Create a template on the other event instead.");
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    /** §4B.6's never-optional mail, being switched off. */
    static class MandatoryTemplateException extends RuntimeException {
        MandatoryTemplateException(String message) {
            super(message);
        }
    }

    /** A {@code {{placeholder}}} that resolves to nothing. */
    static class UnknownMergeTagException extends RuntimeException {
        private final List<String> unknownTags;
        private final List<String> knownTags;

        UnknownMergeTagException(List<String> unknownTags, List<String> knownTags) {
            super(unknownTags.size() == 1
                    ? "{{" + unknownTags.get(0) + "}} is not a merge tag, so it would be printed "
                      + "literally — braces included — in every notification this template renders."
                    : "These are not merge tags and would be printed literally, braces included, in "
                      + "every notification this template renders: "
                      + unknownTags.stream().map(t -> "{{" + t + "}}").reduce((a, b) -> a + ", " + b)
                              .orElse(""));
            this.unknownTags = List.copyOf(unknownTags);
            this.knownTags = List.copyOf(knownTags);
        }

        List<String> unknownTags() {
            return unknownTags;
        }

        List<String> knownTags() {
            return knownTags;
        }
    }

    /**
     * 400, field-keyed: a value the caller can correct.
     *
     * <p>Bean Validation cannot express any of the rules that raise this — three
     * are enum membership checks against vocabularies held in another module, and
     * one depends on a second field in the same body — so they run here and come
     * back in the shape a {@code @Pattern} failure would.
     */
    static class TemplateValidationException extends RuntimeException {
        private final String field;

        TemplateValidationException(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return field;
        }
    }
}
