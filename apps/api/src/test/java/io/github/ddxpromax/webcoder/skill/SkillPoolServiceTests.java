package io.github.ddxpromax.webcoder.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class SkillPoolServiceTests {
    @Test
    void createsAndDownloadsPoolSkillsIntoAWorkspace() throws Exception {
        var root = Files.createTempDirectory("majo-pool-test-");
        var files = new JsonFileStore(new ObjectMapper());
        var workspaceSkills = new SkillWorkspaceService(files);
        var pool = new SkillPoolService(
            new MajoWorkingDirectory(root.toString()), files, workspaceSkills
        );
        var workspace = Files.createTempDirectory("majo-workspace-test-");

        assertThat(pool.create("review", "---\nname: review\ndescription: Review code\n---\nCheck changes.", null, null, null, ""))
            .containsEntry("success", true);
        assertThat(pool.download(workspace, "review", false, false))
            .containsEntry("success", true);

        assertThat(workspaceSkills.list(workspace))
            .singleElement().extracting(skill -> skill.get("name"))
            .isEqualTo("review");
    }
}
