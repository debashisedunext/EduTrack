package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.domain.identity.User;
import com.edunext.edutrack.domain.identity.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The regression {@link RibbonAssemblerTest} could not reproduce, since it
 * mocks this class out. A skip's departure hop leaves {@code to_user_id} null
 * — nobody actually receives the ticket — and {@code RibbonAssembler} looks
 * the id up with a bare {@code owners.get(hop.getToUserId())}, on the
 * documented contract that a missing id renders as a null owner rather than
 * an exception. {@code Map.of()}/{@code Map.copyOf} both throw on
 * {@code get(null)} instead, which only a real database round trip through
 * {@code SkipControllerIT} ever exercised.
 */
class TransitionUserRefsTest {

    private final UserRepository users = mock(UserRepository.class);
    private final TransitionUserRefs refs = new TransitionUserRefs(users);

    @Test
    void aNullIdIsAMissingEntryNotAnException() {
        when(users.findAllById(any())).thenReturn(List.of());

        Map<Long, RibbonWire.UserRef> resolved = refs.resolve(List.of(1L));

        assertThatCode(() -> resolved.get(null)).doesNotThrowAnyException();
        assertThat(resolved.get(null)).isNull();
    }

    @Test
    void aNullIdIsAMissingEntryEvenWhenNothingElseResolved() {
        Map<Long, RibbonWire.UserRef> resolved = refs.resolve(java.util.Arrays.asList((Long) null));

        assertThatCode(() -> resolved.get(null)).doesNotThrowAnyException();
        assertThat(resolved).isEmpty();
    }

    @Test
    void resolvesRealUsersByName() {
        User user = new User();
        user.setId(7L);
        user.setFullName("Priya Nair");
        when(users.findAllById(Set.of(7L))).thenReturn(List.of(user));

        Map<Long, RibbonWire.UserRef> resolved = refs.resolve(java.util.Arrays.asList(7L, null));

        assertThat(resolved.get(7L)).isEqualTo(new RibbonWire.UserRef(7L, "Priya Nair"));
        assertThat(resolved.get(null)).isNull();
    }
}
