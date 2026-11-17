package io.groundedrag;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The layering described in docs/ARCHITECTURE.md, enforced on every build so it cannot erode.
 */
@AnalyzeClasses(packages = "io.groundedrag", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String[] DOMAIN = {
            "io.groundedrag.domain..", "io.groundedrag.store..", "io.groundedrag.ingestion..",
            "io.groundedrag.retrieval..", "io.groundedrag.generation..", "io.groundedrag.chat..",
            "io.groundedrag.cache..", "io.groundedrag.observability.."};

    @ArchTest
    static final ArchRule domainIsFreeOfSpringWiringAndWeb = noClasses().that().resideInAnyPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(
                    // Outbound HTTP clients (org.springframework.web.client) are fine for adapters;
                    // the inbound web stack is not.
                    "org.springframework.web.bind..", "org.springframework.web.servlet..",
                    "org.springframework.web.filter..", "org.springframework.web.multipart..",
                    "org.springframework.security..", "org.springframework.boot..",
                    "org.springframework.stereotype..", "org.springframework.context.annotation..",
                    "jakarta.servlet..")
            .as("Domain packages carry no Spring wiring, web or security types (composition happens in config)");

    @ArchTest
    static final ArchRule domainDoesNotKnowTheEdges = noClasses().that().resideInAnyPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.groundedrag.web..", "io.groundedrag.config..", "io.groundedrag.security..", "io.groundedrag.eval..");

    @ArchTest
    static final ArchRule onlyTheStorePackageTouchesJdbc = noClasses().that()
            .resideOutsideOfPackages("io.groundedrag.store..", "io.groundedrag.observability..", "io.groundedrag.config..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..");

    @ArchTest
    static final ArchRule retrievalDoesNotDependOnGeneration = noClasses().that().resideInAPackage("io.groundedrag.retrieval..")
            .should().dependOnClassesThat().resideInAnyPackage("io.groundedrag.generation..", "io.groundedrag.chat..");

    @ArchTest
    static final ArchRule controllersOnlyInWeb = classes().that()
            .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().resideInAPackage("io.groundedrag.web..");

    @ArchTest
    static final ArchRule noPackageCycles = slices().matching("io.groundedrag.(*)..").should().beFreeOfCycles();
}
