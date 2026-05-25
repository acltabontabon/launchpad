package com.acltabontabon.launchpad.tui.discovery;

import java.nio.file.Path;

/**
 * A Spring Boot Maven project found by walking the user's common dev roots
 * (e.g. ~/Workspace, ~/code). Carries just enough to render in the picker
 * and pass to the support gate on selection.
 *
 * @param path      absolute, normalised project root
 * @param name      display label (directory basename)
 * @param framework label returned by ProjectSupportDetector (e.g. "Spring Boot Java + Maven")
 */
public record DiscoveredProject(Path path, String name, String framework) {}
