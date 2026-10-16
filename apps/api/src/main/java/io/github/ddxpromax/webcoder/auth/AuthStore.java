package io.github.ddxpromax.webcoder.auth;

import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AuthStore {
    private static final long TOKEN_EXPIRY_SECONDS = 7 * 24 * 3600L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path authFile;
    private final JsonFileStore files;
    private final boolean enabled;

    public AuthStore(
        MajoWorkingDirectory workingDirectory,
        JsonFileStore files,
        @Value("${majo.auth.enabled:false}") boolean configuredEnabled
    ) {
        this.authFile = workingDirectory.resolve("auth.json");
        this.files = files;
        String flag = System.getenv("MAJO_AUTH_ENABLED");
        this.enabled = flag == null || flag.isBlank()
            ? configuredEnabled : isTruthy(flag);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public synchronized boolean hasUser() {
        return !text(load().get("username")).isBlank();
    }

    public synchronized String username() {
        return text(load().get("username"));
    }

    public synchronized String register(
        String username,
        String password
    ) {
        if (hasUser()) {
            throw new IllegalStateException("User already registered");
        }
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("username", username);
        updatePassword(auth, password);
        String token = issueToken(auth);
        files.writeAtomic(authFile, auth);
        return token;
    }

    public synchronized String login(
        String username,
        String password
    ) {
        Map<String, Object> auth = load();
        if (!matchesUsername(auth, username)
            || !matchesPassword(auth, password)) {
            return null;
        }
        String token = issueToken(auth);
        files.writeAtomic(authFile, auth);
        return token;
    }

    public synchronized boolean validToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Object expiry = tokenMap(load()).get(token);
        return expiry instanceof Number number
            && number.longValue() > Instant.now().getEpochSecond();
    }

    public synchronized void revokeToken(String token) {
        Map<String, Object> auth = load();
        Map<String, Object> tokens = tokenMap(auth);
        if (tokens.remove(token) != null) {
            auth.put("tokens", tokens);
            files.writeAtomic(authFile, auth);
        }
    }

    public synchronized void revokeAllTokens() {
        Map<String, Object> auth = load();
        auth.put("tokens", new LinkedHashMap<>());
        files.writeAtomic(authFile, auth);
    }

    public synchronized String updateProfile(
        String currentPassword,
        String newUsername,
        String newPassword
    ) {
        Map<String, Object> auth = load();
        if (!matchesPassword(auth, currentPassword)) {
            return null;
        }
        if (newUsername != null && !newUsername.isBlank()) {
            auth.put("username", newUsername.trim());
        }
        if (newPassword != null && !newPassword.isBlank()) {
            updatePassword(auth, newPassword);
        }
        auth.put("tokens", new LinkedHashMap<>());
        String token = issueToken(auth);
        files.writeAtomic(authFile, auth);
        return token;
    }

    private Map<String, Object> load() {
        return files.read(authFile, Map.of());
    }

    private static String issueToken(Map<String, Object> auth) {
        String token = random(32);
        Map<String, Object> tokens = tokenMap(auth);
        long now = Instant.now().getEpochSecond();
        tokens.entrySet().removeIf(entry ->
            !(entry.getValue() instanceof Number number)
                || number.longValue() <= now
        );
        tokens.put(token, now + TOKEN_EXPIRY_SECONDS);
        auth.put("tokens", tokens);
        return token;
    }

    private static boolean matchesUsername(
        Map<String, Object> auth,
        String username
    ) {
        return MessageDigest.isEqual(
            text(auth.get("username")).getBytes(StandardCharsets.UTF_8),
            username.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static boolean matchesPassword(
        Map<String, Object> auth,
        String password
    ) {
        String storedSalt = text(auth.get("salt"));
        String storedHash = text(auth.get("password_hash"));
        if (storedSalt.isEmpty() || storedHash.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
            storedHash.getBytes(StandardCharsets.UTF_8),
            hashPassword(password, storedSalt)
                .getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void updatePassword(
        Map<String, Object> auth,
        String password
    ) {
        String salt = random(16);
        auth.put("salt", salt);
        auth.put("password_hash", hashPassword(password, salt));
    }

    private static String hashPassword(
        String password,
        String salt
    ) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(
                sha256.digest(
                    (salt + ":" + password).getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value);
    }

    private static boolean isTruthy(String value) {
        return "true".equalsIgnoreCase(value.trim())
            || "1".equals(value.trim())
            || "yes".equalsIgnoreCase(value.trim());
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tokenMap(
        Map<String, Object> auth
    ) {
        Object raw = auth.get("tokens");
        return raw instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
    }
}
