package io.github.ddxpromax.webcoder.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Majo browser OAuth flow for the OpenRouter provider. */
@RestController
@RequestMapping("/api/providers")
@CrossOrigin(origins = "*")
public class ProviderOAuthController {
    private final ProviderRepository providers;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public ProviderOAuthController(ProviderRepository providers, ObjectMapper mapper) {
        this(providers, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    ProviderOAuthController(ProviderRepository providers, ObjectMapper mapper, HttpClient http) {
        this.providers = providers; this.mapper = mapper; this.http = http;
    }

    @PostMapping("/{providerId}/oauth/start")
    public ResponseEntity<?> start(@PathVariable String providerId, HttpServletRequest request) {
        if (!"openrouter".equals(providerId)) return ResponseEntity.notFound().build();
        String state = UUID.randomUUID().toString();
        String callback = request.getScheme() + "://" + request.getHeader("Host")
            + "/api/providers/" + providerId + "/oauth/callback";
        sessions.put(state, new Session(providerId));
        return ResponseEntity.ok(Map.of("authorize_url", "https://openrouter.ai/auth?callback_url="
            + URLEncoder.encode(callback, StandardCharsets.UTF_8), "state", state,
            "flow_type", "browser_redirect"));
    }

    @GetMapping("/{providerId}/oauth/callback")
    public ResponseEntity<String> callback(@PathVariable String providerId, @RequestParam String code,
        @RequestParam(defaultValue = "") String state) {
        Session session = sessions.get(state);
        if (session == null || !providerId.equals(session.providerId)) return html(400, error("Session expired or invalid."));
        try {
            providers.updateOAuthConnection(providerId, exchange(code));
            session.status = "completed";
            return html(200, success(providerId));
        } catch (Exception exception) {
            session.status = "failed"; session.error = exception.getMessage();
            return html(500, error("Authorization failed. Please retry."));
        }
    }

    @GetMapping("/{providerId}/oauth/status")
    public Map<String, Object> status(@PathVariable String providerId, @RequestParam String state) {
        Session session = sessions.get(state);
        if (session == null) return Map.of("status", "failed", "error", "Session expired");
        if (!providerId.equals(session.providerId)) return Map.of("status", "failed", "error", "Provider mismatch");
        return Map.of("status", session.status, "error", session.error == null ? "" : session.error);
    }

    private String exchange(String code) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/auth/keys"))
            .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("code", code)))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("OpenRouter key exchange returned HTTP " + response.statusCode());
        JsonNode body = mapper.readTree(response.body()); String key = body.path("key").asText("");
        if (key.isBlank()) throw new IllegalStateException("No key in OpenRouter response");
        return key;
    }

    private ResponseEntity<String> html(int status, String body) { return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(body); }
    private String success(String provider) { return "<!DOCTYPE html><html><head><title>Authorization Successful</title></head><body style=\"font-family:system-ui;text-align:center;padding:60px\"><h2>Connected!</h2><p>You can close this window.</p><script>if(window.opener){window.opener.postMessage({type:'oauth_complete',provider:'" + provider + "'},window.location.origin)}setTimeout(function(){window.close()},1500)</script></body></html>"; }
    private String error(String message) { return "<!DOCTYPE html><html><head><title>Authorization Failed</title></head><body style=\"font-family:system-ui;text-align:center;padding:60px\"><h2>Authorization Failed</h2><p>" + message + "</p><p><a href=\"javascript:window.close()\">Close this window</a></p></body></html>"; }
    private static final class Session { private final String providerId; private volatile String status = "pending"; private volatile String error; private Session(String providerId) { this.providerId = providerId; } }
}
