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
    private static final String CANONICAL_ID = "agents";
    private static final String LEGACY_CLAUDE_ID = "claude";
    private static final String LEGACY_CURSOR_ID = "cursor";
    private static final String DEFAULT_PRIMARY_PATH = "AGENTS.md";

    private final StandardsLoader standardsLoader;

    public AdapterResolver(StandardsLoader standardsLoader) {
        this.standardsLoader = standardsLoader;
    }

    public ResolvedAdapter resolve(Path projectRoot) {
        var adapter = loadAdapterWithLegacyAlias(projectRoot);
        var primaryOutput = adapter.flatMap(AdapterResolver::firstOutput);
        var primaryPath = primaryOutput.map(AdapterOutput::path).orElse(DEFAULT_PRIMARY_PATH);
        var frontmatter = primaryOutput.map(AdapterOutput::frontmatter).orElse(Map.of());
        return new ResolvedAdapter(adapter, primaryOutput, primaryPath,
            frontmatter != null ? frontmatter : Map.of());
    }

    private Optional<Adapter> loadAdapterWithLegacyAlias(Path projectRoot) {
        var found = standardsLoader.loadAdapter(projectRoot, CANONICAL_ID);
        if (found.isPresent()) return found;

        var legacyClaude = standardsLoader.loadAdapter(projectRoot, LEGACY_CLAUDE_ID);
        if (legacyClaude.isPresent()) {
            log.warn("Legacy adapter id 'claude' detected. Treating as 'agents'. "
                + "Please rename the adapter id to 'agents' in standards-pack.yml.");
            return legacyClaude;
        }

        var legacyCursor = standardsLoader.loadAdapter(projectRoot, LEGACY_CURSOR_ID);
        if (legacyCursor.isPresent()) {
            log.warn("Legacy adapter id 'cursor' detected. Launchpad no longer ships a "
                + "dedicated Cursor target; the adapter is ignored. Cursor reads AGENTS.md "
                + "natively. Remove the 'cursor' block from standards-pack.yml or rename it "
                + "to 'agents' if you want it to drive the primary file.");
        }

        return Optional.empty();
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
