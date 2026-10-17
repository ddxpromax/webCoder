package io.github.ddxpromax.webcoder.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class SkillHubInstallServiceTests {
    @Test
    void parsesDirectBundleFilesIntoWorkspaceTrees() throws Exception {
        String bundle = """
            {
              "name": "review",
              "content": "---\\nname: review\\ndescription: Review code\\n---\\nUse this skill.",
              "files": {
                "references/guide.md": "Guidance",
                "scripts/check.sh": "#!/bin/sh",
                "templates/output.txt": "Result"
              }
            }
            """;

        var parsed = SkillHubInstallService.parseBundle(
            bundle, "https://example.test/review.json", "hub"
        );

        assertThat(parsed.name()).isEqualTo("review");
        assertThat(parsed.references()).containsEntry("guide.md", "Guidance");
        assertThat(parsed.scripts()).containsEntry("check.sh", "#!/bin/sh");
        assertThat(parsed.extraFiles()).containsKey("templates");
    }

    @Test
    void rejectsBundlesWithoutSkillContent() {
        assertThatThrownBy(() -> SkillHubInstallService.parseBundle(
            "{\"name\":\"review\"}", "https://example.test/bundle", "hub"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Hub bundle missing SKILL.md content");
    }

    @Test
    void parsesMajoSkillsShRepositoryAndDirectoryTriplet() {
        assertThat(SkillHubInstallService.extractSkillsShSpec(
            URI.create("https://skills.sh/vercel-labs/skills/find-skills")
        )).containsExactly("vercel-labs", "skills", "find-skills");

        assertThat(SkillHubInstallService.extractSkillsShSpec(
            URI.create("https://skills.sh/vercel-labs/skills")
        )).isNull();
        assertThat(SkillHubInstallService.extractSkillsShSpec(
            URI.create("https://skills.sh.other.example/a/b/c")
        )).isNull();
    }
}
