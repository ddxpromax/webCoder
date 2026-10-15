package io.github.ddxpromax.webcoder.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Majo's per-agent built-in tool catalog and persisted switches. */
@Service
public class ToolConfigService {
    private record Definition(
        String name,
        String description,
        String icon
    ) {}

    private static final List<Definition> BUILTIN = List.of(
        def("read_file", "Read file contents", "📄"),
        def("write_file", "Write content to file", "✍️"),
        def("edit_file", "Edit file using find-and-replace", "🖊️"),
        def("append_file", "Append content to a file", "📎"),
        def("grep_search", "Search file contents by pattern", "🔍"),
        def("glob_search", "Find files matching a glob pattern", "📁"),
        def("execute_shell_command", "Execute shell commands", "💻"),
        def("send_file_to_user", "Send files to user", "📤"),
        def("browser_use", "Browser automation and web interaction", "🌐"),
        def("web_search", "Search the web for real-time information", "🔎"),
        def("web_fetch", "Fetch and read content from a URL", "📥"),
        def("desktop_screenshot", "Capture desktop screenshots", "📸"),
        def("view_image", "Load an image for visual analysis", "🖼️"),
        def("view_video", "Load a video for visual analysis", "🎥"),
        def("get_current_time", "Get current date and time", "🕐"),
        def("set_user_timezone", "Set user timezone", "🌍"),
        def("get_token_usage", "Get model token usage", "📊"),
        def("list_agents", "List configured agents", "🤖"),
        def("chat_with_agent", "Message another agent and wait", "💬"),
        def("submit_to_agent", "Submit a background agent task", "📨"),
        def("check_agent_task", "Check a background agent task", "⏳"),
        def("spawn_subagent", "Spawn an ephemeral sub-task", "🔀"),
        def("delegate_external_agent", "Delegate to an ACP agent", "📡"),
        def("materialize_skill", "Materialize a skill", "🧩"),
        def("ast_search", "Search code by AST pattern", "🌳"),
        def("memory_search", "Search persistent agent memory", "🧠")
    );
    private static final Map<String, String> NAMES = Map.ofEntries(
        Map.entry("search_code", "grep_search"),
        Map.entry("list_directory", "glob_search"),
        Map.entry("execute_command", "execute_shell_command"),
        Map.entry("find_symbol", "ast_search")
    );

    private final AgentStore agents;

    public ToolConfigService(AgentStore agents) {
        this.agents = agents;
    }

    public List<Map<String, Object>> list(String agentId) {
        return new ArrayList<>(builtin(agentId).values());
    }

    public Map<String, Object> find(
        String agentId,
        String toolName
    ) {
        return builtin(agentId).get(toolName);
    }

    public boolean isEnabled(String agentId, String sdkName) {
        String name = NAMES.getOrDefault(sdkName, sdkName);
        Map<String, Object> tool = find(agentId, name);
        return tool == null || asBoolean(tool.get("enabled"), true);
    }

    public Map<String, Object> toggle(
        String agentId,
        String toolName
    ) {
        Map<String, Map<String, Object>> tools = builtin(agentId);
        Map<String, Object> tool = tools.get(toolName);
        if (tool == null) {
            return null;
        }
        tool.put("enabled", !asBoolean(
            tool.get("enabled"), true
        ));
        save(agentId, tools);
        return tool;
    }

    public Map<String, Object> setAsyncExecution(
        String agentId,
        String toolName,
        boolean enabled
    ) {
        Map<String, Map<String, Object>> tools = builtin(agentId);
        Map<String, Object> tool = tools.get(toolName);
        if (tool == null) {
            return null;
        }
        tool.put("async_execution", enabled);
        save(agentId, tools);
        return tool;
    }

    public boolean setConfig(
        String agentId,
        String toolName,
        Map<String, Object> config
    ) {
        Map<String, Map<String, Object>> tools = builtin(agentId);
        Map<String, Object> tool = tools.get(toolName);
        if (tool == null) {
            return false;
        }
        tool.put("config", new LinkedHashMap<>(config));
        save(agentId, tools);
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> builtin(
        String agentId
    ) {
        Object stored = agents.getToolsConfig(agentId)
            .get("builtin_tools");
        Map<String, Map<String, Object>> tools =
            new LinkedHashMap<>();
        if (stored instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key != null && value instanceof Map<?, ?> entry) {
                    tools.put(String.valueOf(key),
                        new LinkedHashMap<>((Map<String, Object>) entry));
                }
            });
        }
        for (Definition definition : BUILTIN) {
            tools.computeIfAbsent(definition.name(), key -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", definition.name());
                entry.put("enabled", true);
                entry.put("description", definition.description());
                entry.put("display_to_user", true);
                entry.put("async_execution", false);
                entry.put("icon", definition.icon());
                entry.put("config", Map.of());
                return entry;
            });
        }
        return tools;
    }

    private void save(
        String agentId,
        Map<String, Map<String, Object>> tools
    ) {
        agents.saveToolsConfig(agentId, Map.of(
            "builtin_tools", tools
        ));
    }

    private static Definition def(
        String name,
        String description,
        String icon
    ) {
        return new Definition(name, description, icon);
    }

    private static boolean asBoolean(
        Object value,
        boolean fallback
    ) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }
}
