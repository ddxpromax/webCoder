package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Copies a workspace file to Majo's shared upload/preview directory. */
public class SendFileToUserTool {
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private final Path workspace;
    private final MajoWorkingDirectory workingDirectory;

    public SendFileToUserTool(
        Path workspace,
        MajoWorkingDirectory workingDirectory
    ) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.workingDirectory = workingDirectory;
    }

    @Tool(
        name = "send_file_to_user",
        description = "Copy a workspace file for user preview or download"
    )
    public String sendFileToUser(
        @ToolParam(name = "path", description = "Workspace file path")
        String path
    ) {
        if (path == null || path.isBlank()) {
            return "path must not be empty";
        }
        try {
            Path source = workspace.resolve(path).normalize();
            if (!source.startsWith(workspace)
                || !Files.isRegularFile(source)
                || Files.isSymbolicLink(source)
                || !source.toRealPath().startsWith(
                    workspace.toRealPath())) {
                return "File does not exist inside the workspace: "
                    + path;
            }
            long size = Files.size(source);
            if (size > MAX_BYTES) {
                return "File exceeds the 100 MB limit ("
                    + size + " bytes).";
            }
            Path directory = workingDirectory.resolve("uploads")
                .toAbsolutePath().normalize();
            Files.createDirectories(directory);
            String name = source.getFileName().toString();
            String savedName = UUID.randomUUID().toString()
                .substring(0, 8) + "-" + name;
            Files.copy(source, directory.resolve(savedName),
                StandardCopyOption.REPLACE_EXISTING);
            return "File sent to user: " + name + " ("
                + size + " bytes)\nPreview: /api/files/preview/"
                + savedName;
        } catch (Exception exception) {
            return "File transfer failed: "
                + exception.getMessage();
        }
    }
}
