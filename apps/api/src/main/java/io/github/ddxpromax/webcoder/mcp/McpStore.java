package io.github.ddxpromax.webcoder.mcp;

import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import io.github.ddxpromax.webcoder.agent.WorkspaceMutationGate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Majo-compatible MCP card and credential files in the working directory. */
@Component
public class McpStore {
    private final Path root;
    private final JsonFileStore json;
    private final WorkspaceMutationGate mutationGate;

    public McpStore(
        MajoWorkingDirectory workingDirectory,
        JsonFileStore json,
        WorkspaceMutationGate mutationGate
    ) {
        this.root = workingDirectory.root();
        this.json = json;
        this.mutationGate = mutationGate;
    }

    public Map<String, Object> card(String key) {
        Path path = cardPath(key);
        return Files.isRegularFile(path)
            ? json.read(path, Map.of()) : null;
    }

    public List<String> keys() {
        Path directory = root.resolve("mcp/cards");
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".json"))
                .map(name -> name.substring(0,
                    name.length() - 5))
                .filter(McpStore::validKey)
                .forEach(keys::add);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Cannot list MCP cards", exception
            );
        }
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    public Map<String, String> secrets(String key) {
        Path path = credentialPath(key);
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        harden(path);
        return McpValues.strings(
            json.read(path, Map.of()).get("secrets")
        );
    }

    public void save(
        String key,
        Map<String, Object> card,
        Map<String, String> secrets
    ) {
        try (var lease = mutationGate.acquire(root)) {
            if (secrets.isEmpty()) {
                Files.deleteIfExists(credentialPath(key));
            } else {
                json.writeAtomic(credentialPath(key), Map.of(
                    "ref", "mcp/" + key,
                    "kind", "static",
                    "public", Map.of(),
                    "secrets", secrets
                ));
                harden(credentialPath(key));
            }
            card.put("schema_version", "mcp-card.v1");
            json.writeAtomic(cardPath(key), card);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Cannot save MCP client", exception
            );
        }
    }

    public void saveCard(String key, Map<String, Object> card) {
        try (var lease = mutationGate.acquire(root)) {
            card.put("schema_version", "mcp-card.v1");
            json.writeAtomic(cardPath(key), card);
        }
    }

    public void delete(String key) {
        try (var lease = mutationGate.acquire(root)) {
            Files.deleteIfExists(cardPath(key));
            Files.deleteIfExists(credentialPath(key));
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Cannot delete MCP client", exception
            );
        }
    }

    private Path cardPath(String key) {
        requireKey(key);
        return root.resolve("mcp/cards").resolve(key + ".json");
    }

    private Path credentialPath(String key) {
        requireKey(key);
        return root.resolve("mcp/credentials")
            .resolve("mcp_" + key + ".json");
    }

    static void requireKey(String key) {
        if (!validKey(key)) {
            throw new IllegalArgumentException(
                "MCP client key must contain only letters, numbers, '_' or '-'."
            );
        }
    }

    private static boolean validKey(String key) {
        return key != null && key.matches(
            "[A-Za-z0-9][A-Za-z0-9_-]{0,63}"
        );
    }

    private static void harden(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            ));
        } catch (IOException | UnsupportedOperationException exception) {
            throw new IllegalStateException(
                "Cannot secure MCP credential file", exception
            );
        }
    }
}
