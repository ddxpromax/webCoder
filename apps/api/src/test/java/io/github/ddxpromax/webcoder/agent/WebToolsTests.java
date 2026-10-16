package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebToolsTests {
    @Test
    void fetchesReadableTextAndRejectsUnsupportedSchemes()
        throws Exception {
        HttpServer server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0), 0
        );
        server.createContext("/page", exchange -> {
            byte[] body = ("<html><head><title>Hidden</title></head>"
                + "<body><h1>Heading</h1><p>Useful &amp; clear.</p>"
                + "<script>alert('hidden')</script></body></html>")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                "Content-Type", "text/html"
            );
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            WebTools tools = new WebTools();
            String result = tools.webFetch(
                "http://127.0.0.1:"
                    + server.getAddress().getPort() + "/page"
            );
            assertThat(result).contains("Heading", "Useful & clear.")
                .doesNotContain("Hidden", "alert");
            assertThat(tools.webFetch("file:///etc/passwd"))
                .contains("Only HTTP and HTTPS");
            assertThat(tools.webSearch("", null))
                .contains("query must not be empty");
        } finally {
            server.stop(0);
        }
    }
}
