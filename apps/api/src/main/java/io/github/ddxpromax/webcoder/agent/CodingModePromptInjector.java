package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** Adds Majo's coding discipline at the start of each enabled turn. */
public class CodingModePromptInjector implements Hook {
    private static final Logger log = LoggerFactory.getLogger(
        CodingModePromptInjector.class
    );
    private static final String MARKER = "<coding-mode-discipline>";

    private final CodingModeService codingMode;
    private final String agentId;
    private final Path workspace;

    public CodingModePromptInjector(
        CodingModeService codingMode,
        String agentId,
        Path workspace
    ) {
        this.codingMode = codingMode;
        this.agentId = agentId;
        this.workspace = workspace;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreCallEvent call)
            || !codingMode.isEnabled(agentId)) {
            return Mono.just(event);
        }
        try {
            String existing = call.getSystemMessage() == null
                ? "" : call.getSystemMessage().getTextContent();
            if (!existing.contains(MARKER)) {
                call.appendSystemContent(
                    "\n\n" + MARKER + "\n" + buildPrompt(workspace)
                );
            }
        } catch (RuntimeException exception) {
            log.debug("Coding Mode prompt injection skipped",
                exception);
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 5;
    }

    static String buildPrompt(Path workspace) {
        return """
            # Coding Mode

            You are operating in Coding Mode on the project at `%s`. Sibling
            directories are unrelated repositories — never go hunting for another
            project.

            ## Task tracking
            - For any non-trivial task, pick an UPPERCASE_SNAKE_CASE slug (<= 24
              chars, fallback CODING) and create/overwrite `%s` at the
              project root with a `- [ ]` checklist before starting work.
            - Flip `- [ ]` to `- [x]` immediately when a step is fully done —
              never batch-update checkboxes at the end.
            - When creating a new *_TODO.md, append `*_TODO.md` to `.gitignore`
              (session-local notes are not committed).

            ## Code references
            - Reference code as `relative/path/File.java:42` (ranges `:42-58`),
              always project-relative.

            ## Tool preferences
            - "Where is X defined / who calls Y / what symbols does Z have" →
              use the `lsp` tool first; it will tell you which languages have a
              language server. No server for that language → fall back to
              `search_code`.
            - Structural pattern queries (e.g. "all functions taking Request and
              returning Response") → `ast_search` first. It is strictly
              read-only: to rewrite, read each match then apply targeted
              `edit_file` calls.
            - grep (`search_code`) is the last resort for plain text search.

            ## Working discipline
            - Resolve relative paths against the project directory.
            - Read before you write; prefer targeted `edit_file` over whole-file
              rewrites; change only what the task requires.
            - Give a short summary after each batch of changes."""
            .formatted(
                workspace.toString().replace('\\', '/'),
                "{SLUG}_TODO.md"
            );
    }
}
