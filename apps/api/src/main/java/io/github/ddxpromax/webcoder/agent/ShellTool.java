package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ShellTool {
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_CHARS = 50_000;

    private final Path workspace;

    public ShellTool(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Tool(
        name = "execute_command",
        description = "Run a shell command inside the workspace"
    )
    public String executeCommand(
        @ToolParam(name = "command", description = "Shell command")
        String command,
        @ToolParam(
            name = "timeoutSeconds",
            description = "Timeout in seconds",
            required = false
        ) Integer timeoutSeconds
    ) {
        if (command == null || command.isBlank()) {
            return "[error] command is required";
        }
        int timeout = timeoutSeconds == null
            ? DEFAULT_TIMEOUT_SECONDS
            : Math.max(1, Math.min(timeoutSeconds,
                MAX_TIMEOUT_SECONDS));
        boolean windows = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");
        String effective = windows ? command
            : ShellNormalization.normalizePosixLineContinuations(
                command
            );
        if (!Files.isDirectory(workspace)) {
            return "[error] workspace is not a directory";
        }
        Process process = null;
        try {
            ProcessBuilder builder = windows
                ? new ProcessBuilder("cmd.exe", "/c", effective)
                : new ProcessBuilder("sh", "-c", effective);
            builder.directory(workspace.toFile());
            builder.redirectErrorStream(true);
            process = builder.start();
            Process running = process;
            StringBuilder output = new StringBuilder();
            AtomicBoolean capped = new AtomicBoolean(false);
            Thread pump = Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(running.getInputStream(),
                        StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            if (output.length() + line.length() + 1
                                > MAX_OUTPUT_CHARS) {
                                capped.set(true);
                                destroyTree(running);
                                return;
                            }
                            output.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                    // A destroyed process may close its output stream.
                }
            });
            boolean finished = process.waitFor(
                timeout, TimeUnit.SECONDS
            );
            if (!finished) {
                destroyTree(process);
                pump.join(1000);
                return "[timeout] Command exceeded " + timeout
                    + " seconds and was stopped. Partial output:\n"
                    + snapshot(output);
            }
            pump.join(1000);
            if (capped.get()) {
                return "[output limit] Output exceeded "
                    + MAX_OUTPUT_CHARS + " characters:\n"
                    + snapshot(output);
            }
            return (process.exitValue() == 0
                ? "[success] "
                : "[warning] exit=" + process.exitValue() + " ")
                + snapshot(output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                destroyTree(process);
            }
            return "[interrupted] Command stopped";
        } catch (Exception exception) {
            if (process != null) {
                destroyTree(process);
            }
            return "[error] " + exception.getMessage();
        }
    }

    private static String snapshot(StringBuilder output) {
        synchronized (output) {
            return output.toString();
        }
    }

    private static void destroyTree(Process process) {
        try {
            process.descendants().forEach(
                ProcessHandle::destroyForcibly
            );
        } catch (Exception ignored) {
            // The direct process is still destroyed below.
        }
        process.destroyForcibly();
    }
}
