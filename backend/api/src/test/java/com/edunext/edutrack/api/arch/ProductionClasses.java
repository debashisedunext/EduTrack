package com.edunext.edutrack.api.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * A-037 · the class graph every rule in this package is checked against,
 * imported once.
 *
 * <h2>What is in scope, and why {@code worker} is not</h2>
 *
 * <p>This runs on {@code api}'s test classpath, so it sees {@code api},
 * {@code domain} and {@code common} — every module {@code api} depends on.
 * {@code worker} is a sibling that {@code api} does not depend on, so its
 * classes are absent, and that is the correct boundary rather than a gap to
 * apologise for: the rules here are about an authenticated request. The SLA
 * scanners have no caller by construction, so "did you go through the scope
 * guard" is not a question that can be asked of them. A rule that reached into
 * {@code worker} would have to exempt every scanner it found, which is a rule
 * that means nothing.
 *
 * <p>Rules that genuinely are module-wide — the append-only ones — hold in
 * {@code domain}, where the three repositories are declared, and that <em>is</em>
 * imported here. {@code worker} cannot mutate what {@code domain} does not
 * expose.
 *
 * <h2>The empty-import failure</h2>
 *
 * <p>An ArchUnit suite that imports nothing passes every rule it has, silently
 * and quickly, and looks exactly like a suite that found no violations. That is
 * not hypothetical — the import is a classpath scan by package name, and it
 * returns an empty set rather than an error if the package moves, if a module
 * is not built, or if a typo creeps into the string below. {@link
 * ArchImportSanityTest} is what stands between that and a green build.
 */
final class ProductionClasses {

    /** The one package root. Everything we ship is under it; nothing else is. */
    static final String ROOT = "com.edunext.edutrack";

    private static final JavaClasses IMPORTED = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(ROOT);

    private ProductionClasses() {
    }

    static JavaClasses get() {
        return IMPORTED;
    }
}
