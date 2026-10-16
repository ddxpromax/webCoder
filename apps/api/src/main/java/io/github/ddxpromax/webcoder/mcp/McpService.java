package io.github.ddxpromax.webcoder.mcp;

import io.agentscope.harness.agent.tools.McpServerConfig;
import io.github.ddxpromax.webcoder.chat.ChatRepository;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import org.springframework.stereotype.Service;

@Service
public class McpService {
    private static final Set<String> RESERVED_KEYS = Set.of(
        "tools", "toggle", "oauth", "policy",
        "access-principals"
    );

    private final McpStore store;
    private final ChatRepository chats;

    public McpService(McpStore store, ChatRepository chats) {
        this.store = store;
        this.chats = chats;
    }

    public List<McpModels.ClientInfo> list() {
        return store.keys().stream().map(this::get).toList();
    }

    public McpModels.ClientInfo get(String key) {
        Map<String, Object> card = requireCard(key);
        Map<String, Object> endpoint = McpValues.map(
            card.get("endpoint")
        );
        Map<String, Object> config = McpValues.map(
            card.get("config")
        );
        Map<String, String> secrets = store.secrets(key);
        String displayName = McpValues.string(
            config.get("display_name")
        );
        Map<String, Object> policy = McpValues.map(
            card.get("policy")
        );
        return new McpModels.ClientInfo(
            key,
            displayName.isBlank() ? key : displayName,
            McpValues.string(config.get("description")),
            McpValues.bool(card.get("enabled"), true),
            McpValues.string(endpoint.get("transport")),
            McpValues.string(endpoint.get("url")),
            McpBindings.present(endpoint.get("headers"), secrets),
            McpValues.string(endpoint.get("command")),
            McpValues.list(endpoint.get("args")),
            McpBindings.present(endpoint.get("env"), secrets),
            McpValues.string(endpoint.get("cwd")),
            McpValues.positiveDouble(endpoint.get("http_timeout")),
            McpValues.nullableList(config.get("tools")),
            null,
            new McpModels.AccessSummary(
                McpPolicy.read(policy).defaultEffect(),
                McpPolicy.read(policy).toolOverrides().size()
            )
        );
    }

    public McpModels.ClientInfo create(
        String key,
        McpModels.ClientData data
    ) {
        validateKey(key);
        if (store.card(key) != null) {
            throw new IllegalArgumentException(
                "MCP client '" + key + "' already exists"
            );
        }
        requireUniqueName(name(data.name(), key), key);
        save(key, normalized(data, key), null);
        return get(key);
    }

    public McpModels.ClientInfo update(
        String key,
        McpModels.ClientData changes
    ) {
        McpModels.ClientInfo previous = get(key);
        McpModels.ClientData merged = new McpModels.ClientData(
            choose(changes.name(), previous.name()),
            choose(changes.description(), previous.description()),
            choose(changes.enabled(), previous.enabled()),
            choose(changes.transport(), previous.transport()),
            choose(changes.url(), previous.url()),
            choose(changes.headers(), previous.headers()),
            choose(changes.command(), previous.command()),
            choose(changes.args(), previous.args()),
            choose(changes.env(), previous.env()),
            choose(changes.cwd(), previous.cwd()),
            choose(changes.httpTimeout(), previous.httpTimeout()),
            choose(changes.tools(), previous.tools())
        );
        requireUniqueName(name(merged.name(), key), key);
        save(key, normalized(merged, key), requireCard(key));
        return get(key);
    }

    public McpModels.ClientInfo toggle(String key) {
        Map<String, Object> card = requireCard(key);
        card.put("enabled", !McpValues.bool(
            card.get("enabled"), true
        ));
        store.saveCard(key, card);
        return get(key);
    }

    public McpModels.Message delete(String key) {
        requireCard(key);
        store.delete(key);
        return new McpModels.Message(
            "MCP client '" + key + "' deleted successfully"
        );
    }

    public McpModels.ClientInfo setToolWhitelist(
        String key,
        List<String> tools
    ) {
        Map<String, Object> card = requireCard(key);
        Map<String, Object> config = McpValues.map(
            card.get("config")
        );
        config.put("tools", tools);
        card.put("config", config);
        store.saveCard(key, card);
        return get(key);
    }

    public McpModels.AccessPolicy policy(String key) {
        return McpPolicy.read(requireCard(key).get("policy"));
    }

    public McpModels.AccessPolicy updatePolicy(
        String key,
        McpModels.AccessPolicy update
    ) {
        Map<String, Object> card = requireCard(key);
        card.put("policy", McpPolicy.write(
            card.get("policy"), update
        ));
        store.saveCard(key, card);
        return policy(key);
    }

    public List<McpModels.AccessPrincipalOption>
        accessPrincipals() {
        if (chats == null) {
            return List.of();
        }
        Map<String, McpModels.AccessPrincipalOption> unique =
            new LinkedHashMap<>();
        chats.findAllByArchivedAtIsNullOrderByUpdatedAtDesc()
            .forEach(chat -> {
                String channel = chat.getChannel();
                String user = chat.getUserId();
                if (channel == null || channel.isBlank()
                    || user == null || user.isBlank()) {
                    return;
                }
                String key = channel + "\u0000" + user;
                unique.putIfAbsent(key,
                    new McpModels.AccessPrincipalOption(
                        "channel", channel, "user", user,
                        channel + " / " + user
                            + " (" + chat.getTitle() + ")",
                        chat.getId(), chat.getTitle(),
                        chat.getSessionId(),
                        chat.getUpdatedAt() == null ? ""
                            : chat.getUpdatedAt().toString()
                    ));
            });
        return new ArrayList<>(unique.values()).stream()
            .limit(100).toList();
    }

    public String effectFor(
        String key,
        String toolName,
        String channel,
        String userId
    ) {
        Map<String, Object> card = requireCard(key);
        if (!McpValues.bool(card.get("enabled"), true)) {
            return "deny";
        }
        return McpPolicy.effect(
            McpPolicy.read(card.get("policy")), toolName,
            channel, userId
        );
    }

    public Map<String, McpServerConfig> serverConfigs() {
        Map<String, McpServerConfig> result =
            new LinkedHashMap<>();
        for (String key : store.keys()) {
            Map<String, Object> card = store.card(key);
            if (card != null && McpValues.bool(
                card.get("enabled"), true
            )) {
                Map<String, Object> config = McpValues.map(
                    card.get("config")
                );
                if (config.get("tools") instanceof List<?> tools
                    && tools.isEmpty()) {
                    continue;
                }
                result.put(key, serverConfig(key, card));
            }
        }
        return result;
    }

    public McpServerConfig discoveryConfig(String key) {
        Map<String, Object> card = requireCard(key);
        if (!McpValues.bool(card.get("enabled"), true)) {
            return null;
        }
        McpServerConfig config = serverConfig(key, card);
        config.setEnableTools(null);
        return config;
    }

    private McpServerConfig serverConfig(
        String key,
        Map<String, Object> card
    ) {
        Map<String, Object> endpoint = McpValues.map(
            card.get("endpoint")
        );
        Map<String, Object> config = McpValues.map(
            card.get("config")
        );
        String transport = McpValues.string(
            endpoint.get("transport")
        );
        Map<String, String> secrets = store.secrets(key);
        McpServerConfig result = new McpServerConfig();
        result.setTransport("streamable_http".equals(transport)
            ? "streamable-http" : transport);
        if ("stdio".equals(transport)) {
            result.setCommand(McpValues.string(
                endpoint.get("command")
            ));
            result.setArgs(McpValues.list(endpoint.get("args")));
            result.setEnv(McpBindings.resolve(
                endpoint.get("env"), secrets
            ));
        } else {
            result.setUrl(McpValues.string(endpoint.get("url")));
            result.setHeaders(McpBindings.resolve(
                endpoint.get("headers"), secrets
            ));
        }
        Double seconds = McpValues.positiveDouble(
            endpoint.get("http_timeout")
        );
        if (seconds != null) {
            Duration timeout = Duration.ofMillis(
                (long) (seconds * 1000)
            );
            result.setTimeout(timeout);
            result.setInitializationTimeout(timeout);
        }
        List<String> tools = McpValues.nullableList(
            config.get("tools")
        );
        if (tools != null) {
            result.setEnableTools(tools);
        }
        return result;
    }

    private void save(
        String key,
        McpModels.ClientData data,
        Map<String, Object> previous
    ) {
        String transport = data.transport();
        Map<String, String> priorSecrets = store.secrets(key);
        Map<String, String> newSecrets = new LinkedHashMap<>();
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("transport", transport);
        if ("stdio".equals(transport)) {
            endpoint.put("command", data.command());
            endpoint.put("args", data.args());
            endpoint.put("env", McpBindings.encode(
                "env", data.env(), priorSecrets, newSecrets
            ));
            if (!data.cwd().isBlank()) {
                endpoint.put("cwd", data.cwd());
            }
        } else {
            endpoint.put("url", data.url());
            endpoint.put("headers", McpBindings.encode(
                "headers", data.headers(), priorSecrets,
                newSecrets
            ));
        }
        if (data.httpTimeout() != null
            && data.httpTimeout() > 0) {
            endpoint.put("http_timeout", data.httpTimeout());
        }
        Map<String, Object> credentials = new LinkedHashMap<>();
        if (previous != null) {
            credentials.putAll(McpValues.map(
                previous.get("credentials")
            ));
        }
        if (newSecrets.isEmpty()) {
            credentials.remove("static");
        } else {
            credentials.put("static", Map.of(
                "kind", "static", "ref", "mcp/" + key
            ));
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("display_name", name(data.name(), key));
        config.put("description", data.description());
        config.put("tools", data.tools());
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", key);
        card.put("protocol", "mcp");
        card.put("endpoint", endpoint);
        card.put("credentials", credentials);
        card.put("config", config);
        card.put("enabled", data.enabled());
        card.put("policy", previous == null
            ? Map.of("default_effect", "ask", "rules", List.of())
            : previous.get("policy"));
        store.save(key, card, newSecrets);
    }

    private static McpModels.ClientData normalized(
        McpModels.ClientData data,
        String key
    ) {
        if (data == null) {
            throw new IllegalArgumentException(
                "Field 'client' is required"
            );
        }
        String transport = data.transport() == null
            ? "stdio" : data.transport();
        if (!Set.of("stdio", "streamable_http", "sse")
            .contains(transport)) {
            throw new IllegalArgumentException(
                "Unsupported MCP transport: " + transport
            );
        }
        return new McpModels.ClientData(
            name(data.name(), key),
            data.description() == null ? ""
                : data.description(),
            data.enabled() == null || data.enabled(),
            transport,
            data.url() == null ? "" : data.url(),
            data.headers() == null ? Map.of()
                : data.headers(),
            data.command() == null ? ""
                : data.command(),
            data.args() == null ? List.of() : data.args(),
            data.env() == null ? Map.of() : data.env(),
            data.cwd() == null ? "" : data.cwd(),
            data.httpTimeout(),
            data.tools()
        );
    }

    private Map<String, Object> requireCard(String key) {
        Map<String, Object> card = store.card(key);
        if (card == null) {
            throw new McpNotFoundException(key);
        }
        return card;
    }

    private void requireUniqueName(String display, String key) {
        for (McpModels.ClientInfo existing : list()) {
            if (existing.key().equals(key)) {
                continue;
            }
            if (existing.key().equalsIgnoreCase(display)
                || existing.name().equalsIgnoreCase(display)) {
                throw new IllegalArgumentException(
                    "MCP client name already exists: " + display
                );
            }
        }
    }

    private static void validateKey(String key) {
        McpStore.requireKey(key);
        if (RESERVED_KEYS.contains(key.toLowerCase())) {
            throw new IllegalArgumentException(
                "MCP client key is reserved: " + key
            );
        }
    }

    private static String name(String raw, String key) {
        return raw == null || raw.isBlank()
            ? key : raw.trim();
    }

    private static <T> T choose(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
