package io.github.ddxpromax.webcoder.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A small, pooled LSP JSON-RPC client. It follows Majo's deliberately
 * request-response-only model: a fresh didOpen is sent before each document
 * request, so edits made by shell tools are immediately visible to servers.
 */
public final class LspClient implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(LspClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long REQUEST_TIMEOUT_SECONDS = 15;
    private static final long INITIALIZE_TIMEOUT_SECONDS = 30;
    private static final Map<String, LspClient> POOL = new ConcurrentHashMap<>();

    public record ServerSpec(String language, List<String> extensions, List<List<String>> candidates) { }
    static final List<ServerSpec> SERVER_SPECS = List.of(
        new ServerSpec("typescript", List.of(".ts", ".tsx", ".mts", ".cts"), List.of(List.of("typescript-language-server", "--stdio"))),
        new ServerSpec("javascript", List.of(".js", ".jsx", ".mjs", ".cjs"), List.of(List.of("typescript-language-server", "--stdio"))),
        new ServerSpec("python", List.of(".py", ".pyi"), List.of(List.of("pyright-langserver", "--stdio"), List.of("pylsp")))
    );

    private final Path workspace;
    private final String language;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicLong documentVersion = new AtomicLong();
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile Process process;
    private volatile OutputStream output;
    private volatile InputStream input;
    private volatile String failure;

    private LspClient(Path workspace, String language) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.language = language;
    }

    public static synchronized LspClient get(Path workspace, String language) throws LspException {
        String key = workspace.toAbsolutePath().normalize() + "\u0000" + language;
        LspClient current = POOL.get(key);
        if (current != null && current.isAlive()) return current;
        if (current != null) current.close();
        LspClient client = new LspClient(workspace, language);
        client.start();
        POOL.put(key, client);
        return client;
    }

    public static synchronized void shutdownAll() {
        POOL.values().forEach(LspClient::close);
        POOL.clear();
    }

    public static List<String> discover(String language) {
        return SERVER_SPECS.stream().filter(spec -> spec.language().equals(language)).findFirst()
            .map(spec -> spec.candidates().stream().map(candidate -> {
                String binary = resolveExecutable(candidate.getFirst());
                if (binary == null) return null;
                List<String> argv = new ArrayList<>(candidate); argv.set(0, binary); return argv;
            }).filter(candidate -> candidate != null).findFirst().orElse(null)).orElse(null);
    }

    static String resolveExecutable(String binary) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return null;
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> names = windows ? List.of(binary + ".exe", binary + ".cmd", binary + ".bat", binary) : List.of(binary);
        for (String directory : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (directory.isBlank()) continue;
            for (String name : names) {
                Path candidate = Path.of(directory, name);
                if (Files.isExecutable(candidate)) return candidate.toString();
            }
        }
        return null;
    }

    public static List<String> availableLanguages() {
        return SERVER_SPECS.stream().map(ServerSpec::language).filter(language -> discover(language) != null).toList();
    }

    public static String languageFor(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase();
        return SERVER_SPECS.stream().filter(spec -> spec.extensions().stream().anyMatch(lower::endsWith)).map(ServerSpec::language).findFirst().orElse(null);
    }

    private void start() throws LspException {
        List<String> argv = discover(language);
        if (argv == null) throw new LspException("No language server is installed for " + language + ".");
        try {
            ProcessBuilder builder = new ProcessBuilder(argv).directory(workspace.toFile());
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start(); output = process.getOutputStream(); input = process.getInputStream();
        } catch (IOException exception) {
            throw new LspException("Could not start the " + language + " language server: " + exception.getMessage());
        }
        Thread reader = new Thread(this::readLoop, "majo-lsp-" + language);
        reader.setDaemon(true); reader.start();
        ObjectNode initialize = JSON.createObjectNode();
        initialize.put("processId", ProcessHandle.current().pid());
        initialize.put("rootUri", workspace.toUri().toString());
        initialize.putObject("capabilities").putObject("textDocument").putObject("synchronization");
        try {
            request("initialize", initialize, INITIALIZE_TIMEOUT_SECONDS);
            notify("initialized", JSON.createObjectNode());
        } catch (LspException exception) {
            close(); throw new LspException("LSP initialization failed: " + exception.getMessage());
        }
    }

    public boolean isAlive() { return process != null && process.isAlive() && failure == null; }

    public synchronized JsonNode operate(String operation, String relativePath, Integer line, Integer character, String query) throws LspException {
        if (!isAlive()) throw new LspException("The language server is not running.");
        try {
            return switch (operation) {
                case "goToDefinition", "findReferences", "hover", "goToImplementation" -> {
                    requirePosition(relativePath, line, character);
                    yield request(lspMethod(operation), position(didOpen(relativePath), line, character, operation), REQUEST_TIMEOUT_SECONDS);
                }
                case "documentSymbol" -> {
                    if (blank(relativePath)) throw new LspException("documentSymbol requires file_path.");
                    ObjectNode params = JSON.createObjectNode(); params.putObject("textDocument").put("uri", didOpen(relativePath));
                    yield request("textDocument/documentSymbol", params, REQUEST_TIMEOUT_SECONDS);
                }
                case "workspaceSymbol" -> {
                    if (blank(query)) throw new LspException("workspaceSymbol requires query.");
                    ObjectNode params = JSON.createObjectNode(); params.put("query", query);
                    yield request("workspace/symbol", params, REQUEST_TIMEOUT_SECONDS);
                }
                default -> throw new LspException("Unknown LSP operation '" + operation + "'.");
            };
        } catch (IOException exception) {
            throw new LspException("Could not read source file: " + exception.getMessage());
        }
    }

    private static String lspMethod(String operation) {
        return switch (operation) {
            case "goToDefinition" -> "textDocument/definition";
            case "findReferences" -> "textDocument/references";
            case "goToImplementation" -> "textDocument/implementation";
            default -> "textDocument/hover";
        };
    }

    private static void requirePosition(String path, Integer line, Integer character) throws LspException {
        if (blank(path) || line == null || character == null || line < 1 || character < 1) throw new LspException("This operation requires file_path, line, and character (all 1-based).");
    }

    private ObjectNode position(String uri, int line, int character, String operation) {
        ObjectNode params = JSON.createObjectNode();
        params.putObject("textDocument").put("uri", uri);
        ObjectNode point = params.putObject("position"); point.put("line", line - 1); point.put("character", character - 1);
        if ("findReferences".equals(operation)) params.putObject("context").put("includeDeclaration", true);
        return params;
    }

    private String didOpen(String relativePath) throws IOException, LspException {
        Path file = workspace.resolve(relativePath.strip()).normalize();
        if (!file.startsWith(workspace) || !Files.isRegularFile(file)) throw new IOException("File does not exist in the workspace: " + relativePath);
        ObjectNode params = JSON.createObjectNode();
        ObjectNode document = params.putObject("textDocument");
        document.put("uri", file.toUri().toString()); document.put("languageId", language);
        document.put("version", documentVersion.incrementAndGet());
        document.put("text", Files.readString(file, StandardCharsets.UTF_8));
        notify("textDocument/didOpen", params);
        return file.toUri().toString();
    }

    private void notify(String method, ObjectNode params) throws LspException {
        ObjectNode request = JSON.createObjectNode(); request.put("jsonrpc", "2.0"); request.put("method", method); request.set("params", params); write(request);
    }

    private JsonNode request(String method, ObjectNode params, long timeoutSeconds) throws LspException {
        int id = nextId.getAndIncrement();
        ObjectNode request = JSON.createObjectNode(); request.put("jsonrpc", "2.0"); request.put("id", id); request.put("method", method); request.set("params", params);
        CompletableFuture<JsonNode> response = new CompletableFuture<>(); pending.put(id, response);
        try {
            write(request);
            JsonNode envelope = response.get(timeoutSeconds, TimeUnit.SECONDS);
            if (envelope.has("error")) throw new LspException("LSP " + method + " failed: " + envelope.path("error").path("message").asText("unknown error"));
            return envelope.path("result");
        } catch (LspException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LspException("LSP " + method + " timed out or failed: " + exception.getMessage());
        } finally { pending.remove(id); }
    }

    private synchronized void write(ObjectNode frame) throws LspException {
        try {
            byte[] body = JSON.writeValueAsBytes(frame);
            output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(body); output.flush();
        } catch (IOException exception) { throw new LspException("Could not write to language server: " + exception.getMessage()); }
    }

    private void readLoop() {
        try {
            while (process != null && process.isAlive()) {
                String header = readFrameHeader(input); int length = parseContentLength(header);
                if (length < 0) break;
                byte[] body = input.readNBytes(length); if (body.length != length) break;
                JsonNode frame = JSON.readTree(body);
                if (frame.path("id").canConvertToInt() && (frame.has("result") || frame.has("error"))) {
                    CompletableFuture<JsonNode> future = pending.get(frame.path("id").asInt());
                    if (future != null) future.complete(frame);
                }
            }
        } catch (Exception exception) { log.debug("LSP reader stopped: {}", exception.getMessage()); }
        failure = "language server stopped";
        pending.values().forEach(future -> future.cancel(false)); pending.clear();
    }

    static String readFrameHeader(InputStream input) throws IOException {
        StringBuilder header = new StringBuilder(); int previous = -1;
        while (true) {
            int next = input.read(); if (next < 0) return null;
            header.append((char) next);
            if (previous == '\n' && next == '\n') return header.toString();
            if (header.length() >= 4 && header.substring(header.length() - 4).equals("\r\n\r\n")) return header.toString();
            if (header.length() > 16 * 1024) return null;
            previous = next;
        }
    }

    static int parseContentLength(String header) {
        if (header == null) return -1;
        for (String line : header.split("\\r?\\n")) if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
            try { return Integer.parseInt(line.substring(15).trim()); } catch (NumberFormatException ignored) { return -1; }
        }
        return -1;
    }

    @Override public synchronized void close() {
        Process active = process; process = null;
        if (active != null) { try { active.descendants().forEach(ProcessHandle::destroyForcibly); } catch (Exception ignored) { } active.destroyForcibly(); }
        pending.values().forEach(future -> future.cancel(false)); pending.clear();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    public static final class LspException extends Exception { public LspException(String message) { super(message); } }
}
