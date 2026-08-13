package com.edunext.edutrack.api.feature.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-054 · which ticket codes a message refers to.
 *
 * <p>The interesting half is what must <em>not</em> match. A preview card
 * unfurling out of a filename is noise; a parser loose enough to find codes
 * inside arbitrary text turns chat into a probe for which ticket codes exist.
 */
class TicketRefParserTest {

    @Nested
    @DisplayName("finds what people actually write")
    class Finds {

        @Test
        @DisplayName("a bare code")
        void bareCode() {
            assertThat(TicketRefParser.codesIn("CRM-26-00347 is the one"))
                    .containsExactly("CRM-26-00347");
        }

        @Test
        @DisplayName("a code in the middle of a sentence, and with punctuation after it")
        void inProse() {
            assertThat(TicketRefParser.codesIn("Related to CRM-26-00347, which Ravi closed."))
                    .containsExactly("CRM-26-00347");
        }

        @Test
        @DisplayName("several codes, in the order they were written, without duplicates")
        void severalInOrder() {
            assertThat(TicketRefParser.codesIn(
                    "WEB-26-00012 blocks CRM-26-00347 and CRM-26-00347 blocks PAY-25-00001"))
                    .containsExactly("WEB-26-00012", "CRM-26-00347", "PAY-25-00001");
        }

        @Test
        @DisplayName("a project code that happens to end in another one is still its own code")
        void aProjectCodeIsNotASuffixOfAnother() {
            // XCRM-26-00347 looks like it embeds CRM-26-00347, and the first
            // draft of this test asserted it matched nothing for that reason.
            // XCRM is a perfectly legal PROJECT_CODE, so this is one code for
            // project XCRM — not a longer token containing a shorter code. The
            // test was wrong, not the parser.
            assertThat(TicketRefParser.codesIn("XCRM-26-00347 is separate"))
                    .containsExactly("XCRM-26-00347");
        }

        @Test
        @DisplayName("the shortest and longest project codes C-011 permits")
        void projectCodeBounds() {
            // PROJECT_CODE is [A-Z][A-Z0-9]{1,9} — two to ten characters.
            assertThat(TicketRefParser.codesIn("AB-26-00001 and ABCDEFGHIJ-26-00002"))
                    .containsExactly("AB-26-00001", "ABCDEFGHIJ-26-00002");
        }

        @Test
        @DisplayName("a code inside brackets, as it appears in a mail subject")
        void inBrackets() {
            assertThat(TicketRefParser.codesIn("Re: [CRM-26-00347] Handed to you at QA"))
                    .containsExactly("CRM-26-00347");
        }
    }

    @Nested
    @DisplayName("does not match things that only look like codes")
    class DoesNotMatch {

        @Test
        @DisplayName("a code inside a filename is a filename")
        void insideAFilename() {
            // A card unfurling out of a build artefact's name is noise, and a
            // parser that does it can be fed a list of guesses to find out
            // which codes exist.
            assertThat(TicketRefParser.codesIn("see builds/CRM-26-00347-final.zip")).isEmpty();
        }

        @Test
        @DisplayName("a code with extra digits stuck on the end is not a code")
        void trailingDigits() {
            assertThat(TicketRefParser.codesIn("CRM-26-003470")).isEmpty();
        }

        @Test
        @DisplayName("a code with a hyphenated suffix is not a code")
        void hyphenatedSuffix() {
            assertThat(TicketRefParser.codesIn("CRM-26-00347-rev2")).isEmpty();
        }

        @Test
        @DisplayName("the blueprint's illustrative TKT-000871 — no ticket has ever looked like that")
        void theBlueprintsMockupFormat() {
            // §7.6 and §4A.1 print TKT-xxxx. C-011 mints
            // {PROJECT}-{YY}-{NNNNN}. Matching the mockup would recognise
            // nothing real, and pretending otherwise would hide the deviation.
            assertThat(TicketRefParser.codesIn("TKT-000871 is overdue")).isEmpty();
        }

        @Test
        @DisplayName("lowercase is not a ticket code")
        void lowercase() {
            assertThat(TicketRefParser.codesIn("crm-26-00347")).isEmpty();
        }

        @Test
        @DisplayName("the wrong number of digits")
        void wrongDigitCounts() {
            assertThat(TicketRefParser.codesIn("CRM-2026-00347 CRM-26-0347 CRM-26-000347")).isEmpty();
        }

        @Test
        @DisplayName("a date is not a ticket code")
        void aDate() {
            assertThat(TicketRefParser.codesIn("due 2026-08-12")).isEmpty();
        }

        @Test
        @DisplayName("null and blank")
        void nullAndBlank() {
            assertThat(TicketRefParser.codesIn(null)).isEmpty();
            assertThat(TicketRefParser.codesIn("   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("the cap")
    class Cap {

        @Test
        @DisplayName("stops at MAX_REFS so one message cannot fan out unboundedly")
        void capped() {
            StringBuilder body = new StringBuilder();
            for (int i = 1; i <= TicketRefParser.MAX_REFS + 5; i++) {
                body.append("CRM-26-%05d ".formatted(i));
            }

            assertThat(TicketRefParser.codesIn(body.toString()))
                    .hasSize(TicketRefParser.MAX_REFS)
                    .startsWith("CRM-26-00001");
        }
    }

    @Nested
    @DisplayName("agrees with the formatter that mints the codes")
    class AgreesWithC011 {

        @Test
        @DisplayName("every code C-011's own format produces is recognised here")
        void roundTripsAgainstTicketCode() {
            // The parser duplicates C-011's pattern because TicketCode is
            // package-private to feature/tickets. This is what stops the two
            // drifting: codes built to C-011's documented rule, parsed here.
            String[] codes = {
                    "%s-%02d-%05d".formatted("CRM", 2026 % 100, 347L),
                    "%s-%02d-%05d".formatted("AB", 2025 % 100, 1L),
                    "%s-%02d-%05d".formatted("ABCDEFGHIJ", 2099 % 100, 99999L),
            };
            for (String code : codes) {
                assertThat(TicketRefParser.codesIn("about " + code + " today"))
                        .as("should recognise %s", code)
                        .containsExactly(code);
            }
        }
    }
}
