package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

public class GitTools {
    private final Path workspace;
    private final WorkspaceMutationGate mutationGate;

    public GitTools(Path workspace) {
        this(workspace, LocalWorkspaceMutationGate.shared());
    }

    public GitTools(Path workspace, WorkspaceMutationGate mutationGate) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.mutationGate = mutationGate;
    }

    @Tool(
        name = "git_status",
        description = "Show repository status",
        readOnly = true,
        concurrencySafe = true
    )
    public String status() throws Exception {
        try (Repository repository = repo();
             Git git = new Git(repository)) {
            var status = git.status().call();
            StringBuilder result = new StringBuilder(
                "Branch: " + repository.getBranch() + "\n"
            );
            if (!status.getModified().isEmpty()) {
                result.append("Modified: ")
                    .append(status.getModified()).append('\n');
            }
            if (!status.getAdded().isEmpty()) {
                result.append("Staged: ")
                    .append(status.getAdded()).append('\n');
            }
            if (!status.getUntracked().isEmpty()) {
                result.append("Untracked: ")
                    .append(status.getUntracked()).append('\n');
            }
            if (status.isClean()) {
                result.append("(clean)");
            }
            return result.toString();
        }
    }

    @Tool(
        name = "git_diff",
        description = "Show unstaged or staged changes",
        readOnly = true,
        concurrencySafe = true
    )
    public String diff(
        @ToolParam(
            name = "staged",
            description = "Show staged changes",
            required = false
        ) Boolean staged
    ) throws Exception {
        try (Repository repository = repo();
             Git git = new Git(repository)) {
            ByteArrayOutputStream output =
                new ByteArrayOutputStream();
            git.diff()
                .setCached(Boolean.TRUE.equals(staged))
                .setOutputStream(output)
                .call();
            String result = output.toString(
                StandardCharsets.UTF_8
            );
            return result.isEmpty() ? "(no changes)" : result;
        }
    }

    @Tool(name = "git_branch", description = "List or create branches")
    public String branch(
        @ToolParam(
            name = "name",
            description = "Branch name to create",
            required = false
        ) String name
    ) throws Exception {
        if (name != null && !name.isBlank()) {
            try (var ignored = mutationGate.acquire(workspace)) {
                return createBranch(name);
            }
        }
        try (Repository repository = repo();
             Git git = new Git(repository)) {
            StringBuilder result = new StringBuilder();
            String current = repository.getBranch();
            git.branchList().call().forEach(ref -> {
                String branch = ref.getName()
                    .replace("refs/heads/", "");
                result.append(branch.equals(current) ? "* " : "  ")
                    .append(branch).append('\n');
            });
            return result.toString();
        }
    }

    private String createBranch(String name) throws Exception {
        try (Repository repository = repo();
             Git git = new Git(repository)) {
            git.checkout().setCreateBranch(true)
                .setName(name).call();
            return "Created and switched to: " + name;
        }
    }

    @Tool(name = "git_commit", description = "Commit staged changes")
    public String commit(
        @ToolParam(name = "message", description = "Commit message")
        String message
    ) throws Exception {
        try (var ignored = mutationGate.acquire(workspace);
             Repository repository = repo();
             Git git = new Git(repository)) {
            var revision = git.commit().setMessage(message).call();
            return "Committed: " + revision.getName();
        }
    }

    @Tool(name = "git_add", description = "Stage matching files")
    public String add(
        @ToolParam(
            name = "filePattern",
            description = "Repository-relative file pattern"
        ) String filePattern
    ) throws Exception {
        try (var ignored = mutationGate.acquire(workspace);
             Repository repository = repo();
             Git git = new Git(repository)) {
            git.add().addFilepattern(filePattern).call();
            return "Staged: " + filePattern;
        }
    }

    @Tool(
        name = "git_log",
        description = "Show recent commits",
        readOnly = true,
        concurrencySafe = true
    )
    public String log(
        @ToolParam(
            name = "count",
            description = "Maximum commits",
            required = false
        ) Integer count
    ) throws Exception {
        int limit = count == null ? 5
            : Math.max(1, Math.min(count, 20));
        try (Repository repository = repo();
             Git git = new Git(repository)) {
            StringBuilder result = new StringBuilder();
            git.log().setMaxCount(limit).call().forEach(commit ->
                result.append(String.format(
                    "%s %s%n    %s%n",
                    commit.getName().substring(0, 7),
                    commit.getAuthorIdent().getName(),
                    commit.getFullMessage().trim()
                ))
            );
            return result.toString();
        }
    }

    private Repository repo() throws IOException {
        return new FileRepositoryBuilder()
            .findGitDir(workspace.toFile())
            .build();
    }
}
