package com.edunext.edutrack.api.config;

import org.mapstruct.Mapper;

import java.util.List;

/**
 * B-006 · a second probe, existing only to give {@code injectionStrategy} a
 * collaborator to inject.
 *
 * <p>{@link BaseMapperConfigProbe} cannot prove that setting: a mapper with no
 * dependencies is generated with a no-argument constructor whichever strategy
 * is in force, so field injection and constructor injection are
 * indistinguishable from it. Mapping a {@code List} is the cheapest way to
 * acquire a dependency — MapStruct looks for an existing element mapping before
 * writing its own, finds {@link BaseMapperConfigProbe#toResponse} through
 * {@code uses}, and has to hold a reference to it. How it comes by that
 * reference is exactly what {@code injectionStrategy} decides.
 *
 * <p>Delete alongside {@link BaseMapperConfigProbe}.
 */
@Mapper(config = BaseMapperConfig.class, uses = BaseMapperConfigProbe.class)
public interface BaseMapperConfigCollaboratorProbe {

    List<BaseMapperConfigProbe.ProbeResponse> toResponses(List<BaseMapperConfigProbe.ProbeEntity> entities);
}
