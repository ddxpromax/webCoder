package io.github.ddxpromax.webcoder.backup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.AgentStore;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class BackupServiceTests {
    @Test
    void createsAndListsAZipBackupJob() throws Exception {
        Path root = Files.createTempDirectory("majo-backup-");
        try {
            MajoWorkingDirectory working = new MajoWorkingDirectory(root.toString());
            AgentStore agents = new AgentStore(working, new JsonFileStore(new ObjectMapper()));
            agents.loadConfig();
            BackupService service = new BackupService(working, agents, new ObjectMapper());
            Map<String,Object> job = service.start(Map.of("name", "Test backup", "scope", Map.of("include_agents", false, "include_global_config", true)));
            Map<String,Object> current = job;
            for (int i = 0; i < 100 && "running".equals(current.get("status")); i++) { Thread.sleep(10); current = service.job(String.valueOf(job.get("job_id"))); }
            assertThat(current.get("status")).isEqualTo("completed");
            assertThat(service.list()).singleElement().extracting(item -> item.get("name")).isEqualTo("Test backup");
            String backupId = String.valueOf(current.get("backup_id"));
            assertThat(service.restore(backupId, Map.of(
                "include_agents", false,
                "include_global_config", false,
                "include_secrets", false,
                "include_skill_pool", false
            )).get("ok")).isEqualTo(true);

            Path unsigned = root.resolve("legacy.zip");
            Map<String, Object> legacy = new LinkedHashMap<>();
            legacy.put("id", "legacy-import");
            legacy.put("name", "Legacy import");
            legacy.put("description", "");
            legacy.put("created_at", "2026-01-01T00:00:00Z");
            legacy.put("version", "1");
            legacy.put("scope", Map.of("include_agents", false, "include_global_config", false,
                "include_secrets", false, "include_skill_pool", false));
            legacy.put("agent_count", 0);
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(unsigned))) {
                output.putNextEntry(new ZipEntry("meta.json"));
                output.write(new ObjectMapper().writeValueAsBytes(legacy));
                output.closeEntry();
            }
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.importZip(unsigned, false, null));
            assertThat(service.importZip(unsigned, false, "legacy").get("id"))
                .isEqualTo("legacy-import");
        } finally { try (var files = Files.walk(root)) { for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
}
