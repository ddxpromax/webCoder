package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConsoleUploadControllerTests {
    @TempDir
    Path directory;

    @Test
    void uploadPreviewAndSendWorkspaceFileUseSharedDirectory()
        throws Exception {
        MajoWorkingDirectory workingDirectory =
            new MajoWorkingDirectory(directory.toString());
        var mvc = MockMvcBuilders.standaloneSetup(
            new ConsoleUploadController(workingDirectory)
        ).build();
        byte[] bytes = "hello from upload".getBytes();
        MockMultipartFile file = new MockMultipartFile(
            "file", "../notes.txt", "text/plain", bytes
        );
        String response = mvc.perform(multipart(
                "/api/console/upload").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.file_name")
                .value("notes.txt"))
            .andReturn().getResponse().getContentAsString();
        String savedName = new ObjectMapper().readTree(response)
            .path("stored_name").asText();
        mvc.perform(get("/api/files/preview/" + savedName))
            .andExpect(status().isOk())
            .andExpect(result -> assertThat(result.getResponse()
                .getContentAsByteArray()).isEqualTo(bytes));

        Path workspace = directory.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("report.md"),
            "Finished report");
        SendFileToUserTool tool = new SendFileToUserTool(
            workspace, workingDirectory
        );
        assertThat(tool.sendFileToUser("report.md"))
            .contains("/api/files/preview/");
        assertThat(tool.sendFileToUser("../outside.md"))
            .contains("inside the workspace");
    }
}
