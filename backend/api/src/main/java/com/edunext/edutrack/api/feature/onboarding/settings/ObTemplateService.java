package com.edunext.edutrack.api.feature.onboarding.settings;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B-113 · OB-12 — the module's notification wording.
 *
 * <h2>Two fields are derived and neither is a column</h2>
 *
 * <p><b>{@code isMandatory}</b> is {@code channel == EMAIL} and the category is
 * {@code ESCALATION} or {@code SIGNOFF}. A stored flag would be a second copy of
 * a rule deliberately stated over the category, and an escalation event declared
 * next month would need somebody to remember to set it; derived, it is covered
 * the moment the event exists.
 *
 * <p><b>{@code isDeliverable}</b> is whether this deployment has an adapter for
 * the channel — false for every {@code WHATSAPP} row in phase 2
 * (PHASE-2-BUILD-PLAN §6.1 defers it entirely: no provider, no adapter, no
 * webhook). It is a fact about the deployment rather than about the row, so a
 * column would be a cached answer to a question the running system can answer.
 * OB-12 renders it as "authored, not yet sending" rather than presenting a
 * template that will queue forever looking configured.
 *
 * <h2>An unknown merge tag is refused at write time</h2>
 *
 * <p>This is the last place to catch it. A body containing {@code {{clietn_name}}}
 * renders as literal braces in a client's inbox, and the person who reads it is
 * the one customer who was going to notice. The vocabulary is the event
 * catalogue's own variables — {@code ObNotificationEvent} is the contract both
 * ends already agree on — so a tag added with a new event is offered and
 * accepted the same day, without a second list to update.
 */
@Service
class ObTemplateService {

    /**
     * B-022's {@code {{tag}}} dialect, which {@code ObMailRenderer} substitutes.
     * Matched loosely on purpose: {@code {{ client_name }}} with spaces is the
     * commonest way to type one, and refusing it for whitespace would be a rule
     * about formatting dressed up as a rule about correctness.
     */
    private static final Pattern MERGE_TAG = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    /**
     * <p>The four role codes an onboarding notification can address, plus the
     * client contact. Not {@code roles} in the database: these are the audiences
     * the module's producers resolve, and only three of them are staff roles at
     * all.
     */
    private static final List<String> RECIPIENTS = List.of(
            "STEP_OWNER", "BACKUP_OWNER", "ONBOARDING_MANAGER", "OB_ADMIN", "CLIENT_CONTACT");

    /**
     * <p>{@code EMAIL} only, in phase 2. {@code IN_APP} is B-112's own store and
     * its wording lives in {@code ObInAppTemplate}; {@code WHATSAPP} has no
     * adapter. Both are still offered by the vocabulary, because a template can
     * be authored before it can be sent — which is exactly what
     * {@code isDeliverable} exists to say.
     */
    private static final Set<ObChannel> DELIVERABLE = Set.of(ObChannel.EMAIL);

    private static final Set<ObTemplateDtos.Category> MANDATORY_CATEGORIES =
            Set.of(ObTemplateDtos.Category.ESCALATION, ObTemplateDtos.Category.SIGNOFF);

    private final ObTemplateRepository templates;

    ObTemplateService(ObTemplateRepository templates) {
        this.templates = templates;
    }

    @Transactional(readOnly = true)
    List<ObTemplateDtos.Template> list(ObChannel channel, ObTemplateDtos.Category category) {
        return templates.list(channel, category).stream().map(ObTemplateService::toDto).toList();
    }

    @Transactional(readOnly = true)
    ObTemplateDtos.Template get(long templateId) {
        return toDto(templates.findById(templateId)
                .orElseThrow(() -> new ObTemplateNotFoundException(templateId)));
    }

    /**
     * @throws ObTemplateNotFoundException  no such template
     * @throws MandatoryTemplateException   switching off an {@code ESCALATION}
     *                                      or {@code SIGNOFF} mail
     * @throws UnknownMergeTagException     the body names a tag no event declares
     */
    @Transactional
    ObTemplateDtos.Template update(long templateId, ObTemplateDtos.UpdateRequest request,
                                   Long actorUserId) {

        ObTemplateRepository.Stored current = templates.findById(templateId)
                .orElseThrow(() -> new ObTemplateNotFoundException(templateId));

        if (Boolean.FALSE.equals(request.isActive()) && isMandatory(current)) {
            throw new MandatoryTemplateException(current.eventCode(), current.category());
        }
        if (request.bodyTemplate() != null) {
            requireKnownTags(request.bodyTemplate());
        }
        if (request.subjectTemplate() != null) {
            requireKnownTags(request.subjectTemplate());
        }

        templates.update(templateId, request, actorUserId);
        return get(templateId);
    }

    /**
     * The vocabulary OB-12's editor offers, served rather than hardcoded.
     *
     * <p>Events come from {@code ObNotificationEvent} rather than from the
     * templates table, deliberately: the catalogue is the producers' list, and a
     * vocabulary read off the rows would silently stop offering an event whose
     * template somebody deleted.
     */
    @Transactional(readOnly = true)
    ObTemplateDtos.Vocabulary vocabulary() {
        Map<String, ObTemplateDtos.Category> byEvent = templates.list(null, null).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ObTemplateRepository.Stored::eventCode,
                        ObTemplateRepository.Stored::category,
                        (first, second) -> first));

        List<ObTemplateDtos.EventOption> events = ObNotificationEvent.all().stream()
                .sorted(Comparator.comparing(ObNotificationEvent::key))
                .map(event -> {
                    // An event with no row yet has no category to report. SERVICE
                    // is the honest default rather than a null the screen would
                    // have to branch on: it is the only category that carries no
                    // rule, so guessing it wrong cannot lock or unlock a toggle.
                    ObTemplateDtos.Category category =
                            byEvent.getOrDefault(event.key(), ObTemplateDtos.Category.SERVICE);
                    return new ObTemplateDtos.EventOption(
                            event.key(), category, MANDATORY_CATEGORIES.contains(category));
                })
                .toList();

        return new ObTemplateDtos.Vocabulary(
                events,
                List.of(ObChannel.values()),
                RECIPIENTS,
                knownTags().stream().map(tag -> "{{" + tag + "}}").toList());
    }

    /**
     * Every variable any event declares, required and optional alike, as bare
     * names.
     *
     * <p><b>Served braced, validated bare.</b> The vocabulary hands OB-12
     * {@code "{{client_name}}"} because the MSW mock has served that shape since
     * A-118 and a screen built against one form would break against the other —
     * and the palette inserts what it is given, so the braced form is the one
     * that is directly usable. Validation works on the bare name the regex
     * captures. The contract says only "every tag a body may contain", which
     * admits both; this is the incumbent shape rather than a new choice.
     *
     * <p>A {@code TreeSet} so the served list is stable between calls — the
     * screen renders it as a palette, and a palette that reorders itself on
     * every load is one nobody can learn.
     */
    private static Set<String> knownTags() {
        Set<String> tags = new TreeSet<>();
        for (ObNotificationEvent event : ObNotificationEvent.all()) {
            tags.addAll(event.variables());
        }
        return tags;
    }

    private static void requireKnownTags(String text) {
        Set<String> known = knownTags();
        Set<String> unknown = new LinkedHashSet<>();
        Matcher matcher = MERGE_TAG.matcher(text);
        while (matcher.find()) {
            String tag = matcher.group(1);
            if (!known.contains(tag)) {
                unknown.add(tag);
            }
        }
        if (!unknown.isEmpty()) {
            throw new UnknownMergeTagException(unknown);
        }
    }

    private static boolean isMandatory(ObTemplateRepository.Stored template) {
        return template.channel() == ObChannel.EMAIL
                && MANDATORY_CATEGORIES.contains(template.category());
    }

    private static ObTemplateDtos.Template toDto(ObTemplateRepository.Stored stored) {
        return new ObTemplateDtos.Template(
                stored.id(),
                stored.eventCode(),
                stored.category(),
                stored.channel(),
                stored.recipients(),
                stored.subjectTemplate(),
                stored.bodyTemplate(),
                stored.isActive(),
                isMandatory(stored),
                DELIVERABLE.contains(stored.channel()));
    }
}
