package io.github.ddxpromax.webcoder.auth;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {
    private final AuthStore auth;

    public AuthController(AuthStore auth) {
        this.auth = auth;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
            "enabled", auth.isEnabled(),
            "has_users", auth.hasUser()
        );
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(
        @RequestBody Map<String, Object> body
    ) {
        if (!auth.isEnabled()) {
            return emptyLogin();
        }
        if (auth.hasUser()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("detail", "User already registered"));
        }
        String username = text(body.get("username")).trim();
        String password = text(body.get("password"));
        if (username.isBlank() || password.isBlank()) {
            return ResponseEntity.badRequest().body(
                Map.of("detail", "username and password are required")
            );
        }
        try {
            String token = auth.register(username, password);
            return ResponseEntity.ok(Map.of(
                "token", token, "username", username
            ));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("detail", "User already registered"));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
        @RequestBody Map<String, Object> body
    ) {
        if (!auth.isEnabled()) {
            return emptyLogin();
        }
        String username = text(body.get("username")).trim();
        String token = auth.login(username, text(body.get("password")));
        if (token == null) {
            return unauthorized("Invalid username or password");
        }
        return ResponseEntity.ok(Map.of(
            "token", token, "username", username
        ));
    }

    @GetMapping("/verify")
    public Map<String, Boolean> verify(
        @RequestHeader(
            value = "Authorization",
            required = false
        ) String authorization
    ) {
        return Map.of(
            "valid",
            !auth.isEnabled()
                || auth.validToken(bearerToken(authorization))
        );
    }

    @PostMapping("/revoke-token")
    public Map<String, String> revokeToken(
        @RequestBody Map<String, Object> body
    ) {
        auth.revokeToken(text(body.get("token")));
        return Map.of("status", "ok");
    }

    @PostMapping("/revoke-all-tokens")
    public Map<String, String> revokeAllTokens() {
        auth.revokeAllTokens();
        return Map.of("status", "ok");
    }

    @PostMapping("/update-profile")
    public ResponseEntity<?> updateProfile(
        @RequestBody Map<String, Object> body,
        @RequestHeader(
            value = "Authorization",
            required = false
        ) String authorization
    ) {
        if (!auth.isEnabled()) {
            return emptyLogin();
        }
        String presentedToken = bearerToken(authorization);
        if (!auth.validToken(presentedToken)) {
            return unauthorized("Not authenticated");
        }
        String newUsername = body.get("new_username") == null
            ? null : text(body.get("new_username"));
        String newPassword = body.get("new_password") == null
            ? null : text(body.get("new_password"));
        String token = auth.updateProfile(
            text(body.get("current_password")),
            newUsername,
            newPassword
        );
        if (token == null) {
            return unauthorized("Current password is incorrect");
        }
        return ResponseEntity.ok(Map.of(
            "token", token, "username", auth.username()
        ));
    }

    private static ResponseEntity<?> emptyLogin() {
        return ResponseEntity.ok(Map.of(
            "token", "", "username", ""
        ));
    }

    private static ResponseEntity<?> unauthorized(String detail) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("detail", detail));
    }

    static String bearerToken(String authorization) {
        if (authorization == null
            || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
