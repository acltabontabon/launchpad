package com.acltabontabon.launchpad.scanner;

/**
 * Builds a {@link DatabricksProfile} from the {@link ScanSignals} collected
 * during the project walk. Pure function over its input - no IO.
 * <p>
 * Signal-to-facet mapping:
 * <ul>
 *   <li>{@code terraform-deployment} - any *.tf files present (the facet
 *       content itself asks the LLM to identify which providers, including
 *       the databricks provider when {@code hasDatabricksProvider} is set).</li>
 *   <li>{@code dlt-pipeline} - DLT markers in Python or SQL source.</li>
 *   <li>{@code python-source} - any .py files present.</li>
 *   <li>{@code sql-source} - any .sql files present.</li>
 * </ul>
 */
public final class DatabricksProfileDetector {

    public DatabricksProfile detect(ScanSignals signals) {
        boolean terraform = signals.hasTerraformFiles;
        boolean dlt = signals.hasDltSource || signals.hasDltSqlSource;
        boolean python = signals.hasPythonSource;
        boolean sql = signals.hasSqlSource;
        return new DatabricksProfile(terraform, dlt, python, sql);
    }
}
