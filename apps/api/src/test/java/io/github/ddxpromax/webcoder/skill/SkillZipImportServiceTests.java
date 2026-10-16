package io.github.ddxpromax.webcoder.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillZipImportServiceTests {
    @TempDir
    Path workspace;

    private final SkillWorkspaceService skills =
        new SkillWorkspaceService(new JsonFileStore(
            new ObjectMapper()
        ));
    private final SkillZipImportService zip =
        new SkillZipImportService(skills);

    @Test
    void importsMultipleSkillsAndRetainsReferenceFiles()
        throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("bundle/review/SKILL.md", skill(
            "review", "Review a code change"
        ));
        files.put("bundle/review/references/guide.md",
            "Check the tests.");
        files.put("bundle/explain/SKILL.md", skill(
            "explain", "Explain a code change"
        ));

        Map<String, Object> result = zip.importZip(
            workspace, archive(files), true, "", Map.of()
        );

        assertThat(result).containsEntry("count", 2)
            .containsEntry("enabled", true);
        assertThat(skills.enabledNames(workspace))
            .containsExactly("explain", "review");
        assertThat(skills.loadFile(
            workspace, "review", "references/guide.md"
        )).isEqualTo("Check the tests.");
        assertThat(skills.list(workspace))
            .filteredOn(entry -> "review".equals(
                entry.get("name")
            ))
            .singleElement()
            .satisfies(entry -> assertThat(
                entry.get("installed_from")
            ).isEqualTo("zip"));
    }

    @Test
    void conflictPreventsEverySkillFromImporting() throws Exception {
        skills.create(workspace, "review", skill(
            "review", "Original review skill"
        ), false);
        Map<String, Object> result = zip.importZip(
            workspace,
            archive(Map.of(
                "review/SKILL.md", skill(
                    "review", "Replacement review skill"
                ),
                "other/SKILL.md", skill(
                    "other", "Another skill"
                )
            )),
            false, "", Map.of()
        );
        assertThat(result).containsEntry("count", 0);
        assertThat(Files.exists(workspace.resolve(
            "skills/other/SKILL.md"
        ))).isFalse();
    }

    @Test
    void zipSlipIsRejected() throws Exception {
        assertThatThrownBy(() -> zip.importZip(
            workspace,
            archive(Map.of(
                "../escaped/SKILL.md", skill(
                    "escaped", "Escaping skill"
                )
            )),
            false, "", Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsafe path");
    }

    private static String skill(String name, String description) {
        return "---\nname: " + name + "\ndescription: "
            + description + "\n---\nFollow the instructions.\n";
    }

    private static byte[] archive(Map<String, String> files)
        throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                output.putNextEntry(new ZipEntry(file.getKey()));
                output.write(file.getValue().getBytes(
                    java.nio.charset.StandardCharsets.UTF_8
                ));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
