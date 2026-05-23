package com.acltabontabon.launchpad.scanner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single documentation page surfaced by {@link DocumentationDetector}.
 * <p>
 * {@code path} is project-relative. {@code title} is the extracted heading
 * (Markdown H1, AsciiDoc {@code = Title}, RST underlined heading) or a
 * humanised file stem when no heading is present. {@code format} tells MCP
 * clients which renderer to use.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentationPage(
    String path,
    String title,
    PageFormat format
) {

    public enum PageFormat { MARKDOWN, ASCIIDOC, RST }
}
