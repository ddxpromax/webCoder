package io.github.ddxpromax.webcoder.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillWorkspaceServiceTests {
    @TempDir
    Path workspace;

    private final SkillWorkspaceService service =
        new SkillWorkspaceService(new JsonFileStore(
            new ObjectMapper()
        ));

    private static final String SKILL = """
        ---
        name: review
        description: Review a code change
        metadata:
          version: 1.0.0
        ---
        Read the diff and summarize findings.
        """;

    @Test
    void createListRenameAndDisablePreserveManifestState()
        throws Exception {
        assertThat(service.create(
            workspace, "review", SKILL, true
        )).containsEntry("success", true);
        assertThat(service.enabledNames(workspace))
            .containsExactly("review");
        assertThat(service.list(workspace).getFirst())
            .containsEntry("description", "Review a code change")
            .containsEntry("version_text", "1.0.0");

        service.setTags(workspace, "review", List.of("code"));
        service.setChannels(workspace, "review", List.of("console"));
        service.setConfig(workspace, "review", Map.of(
            "strict", true
        ));
        assertThat(service.save(
            workspace, "review", "reviewer", SKILL,
            null, false
        )).containsEntry("success", true);
        assertThat(service.list(workspace).getFirst())
            .containsEntry("name", "reviewer")
            .containsEntry("tags", List.of("code"))
            .containsEntry("channels", List.of("console"));
        assertThat(service.config(workspace, "reviewer"))
            .containsEntry("config", Map.of("strict", true));
        assertThat(service.enabledNames(workspace))
            .containsExactly("reviewer");
        assertThat(service.delete(workspace, "reviewer"))
            .isFalse();
        service.setEnabled(workspace, "reviewer", false);
        assertThat(service.delete(workspace, "reviewer"))
            .isTrue();
        assertThat(service.list(workspace)).isEmpty();
    }

    @Test
    void reconcilesExistingSkillsAndRejectsTraversal() throws Exception {
        Path directory = workspace.resolve("skills").resolve("review");
        Files.createDirectories(directory.resolve("references"));
        Files.writeString(directory.resolve("SKILL.md"), SKILL);
        Files.writeString(directory.resolve("references/guide.md"),
            "Review every changed file.");

        assertThat(service.list(workspace).getFirst())
            .containsEntry("enabled", false);
        assertThat(service.loadFile(
            workspace, "review", "references/guide.md"
        )).isEqualTo("Review every changed file.");
        assertThatThrownBy(() -> service.loadFile(
            workspace, "review", "references/../../secret"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(
            workspace, "../escape", SKILL, false
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresValidFrontmatter() {
        assertThatThrownBy(() -> service.create(
            workspace, "broken", "# Missing frontmatter", false
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void materializedSkillIsAvailableOnTheNextTurn() {
        MaterializeSkillTool tool = new MaterializeSkillTool(
            workspace, service
        );
        assertThat(tool.materializeSkill(SKILL))
            .contains("available on the next turn");
        assertThat(service.enabledNames(workspace))
            .containsExactly("review");
    }

    @Test
    void createIncludesReferencesScriptsAndConfig() {
        service.create(
            workspace, "review", SKILL,
            Map.of("guides", Map.of("checklist.md",
                "Check behavior.")),
            Map.of("inspect.sh", "cat README.md"),
            Map.of("notes.md", "Keep notes."),
            Map.of("depth", "full"), false
        );
        assertThat(service.loadFile(
            workspace, "review",
            "references/guides/checklist.md"
        )).isEqualTo("Check behavior.");
        assertThat(service.loadFile(
            workspace, "review", "scripts/inspect.sh"
        )).isEqualTo("cat README.md");
        assertThat(service.config(workspace, "review"))
            .containsEntry("config", Map.of("depth", "full"));
    }
}
