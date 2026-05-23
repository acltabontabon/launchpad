package com.acltabontabon.launchpad.scanner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * Sub-stack details for a Databricks project. Populated by
 * DatabricksProfileDetector after dependency extraction and source-content
 * peeks. Drives which facets the prompt composer pulls in.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DatabricksProfile(
    boolean terraform,
    boolean dlt,
    boolean python,
    boolean sql
) {

    public static DatabricksProfile empty() {
        return new DatabricksProfile(false, false, false, false);
    }

    /**
     * Ordered list of facet ids enabled on this profile. The composer reads
     * databricks/facets/&lt;id&gt;.txt for each entry.
     */
    public List<String> facets() {
        var out = new ArrayList<String>();
        if (terraform) out.add("terraform-deployment");
        if (dlt) out.add("dlt-pipeline");
        if (python) out.add("python-source");
        if (sql) out.add("sql-source");
        return out;
    }
}
