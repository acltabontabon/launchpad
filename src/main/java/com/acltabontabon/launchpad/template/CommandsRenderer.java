package com.acltabontabon.launchpad.template;

import com.acltabontabon.launchpad.scanner.StackProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Emits the `## Commands` section for the generated primary context file.
 * Driven by detected build tool (and framework where it changes the run
 * command). Each command is preceded by a one-line `# comment` so the block
 * reads the way a hand-written `/init` would write it.
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
        switch (tool) {
            case "maven" -> {
                if (stack.isSpring()) {
                    out.add(new Command("Run on JVM (fastest iteration loop)", "./mvnw spring-boot:run"));
                } else {
                    out.add(new Command("Run the main class", "./mvnw exec:java"));
                }
                out.add(new Command("Run tests", "./mvnw test"));
                out.add(new Command("Run a single test class", "./mvnw test -Dtest=ClassName"));
                out.add(new Command("Build a JAR", "./mvnw package"));
                if (stack.springProfile() != null && stack.springProfile().nativeImage()) {
                    out.add(new Command("Build a GraalVM native image (~2-3 min)", "./mvnw -Pnative native:compile"));
                }
            }
            case "gradle" -> {
                if (stack.isSpring()) {
                    out.add(new Command("Run on JVM (fastest iteration loop)", "./gradlew bootRun"));
                } else {
                    out.add(new Command("Run the main class", "./gradlew run"));
                }
                out.add(new Command("Run tests", "./gradlew test"));
                out.add(new Command("Run a single test class", "./gradlew test --tests ClassName"));
                out.add(new Command("Build everything", "./gradlew build"));
            }
            case "npm" -> {
                out.add(new Command("Install dependencies", "npm install"));
                out.add(new Command("Start the dev server", "npm run dev"));
                out.add(new Command("Run tests", "npm test"));
                out.add(new Command("Build for production", "npm run build"));
            }
            case "pnpm" -> {
                out.add(new Command("Install dependencies", "pnpm install"));
                out.add(new Command("Start the dev server", "pnpm dev"));
                out.add(new Command("Run tests", "pnpm test"));
                out.add(new Command("Build for production", "pnpm build"));
            }
            case "yarn" -> {
                out.add(new Command("Install dependencies", "yarn install"));
                out.add(new Command("Start the dev server", "yarn dev"));
                out.add(new Command("Run tests", "yarn test"));
                out.add(new Command("Build for production", "yarn build"));
            }
            case "pip" -> {
                out.add(new Command("Install dependencies", "pip install -r requirements.txt"));
                out.add(new Command("Run tests", "python -m pytest"));
            }
            case "poetry" -> {
                out.add(new Command("Install dependencies", "poetry install"));
                out.add(new Command("Run tests", "poetry run pytest"));
            }
            case "uv" -> {
                out.add(new Command("Sync the environment", "uv sync"));
                out.add(new Command("Run tests", "uv run pytest"));
            }
            case "go" -> {
                out.add(new Command("Run the main package", "go run ."));
                out.add(new Command("Run tests", "go test ./..."));
                out.add(new Command("Build everything", "go build ./..."));
            }
            case "cargo" -> {
                out.add(new Command("Run the binary", "cargo run"));
                out.add(new Command("Run tests", "cargo test"));
                out.add(new Command("Release build", "cargo build --release"));
            }
            case "dotnet" -> {
                out.add(new Command("Run the project", "dotnet run"));
                out.add(new Command("Run tests", "dotnet test"));
                out.add(new Command("Build everything", "dotnet build"));
            }
            case "make" -> {
                out.add(new Command("Default target", "make"));
                out.add(new Command("Run tests", "make test"));
            }
            case "terraform" -> {
                out.add(new Command("Initialize providers and modules", "terraform init"));
                out.add(new Command("Show the planned changes", "terraform plan"));
                out.add(new Command("Apply changes", "terraform apply"));
            }
            default -> {
                // unknown build tool: caller will see the placeholder line
            }
        }
        return out;
    }
}
