package com.edunext.edutrack.api.feature.clients;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    /**
     * B-026 · the three S-33 operations exist and are mapped where the contract
     * declares them.
     *
     * <p>Not a formality. All six client operations were "declared, mocked and
     * never mounted" until B-025 — the sixth such set this stream has found — and
     * B-014's {@code PATCH /users/{userId}/status} is the cautionary case for
     * exactly this shape: it was in the contract, answered by the MSW mock,
     * described by three javadocs, and no server ever mounted it, because a
     * {@code PATCH} to that path met a sibling mapping and came back 405.
     * {@code MasterRoutesTest} could not have caught it — it asserts where
     * <em>classes</em> are mounted and that was a missing <em>method</em>. This
     * is the method-level version of the same claim, added on the day the
     * methods were written rather than after a gap was found.
     */
    @Test
    @DisplayName("S-33's detail read, create and update are all mounted")
    void s33OperationsAreMounted() {
        assertThat(getMappingOf("get"))
                .as("without GET /clients/{clientId} there is nowhere to obtain the "
                        + "ETag that updateClient requires as If-Match")
                .isEqualTo("/{clientId}");

        assertThat(hasMapping("create", PostMapping.class))
                .as("createClient has been in the contract since D-001")
                .isTrue();

        assertThat(mappingOf("update")).isEqualTo("/{clientId}");
    }

    /**
     * B-027 · the three contact writes are mounted where the contract declares
     * them.
     *
     * <p>{@code createClientContact} was the <b>seventh</b> "declared, mocked,
     * never mounted" operation this stream has found, and the other two verbs
     * were not declared at all. B-014's {@code PATCH /users/{userId}/status} is
     * the cautionary case for this exact shape: a {@code PATCH} that met a
     * sibling mapping and came back 405, invisible to
     * {@code MasterRoutesTest} because that asserts where <em>classes</em> are
     * mounted and it was a missing <em>method</em>.
     *
     * <p><b>The {@code DELETE} matters most here.</b> It is the only mutation
     * verb this feature has, and its path has two variables where every other
     * route in the controller has at most one — so it is the one most able to be
     * shadowed by a sibling and answer 405 with nothing failing.
     */
    @Test
    @DisplayName("S-33's contact add, edit and remove are all mounted")
    void b027OperationsAreMounted() {
        assertThat(postMappingOf("addContact")).isEqualTo("/{clientId}/contacts");
        assertThat(mappingOf("editContact")).isEqualTo("/{clientId}/contacts/{contactId}");
        assertThat(deleteMappingOf("removeContact"))
                .as("removing a contact deactivates it; the verb is still DELETE, and a "
                        + "missing mapping answers 405 with nothing else failing")
                .isEqualTo("/{clientId}/contacts/{contactId}");
    }

    /**
     * There is no {@code PUT}, and this fails if anybody adds one.
     *
     * <p>The body is the whole representation, which is exactly the argument
     * somebody will make for {@code PUT} — and the verb is {@code PATCH} because
     * that is what the contract has declared since D-001 and what the generated
     * client emits. B-020's {@code thereIsNoDelete} is the same shape of test for
     * the same reason: a route added later would compile and pass every other
     * test in this file.
     */
    @Test
    @DisplayName("no contact route is a PUT")
    void thereIsNoPut() {
        assertThat(Arrays.stream(ClientController.class.getDeclaredMethods())
                .anyMatch(m -> m.getAnnotation(
                        org.springframework.web.bind.annotation.PutMapping.class) != null))
                .as("the contract has declared PATCH since D-001 and the generated client "
                        + "emits it; a PUT beside it would be a second way to say one thing")
                .isFalse();
    }

    /**
     * {@code POST /clients} sits on the class mapping with no path of its own,
     * and {@code PATCH /clients/{clientId}} has one. If either ever grows or
     * loses a segment the contract stops describing the server.
     */
    private static boolean hasMapping(String methodName, Class<PostMapping> annotation) {
        return method(methodName).getAnnotation(annotation) != null;
    }

    private static String postMappingOf(String methodName) {
        PostMapping mapping = method(methodName).getAnnotation(PostMapping.class);
        assertThat(mapping).as("%s is not a @PostMapping", methodName).isNotNull();
        return mapping.value().length > 0 ? mapping.value()[0] : mapping.path()[0];
    }

    private static String deleteMappingOf(String methodName) {
        DeleteMapping mapping = method(methodName).getAnnotation(DeleteMapping.class);
        assertThat(mapping).as("%s is not a @DeleteMapping", methodName).isNotNull();
        return mapping.value().length > 0 ? mapping.value()[0] : mapping.path()[0];
    }

    private static String getMappingOf(String methodName) {
        GetMapping mapping = method(methodName).getAnnotation(GetMapping.class);
        assertThat(mapping).as("%s is not a @GetMapping", methodName).isNotNull();
        return mapping.value().length > 0 ? mapping.value()[0] : mapping.path()[0];
    }

    private static Method method(String methodName) {
        return Arrays.stream(ClientController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "ClientController." + methodName + " is gone. If it was renamed, "
                                + "update this test — the routing rule still applies to it."));
    }

    private static String mappingOf(String methodName) {
        PatchMapping mapping = method(methodName).getAnnotation(PatchMapping.class);
        assertThat(mapping).as("%s is not a @PatchMapping", methodName).isNotNull();
        return mapping.value().length > 0 ? mapping.value()[0] : mapping.path()[0];
    }

    private static String[] paths(RequestMapping mapping) {
        return mapping.value().length > 0 ? mapping.value() : mapping.path();
    }
}
