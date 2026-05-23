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
        ".venv", "venv", ".tox", ".pytest_cache", ".mypy_cache", ".cache",
        ".terraform"
    );

    // Build / config files whose full content we read for stack detection
    // and dependency extraction.
    private static final Set<String> BUILD_FILE_NAMES = Set.of(
        "pom.xml", "build.gradle", "build.gradle.kts", "package.json",
        "pyproject.toml", "requirements.txt", "Cargo.toml", "go.mod",
        "Gemfile", "tsconfig.json", "docker-compose.yml", "Dockerfile",
        "databricks.yml"
    );

    // Files we show as excerpts in the prompt (subset of BUILD_FILE_NAMES;
    // README adds free-form project description that helps the model).
    private static final Set<String> SNIPPET_FILE_NAMES = Set.of(
        "pom.xml", "package.json", "pyproject.toml", "Cargo.toml", "go.mod",
        "Gemfile", "README.md", "docker-compose.yml", "Dockerfile",
        "databricks.yml"
    );

    private static final Set<String> TEST_PATTERNS = Set.of("test", "tests", "spec", "specs", "__tests__");

    private final long maxFileSizeKb;
    private final boolean includeTestNames;
    private final StackDetector stackDetector = new StackDetector();
    private final DependencyExtractor dependencyExtractor = new DependencyExtractor();
    private final StructureSummarizer structureSummarizer = new StructureSummarizer();
    private final SpringProfileDetector springProfileDetector = new SpringProfileDetector();
    private final DatabricksProfileDetector databricksProfileDetector = new DatabricksProfileDetector();

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
        List<String> terraformFiles = new ArrayList<>();
        Map<String, String> fullBuildContent = new LinkedHashMap<>();
        Map<String, String> snippets = new LinkedHashMap<>();
        int[] fileCount = {0};
        var signals = new ScanSignals();

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
                    if (fileName.endsWith(".py")) signals.hasPythonSource = true;
                    if (fileName.endsWith(".sql")) signals.hasSqlSource = true;
                }

                if (fileName.endsWith(".tf")) {
                    signals.hasTerraformFiles = true;
                    terraformFiles.add(relative);
                    String full = readSafe(file);
                    fullBuildContent.put(relative, full);
                }

                if (BUILD_FILE_NAMES.contains(fileName)) {
                    progressCallback.accept("Reading " + fileName + "...");
                    String full = readSafe(file);
                    fullBuildContent.put(relative, full);
                    if (SNIPPET_FILE_NAMES.contains(fileName)) {
                        snippets.put(relative, firstLines(full, 60));
                    }
                    if ("databricks.yml".equals(fileName)) {
                        signals.hasDatabricksYml = true;
                    }
                }

                if (relative.endsWith("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                    || relative.endsWith("META-INF\\spring\\org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
                    signals.hasAutoConfigImports = true;
                }
                if (relative.endsWith("META-INF/spring.factories")
                    || relative.endsWith("META-INF\\spring.factories")) {
                    signals.hasSpringFactories = true;
                }

                return FileVisitResult.CONTINUE;
            }
        });

        // .sql is not in isSourceFile() (which gates the package summariser).
        // Pick those up explicitly so SQL-only Databricks pipelines still
        // surface their files to the LLM.
        sourceFiles.addAll(0, terraformFiles);

        progressCallback.accept("Scanned " + fileCount[0] + " files. Detecting stack...");
        var stack = stackDetector.detect(root, fileNameKeyed(fullBuildContent));

        progressCallback.accept("Extracting dependencies...");
        var dependencies = dependencyExtractor.extract(fileNameKeyed(fullBuildContent));

        // Post-walk source peeks. Spring's @AutoConfiguration / Databricks's
        // notebook magic + DLT markers / Terraform databricks provider all
        // need source content, so we batch them after the walk.
        if (mayBeSpring(stack)) {
            signals.hasAutoConfigAnnotation = findAutoConfigurationAnnotation(root, sourceFiles);
        }
        if (mayBeDatabricks(stack, signals)) {
            signals.hasNotebookMagic = findNotebookMagic(root, sourceFiles);
            signals.hasDltSource = findInPythonSources(root, sourceFiles,
                "import dlt", "@dlt.table", "@dlt.view", "@dlt.expect");
            signals.hasDltSqlSource = findInSqlSources(root, sourceFiles,
                "CREATE STREAMING LIVE TABLE", "CREATE OR REFRESH STREAMING LIVE TABLE",
                "CREATE LIVE TABLE");
            signals.hasDatabricksProvider = anyTerraformFileMentions(root, terraformFiles,
                "databricks/databricks");
        }

        if (stack.isSpring()) {
            stack = stack.withSpringProfile(
                springProfileDetector.detect(dependencies, signals.springSignals()));
        }

        // Promote to Databricks framework when signals fire and no stronger
        // framework was already detected (Java/Spring wins; Node frameworks
        // win; otherwise Databricks claims it).
        if (shouldClassifyAsDatabricks(stack, signals)) {
            stack = stack.withFramework("Databricks");
        }
        if (stack.isDatabricks()) {
            stack = stack.withDatabricksProfile(databricksProfileDetector.detect(signals));
        }

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
     * Cheap pre-check: do not bother scanning Java sources for
     * {@code @AutoConfiguration} unless the stack is already Spring.
     */
    private static boolean mayBeSpring(StackProfile stack) {
        return stack.isSpring();
    }

    /**
     * Cheap pre-check: only run the Databricks source peeks when at least one
     * cheap signal is already present. Avoids reading every .py / .sql / .tf
     * source on unrelated projects.
     */
    private static boolean mayBeDatabricks(StackProfile stack, ScanSignals s) {
        if (stack.isSpring()) return false;  // Java/Spring trumps Databricks
        return s.hasDatabricksYml || s.hasTerraformFiles || s.hasPythonSource || s.hasSqlSource;
    }

    private static boolean shouldClassifyAsDatabricks(StackProfile stack, ScanSignals s) {
        if (stack.isSpring()) return false;
        // Avoid hijacking projects with their own framework already detected
        // (Next.js, FastAPI, Django, Rails, etc.). Databricks is the
        // fall-through for Python / SQL / Terraform shaped projects.
        String fw = stack.framework();
        boolean noPriorFramework = fw == null
            || "Python".equalsIgnoreCase(stack.language()) && (
                "Django".equalsIgnoreCase(fw) == false
                && "FastAPI".equalsIgnoreCase(fw) == false
                && "Flask".equalsIgnoreCase(fw) == false
                && "Pyramid".equalsIgnoreCase(fw) == false
                && "Starlette".equalsIgnoreCase(fw) == false);
        if (!noPriorFramework) return false;
        return s.hasDatabricksYml
            || s.hasDatabricksProvider
            || s.hasNotebookMagic
            || s.hasDltSource
            || s.hasDltSqlSource;
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
            || name.endsWith(".cs") || name.endsWith(".rb") || name.endsWith(".swift")
            || name.endsWith(".sql") || name.endsWith(".scala");
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

    /**
     * Short-circuit scan for the @AutoConfiguration annotation. Used as a
     * corroborating starter-library signal because libraries may carry the
     * source annotation before the build generates the AutoConfiguration.imports
     * file. Stops at the first match.
     */
    private static boolean findAutoConfigurationAnnotation(Path root, List<String> sourceFiles) {
        for (var rel : sourceFiles) {
            if (!rel.endsWith(".java")) continue;
            try {
                var content = Files.readString(root.resolve(rel));
                if (content.contains("@AutoConfiguration")) return true;
            } catch (IOException ignored) { }
        }
        return false;
    }

    /** Short-circuit: returns true on the first source file beginning with Databricks notebook magic. */
    private static boolean findNotebookMagic(Path root, List<String> sourceFiles) {
        for (var rel : sourceFiles) {
            if (!rel.endsWith(".py") && !rel.endsWith(".sql") && !rel.endsWith(".scala")) continue;
            try {
                var content = Files.readString(root.resolve(rel));
                // Magic appears on the first line of an exported notebook,
                // forms: "# Databricks notebook source", "-- Databricks notebook source"
                String head = content.length() > 200 ? content.substring(0, 200) : content;
                if (head.contains("Databricks notebook source")) return true;
            } catch (IOException ignored) { }
        }
        return false;
    }

    private static boolean findInPythonSources(Path root, List<String> sourceFiles, String... needles) {
        return findInSourcesWithExtension(root, sourceFiles, ".py", needles);
    }

    private static boolean findInSqlSources(Path root, List<String> sourceFiles, String... needles) {
        return findInSourcesWithExtension(root, sourceFiles, ".sql", needles);
    }

    private static boolean findInSourcesWithExtension(
        Path root, List<String> sourceFiles, String extension, String... needles
    ) {
        for (var rel : sourceFiles) {
            if (!rel.endsWith(extension)) continue;
            try {
                var content = Files.readString(root.resolve(rel));
                for (var needle : needles) {
                    if (content.contains(needle)) return true;
                }
            } catch (IOException ignored) { }
        }
        return false;
    }

    private static boolean anyTerraformFileMentions(Path root, List<String> tfFiles, String needle) {
        for (var rel : tfFiles) {
            try {
                var content = Files.readString(root.resolve(rel));
                if (content.contains(needle)) return true;
            } catch (IOException ignored) { }
        }
        return false;
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
