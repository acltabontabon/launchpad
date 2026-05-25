package com.acltabontabon.launchpad.springboot.scanner;

import com.acltabontabon.launchpad.scanner.GitIgnoreFilter;
import com.acltabontabon.launchpad.scanner.PackageRepresentativeSource;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.scanner.ProjectScriptsProvider;
import com.acltabontabon.launchpad.scanner.ProjectSupportDetector;
import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.scanner.StructureSummarizer;
import com.acltabontabon.launchpad.scanner.doc.AiPurposeClassifier;
import com.acltabontabon.launchpad.scanner.doc.DocumentationDetector;
import com.acltabontabon.launchpad.scanner.doc.PurposeClassifier;
import com.acltabontabon.launchpad.scanner.doc.ReadmeIntroExtractor;
import com.acltabontabon.launchpad.scanner.doc.ReadmeSectionsExtractor;
import com.acltabontabon.launchpad.springboot.detectors.SpringProfileDetector;
import com.acltabontabon.launchpad.springboot.maven.DependencyExtractor;
import com.acltabontabon.launchpad.springboot.maven.MavenProfileExtractor;
import com.acltabontabon.launchpad.springboot.parser.ActuatorDetector;
import com.acltabontabon.launchpad.springboot.parser.EndpointExtractor;
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

/**
 * Walks a Spring Boot Java + Maven project tree and produces a structured
 * {@link ProjectContext}. Trusts its caller to have cleared
 * {@link ProjectSupportDetector} - downstream phases assume {@code pom.xml}
 * exists at the root, the language is Java, and the framework is Spring Boot.
 */
@Service
public class ProjectScanner {

    /**
     * Directories the file walk never descends into. Covers VCS metadata,
     * IDE state, language/runtime caches, generator/build output, and
     * vendored dependency trees. Anything generated here (including the
     * occasional README.md inside {@code target/site/}) MUST stay invisible
     * to the doc-discovery path so the index is only the project's own
     * authored content.
     */
    private static final Set<String> SKIP_DIRS = Set.of(
        // VCS + IDE
        ".git", ".idea", ".vscode",
        // Maven / Gradle build output + wrappers
        "target", "build", "out", "bin", ".gradle", ".mvn",
        // Generated source trees
        "generated", "generated-sources",
        // Static-site generator output (kept distinct from source `docs/`)
        "site", "_site",
        // Cross-language caches we may encounter in mixed repos
        "node_modules", ".next", ".nuxt", "dist",
        "vendor", ".venv", "venv", ".cache"
    );

    private static final Set<String> BUILD_FILE_NAMES = Set.of(
        "pom.xml", "README.md",
        "Dockerfile", "docker-compose.yml",
        "application.properties", "application.yml", "application.yaml"
    );

    // Files we show as excerpts in the prompt. README and pom carry the bulk
    // of "what is this project" signal, so they get ~200 lines; supporting
    // infra files get 60.
    private static final Set<String> SNIPPET_LONG_FILE_NAMES = Set.of(
        "README.md", "pom.xml"
    );
    private static final Set<String> SNIPPET_SHORT_FILE_NAMES = Set.of(
        "Dockerfile", "docker-compose.yml"
    );
    private static final int SNIPPET_LONG_LINES = 200;
    private static final int SNIPPET_SHORT_LINES = 60;

    /**
     * Early-version doc scope: Markdown and AsciiDoc only. The two
     * canonical extensions are paired with their longer-form aliases so
     * real-world repos using {@code .markdown} or {@code .asciidoc} still
     * surface. Other formats (.rst, .txt, .html, .pdf) are intentionally
     * out of scope.
     */
    private static final Set<String> DOC_EXTENSIONS = Set.of(
        ".md", ".markdown", ".adoc", ".asciidoc"
    );

    private static final Set<String> TEST_PATTERNS = Set.of("test", "tests");

    private final long maxFileSizeKb;
    private final boolean includeTestNames;
    private final StackDetector stackDetector = new StackDetector();
    private final DependencyExtractor dependencyExtractor = new DependencyExtractor();
    private final StructureSummarizer structureSummarizer = new StructureSummarizer();
    private final SpringProfileDetector springProfileDetector = new SpringProfileDetector();
    private final DocumentationDetector documentationDetector;

    public ProjectScanner(
        @Value("${launchpad.scan.max-file-size-kb:512}") long maxFileSizeKb,
        @Value("${launchpad.scan.include-test-names:true}") boolean includeTestNames,
        AiPurposeClassifier aiPurposeClassifier
    ) {
        this.maxFileSizeKb = maxFileSizeKb;
        this.includeTestNames = includeTestNames;
        this.documentationDetector = new DocumentationDetector(new PurposeClassifier(aiPurposeClassifier));
    }

    /** Test-friendly factory with sensible defaults and heuristic-only doc classification. */
    public static ProjectScanner forTesting() {
        return new ProjectScanner(512, true, null);
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
                }

                if (BUILD_FILE_NAMES.contains(fileName)) {
                    progressCallback.accept("Reading " + fileName + "...");
                    String full = readSafe(file);
                    fullBuildContent.put(relative, full);
                    if (SNIPPET_LONG_FILE_NAMES.contains(fileName)) {
                        snippets.put(relative, firstLines(full, SNIPPET_LONG_LINES));
                    } else if (SNIPPET_SHORT_FILE_NAMES.contains(fileName)) {
                        snippets.put(relative, firstLines(full, SNIPPET_SHORT_LINES));
                    }
                }

                if (hasDocExtension(fileName)) {
                    signals.docFiles.add(relative);
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

        progressCallback.accept("Scanned " + fileCount[0] + " files. Detecting stack...");
        var stack = stackDetector.detect(root, fileNameKeyed(fullBuildContent));

        progressCallback.accept("Extracting dependencies...");
        var dependencies = dependencyExtractor.extract(fileNameKeyed(fullBuildContent));

        signals.hasAutoConfigAnnotation = findAutoConfigurationAnnotation(root, sourceFiles);
        stack = stack.withSpringProfile(
            springProfileDetector.detect(dependencies, signals.springSignals()));

        progressCallback.accept("Summarising source structure...");
        var packageSummaries = structureSummarizer.summarize(root, sourceFiles);

        var entryPoints = detectEntryPoints(root, sourceFiles);
        var existingContext = readExistingContextSummary(root);
        var documentation = documentationDetector.detect(root, signals.documentationSignals());

        progressCallback.accept("Extracting HTTP routes...");
        var endpointExtractor = new EndpointExtractor();
        var endpoints = endpointExtractor.extract(root, sourceFiles);
        var controllerSources = endpointExtractor.getControllerSources();

        var readmeContent = fileContent(fullBuildContent, "README.md");
        var readmeIntro = ReadmeIntroExtractor.extract(readmeContent);
        var readmeSections = ReadmeSectionsExtractor.extract(readmeContent);

        var pomContent = fileContent(fullBuildContent, "pom.xml");
        var pomDescription = extractPomDescription(pomContent);
        var profileExtract = MavenProfileExtractor.extractWithRaw(pomContent);

        progressCallback.accept("Cataloging project scripts...");
        var scriptsCatalog = ProjectScriptsProvider.catalog(root);

        progressCallback.accept("Reading representative source per package...");
        var packageRepresentatives = PackageRepresentativeSource.collect(root, packageSummaries, sourceFiles);

        progressCallback.accept("Detecting Spring Actuator endpoints...");
        var appConfigByPath = appConfigContents(fullBuildContent);
        var actuator = ActuatorDetector.detect(dependencies, appConfigByPath, pomContent);

        return new ProjectContext(
            projectName, rootPath, stack,
            sourceFiles, testClassNames,
            entryPoints, dependencies, snippets, packageSummaries,
            existingContext, documentation,
            endpoints, readmeIntro, pomDescription, profileExtract.profiles(),
            controllerSources, profileExtract.rawBodies(), readmeSections,
            scriptsCatalog, packageRepresentatives,
            actuator.endpoints(), actuator.notes()
        );
    }

    private static Map<String, String> appConfigContents(Map<String, String> fullBuildContent) {
        var out = new java.util.LinkedHashMap<String, String>();
        for (var entry : fullBuildContent.entrySet()) {
            var key = entry.getKey().toLowerCase();
            if (key.endsWith("application.properties")
                || key.endsWith("application.yml")
                || key.endsWith("application.yaml")) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private static String fileContent(Map<String, String> fullBuildContent, String fileName) {
        for (var entry : fullBuildContent.entrySet()) {
            var key = entry.getKey();
            var basename = key.substring(Math.max(
                key.lastIndexOf('/') + 1, key.lastIndexOf('\\') + 1));
            if (basename.equals(fileName)) return entry.getValue();
        }
        return "";
    }

    private static final java.util.regex.Pattern POM_DESCRIPTION = java.util.regex.Pattern.compile(
        "<description>(.*?)</description>", java.util.regex.Pattern.DOTALL);

    private static String extractPomDescription(String pomXml) {
        if (pomXml == null || pomXml.isBlank()) return "";
        var matcher = POM_DESCRIPTION.matcher(pomXml);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    private static boolean hasDocExtension(String fileName) {
        String lower = fileName.toLowerCase();
        for (String ext : DOC_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * Pick up any existing AI context file in the project so the LLM is told
     * "this is already documented, don't duplicate". Reads AGENTS.md first
     * (preferred); falls back to legacy CLAUDE.md / .cursorrules.
     */
    private static String readExistingContextSummary(Path root) {
        for (var name : new String[] { "AGENTS.md", "CLAUDE.md", ".cursorrules" }) {
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

    /** Returns the same map keyed by basename (pom.xml, README.md, ...) for detectors. */
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
        return name.endsWith(".java");
    }

    /**
     * Find the Spring Boot main class. Tries the convention-named candidates
     * first (Application.java / App.java) and falls back to scanning Java
     * sources for {@code @SpringBootApplication}. Configuration classes are
     * captured separately so the AI prompt can refer to bean wiring.
     */
    private Map<String, String> detectEntryPoints(Path root, List<String> sourceFiles) {
        var entryPoints = new LinkedHashMap<String, String>();
        for (var f : sourceFiles) {
            var name = Path.of(f).getFileName().toString();
            if (name.endsWith("Application.java") || name.endsWith("App.java")) {
                entryPoints.putIfAbsent("main", f);
            } else if (name.endsWith("Config.java") || name.endsWith("Configuration.java")) {
                entryPoints.putIfAbsent("config", f);
            }
        }
        if (!entryPoints.containsKey("main")) {
            findJavaSpringBootMain(root, sourceFiles).ifPresent(p -> entryPoints.put("main", p));
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

    private static Optional<String> findJavaSpringBootMain(Path root, List<String> sourceFiles) {
        for (var rel : sourceFiles) {
            if (!rel.endsWith(".java")) continue;
            try {
                var content = Files.readString(root.resolve(rel));
                if (content.contains("@SpringBootApplication")) return Optional.of(rel);
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
