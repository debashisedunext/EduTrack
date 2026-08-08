package com.edunext.edutrack.api.config;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * B-006 · a mapper that exists only so {@link BaseMapperConfigTest} has
 * something real to assert against.
 *
 * <p>The shared config is a set of compile-time instructions to an annotation
 * processor, so nothing about it can be trusted from reading it — the question
 * is whether the processor runs at all in this module and whether these
 * settings actually reach a mapper that opts in. Both are answered by
 * generating {@code BaseMapperConfigProbeImpl} and looking at what came out.
 *
 * <p>Test sources sit under the {@code com.edunext.edutrack} scan root, so the
 * generated impl is a real {@code @Component} in every {@code @SpringBootTest}
 * context in this module. That is deliberate and harmless: it takes no
 * constructor arguments and nothing else resolves it. It is also the honest
 * version of the assertion — the bean is registered the same way a feature
 * mapper's would be.
 *
 * <p>Delete this when the first feature mapper lands and can carry the test
 * instead. Until then it is the only proof B-006 works.
 */
@Mapper(config = BaseMapperConfig.class)
public interface BaseMapperConfigProbe {

    /**
     * Every component of {@link ProbeResponse} has a same-named source
     * property, so this method compiles only while
     * {@code unmappedTargetPolicy = ERROR} is satisfiable — it is the case that
     * must keep working, as opposed to the one that must fail.
     */
    ProbeResponse toResponse(ProbeEntity entity);

    /**
     * The partial-update shape from {@link BaseMapperConfig}'s javadoc.
     *
     * <p>{@code id} is on the target and not on the source, so under
     * {@code unmappedTargetPolicy = ERROR} it has to be ignored by name. That
     * is the intended ergonomics: a field a request may never set is refused
     * until someone says so out loud.
     */
    @Mapping(target = "id", ignore = true)
    void applyUpdate(ProbeUpdateRequest request, @MappingTarget ProbeEntity entity);

    /** Read side. A record, so MapStruct maps it through the constructor. */
    record ProbeResponse(Integer id, String code, String name) {
    }

    /** Write side. Either property may be absent from a partial update. */
    record ProbeUpdateRequest(String code, String name) {
    }

    /**
     * Stands in for a JPA entity: mutable, with a no-argument constructor, and
     * carrying an {@code Integer} key the way {@code roles} and
     * {@code permissions} do.
     */
    class ProbeEntity {

        private Integer id;
        private String code;
        private String name;

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
