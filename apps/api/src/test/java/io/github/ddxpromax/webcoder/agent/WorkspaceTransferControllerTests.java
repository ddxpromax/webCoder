package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkspaceTransferControllerTests {
    @TempDir
    Path workingDir;

    @Test
    void fileUploadOffersConflictChoices() throws Exception {
        AgentStore agents = agents();
        var mvc = MockMvcBuilders.standaloneSetup(
            new WorkspaceTransferController(agents)
        ).build();
        MockMultipartFile first = new MockMultipartFile(
            "files", "notes.txt", "text/plain", "first".getBytes()
        );
        mvc.perform(multipart("/api/workspace/file-upload")
                .file(first).param("path", "docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.files[0].status")
                .value("uploaded"));
        Path docs = agents.defaultWorkspaceDir().resolve("docs");
        assertThat(Files.readString(docs.resolve("notes.txt")))
            .isEqualTo("first");

        MockMultipartFile second = new MockMultipartFile(
            "files", "notes.txt", "text/plain", "second".getBytes()
        );
        mvc.perform(multipart("/api/workspace/file-upload")
                .file(second).param("path", "docs"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail.code")
                .value("upload_conflict"));
        mvc.perform(multipart("/api/workspace/file-upload")
                .file(second).param("path", "docs")
                .param("conflict", "rename"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.files[0].name")
                .value("notes (1).txt"));
        assertThat(Files.readString(docs.resolve("notes (1).txt")))
            .isEqualTo("second");
        mvc.perform(multipart("/api/workspace/file-upload")
                .file(second).param("path", "../outside"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void zipUploadAndDownloadPreserveWorkspaceFiles()
        throws Exception {
        AgentStore agents = agents();
        var mvc = MockMvcBuilders.standaloneSetup(
            new WorkspaceTransferController(agents)
        ).build();
        mvc.perform(multipart("/api/workspace/upload")
                .file(new MockMultipartFile(
                    "file", "workspace.zip", "application/zip",
                    zip("src/Main.java", "class Main {}")
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
        Path workspace = agents.defaultWorkspaceDir();
        assertThat(Files.readString(
            workspace.resolve("src/Main.java")
        )).isEqualTo("class Main {}");
        byte[] archive = mvc.perform(get("/api/workspace/download"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        try (ZipInputStream input = new ZipInputStream(
            new ByteArrayInputStream(archive)
        )) {
            assertThat(input.getNextEntry().getName())
                .isEqualTo("src/Main.java");
        }

        mvc.perform(multipart("/api/workspace/upload")
                .file(new MockMultipartFile(
                    "file", "unsafe.zip", "application/zip",
                    zip("../escape.txt", "unsafe")
                )))
            .andExpect(status().isBadRequest());
        assertThat(Files.exists(workingDir.resolve(
            "workspaces/escape.txt"
        ))).isFalse();
    }

    private AgentStore agents() {
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
