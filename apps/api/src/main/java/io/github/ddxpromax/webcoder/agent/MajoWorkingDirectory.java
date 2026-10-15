package io.github.ddxpromax.webcoder.agent;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MajoWorkingDirectory {
    private final Path root;

    public MajoWorkingDirectory(
        @Value("${majo.working-dir:}") String configuredDir
    ) {
        String environmentDir = System.getenv("MAJO_WORKING_DIR");
        Path selected;
        if (configuredDir != null && !configuredDir.isBlank()) {
            selected = Path.of(configuredDir);
        } else if (environmentDir != null && !environmentDir.isBlank()) {
            selected = Path.of(environmentDir);
        } else {
            selected = Path.of(System.getProperty("user.dir"))
                .resolve("data")
                .resolve("majo");
        }
        this.root = selected.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path resolve(String name) {
        return root.resolve(name);
    }
}
