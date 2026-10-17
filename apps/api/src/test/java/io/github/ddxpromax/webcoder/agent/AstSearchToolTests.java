package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AstSearchToolTests {
    @Test void createsMajoKindSearchRule() { assertThat(AstSearchTool.rule("method_declaration", "Controller", "java")).contains("kind: method_declaration", "regex: 'Controller'", "language: java"); }

    @Test void rendersAstGrepCompactJsonWithOneBasedLocations() throws Exception {
        var tool = new AstSearchTool(java.nio.file.Path.of("."), "default", null);
        assertThat(tool.render("[{\"file\":\"src/App.java\",\"range\":{\"start\":{\"line\":2,\"column\":4}},\"text\":\"class App {}\"}]", 20)).contains("\"line\":3", "\"column\":5", "src/App.java");
    }
}
