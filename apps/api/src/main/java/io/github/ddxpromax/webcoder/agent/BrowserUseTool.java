package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Majo's lightweight, session-oriented browser tool. It intentionally uses
 * normal HTTP rather than a headless browser, providing predictable open,
 * links, click and back actions for agents without a browser dependency.
 */
public class BrowserUseTool {
    private static final int MAX_TEXT = 12_000;
    private static final int MAX_LINKS = 30;
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build();
    private static final Pattern TITLE = Pattern.compile(
        "<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern LINK = Pattern.compile(
        "<a[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern TAGS = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final Deque<Page> history = new ArrayDeque<>();
    private Page current;

    @Tool(
        name = "browser_use",
        description = "Browse web pages with open, links, click, and back actions",
        readOnly = true
    )
    public synchronized String browserUse(
        @ToolParam(name = "url", description = "URL for open, or link number for click")
        String url,
        @ToolParam(name = "action", description = "open, links, back, or click (default open)")
        String action
    ) {
        String selected = action == null || action.isBlank()
            ? "open" : action.strip().toLowerCase();
        return switch (selected) {
            case "open" -> open(url, true);
            case "links" -> links();
            case "click" -> click(url);
            case "back" -> back();
            default -> "Unsupported browser action '" + selected
                + "'. Use open, links, click, or back.";
        };
    }

    private String open(String rawUrl, boolean retainHistory) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "A URL is required for browser open.";
        }
        String requested = rawUrl.strip();
        if (!requested.startsWith("http://") && !requested.startsWith("https://")) {
            requested = "https://" + requested;
        }
        try {
            URI uri = URI.create(requested);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Majo/0.4 coding agent")
                .header("Accept", "text/html,text/plain,*/*")
                .GET().build();
            HttpResponse<String> response = HTTP.send(
                request, HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() >= 400) {
                return "Could not open page: HTTP " + response.statusCode()
                    + " (" + requested + ")";
            }
            if (retainHistory && current != null) {
                history.addLast(current);
            }
            URI finalUri = response.uri();
            String html = response.body();
            current = new Page(
                finalUri.toString(), title(html), links(html, finalUri)
            );
            return render(html);
        } catch (Exception exception) {
            return "Could not open page: " + safeMessage(exception);
        }
    }

    private String links() {
        if (current == null) {
            return "No page is open. Use browser_use with action open first.";
        }
        if (current.links().isEmpty()) {
            return "Page: " + current.title() + " (" + current.url() + ")\nNo links found.";
        }
        StringBuilder result = new StringBuilder("Page: ")
            .append(current.title()).append(" (").append(current.url())
            .append(")\nLinks:\n");
        for (int index = 0; index < current.links().size(); index++) {
            Link link = current.links().get(index);
            result.append('[').append(index + 1).append("] ")
                .append(link.text()).append(" -> ").append(link.url()).append('\n');
        }
        return result.toString().stripTrailing();
    }

    private String click(String rawIndex) {
        if (current == null) {
            return "No page is open. Use browser_use with action open first.";
        }
        int index;
        try {
            index = Integer.parseInt(rawIndex == null ? "" : rawIndex.strip());
        } catch (NumberFormatException exception) {
            return "A numeric link number is required for browser click.";
        }
        if (index < 1 || index > current.links().size()) {
            return "Link number is out of range (1-" + current.links().size() + ").";
        }
        return open(current.links().get(index - 1).url(), true);
    }

    private String back() {
        if (history.isEmpty()) {
            return "There is no previous page in browser history.";
        }
        Page previous = history.removeLast();
        return open(previous.url(), false);
    }

    private String render(String html) {
        String text = WebTools.htmlToText(html);
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT) + "\n...[content truncated]";
        }
        StringBuilder result = new StringBuilder("Page: ")
            .append(current.title()).append("\nURL: ").append(current.url())
            .append("\n\n").append(text);
        if (!current.links().isEmpty()) {
            result.append("\n\n").append(current.links().size())
                .append(" links found. Use action links or click with a link number.");
        }
        return result.toString().stripTrailing();
    }

    static List<Link> links(String html, URI base) {
        List<Link> result = new ArrayList<>();
        Matcher matcher = LINK.matcher(html);
        while (matcher.find() && result.size() < MAX_LINKS) {
            String href = matcher.group(1).strip();
            if (href.isBlank() || href.startsWith("#")
                || href.regionMatches(true, 0, "javascript:", 0, 11)
                || href.regionMatches(true, 0, "data:", 0, 5)) {
                continue;
            }
            try {
                URI target = base.resolve(href);
                if (!"http".equalsIgnoreCase(target.getScheme())
                    && !"https".equalsIgnoreCase(target.getScheme())) {
                    continue;
                }
                String text = clean(matcher.group(2));
                result.add(new Link(
                    target.toString(), text.isBlank() ? target.toString()
                        : abbreviate(text, 60)
                ));
            } catch (IllegalArgumentException ignored) {
                // A malformed anchor cannot make the current page unusable.
            }
        }
        return List.copyOf(result);
    }

    private static String title(String html) {
        Matcher matcher = TITLE.matcher(html);
        return matcher.find() ? clean(matcher.group(1)) : "(untitled)";
    }

    private static String clean(String value) {
        return WHITESPACE.matcher(TAGS.matcher(value).replaceAll(" "))
            .replaceAll(" ").strip();
    }

    private static String abbreviate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName()
            : exception.getMessage();
    }

    record Link(String url, String text) {}
    private record Page(String url, String title, List<Link> links) {}
}
