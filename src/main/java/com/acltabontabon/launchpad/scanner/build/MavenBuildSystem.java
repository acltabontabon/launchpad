package com.acltabontabon.launchpad.scanner.build;

import com.acltabontabon.launchpad.scanner.Dependency;
import com.acltabontabon.launchpad.springboot.maven.DependencyExtractor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Maven {@link BuildSystem}. Recognises a {@code pom.xml} at the root and
 * delegates dependency parsing to the existing {@link DependencyExtractor}
 * (the same structured Maven entry point the support gate uses), so reuse is
 * complete and the two readings of the pom never diverge.
 */
@Component
public class MavenBuildSystem implements BuildSystem {

    private static final String POM = "pom.xml";

    private final DependencyExtractor dependencyExtractor;

    public MavenBuildSystem(DependencyExtractor dependencyExtractor) {
        this.dependencyExtractor = dependencyExtractor;
    }

    public MavenBuildSystem() {
        this(new DependencyExtractor());
    }

    @Override
    public String name() {
        return "Maven";
    }

    @Override
    public boolean matches(Path root, Map<String, String> keyFiles) {
        return keyFiles.containsKey(POM) || Files.isRegularFile(root.resolve(POM));
    }

    @Override
    public List<String> buildCommands() {
        return List.of("./mvnw clean package", "./mvnw test");
    }

    @Override
    public List<Dependency> dependencies(Map<String, String> keyFiles) {
        return dependencyExtractor.extract(keyFiles);
    }
}
