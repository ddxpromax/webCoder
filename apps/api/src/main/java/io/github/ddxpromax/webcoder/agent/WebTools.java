package io.github.ddxpromax.webcoder.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Majo's URL fetcher and Tavily-backed search tools. */
public class WebTools {
    private static final String TAVILY_URL =
        "https://api.tavily.com/search";
    private static final int MAX_BODY = 300_000;
    private static final int MAX_TEXT = 20_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    @Tool(
        name = "web_fetch",
        description = "Fetch a URL and extract readable text",
        readOnly = true
    )
    public String webFetch(
        @ToolParam(name = "url", description = "HTTP or HTTPS URL")
        String url
    ) {
        if (url == null || url.isBlank()) {
            return "url must not be empty";
        }
        if (!url.startsWith("http://")
            && !url.startsWith("https://")) {
            return "Only HTTP and HTTPS URLs are supported.";
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Majo/0.4 coding agent")
                .header("Accept",
                    "text/html,text/plain,application/json,*/*")
                .GET().build();
            HttpResponse<java.io.InputStream> response = HTTP.send(
                request, HttpResponse.BodyHandlers.ofInputStream()
            );
            int status = response.statusCode();
            String type = response.headers()
                .firstValue("Content-Type").orElse("");
            try (var input = response.body()) {
                if (status >= 400) {
                    return "Fetch failed: HTTP " + status
                        + " (" + url + ")";
                }
                byte[] bytes = input.readNBytes(MAX_BODY + 1);
                String body = new String(bytes,
                    StandardCharsets.UTF_8);
                if (type.contains("application/json")) {
                    return "[HTTP " + status + "] " + url
                        + "\n" + truncate(body.trim());
                }
                if (!type.contains("html") && !type.contains("text")) {
                    return "[HTTP " + status + "] " + url
                        + "\n[Binary content (" + type
                        + ") omitted]";
                }
                return "[HTTP " + status + "] " + url
                    + "\n" + truncate(htmlToText(body));
            }
        } catch (Exception exception) {
            return "Fetch failed: " + exception.getMessage();
        }
    }

    @Tool(
        name = "web_search",
        description = "Search the web using Tavily when TAVILY_API_KEY is configured",
        readOnly = true
    )
    public String webSearch(
        @ToolParam(name = "query", description = "Search terms")
        String query,
        @ToolParam(
            name = "max_results",
            description = "Maximum results (default 5)",
            required = false
        ) Integer maxResults
    ) {
        if (query == null || query.isBlank()) {
            return "query must not be empty";
        }
        String apiKey = System.getenv("TAVILY_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return "web_search is unavailable because TAVILY_API_KEY is not configured. "
                + "Use web_fetch for a known URL.";
        }
        try {
            int limit = maxResults == null ? 5
                : Math.min(Math.max(maxResults, 1), 10);
            String payload = MAPPER.writeValueAsString(Map.of(
                "api_key", apiKey,
                "query", query,
                "max_results", limit,
                "search_depth", "basic"
            ));
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create(TAVILY_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
            HttpResponse<String> response = HTTP.send(
                request, HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() >= 400) {
                return "Search failed: Tavily HTTP "
                    + response.statusCode();
            }
            JsonNode results = MAPPER.readTree(
                response.body()).path("results");
            if (!results.isArray() || results.isEmpty()) {
                return "No results found for '" + query + "'.";
            }
            StringBuilder output = new StringBuilder(
                "Results for '" + query + "':\n"
            );
            int index = 1;
            for (JsonNode result : results) {
                if (index > limit) {
                    break;
                }
                output.append('[').append(index++)
                    .append("] ")
                    .append(result.path("title")
                        .asText("(untitled)"))
                    .append('\n')
                    .append(result.path("url").asText(""))
                    .append('\n')
                    .append(result.path("content").asText(""))
                    .append("\n\n");
            }
            return output.toString().trim();
        } catch (Exception exception) {
            return "Search failed: " + exception.getMessage();
        }
    }

    private static String truncate(String value) {
        return value.length() <= MAX_TEXT ? value
            : value.substring(0, MAX_TEXT)
                + "\n...[content truncated]";
    }

    static String htmlToText(String html) {
        String text = html.replaceAll(
            "(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>",
            " "
        );
        text = text.replaceAll("(?s)<!--.*?-->", " ");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll(
            "(?i)<(p|div|h[1-6]|li|tr|pre|blockquote|section|article)[^>]*>",
            "\n"
        );
        text = text.replaceAll(
            "(?i)</(p|div|h[1-6]|li|tr|pre|blockquote|section|article)>",
            "\n"
        );
        text = text.replaceAll("(?s)<[^>]+>", " ");
        text = text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'");
        text = text.replaceAll("[ \\t]+", " ");
        text = text.replaceAll(
            "(?m)^[ \\t]+|[ \\t]+$", ""
        );
        return text.replaceAll("\\n{3,}", "\n\n").trim();
    }
}
