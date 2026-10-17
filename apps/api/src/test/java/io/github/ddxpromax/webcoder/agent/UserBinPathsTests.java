package io.github.ddxpromax.webcoder.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Majo user-bin PATH behavior for shell, ACP, and language-server tools. */
class UserBinPathsTests {
    @TempDir
    Path home;

    @Test
    void prependsAnExistingUserBinWithoutDuplicatingIt() throws Exception {
        Path bin = Files.createDirectories(home.resolve(".local/bin"));
        Map<String, String> environment = new HashMap<>(
            Map.of("PATH", "/usr/bin")
        );

        UserBinPaths.applyTo(environment, home.toString(), false);

        assertEquals(bin.toString(), environment.get("PATH")
            .split(java.io.File.pathSeparator)[0]);
        assertTrue(environment.get("PATH").contains("/usr/bin"));
    }

    @Test
    void leavesMissingCandidateDirectoriesOutOfPath() {
        Map<String, String> environment = new HashMap<>(
            Map.of("PATH", "/usr/bin")
        );

        UserBinPaths.applyTo(environment, home.toString(), false);

        assertEquals("/usr/bin", environment.get("PATH"));
    }

    @Test
    void demotesGitForWindowsMsysUtilities() {
        Map<String, String> environment = new HashMap<>(Map.of("PATH",
            "C:\\Program Files\\Git\\usr\\bin;C:\\Windows\\System32"));

        UserBinPaths.demoteMsysUsrBin(environment, "PATH", ";");

        assertTrue(environment.get("PATH").startsWith(
            "C:\\Windows\\System32;"));
        assertTrue(environment.get("PATH").endsWith(
            "C:\\Program Files\\Git\\usr\\bin"));
        assertFalse(UserBinPaths.isMsysUsrBin("C:\\msys64\\usr\\bin"));
    }
}
