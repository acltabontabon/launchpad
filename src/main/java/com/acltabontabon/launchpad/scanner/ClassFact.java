package com.acltabontabon.launchpad.scanner;

import java.util.List;

/**
 * One classified JVM source file: its primary type, kind, leaf package, and
 * (best-effort) cross-file relationships. Drives the deep `## Architecture`
 * tree in the generated AGENTS.md.
 *
 * @param name          The simple class / record / enum / interface name.
 * @param relativePath  Source-file path relative to project root.
 * @param leafPackage   Last path segment containing this file (e.g. "controller").
 * @param kind          What this top-level declaration is.
 * @param routes        For controllers: detected HTTP routes like "POST /loan-decision".
 *                      Empty for non-controllers.
 * @param impls         For interfaces: implementing class names found elsewhere in the project.
 *                      Empty otherwise.
 */
public record ClassFact(
    String name,
    String relativePath,
    String leafPackage,
    Kind kind,
    List<String> routes,
    List<String> impls
) {

    public enum Kind { CLASS, RECORD, ENUM, INTERFACE, REST_CONTROLLER }

    public ClassFact {
        if (routes == null) routes = List.of();
        if (impls == null) impls = List.of();
    }
}
