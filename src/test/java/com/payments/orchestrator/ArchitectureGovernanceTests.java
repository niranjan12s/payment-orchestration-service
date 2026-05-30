package com.payments.orchestrator;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.payments.orchestrator", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureGovernanceTests {

    @ArchTest
    static final ArchRule controllerLayerRules = classes()
            .that().resideInAPackage("..controller..")
            .should().onlyHaveDependentClassesThat().resideInAnyPackage("..controller..", "..config..");

    @ArchTest
    static final ArchRule serviceExposuresRule = classes()
            .that().resideInAPackage("..service..")
            .should().onlyHaveDependentClassesThat().resideInAnyPackage("..controller..", "..service..", "..worker..", "..config..", "..security..");

    @ArchTest
    static final ArchRule pspConnectorLayerRule = noClasses()
            .that().resideInAPackage("..psp..")
            .should().dependOnClassesThat().resideInAPackage("..controller..");

    @ArchTest
    static final ArchRule domainLayerRestrictionsRule = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..", "..repository..", "..worker..");
}
