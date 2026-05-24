package com.acltabontabon.launchpad.springboot.detectors;

import com.acltabontabon.launchpad.scanner.ProjectSupportSignal;
import com.acltabontabon.launchpad.springboot.maven.MavenModel;
import com.acltabontabon.launchpad.springboot.maven.MavenModelParser;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Recognises Spring Boot Java + Maven projects by parsing {@code pom.xml}
 * through {@link MavenModelParser} and looking for structured Spring Boot
 * signals: the {@code spring-boot-starter-parent}, any dependency under
 * {@code org.springframework.boot}, or the {@code spring-boot-maven-plugin}.
 * <p>
 * Deliberately strict - false negatives are preferred over fuzzy text matches
 * on the raw pom. A project that declares Spring Boot only through a custom
 * BOM with no other signals will be rejected; that is acceptable today and
 * fixable by adding a more specific signal later.
 */
@Component
public class SpringBootMavenSupportSignal implements ProjectSupportSignal {

    private static final String FRAMEWORK_LABEL = "Spring Boot Java + Maven";
    private static final String SPRING_BOOT_GROUP = "org.springframework.boot";
    private static final String SPRING_BOOT_PARENT_ARTIFACT = "spring-boot-starter-parent";
    private static final String SPRING_BOOT_MAVEN_PLUGIN = "spring-boot-maven-plugin";

    private final MavenModelParser mavenModelParser;

    public SpringBootMavenSupportSignal(MavenModelParser mavenModelParser) {
        this.mavenModelParser = mavenModelParser;
    }

    public SpringBootMavenSupportSignal() {
        this(new MavenModelParser());
    }

    @Override
    public Optional<Match> evaluate(Path projectRoot) {
        if (projectRoot == null) return Optional.empty();
        return mavenModelParser.parseAtRoot(projectRoot)
            .filter(SpringBootMavenSupportSignal::hasSpringSignal)
            .map(model -> new Match(FRAMEWORK_LABEL));
    }

    private static boolean hasSpringSignal(MavenModel model) {
        if (SPRING_BOOT_PARENT_ARTIFACT.equals(model.parentArtifactId())
            && SPRING_BOOT_GROUP.equals(model.parentGroupId())) {
            return true;
        }
        boolean springDependency = model.dependencies().stream()
            .map(d -> d.name() == null ? "" : d.name())
            .anyMatch(name -> name.startsWith(SPRING_BOOT_GROUP + ":"));
        if (springDependency) return true;
        return model.plugins().stream()
            .anyMatch(p -> SPRING_BOOT_MAVEN_PLUGIN.equals(p.artifactId()));
    }
}
