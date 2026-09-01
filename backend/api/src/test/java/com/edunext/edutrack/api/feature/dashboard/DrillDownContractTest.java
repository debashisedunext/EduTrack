package com.edunext.edutrack.api.feature.dashboard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-060 · every parameter the dashboard deep-links with is one the ticket list
 * actually implements.
 *
 * <h2>The defect this exists to make impossible</h2>
 *
 * <p>From A-055 until A-060 every date-scoped card and chart segment emitted
 * {@code ?from=…&to=…}, and {@code TicketListController} had no such parameter.
 * Spring drops unknown query parameters silently, so the link worked, the list
 * loaded, the other filters applied — and the window did not. A card reading
 * "31 created this week" opened a list of every ticket ever created. <b>Nothing
 * anywhere reported an error</b>, on either side, and the only way to notice was
 * to count the rows.
 *
 * <p>Three tasks emitted that convention before anyone caught it, because each
 * new widget copied the one before. A test is the only thing that closes a gap
 * shaped like this: it is not a bug in either component, it is a disagreement
 * between two, and neither one's own suite can see it.
 *
 * <h2>Why reflection rather than a hand-kept list</h2>
 *
 * <p>Reading {@code TicketListController}'s actual signature means the
 * allow-list cannot go stale. A hand-written {@code Set.of("projectId", …)}
 * would be a third statement of the same truth, and the first thing to drift
 * when Stream C adds a filter — which is exactly the failure mode being tested
 * for, reintroduced one level up.
 *
 * <p><b>This is a unit test on purpose.</b> It needs no database and no Spring
 * context: it compares two things that are both knowable statically, and making
 * it an integration test would have added a MySQL container to a suite whose
 * per-class container cost is already the gate's dominant expense.
 */
class DrillDownContractTest {

    /**
     * Every {@code key=} in a drill-down URL the dashboard can emit.
     *
     * <p>Taken from the source rather than by calling the services, because a
     * link built only for a Developer, or only when a project filter is active,
     * would otherwise never be reached — and those conditional branches are
     * precisely where a typo survives review.
     */
    private static final Pattern QUERY_KEY = Pattern.compile("[?&]([a-zA-Z][a-zA-Z0-9_]*)=");

    private static final String[] SOURCES = {
            "src/main/java/com/edunext/edutrack/api/feature/dashboard/DashboardService.java",
            "src/main/java/com/edunext/edutrack/api/feature/dashboard/WidgetService.java",
            // Dashboard Rework Dev 1, PR 6 · every /tickets link the Today tab emits.
            "src/main/java/com/edunext/edutrack/api/feature/dashboard/TodayProgressService.java",
            // Dashboard Rework Dev 2, PR 9 · every /tickets link the Overview tab emits.
            "src/main/java/com/edunext/edutrack/api/feature/dashboard/OverviewService.java",
    };

    @Test
    @DisplayName("every drill-down parameter is one GET /tickets implements")
    void everyDrillDownParameterIsHonoured() throws Exception {
        Set<String> emitted = emittedParameters();
        Set<String> accepted = listParameters();

        assertThat(emitted)
                .as("the dashboard emits these; GET /tickets accepts %s", accepted)
                .isNotEmpty()
                .allSatisfy(parameter -> assertThat(accepted)
                        .as("'%s' is emitted in a drill-down but the ticket list "
                                + "does not implement it — Spring will drop it silently and the "
                                + "list will open wider than the figure that was clicked", parameter)
                        .contains(parameter));
    }

    /**
     * The specific pair that was missing, named so a regression reads as itself
     * rather than as "some parameter is unknown".
     */
    @Test
    @DisplayName("the reported-date window survives, since three widgets depend on it")
    void reportedDateWindowExists() throws Exception {
        assertThat(listParameters()).contains("reportedFrom", "reportedTo");
        assertThat(emittedParameters())
                .as("the cards and the daily charts scope by when a ticket was raised")
                .contains("reportedFrom", "reportedTo");
    }

    /**
     * {@code from}/{@code to} were the names that silently did nothing. Nothing
     * should reintroduce them: they are also ambiguous on a screen that already
     * filters on three different dates.
     */
    @Test
    @DisplayName("the old bare from/to convention is gone and stays gone")
    void bareFromToIsNotReintroduced() throws Exception {
        assertThat(emittedParameters()).doesNotContain("from", "to");
    }

    // ── the two sides being compared ─────────────────────────────────────────

    private static Set<String> emittedParameters() throws Exception {
        Set<String> keys = new LinkedHashSet<>();
        for (String source : SOURCES) {
            String text = java.nio.file.Files.readString(java.nio.file.Path.of(source));
            // Only the literal fragments that build a /tickets link. Matching the
            // whole file would sweep up unrelated query strings if any ever
            // appear here.
            Matcher links = Pattern.compile("\"/tickets\\?[^\"]*\"|\"[?&][a-zA-Z][^\"]*\"")
                    .matcher(text);
            while (links.find()) {
                Matcher key = QUERY_KEY.matcher(links.group());
                while (key.find()) {
                    keys.add(key.group(1));
                }
            }
            // The window strings are built as bare `key=` concatenations rather
            // than inside a `/tickets?` literal, so they are collected too.
            Matcher bare = Pattern.compile("\"([a-zA-Z][a-zA-Z0-9_]*)=\"").matcher(text);
            while (bare.find()) {
                keys.add(bare.group(1));
            }
            Matcher amp = Pattern.compile("\"&([a-zA-Z][a-zA-Z0-9_]*)=\"").matcher(text);
            while (amp.find()) {
                keys.add(amp.group(1));
            }
        }
        return keys;
    }

    private static Set<String> listParameters() throws Exception {
        Class<?> controller = Class.forName(
                "com.edunext.edutrack.api.feature.tickets.list.TicketListController");
        Method list = Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.getName().equals("list"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "TicketListController.list is gone — this test compares against its signature"));

        return Arrays.stream(list.getParameters())
                .filter(p -> p.isAnnotationPresent(RequestParam.class))
                .map(DrillDownContractTest::parameterName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * {@code @RequestParam("x")} wins over the declared name, which is how a
     * parameter can be called one thing in Java and another on the wire.
     *
     * <p>Falls back to the reflective name, which requires {@code -parameters}
     * — Spring Boot's parent POM sets it, and without it every name would read
     * {@code arg0} and this test would fail loudly rather than pass vacuously.
     */
    private static String parameterName(Parameter parameter) {
        RequestParam annotation = parameter.getAnnotation(RequestParam.class);
        if (!annotation.value().isBlank()) {
            return annotation.value();
        }
        if (!annotation.name().isBlank()) {
            return annotation.name();
        }
        return parameter.getName();
    }
}
