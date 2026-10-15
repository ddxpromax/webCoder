package io.github.ddxpromax.webcoder.settings;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/envs")
public class EnvsController {
    private final EnvVarRepository repository;

    public EnvsController(EnvVarRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<EnvVarRepository.EnvVar> list() {
        return repository.findAll();
    }

    @PutMapping
    public ResponseEntity<?> replace(
        @RequestBody Map<String, String> values
    ) {
        try {
            return ResponseEntity.ok(repository.replaceAll(values));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(
                Map.of("detail", exception.getMessage())
            );
        }
    }

    @DeleteMapping("/{key}")
    public List<EnvVarRepository.EnvVar> delete(
        @PathVariable String key
    ) {
        return repository.delete(key);
    }
}
