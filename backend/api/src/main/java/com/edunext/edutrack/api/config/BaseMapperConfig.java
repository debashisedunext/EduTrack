package com.edunext.edutrack.api.config;

import org.mapstruct.InjectionStrategy;
import org.mapstruct.MapperConfig;
import org.mapstruct.MappingConstants;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * B-006 · the settings every entity ⇄ DTO mapper in this application shares.
 *
 * <p>Mappers opt in with {@code @Mapper(config = BaseMapperConfig.class)} and
 * inherit all of it. PLAN.md §2.1 picks MapStruct precisely because the mapping
 * is decided at compile time; a shared config is what keeps that decision the
 * same one in every feature package. Feature packaging (TEAM-PLAN.md §6) means
 * four developers write mappers in four directories that never meet — without
 * this file they would each pick their own null handling and their own answer
 * to "what happens when a DTO field is left unmapped", and the answers would
 * differ per screen rather than per intent.
 *
 * <p>This interface holds no methods and is never implemented. It exists only
 * to carry the annotation.
 *
 * <h2>Why each setting</h2>
 *
 * <p><b>{@code componentModel = SPRING}</b> — the generated {@code *Impl} is a
 * {@code @Component}, so a service takes a mapper the same way it takes a
 * repository. The alternative, {@code Mappers.getMapper(...)}, is a static
 * lookup that cannot be replaced in a test.
 *
 * <p><b>{@code injectionStrategy = CONSTRUCTOR}</b> — matches the house style.
 * Every collaborator in this codebase is a {@code private final} field set by
 * the constructor, with no {@code @Autowired} anywhere; a mapper that composes
 * other mappers should not be the one exception.
 *
 * <p><b>{@code unmappedTargetPolicy = ERROR}</b> — the setting that earns this
 * file. A response DTO field nobody mapped is otherwise silently {@code null}:
 * the build stays green, the tests pass if they do not assert that field, and
 * the screen renders a blank where a value belongs. Making it a compile error
 * puts the failure at the keystroke that caused it. This is the same choice
 * A-022 made when a short signing secret was made to refuse to boot rather than
 * to fail at the first login, and B-008 when a migration with no manifest row
 * was made to fail the build.
 *
 * <p>Partial-update methods are the deliberate exception, not a reason to
 * loosen the default: a {@code @MappingTarget} entity has fields — {@code id},
 * {@code createdAt} — that no request may ever set. Ignore them explicitly, per
 * method, so the exemption is visible where it applies:
 *
 * <pre>{@code
 * @Mapping(target = "id",        ignore = true)
 * @Mapping(target = "createdAt", ignore = true)
 * void applyUpdate(RoleUpdateRequest request, @MappingTarget Role role);
 * }</pre>
 *
 * <p><b>{@code unmappedSourcePolicy = IGNORE}</b> — MapStruct's own default,
 * stated rather than assumed. An unread *source* field is normal and harmless:
 * an entity carries far more than any one DTO exposes, and the append-only
 * tables in particular are read by many narrow projections.
 *
 * <p><b>{@code typeConversionPolicy = ERROR}</b> — refuses a silent narrowing
 * conversion. This schema mixes key widths on purpose: blueprint §8.2 declares
 * {@code roles.id} and {@code permissions.id} as {@code INT} while {@code users}
 * and {@code tickets} are {@code BIGINT}, so {@code Integer} and {@code Long}
 * ids sit next to each other in the same DTOs. A {@code long → int} that
 * truncates should not compile.
 *
 * <p><b>{@code nullValuePropertyMappingStrategy = IGNORE}</b> — what makes
 * {@code PATCH} mean PATCH. On an update into a {@code @MappingTarget}, a
 * {@code null} source property leaves the target's value alone instead of
 * writing {@code null} over it. Under the default, a partial update that sent
 * only {@code description} would blank the role's {@code name}. Where a caller
 * genuinely needs to clear a field, model it in the DTO rather than by relying
 * on this — the two intents look identical on the wire and only the DTO can
 * tell them apart.
 */
@MapperConfig(
        componentModel = MappingConstants.ComponentModel.SPRING,
        injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        unmappedSourcePolicy = ReportingPolicy.IGNORE,
        typeConversionPolicy = ReportingPolicy.ERROR,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface BaseMapperConfig {
}
