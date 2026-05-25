package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.standards.Adapter;
import com.acltabontabon.launchpad.standards.AdapterOutput;
import com.acltabontabon.launchpad.standards.StandardsLoader;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AdapterResolver {

    private static final Logger log = LoggerFactory.getLogger(AdapterResolver.class);

    private final StandardsLoader standardsLoader;

    public AdapterResolver(StandardsLoader standardsLoader) {
        this.standardsLoader = standardsLoader;
    }

    public ResolvedAdapter resolve(Path projectRoot, ContextTarget target) {
        var adapter = loadAdapterWithLegacyAlias(projectRoot, target);
        var primaryOutput = adapter.flatMap(AdapterResolver::firstOutput);
        var defaultPath = target == ContextTarget.CLAUDE ? "AGENTS.md" : ".cursorrules";
        var primaryPath = primaryOutput.map(AdapterOutput::path).orElse(defaultPath);
        var frontmatter = primaryOutput.map(AdapterOutput::frontmatter).orElse(Map.of());
        return new ResolvedAdapter(adapter, primaryOutput, primaryPath,
            frontmatter != null ? frontmatter : Map.of());
    }

    private Optional<Adapter> loadAdapterWithLegacyAlias(Path projectRoot, ContextTarget target) {
        var canonical = adapterIdFor(target);
        var found = standardsLoader.loadAdapter(projectRoot, canonical);
        if (found.isPresent()) return found;
        if (target == ContextTarget.CLAUDE) {
            var legacy = standardsLoader.loadAdapter(projectRoot, "claude");
            if (legacy.isPresent()) {
                log.warn("Legacy adapter id 'claude' detected. Treating as 'agents'. "
                    + "Please rename the adapter id to 'agents' in standards-pack.yml.");
                return legacy;
            }
        }
        return Optional.empty();
    }

    private static String adapterIdFor(ContextTarget target) {
        return switch (target) {
            case CLAUDE -> "agents";
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
