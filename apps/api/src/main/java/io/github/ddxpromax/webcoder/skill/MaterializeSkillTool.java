package io.github.ddxpromax.webcoder.skill;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Path;
import java.util.Map;

/** Materialize a SKILL.md definition into the current agent workspace. */
public class MaterializeSkillTool {
    private final Path workspace;
    private final SkillWorkspaceService skills;

    public MaterializeSkillTool(
        Path workspace,
        SkillWorkspaceService skills
    ) {
        this.workspace = workspace;
        this.skills = skills;
    }

    @Tool(
        name = "materialize_skill",
        description = "Create a workspace skill from SKILL.md content"
    )
    public String materializeSkill(
        @ToolParam(
            name = "skill_content",
            description = "SKILL.md with YAML frontmatter and instructions"
        ) String skillContent
    ) {
        try {
            SkillWorkspaceService.validateContent(skillContent);
            Map<String, Object> frontmatter =
                SkillWorkspaceService.parseFrontmatter(skillContent);
            String name = String.valueOf(frontmatter.get("name"));
            Map<String, Object> result = skills.create(
                workspace, name, skillContent, true
            );
            if (!Boolean.TRUE.equals(result.get("success"))) {
                return "Skill already exists: " + name;
            }
            return "Skill materialized at "
                + workspace.resolve("skills")
                    .resolve(name).resolve("SKILL.md")
                + ". It will be available on the next turn.";
        } catch (Exception exception) {
            return "Could not materialize skill: "
                + exception.getMessage();
        }
    }
}
