package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.scanner.StackProfile;
import java.util.List;

/**
 * Per-framework prompt routing. Each supported framework contributes one
 * strategy bean that names its prompt directory slug and reports which facet
 * fragments to compose for a given project.
 *
 * <p>This is the extension seam for adding new framework paths. Once a new
 * framework is in scope (admitted by {@link com.acltabontabon.launchpad.scanner.ProjectSupportSignal}
 * and detected into {@link StackProfile}), implementing this interface as a
 * Spring bean is the only PromptSelector-side change needed - {@link PromptSelector}
 * discovers every {@code FrameworkPromptStrategy} via constructor injection of
 * the bean list and picks the first one whose {@link #appliesTo} returns true.
 *
 * <p>Today only {@code SpringPromptStrategy} is registered. Adding (for
 * example) a Databricks path later means:
 * <ol>
 *   <li>Drop {@code prompts/databricks/base/*.txt} + {@code prompts/databricks/facets/*.txt}.</li>
 *   <li>Add a {@code DatabricksPromptStrategy} bean returning slug {@code "databricks"}.</li>
 *   <li>Register the matching support signal so {@link com.acltabontabon.launchpad.scanner.ProjectSupportDetector}
 *       admits Databricks projects.</li>
 * </ol>
 * No edits to {@link PromptSelector} are required.
 */
public interface FrameworkPromptStrategy {

    /** Does this strategy own prompt routing for the given project? */
    boolean appliesTo(StackProfile stack);

    /**
     * Directory name under {@code prompts/} - the {@code <slug>} in
     * {@code prompts/<slug>/base/...} and {@code prompts/<slug>/facets/...}.
     * Lowercase, hyphen-separated, no leading slash.
     */
    String slug();

    /**
     * Facet ids to compose against the base template for the given project.
     * Each id maps to {@code prompts/<slug>/facets/<id>.txt}. Returning an
     * empty list yields the bare base template.
     */
    List<String> facetsFor(StackProfile stack);
}
