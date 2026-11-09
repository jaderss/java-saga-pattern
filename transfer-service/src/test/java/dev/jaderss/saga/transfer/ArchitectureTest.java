package dev.jaderss.saga.transfer;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "dev.jaderss.saga.transfer", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_is_plain_java = noClasses()
            .that()
            .resideInAPackage("..transfer.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "tools.jackson..",
                    "java.sql..",
                    "..transfer.app..",
                    "..transfer.persistence..",
                    "..transfer.api..");

    @ArchTest
    static final ArchRule persistence_does_not_reach_up = noClasses()
            .that()
            .resideInAPackage("..transfer.persistence..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..transfer.app..", "..transfer.api..");

    @ArchTest
    static final ArchRule app_does_not_know_the_web = noClasses()
            .that()
            .resideInAPackage("..transfer.app..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..transfer.api..");
}
