package com.acltabontabon.launchpad.eval;

import java.io.File;
import java.nio.file.Path;

final class FixtureSupport {

    private FixtureSupport() {}

    /** Resolve to an absolute filesystem path under src/test/resources/fixtures/. */
    static Path fixturePath(String classpathDir) {
        // src/test/resources copies onto the classpath, but we want the source path so
        // ProjectScanner can walk it like a real project. JUnit runs from the module root.
        var p = new File("src/test/resources/fixtures/" + classpathDir).getAbsoluteFile().toPath();
        if (!p.toFile().isDirectory()) {
            throw new IllegalStateException("fixture missing: " + p);
        }
        return p;
    }
}
