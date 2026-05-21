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
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ProjectScanner {

    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "target", "build", ".idea", ".vscode",
        "__pycache__", ".gradle", "dist", "out", ".next", ".nuxt", "vendor"
    );

    private static final Set<String> KEY_FILE_NAMES = Set.of(
        "pom.xml", "build.gradle", "package.json", "pyproject.toml",
        "Cargo.toml", "go.mod", "README.md", "docker-compose.yml", "Dockerfile"
    );

    private static final Set<String> TEST_PATTERNS = Set.of("test", "tests", "spec", "specs");

    @Value("${launchpad.scan.max-file-size-kb:512}")
    private long maxFileSizeKb;

    @Value("${launchpad.scan.include-test-names:true}")
    private boolean includeTestNames;

    public ProjectContext scan(String rootPath, Consumer<String> progressCallback) throws IOException {
        var root = Path.of(rootPath).toAbsolutePath();
        var projectName = root.getFileName().toString();

        List<String> sourceFiles = new ArrayList<>();
        List<String> testClassNames = new ArrayList<>();
        Map<String, String> fileSnippets = new LinkedHashMap<>();

        progressCallback.accept("Scanning file tree...");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                var name = dir.getFileName().toString();
                return SKIP_DIRS.contains(name) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.size() > maxFileSizeKb * 1024) return FileVisitResult.CONTINUE;

                var relative = root.relativize(file).toString();
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

                if (KEY_FILE_NAMES.contains(fileName)) {
                    progressCallback.accept("Reading " + fileName + "...");
                    fileSnippets.put(relative, readFirstLines(file, 60));
                }

                return FileVisitResult.CONTINUE;
            }
        });

        progressCallback.accept("Detecting project stack...");
        var stack = detectStack(fileSnippets.keySet(), root);
        var dependencies = extractDependencies(fileSnippets);
        var entryPoints = detectEntryPoints(sourceFiles);

        return new ProjectContext(
            projectName, rootPath, stack,
            sourceFiles, testClassNames,
            entryPoints, dependencies, fileSnippets
        );
    }

    private boolean isSourceFile(String name) {
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".py")
            || name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".js")
            || name.endsWith(".jsx") || name.endsWith(".go") || name.endsWith(".rs")
            || name.endsWith(".cs") || name.endsWith(".rb") || name.endsWith(".swift");
    }

    private String detectStack(Set<String> keyFiles, Path root) {
        var f = keyFiles.stream().map(Path::of).map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        if (f.contains("pom.xml")) return "Java / Maven";
        if (f.contains("build.gradle")) return "Java / Gradle";
        if (f.contains("package.json")) return "Node.js / JavaScript";
        if (f.contains("pyproject.toml")) return "Python";
        if (f.contains("Cargo.toml")) return "Rust";
        if (f.contains("go.mod")) return "Go";
        return "Unknown";
    }

    private List<String> extractDependencies(Map<String, String> snippets) {
        var deps = new ArrayList<String>();
        snippets.forEach((path, content) -> {
            var fileName = Path.of(path).getFileName().toString();
            if ("pom.xml".equals(fileName)) {
                content.lines()
                    .filter(l -> l.contains("<artifactId>"))
                    .map(l -> l.replaceAll(".*<artifactId>(.*)</artifactId>.*", "$1").trim())
                    .filter(l -> !l.isEmpty())
                    .forEach(deps::add);
            } else if ("package.json".equals(fileName)) {
                content.lines()
                    .filter(l -> l.contains("\"") && l.contains(":"))
                    .map(l -> l.replaceAll(".*\"(\\S+)\":\\s*\".*\".*", "$1").trim())
                    .filter(l -> !l.startsWith("{") && !l.isEmpty())
                    .forEach(deps::add);
            }
        });
        return deps.stream().distinct().limit(50).collect(Collectors.toList());
    }

    private Map<String, String> detectEntryPoints(List<String> sourceFiles) {
        var entryPoints = new LinkedHashMap<String, String>();
        for (var f : sourceFiles) {
            var name = Path.of(f).getFileName().toString();
            if (name.endsWith("Application.java") || name.endsWith("App.java") || name.equals("main.py")) {
                entryPoints.put("main", f);
            } else if (name.endsWith("Config.java") || name.endsWith("Configuration.java")) {
                entryPoints.putIfAbsent("config", f);
            }
        }
        return entryPoints;
    }

    private String readFirstLines(Path file, int maxLines) throws IOException {
        try (var lines = Files.lines(file)) {
            return lines.limit(maxLines).collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "[binary or unreadable]";
        }
    }
}
