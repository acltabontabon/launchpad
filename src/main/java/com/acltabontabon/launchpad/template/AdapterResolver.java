package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.standards.Adapter;
import com.acltabontabon.launchpad.standards.AdapterOutput;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class AdapterResolver {

    private final StandardsLoader standardsLoader;

    public AdapterResolver(StandardsLoader standardsLoader) {
        this.standardsLoader = standardsLoader;
    }

    public ResolvedAdapter resolve(Path projectRoot, ContextTarget target) {
        var adapterId = adapterIdFor(target);
        var adapter = standardsLoader.loadAdapter(projectRoot, adapterId);
        var primaryOutput = adapter.flatMap(AdapterResolver::firstOutput);
        var defaultPath = target == ContextTarget.CLAUDE ? "CLAUDE.md" : ".cursorrules";
        var primaryPath = primaryOutput.map(AdapterOutput::path).orElse(defaultPath);
        var frontmatter = primaryOutput.map(AdapterOutput::frontmatter).orElse(Map.of());
        return new ResolvedAdapter(adapter, primaryOutput, primaryPath,
            frontmatter != null ? frontmatter : Map.of());
    }

    private static String adapterIdFor(ContextTarget target) {
        return switch (target) {
            case CLAUDE -> "claude";
            case CURSOR -> "cursor";
        };
    }

    private static Optional<AdapterOutput> firstOutput(Adapter a) {
        return a.outputs() == null || a.outputs().isEmpty()
            ? Optional.empty()
            : Optional.of(a.outputs().get(0));
    }

    public record ResolvedAdapter(
        Optional<Adapter> adapter,
        Optional<AdapterOutput> primaryOutput,
        String primaryPath,
        Map<String, String> frontmatter
    ) {}
}
