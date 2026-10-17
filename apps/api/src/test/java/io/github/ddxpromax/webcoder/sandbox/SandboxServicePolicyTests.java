package io.github.ddxpromax.webcoder.sandbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Majo AppContainer grant policy: denies constrain grants by construction. */
class SandboxServicePolicyTests {
    @TempDir
    Path root;

    @Test
    void grantIsUnsafeWhenDenyIsBelowIt() {
        Path tools = root.resolve("tools");
        assertTrue(SandboxService.overlapsDeny(tools,
            List.of(tools.resolve("secrets").toString())));
    }

    @Test
    void grantIsUnsafeWhenDenyContainsIt() {
        Path tools = root.resolve("tools");
        assertTrue(SandboxService.overlapsDeny(tools.resolve("bin"),
            List.of(tools.toString())));
    }

    @Test
    void unrelatedGrantIsAllowed() {
        assertFalse(SandboxService.overlapsDeny(root.resolve("tools"),
            List.of(root.resolve("other").toString())));
    }

    @Test
    void comparisonNormalizesCaseAndSeparators() {
        assertTrue(SandboxService.overlapsDeny(root.resolve("Tools"),
            List.of(root.resolve("TOOLS").resolve("secrets").toString())));
    }
}
