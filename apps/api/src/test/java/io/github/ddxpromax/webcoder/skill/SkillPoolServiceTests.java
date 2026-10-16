package io.github.ddxpromax.webcoder.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillPoolServiceTests {
    @Test
    void createsAndDownloadsPoolSkillsIntoAWorkspace() throws Exception {
        var root = Files.createTempDirectory("majo-pool-test-");
        var files = new JsonFileStore(new ObjectMapper());
        var workspaceSkills = new SkillWorkspaceService(files);
        var pool = new SkillPoolService(
            new MajoWorkingDirectory(root.toString()), files, workspaceSkills,
            new SkillZipImportService(workspaceSkills), null
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

    @Test
    void savesPoolSkillContentWithoutDroppingItsEntry() throws Exception {
        var root = Files.createTempDirectory("majo-pool-test-");
        var files = new JsonFileStore(new ObjectMapper());
        var workspaceSkills = new SkillWorkspaceService(files);
        var pool = new SkillPoolService(new MajoWorkingDirectory(root.toString()), files,
            workspaceSkills, new SkillZipImportService(workspaceSkills), null);
        pool.create("review", "---\nname: review\ndescription: Original\n---\nFirst.", null, null, null, "");

        assertThat(pool.save("review", "review", "---\nname: review\ndescription: Updated\n---\nSecond.", null, false))
            .containsEntry("success", true);
        assertThat(pool.list()).singleElement()
            .extracting(skill -> skill.get("description")).isEqualTo("Updated");
    }

    @Test
    void importsPackagedBuiltInSkill() throws Exception {
        var root = Files.createTempDirectory("majo-builtin-test-");
        var files = new JsonFileStore(new ObjectMapper());
        var workspaceSkills = new SkillWorkspaceService(files);
        var pool = new SkillPoolService(new MajoWorkingDirectory(root.toString()), files,
            workspaceSkills, new SkillZipImportService(workspaceSkills), null);

        var result = new BuiltinSkillService(pool).importSkills(List.of("guidance-en"), false);

        assertThat(result.get("imported")).isEqualTo(List.of("guidance-en"));
    }

    @Test
    void batchDeleteReportsMissingSkills() throws Exception {
        var root = Files.createTempDirectory("majo-pool-test-"); var files = new JsonFileStore(new ObjectMapper());
        var workspaceSkills = new SkillWorkspaceService(files); var pool = new SkillPoolService(new MajoWorkingDirectory(root.toString()), files, workspaceSkills, new SkillZipImportService(workspaceSkills), null);
        pool.create("review", "---\nname: review\ndescription: Review\n---\nCheck.", null, null, null, "");
        assertThat(pool.batchDelete(List.of("review", "absent"))).containsEntry("deleted", List.of("review")).containsEntry("failed", List.of("absent"));
    }
}
