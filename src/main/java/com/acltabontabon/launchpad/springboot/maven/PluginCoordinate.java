package com.acltabontabon.launchpad.springboot.maven;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A Maven plugin coordinate parsed from {@code <build><plugins>} or
 * {@code <build><pluginManagement><plugins>}. Used by support detection to
 * recognize the Spring Boot Maven plugin without grepping raw pom text.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PluginCoordinate(String groupId, String artifactId, String version) {

    public PluginCoordinate {
        if (groupId == null) groupId = "";
        if (artifactId == null) artifactId = "";
        if (version == null) version = "";
    }
}
