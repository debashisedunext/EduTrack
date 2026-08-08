package com.edunext.edutrack.api.config;

import com.edunext.edutrack.api.config.BaseMapperConfigProbe.ProbeEntity;
import com.edunext.edutrack.api.config.BaseMapperConfigProbe.ProbeResponse;
import com.edunext.edutrack.api.config.BaseMapperConfigProbe.ProbeUpdateRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-006 · proves the shared mapper config reaches a mapper that opts in.
 *
 * <p><b>The settings cannot be read back.</b> {@code @MapperConfig} is declared
 * {@code RetentionPolicy.CLASS}, so it is gone by the time anything runs and
 * {@code BaseMapperConfig.class.getAnnotation(MapperConfig.class)} is null. A
 * test asserting the annotation's values would therefore be asserting nothing.
 * Everything below instead inspects what the processor <em>produced</em>, which
 * is the thing that actually governs the application.
 *
 * <p>Neither probe sets {@code componentModel}, {@code injectionStrategy} or
 * {@code nullValuePropertyMappingStrategy} itself. Each is left to
 * {@code @Mapper(config = BaseMapperConfig.class)}, so an assertion that finds
 * one in the generated code found it because the shared config carried it
 * there.
 *
 * <p>Two settings are deliberately not covered, because nothing that runs can
 * cover them: {@code unmappedTargetPolicy} and {@code typeConversionPolicy} are
 * compile-time gates, and their only observable behaviour is a build that
 * fails. The build compiling is the whole of the evidence for those two.
 */
class BaseMapperConfigTest {

    private static Class<?> probeImpl;
    private static Class<?> collaboratorImpl;
    private static BaseMapperConfigProbe mapper;

    /**
     * Loaded by name rather than referenced as {@code BaseMapperConfigProbeImpl}
     * so the test does not carry a source-level dependency on generated code —
     * and so a processor that never ran fails here, on the class that is
     * missing, instead of somewhere less obvious.
     */
    @BeforeAll
    static void loadTheGeneratedImplementations() throws Exception {
        probeImpl = Class.forName("com.edunext.edutrack.api.config.BaseMapperConfigProbeImpl");
        collaboratorImpl = Class.forName("com.edunext.edutrack.api.config.BaseMapperConfigCollaboratorProbeImpl");
        mapper = (BaseMapperConfigProbe) probeImpl.getDeclaredConstructor().newInstance();
    }

    @Test
    @DisplayName("the MapStruct processor runs in this module and implements the mapper")
    void theProcessorRuns() {
        assertThat(mapper).isInstanceOf(BaseMapperConfigProbe.class);
    }

    @Test
    @DisplayName("componentModel = spring: the generated impl is an injectable bean")
    void generatesASpringComponent() {
        assertThat(probeImpl.getAnnotation(Component.class))
                .as("the probe declares no componentModel of its own, so @Component can only "
                        + "have come from BaseMapperConfig")
                .isNotNull();
    }

    @Test
    @DisplayName("injectionStrategy = constructor: collaborators arrive through the constructor, not a field")
    void injectsCollaboratorsThroughTheConstructor() {
        Constructor<?>[] constructors = collaboratorImpl.getDeclaredConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes())
                .as("the collaborating mapper should be a constructor argument")
                .containsExactly(BaseMapperConfigProbe.class);

        assertThat(collaboratorImpl.getDeclaredFields())
                .as("no field injection — the house style has no @Autowired field anywhere")
                .noneMatch(field -> field.isAnnotationPresent(Autowired.class));
        assertThat(collaboratorImpl.getDeclaredFields())
                .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("a constructor-injected collaborator should be final")
                        .isTrue());
    }

    @Test
    @DisplayName("a read mapping copies every property of the response")
    void mapsEntityToResponse() {
        ProbeEntity entity = entity(3, "QA", "QA");

        assertThat(mapper.toResponse(entity)).isEqualTo(new ProbeResponse(3, "QA", "QA"));
    }

    @Test
    @DisplayName("nullValuePropertyMappingStrategy = IGNORE: an absent field is left alone, not blanked")
    void anAbsentFieldSurvivesAPartialUpdate() {
        ProbeEntity entity = entity(7, "ADMIN", "Admin");

        mapper.applyUpdate(new ProbeUpdateRequest(null, "Administrator"), entity);

        assertThat(entity.getCode())
                .as("null means 'not sent', so the stored value stands — under the MapStruct "
                        + "default this would now be null")
                .isEqualTo("ADMIN");
        assertThat(entity.getName())
                .as("a value that was sent still overwrites")
                .isEqualTo("Administrator");
        assertThat(entity.getId())
                .as("ignored by name: no request may set a key")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("a collaborating mapper delegates rather than duplicating the element mapping")
    void delegatesElementMappingToTheCollaborator() throws Exception {
        BaseMapperConfigCollaboratorProbe collaborator = (BaseMapperConfigCollaboratorProbe) collaboratorImpl
                .getDeclaredConstructor(BaseMapperConfigProbe.class)
                .newInstance(mapper);

        List<ProbeResponse> responses = collaborator.toResponses(List.of(entity(1, "PM", "PM"), entity(2, "DEV", "Developer")));

        assertThat(responses).containsExactly(new ProbeResponse(1, "PM", "PM"), new ProbeResponse(2, "DEV", "Developer"));
    }

    private static ProbeEntity entity(Integer id, String code, String name) {
        ProbeEntity entity = new ProbeEntity();
        entity.setId(id);
        entity.setCode(code);
        entity.setName(name);
        return entity;
    }
}
