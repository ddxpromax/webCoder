package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentsControllerTests {
    @TempDir
    Path workingDir;

    @Test
    void agentRegistryPersistsMajoProfileShapeAndOrder()
        throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentStore store = new AgentStore(
            new MajoWorkingDirectory(workingDir.toString()),
            new JsonFileStore(mapper)
        );
        var mvc = MockMvcBuilders.standaloneSetup(
            new AgentsController(store)
        ).build();

        mvc.perform(get("/api/agents"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agents.length()")
                .value(1))
            .andExpect(jsonPath("$.agents[0].id")
                .value("default"))
            .andExpect(jsonPath("$.agents[0].pinned")
                .value(true));

        mvc.perform(post("/api/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "id":"research",
                      "name":"Research",
                      "description":"Read code",
                      "backend":"majo",
                      "language":"en"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("research"))
            .andExpect(jsonPath("$.enabled").value(true));

        assertThat(Files.isDirectory(
            workingDir.resolve("workspaces/research/skills")
        )).isTrue();

        mvc.perform(patch("/api/agents/research/pin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pinned\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pinned").value(true));

        mvc.perform(patch("/api/agents/research/toggle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(put("/api/agents/order")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"agent_ids":["default","research"]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agent_ids[1]")
                .value("research"));

        mvc.perform(patch("/api/agents/research/backend-settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"model":"openai/gpt-5.2"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active_model.provider_id")
                .value("openai"))
            .andExpect(jsonPath("$.active_model.model")
                .value("gpt-5.2"));

        Path registry = workingDir.resolve("agents.json");
        assertThat(Files.isRegularFile(registry)).isTrue();
        assertThat(mapper.readTree(registry.toFile())
            .path("schema_version").asText())
            .isEqualTo("agents.v1");

        Path skillFile = workingDir.resolve(
            "workspaces/research/skills/example/SKILL.md"
        );
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, "# Example");
        String copied = mvc.perform(post("/api/agents/research/copy")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Research Copy","copy_skills":true}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isString())
            .andReturn().getResponse().getContentAsString();
        String copiedId = mapper.readTree(copied).path("id").asText();
        assertThat(Files.readString(workingDir.resolve(
            "workspaces/" + copiedId + "/skills/example/SKILL.md"
        ))).isEqualTo("# Example");

        mvc.perform(delete("/api/agents/research"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/api/agents/research"))
            .andExpect(status().isNotFound());
        mvc.perform(patch("/api/agents/default/toggle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isBadRequest());
    }
}
