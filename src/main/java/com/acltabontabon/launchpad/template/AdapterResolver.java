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
        var resolved = loadAdapterWithLegacyAlias(projectRoot);
        var adapter = resolved.adapter;
        var primaryOutput = adapter.flatMap(AdapterResolver::firstOutput);
        var declaredPath = primaryOutput.map(AdapterOutput::path).orElse(DEFAULT_PRIMARY_PATH);
        // Legacy 'claude' adapters declare path: CLAUDE.md. When that adapter
        // is alias-loaded, ignore the declared path - the whole point of the
        // alias is to migrate to the vendor-neutral AGENTS.md. The adapter's
        // includes and frontmatter still apply.
        var primaryPath = resolved.legacy ? DEFAULT_PRIMARY_PATH : declaredPath;
        var frontmatter = primaryOutput.map(AdapterOutput::frontmatter).orElse(Map.of());
        return new ResolvedAdapter(adapter, primaryOutput, primaryPath,
            frontmatter != null ? frontmatter : Map.of());
    }

    private LoadResult loadAdapterWithLegacyAlias(Path projectRoot) {
        var found = standardsLoader.loadAdapter(projectRoot, CANONICAL_ID);
        if (found.isPresent()) return new LoadResult(found, false);

        var legacyClaude = standardsLoader.loadAdapter(projectRoot, LEGACY_CLAUDE_ID);
        if (legacyClaude.isPresent()) {
            log.warn("Legacy adapter id 'claude' detected. Treating as 'agents' "
                + "(primary file lands at AGENTS.md regardless of the adapter's declared path). "
                + "Please rename the adapter id to 'agents' in standards-pack.yml and update "
                + "its path to AGENTS.md to silence this warning.");
            return new LoadResult(legacyClaude, true);
        }

        var legacyCursor = standardsLoader.loadAdapter(projectRoot, LEGACY_CURSOR_ID);
        if (legacyCursor.isPresent()) {
            log.warn("Legacy adapter id 'cursor' detected. Launchpad no longer ships a "
                + "dedicated Cursor target; the adapter is ignored. Cursor reads AGENTS.md "
                + "natively. Remove the 'cursor' block from standards-pack.yml or rename it "
                + "to 'agents' if you want it to drive the primary file.");
        }

        return new LoadResult(Optional.empty(), false);
    }

    /** Internal result tracking whether the adapter came from a legacy alias. */
    private record LoadResult(Optional<Adapter> adapter, boolean legacy) {}

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
