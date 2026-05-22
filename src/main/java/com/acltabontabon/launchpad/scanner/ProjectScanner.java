package com.acltabontabon.launchpad.scanner;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ProjectScanner {

    // Always-skip dirs regardless of .gitignore - well-known build outputs
    // and VCS dirs that no project would intentionally want scanned.
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "target", "build", ".idea", ".vscode",
        "__pycache__", ".gradle", "dist", "out", ".next", ".nuxt", "vendor",
        ".venv", "venv", ".tox", ".pytest_cache", ".mypy_cache", ".cache"
    );

    // Build / config files whose full content we read for stack detection
    // and dependency extraction.
    private static final Set<String> BUILD_FILE_NAMES = Set.of(
        "pom.xml", "build.gradle", "build.gradle.kts", "package.json",
        "pyproject.toml", "requirements.txt", "Cargo.toml", "go.mod",
        "Gemfile", "tsconfig.json", "docker-compose.yml", "Dockerfile"
    );

    // Files we show as excerpts in the prompt (subset of BUILD_FILE_NAMES;
    // README adds free-form project description that helps the model).
    private static final Set<String> SNIPPET_FILE_NAMES = Set.of(
        "pom.xml", "package.json", "pyproject.toml", "Cargo.toml", "go.mod",
        "Gemfile", "README.md", "docker-compose.yml", "Dockerfile"
    );

    private static final Set<String> TEST_PATTERNS = Set.of("test", "tests", "spec", "specs", "__tests__");

    private final long maxFileSizeKb;
    private final boolean includeTestNames;
    private final StackDetector stackDetector = new StackDetector();
    private final DependencyExtractor dependencyExtractor = new DependencyExtractor();
    private final StructureSummarizer structureSummarizer = new StructureSummarizer();

    public ProjectScanner(
        @Value("${launchpad.scan.max-file-size-kb:512}") long maxFileSizeKb,
        @Value("${launchpad.scan.include-test-names:true}") boolean includeTestNames
    ) {
        this.maxFileSizeKb = maxFileSizeKb;
        this.includeTestNames = includeTestNames;
    }

    /** Test-friendly factory with sensible defaults. */
    public static ProjectScanner forTesting() {
        return new ProjectScanner(512, true);
    }

    public ProjectContext scan(String rootPath, Consumer<String> progressCallback) throws IOException {
        var root = Path.of(rootPath).toAbsolutePath();
        var projectName = root.getFileName().toString();
        var gitignore = GitIgnoreFilter.forRoot(root);

        List<String> sourceFiles = new ArrayList<>();
        List<String> testClassNames = new ArrayList<>();
        Map<String, String> fullBuildContent = new LinkedHashMap<>();
        Map<String, String> snippets = new LinkedHashMap<>();
        int[] fileCount = {0};

        progressCallback.accept("Scanning file tree...");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                var name = dir.getFileName().toString();
                if (SKIP_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                if (!dir.equals(root)) {
                    var rel = root.relativize(dir);
                    if (gitignore.isIgnored(rel, true)) return FileVisitResult.SKIP_SUBTREE;
                    progressCallback.accept("Scanning " + truncateLeft(rel.toString(), 70) + "/");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                fileCount[0]++;
                if (fileCount[0] % 50 == 0) {
                    progressCallback.accept("Scanned " + fileCount[0] + " files...");
                }

                var rel = root.relativize(file);
                if (gitignore.isIgnored(rel, false)) return FileVisitResult.CONTINUE;
                if (attrs.size() > maxFileSizeKb * 1024) return FileVisitResult.CONTINUE;

                var relative = rel.toString();
                var fileName = file.getFileName().toString();
                var pathParts = relative.split("[/\\\\]");

                boolean isTestFile = Arrays.stream(pathParts)
                    .anyMatch(p -> TEST_PATTERNS.contains(p.toLowerCase()));

                if (isTestFile) {
                    if (includeTestNames && isSourceFile(fileName)) {
                        testClassNames.add(relative);
                    }
                    return FileVisitResult.CONTINUE;
                }

                if (isSourceFile(fileName)) {
                    sourceFiles.add(relative);
                }

                if (BUILD_FILE_NAMES.contains(fileName)) {
                    progressCallback.accept("Reading " + fileName + "...");
                    String full = readSafe(file);
                    fullBuildContent.put(relative, full);
                    if (SNIPPET_FILE_NAMES.contains(fileName)) {
                        snippets.put(relative, firstLines(full, 60));
                    }
                }

                return FileVisitResult.CONTINUE;
            }
        });

        progressCallback.accept("Scanned " + fileCount[0] + " files. Detecting stack...");
        var stack = stackDetector.detect(root, fileNameKeyed(fullBuildContent));

        progressCallback.accept("Extracting dependencies...");
        var dependencies = dependencyExtractor.extract(fileNameKeyed(fullBuildContent));

        progressCallback.accept("Summarising source structure...");
        var packageSummaries = structureSummarizer.summarize(root, sourceFiles);

        var entryPoints = detectEntryPoints(root, sourceFiles, stack);
        var existingContext = readExistingContextSummary(root);

        return new ProjectContext(
            projectName, rootPath, stack,
            sourceFiles, testClassNames,
            entryPoints, dependencies, snippets, packageSummaries,
            existingContext
        );
    }

    /**
     * Pick up any existing AI context file in the project so the LLM is told
     * "this is already documented, don't duplicate". Reads CLAUDE.md first
     * (preferred), falls back to .cursorrules.
     */
    private static String readExistingContextSummary(Path root) {
        for (var name : new String[] { "CLAUDE.md", ".cursorrules" }) {
            var p = root.resolve(name);
            if (Files.isRegularFile(p)) {
                try {
                    var s = Files.readString(p);
                    return s.length() <= 800 ? s : s.substring(0, 800);
                } catch (IOException ignored) { }
            }
        }
        return null;
    }

    /** Returns the same map keyed by basename (pom.xml, package.json, ...) for detectors. */
    private static Map<String, String> fileNameKeyed(Map<String, String> byRelPath) {
        var out = new LinkedHashMap<String, String>();
        byRelPath.forEach((path, content) -> {
            var name = path.contains("/") || path.contains("\\")
                ? path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)
                : path;
            out.putIfAbsent(name, content);
        });
        return out;
    }

    private boolean isSourceFile(String name) {
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".py")
            || name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".js")
            || name.endsWith(".jsx") || name.endsWith(".go") || name.endsWith(".rs")
            || name.endsWith(".cs") || name.endsWith(".rb") || name.endsWith(".swift");
    }

    /**
     * Find likely entry points. Beyond file-name heuristics, peek inside files
     * for framework markers (@SpringBootApplication, "if __name__ == '__main__'")
     * so we catch projects whose entry isn't named after a convention.
     */
    private Map<String, String> detectEntryPoints(Path root, List<String> sourceFiles, StackProfile stack) {
        var entryPoints = new LinkedHashMap<String, String>();
        for (var f : sourceFiles) {
            var name = Path.of(f).getFileName().toString();
            if (name.endsWith("Application.java") || name.endsWith("App.java") || name.equals("main.py")
                || name.equals("main.go") || name.equals("main.rs") || name.equals("main.ts")
                || name.equals("index.ts") || name.equals("server.js") || name.equals("app.js")) {
                entryPoints.putIfAbsent("main", f);
            } else if (name.endsWith("Config.java") || name.endsWith("Configuration.java")) {
                entryPoints.putIfAbsent("config", f);
            }
        }
        if (!entryPoints.containsKey("main") && "Java".equals(stack.language())) {
            findJavaSpringBootMain(root, sourceFiles).ifPresent(p -> entryPoints.put("main", p));
        }
        if (!entryPoints.containsKey("main") && "Python".equals(stack.language())) {
            findPythonMain(root, sourceFiles).ifPresent(p -> entryPoints.put("main", p));
        }
        return entryPoints;
    }

    private static java.util.Optional<String> findJavaSpringBootMain(Path root, List<String> sourceFiles) {
        for (var rel : sourceFiles) {
            if (!rel.endsWith(".java")) continue;
            try {
                var content = Files.readString(root.resolve(rel));
                if (content.contains("@SpringBootApplication")) return Optional.of(rel);
            } catch (IOException ignored) { }
        }
        return Optional.empty();
    }

    private static java.util.Optional<String> findPythonMain(Path root, List<String> sourceFiles) {
        for (var rel : sourceFiles) {
            if (!rel.endsWith(".py")) continue;
            try {
                var content = Files.readString(root.resolve(rel));
                if (content.contains("if __name__")) return Optional.of(rel);
            } catch (IOException ignored) { }
        }
        return Optional.empty();
    }

    private static String truncateLeft(String s, int max) {
        return s.length() <= max ? s : "..." + s.substring(s.length() - (max - 3));
    }

    private static String readSafe(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            return "";
        }
    }

    private static String firstLines(String content, int maxLines) {
        return content.lines().limit(maxLines).collect(Collectors.joining("\n"));
    }
}
