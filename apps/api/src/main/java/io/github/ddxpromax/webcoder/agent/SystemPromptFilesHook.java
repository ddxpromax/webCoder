package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** Hot-loads the same workspace prompt files as Majo on PRE_CALL. */
public class SystemPromptFilesHook implements Hook {
    private static final Logger log = LoggerFactory.getLogger(
        SystemPromptFilesHook.class
    );
    private static final List<String> PROMPT_FILES = List.of(
        "AGENTS.md", "SOUL.md", "PROFILE.md"
    );
    private static final int MAX_PROMPT_FILE_CHARS = 64 * 1024;
    private static final String MARKER = "<workspace-prompt-files>";

    private final Path workspace;

    public SystemPromptFilesHook(Path workspace) {
        this.workspace = workspace;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreCallEvent call)) {
            return Mono.just(event);
        }
        String existing = call.getSystemMessage() == null
            ? "" : call.getSystemMessage().getTextContent();
        if (existing.contains(MARKER)) {
            return Mono.just(event);
        }
        StringBuilder additions = new StringBuilder();
        for (String name : PROMPT_FILES) {
            Path file = workspace.resolve(name);
            if (!Files.isRegularFile(file)
                || Files.isSymbolicLink(file)) {
                continue;
            }
            try {
                String content = Files.readString(
                    file, StandardCharsets.UTF_8
                );
                if (content.isBlank()) {
                    continue;
                }
                if (content.length() > MAX_PROMPT_FILE_CHARS) {
                    content = content.substring(
                        0, MAX_PROMPT_FILE_CHARS
                    ) + "\n[truncated]";
                }
                additions.append("\n\n# ").append(name)
                    .append("\n").append(content.strip());
            } catch (IOException exception) {
                log.debug("Could not read workspace prompt file {}",
                    name, exception);
            }
        }
        if (!additions.isEmpty()) {
            call.appendSystemContent(
                "\n\n" + MARKER + additions
            );
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 4;
    }
}
