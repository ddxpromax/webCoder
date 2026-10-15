package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkspaceProjectControllerTests {
    @TempDir
    Path workingDir;

    @Test
    void createSelectAndOrderProjectFolders() throws Exception {
        AgentStore store = agentStore();
        var mvc = MockMvcBuilders.standaloneSetup(
            new WorkspaceProjectController(store)
        ).build();
        mvc.perform(get("/api/workspace/coding-project"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.is_workspace_default")
                .value(true));

        mvc.perform(post("/api/workspace/coding-project/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"alpha\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("alpha"));
        Path project = store.defaultWorkspaceDir()
            .resolve("coding_projects/alpha");
        assertThat(Files.isDirectory(project.resolve(".git")))
            .isTrue();
        mvc.perform(get("/api/workspace/coding-project/list"))
            .andExpect(jsonPath("$[0].name").value("alpha"))
            .andExpect(jsonPath("$[0].is_git").value(true));
        mvc.perform(get("/api/workspace/coding-project"))
            .andExpect(jsonPath("$.path")
                .value(project.toString()))
            .andExpect(jsonPath("$.is_workspace_default")
                .value(false));

        String dirs = """
            {"project_dirs":[
              {"path":"%s","label":"Main"},
              {"path":"%s","label":"Missing"}
            ]}
            """.formatted(
                project,
                workingDir.resolve("not-here")
            );
        mvc.perform(put("/api/workspace/coding-project/dirs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(dirs))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].label")
                .value("Main"))
            .andExpect(jsonPath("$.length()")
                .value(1));
        mvc.perform(put("/api/workspace/coding-project")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.is_workspace_default")
                .value(true));
        mvc.perform(delete("/api/workspace/coding-project/dirs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void cloneAndZipImportRejectTraversal() throws Exception {
        AgentStore store = agentStore();
        var mvc = MockMvcBuilders.standaloneSetup(
            new WorkspaceProjectController(store)
        ).build();
        mvc.perform(post("/api/workspace/coding-project/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"alpha\"}"))
            .andExpect(status().isOk());
        Path source = store.defaultWorkspaceDir()
            .resolve("coding_projects/alpha");
        mvc.perform(post("/api/workspace/coding-project/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(
                    java.util.Map.of(
                        "url", source.toUri().toString(),
                        "name", "beta"
                    )
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("beta"));
        assertThat(Files.isDirectory(store.defaultWorkspaceDir()
            .resolve("coding_projects/beta/.git"))).isTrue();

        mvc.perform(post("/api/workspace/coding-project/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"../escape\"}"))
            .andExpect(status().isBadRequest());

        MockMultipartFile goodZip = new MockMultipartFile(
            "file", "project.zip", "application/zip",
            zip("src/Main.java", "class Main {}")
        );
        mvc.perform(multipart(
                "/api/workspace/coding-project/upload-zip"
            ).file(goodZip).param("name", "gamma"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("gamma"));
        assertThat(Files.readString(store.defaultWorkspaceDir()
            .resolve("coding_projects/gamma/src/Main.java")))
            .isEqualTo("class Main {}");

        MockMultipartFile unsafeZip = new MockMultipartFile(
            "file", "unsafe.zip", "application/zip",
            zip("../outside.txt", "unsafe")
        );
        mvc.perform(multipart(
                "/api/workspace/coding-project/upload-zip"
            ).file(unsafeZip).param("name", "delta"))
            .andExpect(status().isBadRequest());
        assertThat(Files.exists(store.defaultWorkspaceDir()
            .resolve("coding_projects/outside.txt"))).isFalse();
    }

    private AgentStore agentStore() {
        return new AgentStore(
            new MajoWorkingDirectory(workingDir.toString()),
            new JsonFileStore(new ObjectMapper())
        );
    }

    private static byte[] zip(String name, String content)
        throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry(name));
            output.write(content.getBytes(
                java.nio.charset.StandardCharsets.UTF_8
            ));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }
}
