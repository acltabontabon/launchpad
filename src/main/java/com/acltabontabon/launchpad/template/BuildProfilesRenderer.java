package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.scanner.MavenProfile;
import java.util.List;

/**
 * Renders detected Maven `<profile>` entries as a small markdown table.
 * Returns an empty string when no profiles were detected so the caller can
 * skip the whole `## Build profiles` section.
 */
public final class BuildProfilesRenderer {

    private BuildProfilesRenderer() {}

    public static String render(List<MavenProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) return "";
        var sb = new StringBuilder();
        sb.append("| Profile | Activation | Key flags |\n");
        sb.append("|---------|------------|-----------|\n");
        for (var p : profiles) {
            sb.append("| `").append(p.id()).append("` | ");
            sb.append(p.activation().isEmpty() ? "_(opt-in via `-P`)_" : escapePipes(p.activation()));
            sb.append(" | ");
            sb.append(p.keyFlags().isEmpty()
                ? "_(none captured)_"
                : "`" + String.join("` `", p.keyFlags()) + "`");
            sb.append(" |\n");
        }
        return sb.toString();
    }

    private static String escapePipes(String s) {
        return s.replace("|", "\\|");
    }
}
