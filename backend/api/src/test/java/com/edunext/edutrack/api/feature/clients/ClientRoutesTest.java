package com.edunext.edutrack.api.feature.clients;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-025 · the clients controller is mounted where the contract says it is.
 *
 * <p>{@code MasterRoutesTest} makes this assertion for every controller in the
 * masters feature and cannot make it for this one — {@code feature/clients} is a
 * sibling package, not a subpackage, because the contract serves clients at the
 * top level rather than under {@code /masters}. The rule it enforces applies here
 * unchanged, so it is repeated rather than skipped.
 *
 * <p><b>This exists because the calendar's prefix was wrong.</b> B-023 shipped
 * {@code CalendarController} mapped at {@code /masters}, and nothing in the
 * application declares {@code /api/v1} globally — no servlet context path, no
 * {@code configurePathMatch} — so all nine calendar operations answered on a path
 * no client calls. A wiring mistake needs a wiring test, and the unit tests in
 * this package construct the controller with {@code new}, which never asks Spring
 * where the class is mounted.
 *
 * <p>Reflection over the annotations rather than a {@code @SpringBootTest}: this
 * catches the same class of mistake in milliseconds and without a database.
 */
class ClientRoutesTest {

    @Test
    @DisplayName("the client master is at /api/v1/clients, not under /masters")
    void clientMasterIsMountedWhereTheContractPutsIt() {
        RequestMapping mapping = ClientController.class.getAnnotation(RequestMapping.class);

        assertThat(mapping)
                .as("ClientController has no @RequestMapping")
                .isNotNull();
        assertThat(paths(mapping))
                .as("the contract has served clients at the top level since D-001; "
                        + "moving them under /masters would break every caller")
                .containsExactly("/api/v1/clients");
    }

    /**
     * The literal beats the variable — asserted, not assumed.
     *
     * <p>{@code /bulk-status} and {@code /{clientId}/status} are both three
     * segments under {@code /clients}, and the bulk route only works because
     * Spring's {@code PathPatternParser} ranks a literal segment above a path
     * variable. That ranking is what makes the route reachable at all, so this
     * pins the two mappings that depend on it: if somebody ever renames the bulk
     * path to something that could parse as an id, this fails rather than the
     * route quietly starting to 400.
     */
    @Test
    @DisplayName("the bulk setter's path cannot be mistaken for a client id")
    void bulkStatusPathIsNotNumeric() {
        String bulkPath = mappingOf("bulkStatus");

        assertThat(bulkPath).isEqualTo("/bulk-status");
        assertThat(mappingOf("status")).isEqualTo("/{clientId}/status");
        assertThat(bulkPath.replace("/", "").chars().allMatch(Character::isDigit))
                .as("a bulk path that parses as a long would be ambiguous with /{clientId}")
                .isFalse();
    }

    private static String mappingOf(String methodName) {
        Method method = Arrays.stream(ClientController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "ClientController." + methodName + " is gone. If it was renamed, "
                                + "update this test — the routing rule still applies to it."));

        PatchMapping mapping = method.getAnnotation(PatchMapping.class);
        assertThat(mapping).as("%s is not a @PatchMapping", methodName).isNotNull();
        return mapping.value().length > 0 ? mapping.value()[0] : mapping.path()[0];
    }

    private static String[] paths(RequestMapping mapping) {
        return mapping.value().length > 0 ? mapping.value() : mapping.path();
    }
}
