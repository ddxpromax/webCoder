package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkspaceFilesControllerTests {
    @TempDir
    Path workingDir;

    @Test
    void codeFilesUseSelectedProjectOrExplicitWorkspaceRoot()
        throws Exception {
        AgentStore agents = agents();
        Path project = workingDir.resolve("project");
        Files.createDirectories(project);
        agents.setProjectDir("default", project.toString());
        var mvc = MockMvcBuilders.standaloneSetup(
            new WorkspaceFilesController(agents)
        ).build();

        mvc.perform(put("/api/workspace/code-files/src/App.java")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"class App {}\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));
        assertThat(Files.readString(project.resolve("src/App.java")))
            .isEqualTo("class App {}");
        mvc.perform(get("/api/workspace/code-files"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].filename")
                .value("src/App.java"));
        mvc.perform(get("/api/workspace/code-files/src/App.java"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content")
                .value("class App {}"));
        mvc.perform(get("/api/workspace/code-files/src"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].is_dir").value(false));
        mvc.perform(get("/api/workspace/code-files")
                .param("root", "workspace"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()")
                .value(0));
    }

    @Test
    void agentMarkdownMemoryAndBinaryFilesFollowMajoShapes()
        throws Exception {
        AgentStore agents = agents();
        agents.createAgent(Map.of("id", "worker"));
        Path workspace = agents.workspaceDirForAgent("worker");
        var mvc = MockMvcBuilders.standaloneSetup(
            new WorkspaceFilesController(agents)
        ).build();

        mvc.perform(put("/api/agents/worker/workspace/files/AGENTS.md")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Agent notes\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.written").value(true));
        mvc.perform(get("/api/agents/worker/workspace/files"))
            .andExpect(jsonPath("$[0].filename")
                .value("AGENTS.md"));
        mvc.perform(get("/api/agents/worker/workspace/files/AGENTS.md"))
            .andExpect(jsonPath("$.content")
                .value("Agent notes"));
        mvc.perform(put("/api/workspace/memory/2026-10-15.md")
                .header("X-Agent-Id", "worker")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Memory note\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/workspace/memory")
                .header("X-Agent-Id", "worker"))
            .andExpect(jsonPath("$[0].path")
                .value("2026-10-15.md"));
        mvc.perform(put("/api/workspace/memory/daily/2026-10-16.md")
                .header("X-Agent-Id", "worker")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Nested note\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/workspace/memory/daily/2026-10-16.md")
                .header("X-Agent-Id", "worker"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content")
                .value("Nested note"));
        mvc.perform(get("/api/workspace/memory")
                .header("X-Agent-Id", "worker")
                .param("section", "daily"))
            .andExpect(jsonPath("$[1].path")
                .value("daily/2026-10-16.md"));

        Files.write(workspace.resolve("image.png"),
            new byte[] {1, 2, 3});
        mvc.perform(get(
                "/api/agents/worker/workspace/binary-files/image.png"
            ))
            .andExpect(status().isOk())
            .andExpect(result -> assertThat(result.getResponse()
                .getContentType()).isEqualTo("image/png"));
        mvc.perform(get(
                "/api/agents/worker/workspace/binary-files/AGENTS.md"
            ))
            .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void symlinkCannotEscapeWorkspace() throws Exception {
        AgentStore agents = agents();
        Path workspace = agents.defaultWorkspaceDir();
        Files.createDirectories(workspace);
        Path outside = workingDir.resolve("outside.txt");
        Files.writeString(outside, "private");
        Files.createSymbolicLink(
            workspace.resolve("linked.txt"), outside
        );
        var mvc = MockMvcBuilders.standaloneSetup(
            new WorkspaceFilesController(agents)
        ).build();
        mvc.perform(get("/api/workspace/code-files/linked.txt"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/workspace/code-files/linked.txt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"overwrite\"}"))
            .andExpect(status().isBadRequest());
        assertThat(Files.readString(outside)).isEqualTo("private");
    }

    private AgentStore agents() {
        return new AgentStore(
            new MajoWorkingDirectory(workingDir.toString()),
            new JsonFileStore(new ObjectMapper())
        );
    }
}
