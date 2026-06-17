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
 * @param startLine     1-based line of the primary declaration, or 0 when unknown.
 * @param endLine       1-based last line of the source file, or 0 when unknown.
 */
public record ClassFact(
    String name,
    String relativePath,
    String leafPackage,
    Kind kind,
    List<String> routes,
    List<String> impls,
    int startLine,
    int endLine
) {

    public enum Kind { CLASS, RECORD, ENUM, INTERFACE, REST_CONTROLLER }

    public ClassFact {
        if (routes == null) routes = List.of();
        if (impls == null) impls = List.of();
    }

    /** Backward-compatible constructor for callers that predate line-range capture. */
    public ClassFact(String name, String relativePath, String leafPackage, Kind kind,
                     List<String> routes, List<String> impls) {
        this(name, relativePath, leafPackage, kind, routes, impls, 0, 0);
    }
}
