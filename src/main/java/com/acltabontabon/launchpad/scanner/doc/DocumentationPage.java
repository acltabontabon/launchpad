package com.acltabontabon.launchpad.scanner.doc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single documentation page surfaced by {@link DocumentationDetector}.
 * <p>
 * {@code path} is project-relative. {@code title} is the extracted heading
 * (Markdown H1 or AsciiDoc {@code = Title}) or a humanised file stem when no
 * heading is present. {@code format} tells MCP clients which renderer to
 * use. {@code purpose} is a coarse classification (see {@link Purpose}) used
 * by clients to filter the index without reading every page.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentationPage(
    String path,
    String title,
    PageFormat format,
    Purpose purpose
) {

    public DocumentationPage {
        if (purpose == null) purpose = Purpose.UNKNOWN;
    }

    public enum PageFormat { MARKDOWN, ASCIIDOC }
}
