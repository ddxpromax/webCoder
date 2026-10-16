package io.github.ddxpromax.webcoder.system;

import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majo's legacy health endpoint for reverse proxies and desktop clients. */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class HealthController {
    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "ok"); }
}
