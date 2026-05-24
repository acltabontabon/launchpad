package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.scanner.StackProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Emits the `## Commands` section for the generated primary context file.
 * Driven by detected build tool. Each command is preceded by a one-line
 * `# comment` so the block reads the way a hand-written `/init` would write
 * it.
 */
final class CommandsRenderer {

    private CommandsRenderer() {}

    /** A command line with the comment that introduces it. */
    record Command(String comment, String command) {}

    /**
     * Whether the renderer would produce any actionable commands for this
     * stack. False for unknown build tools - callers should skip both the
     * `## Commands` block and any follow-up workflow narrative in that case
     * to avoid emitting empty / generic filler.
     */
    static boolean hasCommands(StackProfile stack) {
        return !commandsFor(stack).isEmpty();
    }

    static String render(StackProfile stack) {
        var commands = commandsFor(stack);
        var sb = new StringBuilder();
        sb.append("## Commands\n\n");
        if (commands.isEmpty()) {
            sb.append("_Build tool not detected - add commands here._\n\n");
            return sb.toString();
        }
        sb.append("```bash\n");
        for (int i = 0; i < commands.size(); i++) {
            var c = commands.get(i);
            if (i > 0) sb.append("\n");
            sb.append("# ").append(c.comment()).append("\n");
            sb.append(c.command()).append("\n");
        }
        sb.append("```\n\n");
        return sb.toString();
    }

    private static List<Command> commandsFor(StackProfile stack) {
        if (stack == null || stack.buildTool() == null) return List.of();
        var tool = stack.buildTool().toLowerCase(Locale.ROOT);
        var out = new ArrayList<Command>();
        if ("maven".equals(tool)) {
            out.add(new Command("Run on JVM (fastest iteration loop)", "./mvnw spring-boot:run"));
            out.add(new Command("Run tests", "./mvnw test"));
            out.add(new Command("Run a single test class", "./mvnw test -Dtest=ClassName"));
            out.add(new Command("Build a JAR", "./mvnw package"));
            if (stack.springProfile() != null && stack.springProfile().nativeImage()) {
                out.add(new Command("Build a GraalVM native image (~2-3 min)", "./mvnw -Pnative native:compile"));
            }
        }
        return out;
    }
}
