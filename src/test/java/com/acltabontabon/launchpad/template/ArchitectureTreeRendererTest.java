package com.acltabontabon.launchpad.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.launchpad.scanner.ClassFact;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArchitectureTreeRendererTest {

    @Test
    void rendersLeafPackagesWithPerClassAnnotations() {
        var facts = List.of(
            new ClassFact("LoanDecisionController", "src/.../controller/LoanDecisionController.java",
                "controller", ClassFact.Kind.REST_CONTROLLER,
                List.of("POST /loan-decision", "GET /loan-decision"), List.of()),
            new ClassFact("HelloController", "src/.../controller/HelloController.java",
                "controller", ClassFact.Kind.REST_CONTROLLER, List.of("GET /hello"), List.of()),
            new ClassFact("LoanApplication", "src/.../model/LoanApplication.java",
                "model", ClassFact.Kind.RECORD, List.of(), List.of()),
            new ClassFact("CreditTier", "src/.../model/CreditTier.java",
                "model", ClassFact.Kind.ENUM, List.of(), List.of()),
            new ClassFact("Outcome", "src/.../model/Outcome.java",
                "model", ClassFact.Kind.ENUM, List.of(), List.of()),
            new ClassFact("RiskModel", "src/.../risk/RiskModel.java",
                "risk", ClassFact.Kind.INTERFACE, List.of(),
                List.of("PrimeRiskModel", "NearPrimeRiskModel", "SubprimeRiskModel"))
        );

        var out = ArchitectureTreeRenderer.render(facts);

        assertThat(out).startsWith("```\n").endsWith("```\n");
        assertThat(out).contains("controller/");
        assertThat(out).contains("LoanDecisionController");
        assertThat(out).contains("POST /loan-decision, GET /loan-decision");
        assertThat(out).contains("HelloController");
        assertThat(out).contains("GET /hello");
        assertThat(out).contains("model/");
        assertThat(out).contains("LoanApplication");
        assertThat(out).contains("Java record");
        // Multiple enums collapsed onto one line.
        assertThat(out).contains("CreditTier, Outcome");
        assertThat(out).contains("Enums");
        assertThat(out).contains("risk/");
        assertThat(out).contains("Interface; 3 impls: PrimeRiskModel, NearPrimeRiskModel, SubprimeRiskModel");
    }

    @Test
    void emptyOutputForEmptyFacts() {
        assertThat(ArchitectureTreeRenderer.render(List.of())).isEmpty();
        assertThat(ArchitectureTreeRenderer.render(null)).isEmpty();
    }

    @Test
    void singleInterfaceWithSingularImplsLabel() {
        var facts = List.of(new ClassFact("Foo", "p/Foo.java", "p",
            ClassFact.Kind.INTERFACE, List.of(), List.of("FooImpl")));
        var out = ArchitectureTreeRenderer.render(facts);
        assertThat(out).contains("Interface; 1 impl: FooImpl");
    }
}
