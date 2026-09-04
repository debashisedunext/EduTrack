package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.notifications.MergeTag;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-111 · the catalogue checked against the contract it renders from.
 *
 * <p>This is the test that earns its place. Every other failure in this feature
 * is visible in a log or in a test that renders one mail; a template referencing
 * a variable no enqueuer sends is invisible everywhere — it renders, the
 * paragraph is quietly dropped, and the mail simply says less than it was
 * written to say. Section 11's matrix test does the same job for ticket mail,
 * for the same reason.
 */
class ObMailCatalogueTest {

    @Test
    @DisplayName("every placeholder in every template is a variable its event declares")
    void placeholdersAreDeclared() {
        List<String> undeclared = new ArrayList<>();
        for (ObMailTemplate template : ObMailTemplate.values()) {
            Set<String> allowed = template.event().variables();
            for (String used : placeholdersIn(template.subject() + template.body())) {
                if (!allowed.contains(used)) {
                    undeclared.add(template + " uses {{" + used + "}}, which "
                            + template.event().key() + " does not declare");
                }
            }
        }
        // The list rather than the first failure: a rename touches several
        // templates at once, and fixing them one build at a time is how the
        // fifth one gets forgotten.
        assertThat(undeclared).isEmpty();
    }

    @Test
    @DisplayName("every event has wording for at least one audience")
    void everyEventIsCovered() {
        for (ObNotificationEvent event : ObNotificationEvent.values()) {
            boolean covered = ObMailTemplate.forEvent(event, ObMailAudience.STAFF).isPresent()
                    || ObMailTemplate.forEvent(event, ObMailAudience.CLIENT).isPresent();
            assertThat(covered)
                    .as("%s would fall to the generic notice", event)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("no two templates claim the same event and audience")
    void resolutionIsUnambiguous() {
        Set<String> seen = new HashSet<>();
        for (ObMailTemplate template : ObMailTemplate.values()) {
            String pair = template.event() + "/" + template.audience();
            assertThat(seen.add(pair))
                    .as("%s is the second template for %s — forEvent picks whichever is "
                            + "declared first, which is not a decision anybody made", template, pair)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the fallback subject never contains a placeholder")
    void theFallbackIsStatic() {
        // It exists precisely for the case where a value is missing. A
        // placeholder in it would render as nothing and leave the same hanging
        // sentence the fallback was reached to avoid.
        for (ObMailTemplate template : ObMailTemplate.values()) {
            assertThat(placeholdersIn(template.fallbackSubject()))
                    .as("%s's fallback subject", template)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("both subjects are present and are one line")
    void subjectsAreSubjects() {
        for (ObMailTemplate template : ObMailTemplate.values()) {
            assertThat(template.subject()).as("%s", template).isNotBlank().doesNotContain("\n");
            assertThat(template.fallbackSubject()).as("%s", template).isNotBlank().doesNotContain("\n");
            // No hard limit in the schema — ob_notification_outbox stores no
            // subject at all — but a subject past ~120 characters is truncated
            // by most clients, and the truncation lands mid-sentence.
            assertThat(template.fallbackSubject().length()).as("%s", template).isLessThan(120);
        }
    }

    @Test
    @DisplayName("every body is marked up in paragraphs")
    void bodiesArePruneable() {
        // The renderer drops a paragraph whose placeholders did not all resolve.
        // A body written as bare text is one block, so an absent optional value
        // takes the whole mail down to the generic notice.
        for (ObMailTemplate template : ObMailTemplate.values()) {
            assertThat(template.body()).as("%s", template).contains("<p>").contains("</p>");
        }
    }

    @Test
    @DisplayName("an optional variable stands in a paragraph of its own")
    void optionalValuesAreSeverable() {
        List<String> inlined = new ArrayList<>();
        for (ObMailTemplate template : ObMailTemplate.values()) {
            Set<String> optional = template.event().optionalVariables();
            for (String paragraph : paragraphsIn(template.body())) {
                Set<String> used = placeholdersIn(paragraph);
                boolean carriesOptional = used.stream().anyMatch(optional::contains);
                boolean carriesRequired = used.stream()
                        .anyMatch(template.event().requiredVariables()::contains);
                if (carriesOptional && carriesRequired) {
                    inlined.add(template + ": " + used);
                }
            }
        }
        // Mixing them means an absent optional takes a sentence built on a
        // required value with it — the reader loses the fact the mail was for.
        // Splitting the sentence in two costs nothing and keeps the pruning
        // rule honest.
        assertThat(inlined).isEmpty();
    }

    @Test
    @DisplayName("a client-facing template never names internal detail")
    void clientTemplatesRespectThePortalBoundary() {
        // CP-03 shows a client step status and nothing else — no owner names, no
        // internal comments, no block reasons. A mail is not a way around that.
        Set<String> internal = Set.of("owner_name", "skipped_by", "skip_reason",
                "verified_by", "returned_by", "raised_by", "resolved_by", "requested_by");
        for (ObMailTemplate template : ObMailTemplate.values()) {
            if (template.audience() != ObMailAudience.CLIENT) {
                continue;
            }
            assertThat(placeholdersIn(template.subject() + template.body()))
                    .as("%s is read by a client", template)
                    .doesNotContainAnyElementsOf(internal);
        }
    }

    @Test
    @DisplayName("a breach or an escalation is chipped, and routine mail is not")
    void urgencyMatchesTheEvent() {
        assertThat(ObMailTemplate.TAT_BREACHED.urgency()).isEqualTo(ObMailTemplate.Urgency.BREACH);
        assertThat(ObMailTemplate.ESCALATION_RAISED.urgency()).isEqualTo(ObMailTemplate.Urgency.BREACH);
        assertThat(ObMailTemplate.CLIENT_ESCALATION_RAISED.urgency())
                .isEqualTo(ObMailTemplate.Urgency.BREACH);
        assertThat(ObMailTemplate.TAT_REMINDER.urgency()).isEqualTo(ObMailTemplate.Urgency.ATTENTION);
        // §12.1 as a rule: a chip on everything is a chip on nothing.
        assertThat(ObMailTemplate.GO_LIVE_CLIENT.urgency()).isEqualTo(ObMailTemplate.Urgency.NONE);
        assertThat(ObMailTemplate.PREREQ_VERIFIED.urgency()).isEqualTo(ObMailTemplate.Urgency.NONE);
    }

    @Test
    @DisplayName("the chip colours are the design tokens, soft background and solid text")
    void chipColoursAreTokens() {
        // Blueprint §12.1's High and Critical pairs, the same hex LevelChip uses
        // on the ticketing side. A colour introduced here is a colour that is not
        // a token, which CLAUDE.md rules out.
        assertThat(ObMailTemplate.Urgency.ATTENTION.background()).isEqualTo("#FFFBEB");
        assertThat(ObMailTemplate.Urgency.ATTENTION.text()).isEqualTo("#B45309");
        assertThat(ObMailTemplate.Urgency.BREACH.background()).isEqualTo("#FEF2F2");
        assertThat(ObMailTemplate.Urgency.BREACH.text()).isEqualTo("#B91C1C");
        assertThat(ObMailTemplate.Urgency.NONE.label()).isEmpty();
    }

    @Test
    @DisplayName("the sign-off code mail has no button")
    void theOtpMailHasNoButton() {
        // The reader already has the sign-off page open. A second entry point
        // sends them to a fresh attempt the code they were given does not match.
        assertThat(ObMailTemplate.SIGNOFF_OTP.actionLabel()).isNull();
    }

    @Test
    @DisplayName("every other template's button says what it opens")
    void buttonsAreLabelled() {
        for (ObMailTemplate template : ObMailTemplate.values()) {
            if (template == ObMailTemplate.SIGNOFF_OTP) {
                continue;
            }
            assertThat(template.actionLabel()).as("%s", template).isNotBlank();
        }
    }

    // ───────────────────────────────────────────────────────────────── helpers

    private static Set<String> placeholdersIn(String text) {
        Set<String> names = new HashSet<>();
        Matcher matcher = MergeTag.PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static List<String> paragraphsIn(String body) {
        List<String> paragraphs = new ArrayList<>();
        Matcher matcher = java.util.regex.Pattern.compile("(?s)<p\\b[^>]*>.*?</p>").matcher(body);
        while (matcher.find()) {
            paragraphs.add(matcher.group());
        }
        return paragraphs;
    }
}
