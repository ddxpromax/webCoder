package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class BrowserUseToolTests {
    @Test
    void extractsOnlySafeHttpLinksAndResolvesRelativeUrls() {
        var links = BrowserUseTool.links("""
            <a href="guide">Guide</a><a href="#part">Fragment</a>
            <a href="javascript:alert(1)">Unsafe</a><a href="https://example.test/a">Remote</a>
            """, URI.create("https://docs.example.test/start/"));

        assertThat(links).extracting(BrowserUseTool.Link::url)
            .containsExactly("https://docs.example.test/start/guide", "https://example.test/a");
        assertThat(links).extracting(BrowserUseTool.Link::text)
            .containsExactly("Guide", "Remote");
    }

    @Test
    void rejectsClickUntilAPageIsOpen() {
        assertThat(new BrowserUseTool().browserUse("1", "click"))
            .isEqualTo("No page is open. Use browser_use with action open first.");
    }
}
