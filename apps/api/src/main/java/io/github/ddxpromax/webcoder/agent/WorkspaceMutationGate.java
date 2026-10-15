package io.github.ddxpromax.webcoder.agent;

import java.nio.file.Files;
import java.nio.file.Path;

/** Exclusive write lease for a repository, shared by mutating tools. */
public interface WorkspaceMutationGate {
    Lease acquire(Path workspace);

    interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    static Path repositoryRoot(Path workspace) {
        Path normalized = workspace.toAbsolutePath().normalize();
        try {
            if (Files.exists(normalized)) {
                normalized = normalized.toRealPath();
            }
        } catch (java.io.IOException ignored) {
            // The normalized path still gives a stable local key.
        }
        for (Path candidate = normalized; candidate != null;
             candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve(".git"))) {
                return candidate;
            }
        }
        return normalized;
    }
}
