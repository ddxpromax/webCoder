package io.github.ddxpromax.webcoder.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Path;
import java.util.List;

/** Majo's language-server tool: positions are intentionally 1-based for agents. */
public final class LspTool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_OUTPUT_CHARS = 80_000;
    private final Path workspace;
    private final String agentId;
    private final CodingModeService codingMode;

    public LspTool(Path workspace, String agentId, CodingModeService codingMode) {
        this.workspace = workspace; this.agentId = agentId; this.codingMode = codingMode;
    }

    @Tool(name = "lsp", description = "Use a language server for goToDefinition, findReferences, hover, goToImplementation, documentSymbol, or workspaceSymbol. Positions are 1-based.")
    public String lsp(
        @ToolParam(name = "operation", description = "LSP operation") String operation,
        @ToolParam(name = "file_path", description = "Relative file path for document operations", required = false) String filePath,
        @ToolParam(name = "line", description = "One-based line", required = false) Integer line,
        @ToolParam(name = "character", description = "One-based character", required = false) Integer character,
        @ToolParam(name = "query", description = "Symbol query for workspaceSymbol", required = false) String query
    ) {
        if (!codingMode.isEnabled(agentId)) return CodingModeService.disabledReply();
        if (operation == null || operation.isBlank()) return "operation is required.";
        String normalized = operation.strip();
        String language = filePath == null || filePath.isBlank()
            ? ("workspaceSymbol".equals(normalized) ? LspClient.availableLanguages().stream().findFirst().orElse(null) : null)
            : LspClient.languageFor(Path.of(filePath).getFileName().toString());
        if (language == null) return "No supported language server can be selected. Install typescript-language-server, pyright, or pylsp, or use search_code / ast_search instead.";
        if (LspClient.discover(language) == null) return "No " + language + " language server is installed. Install one or use search_code / ast_search instead.";
        try {
            String result = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(LspClient.get(workspace, language).operate(normalized, filePath, line, character, query));
            return result.length() <= MAX_OUTPUT_CHARS ? result : result.substring(0, MAX_OUTPUT_CHARS) + "\n...[result truncated]";
        } catch (LspClient.LspException exception) {
            return "LSP " + normalized + " failed: " + exception.getMessage() + " Use search_code / ast_search as a fallback.";
        } catch (Exception exception) {
            return "LSP " + normalized + " failed: " + exception.getMessage();
        }
    }

    static String languageFor(String fileName) { return LspClient.languageFor(fileName); }
    static List<String> advertisedLanguages() { return LspClient.availableLanguages(); }
}
