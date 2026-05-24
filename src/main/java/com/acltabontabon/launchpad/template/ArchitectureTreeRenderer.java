package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.scanner.ClassFact;
import com.acltabontabon.launchpad.scanner.ProjectClassFacts;
import java.util.List;

/**
 * Renders {@link ClassFact} entries as a hand-written-style architecture
 * tree: leaf packages on the outer level, classes indented underneath, each
 * carrying a short annotation that names what the class is (record, enum,
 * interface with N impls, REST controller routes, plain class).
 * <p>
 * Example output:
 * <pre>
 * ```
 * controller/
 *   LoanDecisionController   POST /loan-decision, GET /loan-decision
 *   HelloController          GET /hello
 * model/
 *   LoanApplication          Java record
 *   LoanDecision             Java record
 *   CreditTier, Outcome      Enums
 * risk/
 *   RiskModel                Interface; 3 impls: PrimeRiskModel, NearPrimeRiskModel, SubprimeRiskModel
 *   FeatureScorer            Interface
 *   FeatureScorers           class
 * ```
 * </pre>
 */
public final class ArchitectureTreeRenderer {

    private static final int CLASS_NAME_WIDTH = 24;
    private static final int MAX_PACKAGES = 12;
    private static final int MAX_CLASSES_PER_PACKAGE = 15;

    /**
     * Render order within a package: interfaces first (they document the
     * contracts), then controllers (visible API), then classes / records.
     * Enums are collapsed onto a single trailing line by the renderer.
     */
    private static int kindOrder(ClassFact.Kind k) {
        return switch (k) {
            case INTERFACE -> 0;
            case REST_CONTROLLER -> 1;
            case RECORD -> 2;
            case CLASS -> 3;
            case ENUM -> 4;
        };
    }

    private ArchitectureTreeRenderer() {}

    public static String render(List<ClassFact> facts) {
        if (facts == null || facts.isEmpty()) return "";
        var grouped = ProjectClassFacts.groupByLeafPackage(facts);
        if (grouped.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("```\n");
        int packagesShown = 0;
        for (var entry : grouped.entrySet()) {
            if (packagesShown >= MAX_PACKAGES) {
                sb.append("... and ").append(grouped.size() - packagesShown).append(" more packages\n");
                break;
            }
            sb.append(entry.getKey()).append("/\n");
            var classes = entry.getValue();
            // Collapse multiple enums (Foo + Bar) onto one line for readability.
            var enums = classes.stream().filter(c -> c.kind() == ClassFact.Kind.ENUM).toList();
            var nonEnums = classes.stream()
                .filter(c -> c.kind() != ClassFact.Kind.ENUM)
                .sorted(java.util.Comparator.comparingInt((ClassFact c) -> kindOrder(c.kind()))
                    .thenComparing(ClassFact::name))
                .toList();
            int shown = 0;
            for (var c : nonEnums) {
                if (shown >= MAX_CLASSES_PER_PACKAGE) {
                    sb.append("  ... and ").append(nonEnums.size() - shown).append(" more\n");
                    break;
                }
                sb.append("  ").append(pad(c.name())).append(annotation(c)).append("\n");
                shown++;
            }
            if (!enums.isEmpty() && shown < MAX_CLASSES_PER_PACKAGE) {
                var joinedNames = enums.stream().map(ClassFact::name).reduce((a, b) -> a + ", " + b).orElse("");
                sb.append("  ").append(pad(joinedNames)).append("Enums\n");
            }
            packagesShown++;
        }
        sb.append("```\n");
        return sb.toString();
    }

    private static String pad(String s) {
        if (s.length() >= CLASS_NAME_WIDTH) return s + " ";
        var sb = new StringBuilder(s);
        while (sb.length() < CLASS_NAME_WIDTH) sb.append(' ');
        return sb.toString();
    }

    private static String annotation(ClassFact f) {
        return switch (f.kind()) {
            case RECORD -> "Java record";
            case ENUM -> "Enum";
            case INTERFACE -> {
                if (f.impls().isEmpty()) yield "Interface";
                yield "Interface; " + f.impls().size() + " impl"
                    + (f.impls().size() == 1 ? "" : "s")
                    + ": " + String.join(", ", f.impls());
            }
            case REST_CONTROLLER -> f.routes().isEmpty() ? "REST controller" : String.join(", ", f.routes());
            case CLASS -> "class";
        };
    }
}
