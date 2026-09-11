package com.edunext.edutrack.api.feature.onboarding.implementationstages;

import com.edunext.edutrack.domain.onboarding.ObImplementationStage;
import com.edunext.edutrack.domain.onboarding.ObImplementationStageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * OB-15 · the master's one real rule — {@code sequence} is 1..N, always.
 *
 * <p>A hand-written fake rather than a mock, for {@code ObProductServiceTest}'s
 * reason: every test here is about what the service does with what the
 * repository returns, and the fake also lets the name lookup behave the way
 * {@code utf8mb4_0900_ai_ci} does, which is what the duplicate rule rests on.
 *
 * <p><b>The fake sorts on read.</b> That matters more than it looks: the
 * service renumbers by mutating rows and saving them, so a fake that returned
 * insertion order would let a broken renumbering pass by accident. Sorting the
 * way {@code findAllByOrderBySequenceAscIdAsc} does means the assertions below
 * are made against the order a database would actually hand back.
 */
class ObImplementationStageServiceTest {

    private static class FakeRepository implements ObImplementationStageRepository {
        private final List<ObImplementationStage> rows = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong();

        @Override
        public ObImplementationStage save(ObImplementationStage stage) {
            if (stage.getId() == null) {
                setId(stage, ids.incrementAndGet());
                rows.add(stage);
            }
            return stage;
        }

        @Override
        public List<ObImplementationStage> saveAll(Iterable<ObImplementationStage> stages) {
            List<ObImplementationStage> saved = new ArrayList<>();
            stages.forEach(s -> saved.add(save(s)));
            return saved;
        }

        @Override
        public Optional<ObImplementationStage> findById(Long id) {
            return rows.stream().filter(r -> id.equals(r.getId())).findFirst();
        }

        @Override
        public List<ObImplementationStage> findAllByOrderBySequenceAscIdAsc() {
            return rows.stream()
                    .sorted(Comparator.comparingInt(ObImplementationStage::getSequence)
                            .thenComparing(ObImplementationStage::getId))
                    .toList();
        }

        @Override
        public List<ObImplementationStage> findAllByIsActiveOrderBySequenceAscIdAsc(boolean isActive) {
            return findAllByOrderBySequenceAscIdAsc().stream()
                    .filter(r -> r.isActive() == isActive)
                    .toList();
        }

        /** Mirrors the column's collation: {@code reports} finds {@code Reports}. */
        @Override
        public Optional<ObImplementationStage> findByName(String name) {
            return rows.stream().filter(r -> r.getName().equalsIgnoreCase(name)).findFirst();
        }

        private static void setId(ObImplementationStage stage, long id) {
            try {
                var field = ObImplementationStage.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(stage, id);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private final FakeRepository repository = new FakeRepository();
    private final ObImplementationStageService service = new ObImplementationStageService(repository);

    /** The six values the migration seeds, in the order it seeds them. */
    @BeforeEach
    void seed() {
        int sequence = 1;
        for (String name : List.of("Configuration", "Data Migration", "Reports",
                "Training", "Communication", "Third Party Integration")) {
            repository.save(new ObImplementationStage(name, sequence++, true, null));
        }
    }

    private List<String> namesInOrder() {
        return service.list(null).stream().map(ObImplementationStageDtos.Stage::name).toList();
    }

    private List<Integer> sequencesInOrder() {
        return service.list(null).stream().map(ObImplementationStageDtos.Stage::sequence).toList();
    }

    private static ObImplementationStageDtos.WriteRequest write(String name, Integer sequence) {
        return new ObImplementationStageDtos.WriteRequest(name, sequence, null);
    }

    @Test
    @DisplayName("a create with no position appends, and renumbers nothing")
    void createAppends() {
        ObImplementationStageDtos.Stage created = service.create(write("UAT", null), null);

        assertThat(created.sequence()).isEqualTo(7);
        assertThat(namesInOrder()).endsWith("UAT");
        assertThat(sequencesInOrder()).containsExactly(1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    @DisplayName("a create at a position inserts there and shifts the rest down")
    void createAtPosition() {
        service.create(write("Kickoff", 1), null);

        assertThat(namesInOrder()).startsWith("Kickoff", "Configuration");
        assertThat(sequencesInOrder()).containsExactly(1, 2, 3, 4, 5, 6, 7);
    }

    /** The requirement in one test: type a position, get that order. */
    @Test
    @DisplayName("moving a stage up shifts everything between it and its target down")
    void moveUp() {
        long training = idOf("Training");

        service.update(training, write("Training", 1));

        assertThat(namesInOrder()).containsExactly(
                "Training", "Configuration", "Data Migration", "Reports",
                "Communication", "Third Party Integration");
        assertThat(sequencesInOrder()).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    @DisplayName("moving a stage down shifts everything between it and its target up")
    void moveDown() {
        long configuration = idOf("Configuration");

        service.update(configuration, write("Configuration", 4));

        assertThat(namesInOrder()).containsExactly(
                "Data Migration", "Reports", "Training", "Configuration",
                "Communication", "Third Party Integration");
        assertThat(sequencesInOrder()).containsExactly(1, 2, 3, 4, 5, 6);
    }

    /**
     * The clamp, and why it is not a validation error: somebody typing 99 into
     * a position box means "last", which the master can satisfy exactly.
     */
    @Test
    @DisplayName("a position past the end of the list means last")
    void clampsBeyondTheEnd() {
        service.update(idOf("Configuration"), write("Configuration", 99));

        assertThat(namesInOrder()).endsWith("Configuration");
        assertThat(sequencesInOrder()).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    @DisplayName("moving a stage to where it already is changes nothing")
    void movingToItsOwnPositionIsANoOp() {
        service.update(idOf("Reports"), write("Reports", 3));

        assertThat(namesInOrder()).containsExactly(
                "Configuration", "Data Migration", "Reports", "Training",
                "Communication", "Third Party Integration");
        assertThat(sequencesInOrder()).containsExactly(1, 2, 3, 4, 5, 6);
    }

    /**
     * A rename must not move the row. Worth its own test because the request
     * carries both fields, and the bug this catches — treating an absent
     * position as "position 1", or as "whatever the field defaults to" — leaves
     * the master looking fine and in the wrong order.
     */
    @Test
    @DisplayName("a rename with no position leaves the order alone")
    void renameDoesNotReorder() {
        service.update(idOf("Reports"), write("Reporting", null));

        assertThat(namesInOrder()).containsExactly(
                "Configuration", "Data Migration", "Reporting", "Training",
                "Communication", "Third Party Integration");
    }

    @Test
    @DisplayName("a retired stage keeps its slot")
    void retiringDoesNotReorder() {
        service.update(idOf("Reports"),
                new ObImplementationStageDtos.WriteRequest("Reports", null, false));

        assertThat(namesInOrder().get(2)).isEqualTo("Reports");
        assertThat(service.list(true)).extracting(ObImplementationStageDtos.Stage::name)
                .doesNotContain("Reports");
        assertThat(service.list(false)).extracting(ObImplementationStageDtos.Stage::name)
                .containsExactly("Reports");
    }

    @Test
    @DisplayName("a duplicate name is refused case-insensitively, as the index would")
    void refusesDuplicateName() {
        assertThatExceptionOfType(DuplicateImplementationStageNameException.class)
                .isThrownBy(() -> service.create(write("reports", null), null));

        assertThat(namesInOrder()).hasSize(6);
    }

    /**
     * The round trip a form makes: read the row, change the position, send both
     * fields back. Refusing the unchanged name here would fail every edit.
     */
    @Test
    @DisplayName("a stage may keep its own name on an update")
    void allowsItsOwnName() {
        service.update(idOf("Reports"), write("Reports", 1));

        assertThat(namesInOrder()).startsWith("Reports");
    }

    @Test
    @DisplayName("names are trimmed, and otherwise left exactly as typed")
    void trimsButDoesNotRecase() {
        ObImplementationStageDtos.Stage created = service.create(write("  Cutover Plan  ", null), null);

        assertThat(created.name()).isEqualTo("Cutover Plan");
    }

    @Test
    @DisplayName("an unknown id is empty rather than an exception")
    void updateOfUnknownIdIsEmpty() {
        assertThat(service.update(9_999L, write("Anything", null))).isEmpty();
    }

    private long idOf(String name) {
        return repository.findByName(name).orElseThrow().getId();
    }
}
