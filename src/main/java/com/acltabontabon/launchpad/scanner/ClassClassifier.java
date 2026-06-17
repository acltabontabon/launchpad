package com.acltabontabon.launchpad.scanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cheap regex pass over a single JVM source file. Returns a {@link ClassFact}
 * describing the primary top-level declaration (class / record / enum /
 * interface) and any controller-style annotation. Returns {@code null} when
 * the file is unreadable or contains no top-level declaration we can name.
 * <p>
 * Implementation lists for interfaces and route lists for controllers are
 * filled in by {@link ProjectClassFacts} during a second pass.
 */
public final class ClassClassifier {

    /** Read this many lines per file - enough to capture the class declaration + nearby annotations. */
    private static final int MAX_LINES_PER_FILE = 60;
    /** Hard byte ceiling to skip generated mega-files. */
    private static final long MAX_FILE_BYTES = 24 * 1024;

    // First-match top-level declarations. Order matters: record / enum / interface
    // are matched BEFORE class to avoid the bare-class regex eating a `class`
    // keyword that's part of e.g. `final class`.
    // Optional leading annotation block - "@RestController @RequestMapping(...) " etc.
    private static final String ANNOTATIONS = "(?:@\\w+(?:\\s*\\([^)]*\\))?\\s+)*";
    private static final Pattern RECORD_DECL = Pattern.compile(
        "^\\s*" + ANNOTATIONS + "(?:public\\s+)?record\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern ENUM_DECL = Pattern.compile(
        "^\\s*" + ANNOTATIONS + "(?:public\\s+)?enum\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern INTERFACE_DECL = Pattern.compile(
        "^\\s*" + ANNOTATIONS + "(?:public\\s+)?(?:sealed\\s+|non-sealed\\s+)?interface\\s+(\\w+)",
        Pattern.MULTILINE);
    private static final Pattern CLASS_DECL = Pattern.compile(
        "^\\s*" + ANNOTATIONS
            + "(?:public\\s+)?(?:abstract\\s+|final\\s+|sealed\\s+|non-sealed\\s+)?class\\s+(\\w+)",
        Pattern.MULTILINE);

    private static final Pattern REST_CONTROLLER_ANN = Pattern.compile(
        "@(?:RestController|Controller)\\b");

    private ClassClassifier() {}

    /**
     * Returns a {@link ClassFact} for the file or {@code null} when there is
     * no recognisable top-level declaration. Never throws.
     */
    public static ClassFact classify(Path projectRoot, String relativePath) {
        if (projectRoot == null || relativePath == null) return null;
        if (!isJvmSource(relativePath)) return null;
        var path = projectRoot.resolve(relativePath);
        if (!Files.isRegularFile(path)) return null;

        String head;
        int totalLines;
        try {
            if (Files.size(path) > MAX_FILE_BYTES) return null;
            var lines = Files.readAllLines(path);
            totalLines = lines.size();
            if (lines.size() > MAX_LINES_PER_FILE) lines = lines.subList(0, MAX_LINES_PER_FILE);
            head = String.join("\n", lines);
        } catch (Exception e) {
            return null;
        }

        var firstMatch = firstDeclaration(head);
        if (firstMatch == null) return null;

        var leaf = leafPackage(relativePath);
        var kind = firstMatch.kind;
        if (kind == ClassFact.Kind.CLASS && REST_CONTROLLER_ANN.matcher(head).find()) {
            kind = ClassFact.Kind.REST_CONTROLLER;
        }
        // startLine = 1-based line of the matched declaration; endLine = last line of the file.
        int startLine = SourceLines.lineNumberAt(head, firstMatch.offset);
        return new ClassFact(firstMatch.name, relativePath, leaf, kind, List.of(), List.of(),
            startLine, totalLines);
    }

    private record FirstDecl(String name, ClassFact.Kind kind, int offset) {}

    /** Returns the lowest-offset top-level declaration found in the head. */
    private static FirstDecl firstDeclaration(String head) {
        FirstDecl best = null;
        var probes = new FirstDecl[] {
            probe(RECORD_DECL, head, ClassFact.Kind.RECORD),
            probe(ENUM_DECL, head, ClassFact.Kind.ENUM),
            probe(INTERFACE_DECL, head, ClassFact.Kind.INTERFACE),
            probe(CLASS_DECL, head, ClassFact.Kind.CLASS)
        };
        for (var p : probes) {
            if (p == null) continue;
            if (best == null || p.offset < best.offset) best = p;
        }
        return best;
    }

    private static FirstDecl probe(Pattern p, String head, ClassFact.Kind kind) {
        Matcher m = p.matcher(head);
        return m.find() ? new FirstDecl(m.group(1), kind, m.start()) : null;
    }

    static String leafPackage(String relativePath) {
        var slash = relativePath.lastIndexOf('/');
        if (slash < 0) return "";
        var dir = relativePath.substring(0, slash);
        var prevSlash = dir.lastIndexOf('/');
        return prevSlash < 0 ? dir : dir.substring(prevSlash + 1);
    }

    static boolean isJvmSource(String relative) {
        return relative.endsWith(".java") || relative.endsWith(".kt");
    }
}
