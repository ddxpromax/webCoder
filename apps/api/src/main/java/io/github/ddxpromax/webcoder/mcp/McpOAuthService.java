package io.github.ddxpromax.webcoder.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * OAuth 2.1 authorization-code flow for remote MCP servers.
 *
 * <p>This follows Majo's protected-resource and authorization-server metadata
 * discovery, RFC 7636 PKCE, and credential-card persistence model. OAuth
 * tokens are never sent to the browser or included in an MCP client response.</p>
 */
final class McpOAuthService {
    private static final String CALLBACK_PATH = "/api/mcp/oauth/callback";
    private static final long SESSION_TTL_NANOS = Duration.ofMinutes(10).toNanos();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final McpService mcp;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private record Session(
        String clientKey, String verifier, String clientId,
        String authorizationEndpoint, String tokenEndpoint,
        String redirectUri, String scope, long createdAtNanos
    ) {
        boolean expired() {
            return System.nanoTime() - createdAtNanos > SESSION_TTL_NANOS;
        }
    }

    McpOAuthService(McpService mcp) {
        this.mcp = mcp;
    }

    McpModels.OAuthStartResponse start(
        String clientKey, McpModels.OAuthStartRequest request,
        String redirectUri
    ) {
        purgeExpired();
        McpModels.ClientInfo client = mcp.get(clientKey);
        String endpointUrl = value(request == null ? null : request.url());
        if (endpointUrl.isBlank()) endpointUrl = client.url();
        if (!remote(client.transport()) || !httpUrl(endpointUrl)) {
            throw badRequest("OAuth MCP clients must use an HTTP or HTTPS endpoint.");
        }

        String authEndpoint = value(request == null ? null : request.authEndpoint());
        String tokenEndpoint = value(request == null ? null : request.tokenEndpoint());
        if (authEndpoint.isBlank() && tokenEndpoint.isBlank()) {
            String[] discovered = discoverEndpoints(endpointUrl);
            authEndpoint = discovered[0];
            tokenEndpoint = discovered[1];
        } else if (authEndpoint.isBlank() || tokenEndpoint.isBlank()
            || !httpUrl(authEndpoint) || !httpUrl(tokenEndpoint)) {
            throw badRequest("Both valid authorization and token endpoints are required.");
        }

        String clientId = value(request == null ? null : request.clientId());
        Map<String, Object> previous = mcp.store().credential(
            McpStore.mcpOauthCredentialRef(clientKey)
        );
        if (clientId.isBlank() && previous != null) {
            clientId = value(map(previous.get("public")).get("client_id"));
        }

        String verifier = verifier();
        String state = state();
        String scope = value(request == null ? null : request.scope());
        sessions.put(state, new Session(clientKey, verifier, clientId,
            authEndpoint, tokenEndpoint, redirectUri, scope, System.nanoTime()));

        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("response_type", "code");
        parameters.put("redirect_uri", redirectUri);
        parameters.put("state", state);
        parameters.put("code_challenge", challenge(verifier));
        parameters.put("code_challenge_method", "S256");
        if (!clientId.isBlank()) parameters.put("client_id", clientId);
        if (!scope.isBlank()) parameters.put("scope", scope);
        return new McpModels.OAuthStartResponse(
            appendQuery(authEndpoint, parameters), state
        );
    }

    String callback(String code, String state, String error, String description) {
        purgeExpired();
        if (!value(error).isBlank()) return popup("error", errorPage(
            value(description).isBlank() ? error : description), Map.of());
        if (value(code).isBlank() || value(state).isBlank()) {
            return popup("error", errorPage("Missing authorization code or state."), Map.of());
        }
        Session session = sessions.remove(state);
        if (session == null || session.expired()) {
            return popup("error", errorPage("OAuth session expired or was not found."), Map.of());
        }
        try {
            persist(session, exchange(session, code));
            return popup("success", "<p class='symbol'>&#10003;</p><h1>Authorization successful</h1><p>You can close this window.</p>",
                Map.of("clientKey", session.clientKey()));
        } catch (ResponseStatusException exception) {
            return popup("error", errorPage(exception.getReason()), Map.of());
        } catch (Exception exception) {
            return popup("error", errorPage("OAuth authorization failed."), Map.of());
        }
    }

    McpModels.Message revoke(String clientKey) {
        Map<String, Object> card = mcp.store().card(clientKey);
        if (card == null) throw new McpNotFoundException(clientKey);
        mcp.store().deleteCredential(McpStore.mcpOauthCredentialRef(clientKey));
        Map<String, Object> credentials = map(card.get("credentials"));
        credentials.remove("oauth");
        card.put("credentials", credentials);
        Map<String, Object> endpoint = map(card.get("endpoint"));
        Map<String, Object> headers = map(endpoint.get("headers"));
        Map<String, Object> authorization = map(headers.get("Authorization"));
        if ("oauth".equals(value(authorization.get("credential")))) {
            headers.remove("Authorization");
            endpoint.put("headers", headers);
            card.put("endpoint", endpoint);
        }
        mcp.store().saveCard(clientKey, card);
        return new McpModels.Message("OAuth tokens cleared");
    }

    static String redirectUri(String scheme, String host, int port) {
        boolean defaultPort = ("http".equals(scheme) && port == 80)
            || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port)
            + CALLBACK_PATH;
    }

    private void persist(Session session, Map<String, Object> tokens) {
        String accessToken = value(tokens.get("access_token"));
        if (accessToken.isBlank()) throw badRequest("Token response did not include an access_token.");
        Map<String, Object> prior = mcp.store().credential(
            McpStore.mcpOauthCredentialRef(session.clientKey())
        );
        Map<String, Object> publicData = prior == null ? new LinkedHashMap<>()
            : map(prior.get("public"));
        publicData.put("client_id", session.clientId());
        publicData.put("scope", value(tokens.get("scope")).isBlank()
            ? session.scope() : value(tokens.get("scope")));
        publicData.put("expires_at", System.currentTimeMillis() / 1000.0
            + positiveNumber(tokens.get("expires_in"), 3600));
        publicData.put("token_endpoint", session.tokenEndpoint());
        publicData.put("auth_endpoint", session.authorizationEndpoint());
        Map<String, Object> secrets = prior == null ? new LinkedHashMap<>()
            : map(prior.get("secrets"));
        secrets.put("access_token", accessToken);
        String refresh = value(tokens.get("refresh_token"));
        if (!refresh.isBlank()) secrets.put("refresh_token", refresh);
        mcp.store().saveCredential(McpStore.mcpOauthCredentialRef(session.clientKey()), Map.of(
            "kind", "oauth2_auth_code", "public", publicData,
            "secrets", secrets, "meta", Map.of("updated_at", System.currentTimeMillis() / 1000.0)
        ));

        Map<String, Object> card = mcp.store().card(session.clientKey());
        Map<String, Object> credentials = map(card.get("credentials"));
        credentials.put("oauth", Map.of("kind", "oauth2_auth_code",
            "ref", McpStore.mcpOauthCredentialRef(session.clientKey())));
        card.put("credentials", credentials);
        Map<String, Object> endpoint = map(card.get("endpoint"));
        if (!"stdio".equals(value(endpoint.get("transport")))) {
            Map<String, Object> headers = map(endpoint.get("headers"));
            headers.put("Authorization", Map.of("source", "credential",
                "credential", "oauth", "field", "access_token",
                "format", "Bearer {value}"));
            endpoint.put("headers", headers);
            card.put("endpoint", endpoint);
        }
        mcp.store().saveCard(session.clientKey(), card);
    }

    private Map<String, Object> exchange(Session session, String code) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", session.redirectUri());
        form.put("code_verifier", session.verifier());
        if (!session.clientId().isBlank()) form.put("client_id", session.clientId());
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                URI.create(session.tokenEndpoint())).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(query(form))).build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                throw badRequest("Token exchange failed with HTTP " + response.statusCode() + ".");
            }
            return map(JSON.readValue(response.body(), Object.class));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw badRequest("Token exchange request failed.");
        }
    }

    private String[] discoverEndpoints(String mcpUrl) {
        String server = authorizationServer(mcpUrl);
        if (server == null) throw badRequest("Could not discover an OAuth authorization server. Enter endpoints manually.");
        for (String metadata : authorizationMetadataUrls(server)) {
            Map<String, Object> data = fetchJson(metadata);
            String auth = value(data.get("authorization_endpoint"));
            String token = value(data.get("token_endpoint"));
            if (httpUrl(auth) && httpUrl(token)) return new String[]{auth, token};
        }
        throw badRequest("Could not discover OAuth authorization and token endpoints. Enter endpoints manually.");
    }

    private String authorizationServer(String mcpUrl) {
        List<String> resources = new ArrayList<>();
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(mcpUrl))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            String header = response.headers().firstValue("www-authenticate").orElse("");
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("resource_metadata=\\\"([^\\\"]+)\\\"")
                .matcher(header);
            if (match.find()) resources.add(match.group(1));
        } catch (Exception ignored) { }
        URI uri = URI.create(mcpUrl);
        String root = uri.getScheme() + "://" + uri.getAuthority();
        String path = uri.getPath().replaceAll("^/+|/+$", "");
        if (!path.isBlank()) resources.add(root + "/.well-known/oauth-protected-resource/" + path);
        resources.add(root + "/.well-known/oauth-protected-resource");
        for (String resource : resources) {
            Object servers = fetchJson(resource).get("authorization_servers");
            if (servers instanceof List<?> list && !list.isEmpty() && httpUrl(String.valueOf(list.get(0)))) {
                return String.valueOf(list.get(0));
            }
        }
        return null;
    }

    private List<String> authorizationMetadataUrls(String server) {
        URI uri = URI.create(server);
        String root = uri.getScheme() + "://" + uri.getAuthority();
        String path = uri.getPath().replaceAll("^/+|/+$", "");
        List<String> result = new ArrayList<>();
        if (!path.isBlank()) {
            result.add(root + "/.well-known/oauth-authorization-server/" + path);
            result.add(root + "/.well-known/openid-configuration/" + path);
        }
        result.add(root + "/.well-known/oauth-authorization-server");
        result.add(root + "/.well-known/openid-configuration");
        return result;
    }

    private Map<String, Object> fetchJson(String url) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300
                ? map(JSON.readValue(response.body(), Object.class)) : Map.of();
        } catch (Exception ignored) { return Map.of(); }
    }

    private void purgeExpired() { sessions.entrySet().removeIf(entry -> entry.getValue().expired()); }

    private static String verifier() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String state() {
        byte[] bytes = new byte[16]; RANDOM.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) { throw new IllegalStateException("Cannot create PKCE challenge", exception); }
    }

    private static String appendQuery(String url, Map<String, String> parameters) {
        return url + (url.contains("?") ? "&" : "?") + query(parameters);
    }

    private static String query(Map<String, String> parameters) {
        return parameters.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
            + "=" + URLEncoder.encode(value(entry.getValue()), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }

    private static boolean remote(String transport) { return "sse".equals(transport) || "streamable_http".equals(transport); }
    private static boolean httpUrl(String value) { try { URI uri = URI.create(value); return "http".equals(uri.getScheme()) || "https".equals(uri.getScheme()); } catch (Exception e) { return false; } }
    private static double positiveNumber(Object raw, double fallback) { try { double result = Double.parseDouble(String.valueOf(raw)); return result > 0 ? result : fallback; } catch (RuntimeException e) { return fallback; } }
    private static String value(Object raw) { return raw == null ? "" : String.valueOf(raw).trim(); }
    private static Map<String, Object> map(Object raw) { return McpValues.map(raw); }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static String errorPage(String message) { return "<h1>Authorization failed</h1><p>" + escape(message) + "</p>"; }
    private static String escape(String text) { return value(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static String popup(String status, String body, Map<String, String> data) {
        String client = data.getOrDefault("clientKey", "").replace("'", "");
        return "<!doctype html><html><head><meta charset='utf-8'><title>MCP OAuth</title><style>body{font-family:system-ui;display:grid;place-items:center;min-height:100vh;background:#f8fafc;margin:0}.card{background:white;padding:32px;border-radius:12px;text-align:center;box-shadow:0 4px 24px #0002}.symbol{font-size:30px;color:#27804a}h1{font-size:20px}</style></head><body><main class='card'>" + body + "<button onclick='window.close()'>Close</button></main><script>const d={type:'mcp-oauth',status:'" + status + "',clientKey:'" + client + "'};try{localStorage.setItem('mcp_oauth_result',JSON.stringify(d))}catch(e){};if(window.opener)window.opener.postMessage(d,'*');setTimeout(()=>window.close(),1500)</script></body></html>";
    }
}
