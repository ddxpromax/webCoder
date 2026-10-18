package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class QwenEventsTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void emitsMajoCompatibleToolCallProgress() throws Exception {
        QwenEvents events = new QwenEvents(mapper);
        var calling = mapper.readTree(events.toolCall(
            "call-1", "read_file", "{\"path\":\"README.md\"}", "calling"
        ));
        var done = mapper.readTree(events.toolCall(
            "call-1", "read_file", "{\"path\":\"README.md\"}", "done"
        ));

        assertThat(calling.path("object").asText()).isEqualTo("tool_call");
        assertThat(calling.path("id").asText()).isEqualTo("call-1");
        assertThat(calling.path("name").asText()).isEqualTo("read_file");
        assertThat(calling.path("status").asText()).isEqualTo("calling");
        assertThat(done.path("status").asText()).isEqualTo("done");
        assertThat(done.path("sequence_number").asLong())
            .isGreaterThan(calling.path("sequence_number").asLong());
    }

    @Test
    void emitsToolResultOnlyWhenTheToolProvidedOne() throws Exception {
        QwenEvents events = new QwenEvents(mapper);
        var completed = mapper.readTree(events.toolCall(
            "call-1", "read_file", "{\"path\":\"README.md\"}",
            "# Readme", "done"
        ));

        assertThat(completed.path("result").asText()).isEqualTo("# Readme");
        assertThat(completed.path("status").asText()).isEqualTo("done");
    }
}
