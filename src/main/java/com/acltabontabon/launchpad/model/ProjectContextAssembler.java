package com.acltabontabon.launchpad.model;

import com.acltabontabon.launchpad.scanner.PackageSummary;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.scanner.doc.DocumentationPage;
import com.acltabontabon.launchpad.springboot.maven.MavenProfile;
import com.acltabontabon.launchpad.springboot.runtime.Endpoint;
import com.acltabontabon.launchpad.standards.infer.StandardsInferer;
import com.acltabontabon.launchpad.workflow.WorkflowDiscoverer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link VirtualProjectContext} from the deterministic scan
 * ({@link ProjectContext}). It maps the structural facts of a scan into the
 * synthesized model shape, attaching provenance and confidence. Architecture,
 * systems, operations, documentation, and workflows (via
 * {@link WorkflowDiscoverer}) are populated here; the remaining inference-heavy
 * fields (detected patterns, risks) are left empty and filled in later, their
 * containers present so the model shape is stable.
 * <p>
 * Everything this assembler produces is {@link Confidence#DETERMINISTIC} - no
 * model is involved. The local model only enriches narrative and inference
 * fields downstream.
 */
@Component
public class ProjectContextAssembler {

    /** Package-name segments that signal a classic application layer. */
    private static final List<String> LAYER_HINTS =
        List.of("controller", "service", "repository", "domain", "config", "model");

    private static final int MAX_SYSTEMS = 12;

    private final WorkflowDiscoverer workflowDiscoverer = new WorkflowDiscoverer();
    private final StandardsInferer standardsInferer = new StandardsInferer();

    /**
     * Assemble the virtualized model. {@code packVersion} is the resolved
     * standards-pack version (or ""), {@code generatedAt} is an ISO-8601
     * timestamp supplied by the caller (kept as a parameter so the result is
     * deterministic and unit-testable).
     */
    public VirtualProjectContext assemble(ProjectContext scan, String packVersion, String generatedAt) {
        if (scan == null) {
            return new VirtualProjectContext(null, null, null, null, null, null, null, null);
        }

        ProjectIdentity identity = new ProjectIdentity(
            scan.name(),
            scan.rootPath(),
            scan.stack() != null ? scan.stack().displayName() : "",
            generatedAt,
            packVersion,
            contentHash(scan)
        );

        StandardsInferer.Inference inference = standardsInferer.infer(scan);

        return new VirtualProjectContext(
            identity,
            architecture(scan),
            systems(scan),
            workflowDiscoverer.discover(scan),
            new StandardsProfile(List.of(), inference.patterns(), inference.inferredStandards()),
            operations(scan),
            documentation(scan),
            inference.risks()
        );
    }

    private Architecture architecture(ProjectContext scan) {
        List<String> modules = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        for (PackageSummary pkg : safe(scan.packageSummaries())) {
            modules.add(pkg.path());
            evidence.add(Evidence.of(pkg.path(), pkg.fileCount() + " files"));
        }

        List<String> layers = new ArrayList<>();
        for (String hint : LAYER_HINTS) {
            boolean present = modules.stream().anyMatch(m -> m.toLowerCase().contains(hint));
            if (present) {
                layers.add(hint);
            }
        }
        String style = layers.contains("controller") && layers.contains("service")
            && layers.contains("repository") ? "layered" : "unclassified";

        return new Architecture(style, layers, modules, Map.of(), "", List.of(),
            Confidence.DETERMINISTIC, evidence);
    }

    private List<SystemComponent> systems(ProjectContext scan) {
        List<SystemComponent> systems = new ArrayList<>();
        List<PackageSummary> packages = safe(scan.packageSummaries());
        int limit = Math.min(packages.size(), MAX_SYSTEMS);
        for (int i = 0; i < limit; i++) {
            PackageSummary pkg = packages.get(i);
            systems.add(new SystemComponent(
                slug(pkg.path()),
                pkg.path(),
                "",
                List.of(),
                List.of(pkg.path()),
                List.of(),
                Confidence.DETERMINISTIC,
                List.of(Evidence.of(pkg.path(), pkg.fileCount() + " files"))
            ));
        }
        return systems;
    }

    private Operations operations(ProjectContext scan) {
        // Launchpad gates on Spring Boot + Maven, so the build commands are known.
        List<String> buildCommands = List.of("./mvnw clean package", "./mvnw test");

        List<String> runProfiles = new ArrayList<>();
        for (MavenProfile profile : safe(scan.mavenProfiles())) {
            runProfiles.add(formatProfile(profile));
        }

        List<String> healthEndpoints = new ArrayList<>();
        for (Endpoint endpoint : safe(scan.actuatorEndpoints())) {
            healthEndpoints.add(endpoint.method() + " " + endpoint.path());
        }

        return new Operations(runProfiles, List.of(), List.of(), healthEndpoints,
            buildCommands, List.of(), List.of());
    }

    /** Renders a Maven profile as "id - activation (flags)", omitting blank parts. */
    private static String formatProfile(MavenProfile profile) {
        StringBuilder label = new StringBuilder(profile.id());
        if (profile.activation() != null && !profile.activation().isBlank()) {
            label.append(" - ").append(profile.activation());
        }
        if (profile.keyFlags() != null && !profile.keyFlags().isEmpty()) {
            label.append(" (").append(String.join(", ", profile.keyFlags())).append(")");
        }
        return label.toString();
    }

    private DocumentationMap documentation(ProjectContext scan) {
        if (scan.documentation() == null || scan.documentation().isEmpty()) {
            return DocumentationMap.empty();
        }
        List<DocEntry> entries = new ArrayList<>();
        for (DocumentationPage page : safe(scan.documentation().pages())) {
            String purposeTag = page.purpose() != null ? page.purpose().name().toLowerCase() : "";
            entries.add(new DocEntry(page.title(), purposeTag, page.path(), "", List.of()));
        }
        return new DocumentationMap(entries, List.of());
    }

    /**
     * Stable digest over the deterministic inputs that define this project, so
     * snapshots can be compared and staleness detected. Order-stable: it walks
     * the same accessors in the same order every run.
     */
    private String contentHash(ProjectContext scan) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(nullToEmpty(scan.name())).append('\n');
        canonical.append(nullToEmpty(scan.rootPath())).append('\n');
        canonical.append(scan.stack() != null ? scan.stack().displayName() : "").append('\n');
        for (PackageSummary pkg : safe(scan.packageSummaries())) {
            canonical.append("pkg:").append(pkg.path()).append(':').append(pkg.fileCount()).append('\n');
        }
        safe(scan.dependencies()).forEach(d -> canonical.append("dep:").append(d.display()).append('\n'));
        return sha256(canonical.toString());
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; this branch is unreachable.
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String slug(String path) {
        if (path == null) return "";
        return path.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
