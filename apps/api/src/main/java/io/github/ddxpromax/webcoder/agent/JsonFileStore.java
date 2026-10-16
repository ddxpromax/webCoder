package io.github.ddxpromax.webcoder.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JsonFileStore {
    private final ObjectMapper mapper;

    public JsonFileStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> read(
        Path file,
        Map<String, Object> fallback
    ) {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>(fallback);
        }
        try {
            Map<String, Object> data = mapper.readValue(
                file.toFile(), new TypeReference<>() {}
            );
            return data == null
                ? new LinkedHashMap<>(fallback)
                : new LinkedHashMap<>(data);
        } catch (IOException exception) {
            return new LinkedHashMap<>(fallback);
        }
    }

    public void writeAtomic(Path file, Map<String, Object> data) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(
                file.getParent(), file.getFileName().toString(), ".tmp"
            );
            mapper.writeValue(temporary.toFile(), data);
            try {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to write JSON file: " + file,
                exception
            );
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A completed atomic replacement remains intact.
                }
            }
        }
    }
}
