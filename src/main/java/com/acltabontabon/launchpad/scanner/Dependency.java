package com.acltabontabon.launchpad.scanner;

/**
 * One dependency declaration. `scope` is "runtime" / "dev" / "test" /
 * "provided" / null when not derivable from the source format.
 */
public record Dependency(String name, String version, String scope) {

    public String display() {
        var base = version == null || version.isBlank() ? name : name + "@" + version;
        return scope == null || scope.isBlank() ? base : base + " (" + scope + ")";
    }
}
