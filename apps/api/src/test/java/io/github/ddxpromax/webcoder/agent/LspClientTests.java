package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LspClientTests {
    @Test
    void parsesLanguageServerHeadersAndUsesPlatformPathSeparators() throws Exception {
        String header = "Content-Type: application/vscode-jsonrpc; charset=utf-8\r\nContent-Length: 42\r\n\r\n";

        assertThat(LspClient.readFrameHeader(new ByteArrayInputStream(
            header.getBytes(StandardCharsets.US_ASCII)
        ))).isEqualTo(header);
        assertThat(LspClient.parseContentLength(header)).isEqualTo(42);
        assertThat(LspClient.parseContentLength("Content-Length: bad\r\n\r\n")).isEqualTo(-1);
    }

    @Test
    void mapsMajoSupportedExtensionsToLanguageServers() {
        assertThat(LspTool.languageFor("Component.tsx")).isEqualTo("typescript");
        assertThat(LspTool.languageFor("worker.mjs")).isEqualTo("javascript");
        assertThat(LspTool.languageFor("service.pyi")).isEqualTo("python");
        assertThat(LspTool.languageFor("README.md")).isNull();
    }
}
