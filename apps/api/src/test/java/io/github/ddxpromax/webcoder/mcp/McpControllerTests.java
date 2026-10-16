package io.github.ddxpromax.webcoder.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.LocalWorkspaceMutationGate;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class McpControllerTests {
    @TempDir
    Path workingDirectory;

    @Test
    void clientCardsKeepSecretsOutOfResponsesAndPreserveMaskedUpdates()
        throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        McpStore store = new McpStore(
            new MajoWorkingDirectory(workingDirectory.toString()),
            new JsonFileStore(mapper),
            new LocalWorkspaceMutationGate()
        );
        McpService service = new McpService(store, null);
        var mvc = MockMvcBuilders.standaloneSetup(
            new McpController(service, new McpRuntime(service))
        ).build();
        String secret = "Bearer token-secret-123456";
        String body = mapper.writeValueAsString(Map.of(
            "client_key", "search",
            "client", Map.of(
                "name", "Search MCP",
                "transport", "streamable_http",
                "url", "https://example.invalid/mcp",
                "headers", Map.of("Authorization", secret)
            )
        ));
        String created = mvc.perform(post("/api/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key").value("search"))
            .andReturn().getResponse().getContentAsString();
        assertThat(created).doesNotContain(secret);
        JsonNode createdJson = mapper.readTree(created);
        String masked = createdJson.path("headers")
            .path("Authorization").asText();
        assertThat(masked).contains("*");
        assertThat(Files.readString(workingDirectory
            .resolve("mcp/cards/search.json")))
            .doesNotContain(secret);

        String policy = mapper.writeValueAsString(Map.of(
            "default_effect", "deny",
            "client_overrides", List.of(),
            "tool_defaults", List.of(Map.of(
                "tool_name", "echo", "effect", "ask"
            )),
            "tool_overrides", List.of(Map.of(
                "tool_name", "echo",
                "source_type", "channel",
                "source_value", "console",
                "subject_type", "user",
                "subject_value", "web-user",
                "effect", "allow"
            ))
        ));
        mvc.perform(put("/api/mcp/policy/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(policy))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.default_effect")
                .value("deny"));
        assertThat(service.effectFor("search", "echo",
            "console", "web-user")).isEqualTo("allow");
        assertThat(service.effectFor("search", "echo",
            "console", "another-user")).isEqualTo("ask");
        assertThat(service.effectFor("search", "other",
            "console", "web-user")).isEqualTo("deny");

        String updated = mapper.writeValueAsString(Map.of(
            "description", "Updated",
            "headers", Map.of("Authorization", masked)
        ));
        mvc.perform(put("/api/agents/default/mcp/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updated))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.description")
                .value("Updated"));
        assertThat(Files.readString(workingDirectory
            .resolve("mcp/credentials/mcp_search.json")))
            .contains(secret);

        mvc.perform(patch("/api/mcp/toggle/search"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/mcp"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].key").value("search"));
        mvc.perform(delete("/api/mcp/search"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/mcp/search"))
            .andExpect(status().isNotFound());
        assertThat(Files.exists(workingDirectory
            .resolve("mcp/credentials/mcp_search.json")))
            .isFalse();
    }

    @Test
    void configuredStdioServerExposesItsToolsThroughAgentScope()
        throws Exception {
        Path server = workingDirectory.resolve("mcp_server.py");
        Files.writeString(server, """
            import json
            import sys
            for line in sys.stdin:
                request = json.loads(line)
                method = request.get('method')
                if method == 'initialize':
                    result = {
                        'protocolVersion': '2024-11-05',
                        'capabilities': {'tools': {}},
                        'serverInfo': {'name': 'local-test', 'version': '1'}
                    }
                elif method == 'tools/list':
                    result = {'tools': [{
                        'name': 'echo',
                        'description': 'Echo a string',
                        'inputSchema': {
                            'type': 'object',
                            'properties': {'text': {'type': 'string'}}
                        }
                    }]}
                else:
                    continue
                sys.stdout.write(json.dumps({
                    'jsonrpc': '2.0',
                    'id': request['id'],
                    'result': result
                }) + '\\n')
                sys.stdout.flush()
            """);
        ObjectMapper mapper = new ObjectMapper();
        McpStore store = new McpStore(
            new MajoWorkingDirectory(workingDirectory.toString()),
            new JsonFileStore(mapper),
            new LocalWorkspaceMutationGate()
        );
        McpService service = new McpService(store, null);
        service.create("local", new McpModels.ClientData(
            "Local", "", true, "stdio", "", Map.of(),
            "python3", List.of("-u", server.toString()),
            Map.of(), "", null, null
        ));
        McpRuntime runtime = new McpRuntime(service);
        var tools = runtime.listTools("local");
        assertThat(tools).extracting(McpModels.ToolInfo::name)
            .anyMatch(name -> name.contains("echo"));
        service.setToolWhitelist("local", List.of());
        Toolkit disabled = new Toolkit();
        try {
            runtime.register(disabled);
            assertThat(disabled.getToolNames())
                .doesNotContain("echo");
        } finally {
            runtime.close(disabled);
        }
    }
}
