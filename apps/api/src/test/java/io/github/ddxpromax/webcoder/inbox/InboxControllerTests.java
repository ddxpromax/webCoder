package io.github.ddxpromax.webcoder.inbox;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InboxControllerTests {
    @Test
    void readsMajoSnakeCaseEventIds() throws Exception {
        InboxService service = org.mockito.Mockito.mock(InboxService.class);
        when(service.markRead(anyList())).thenReturn(2);
        var mvc = MockMvcBuilders.standaloneSetup(new InboxController(service)).build();
        mvc.perform(post("/api/console/inbox/read").contentType(MediaType.APPLICATION_JSON)
                .content("{\"event_ids\":[\"a\",\"b\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(2));
        verify(service).markRead(List.of("a", "b"));
    }

    @Test
    void scopedListUsesPathAgentWhenTheQueryOmitsIt() throws Exception {
        InboxService service = org.mockito.Mockito.mock(InboxService.class);
        when(service.listEvents(50, 0, null, null, "reviewer", true)).thenReturn(List.of(Map.of("id", "event-1")));
        var mvc = MockMvcBuilders.standaloneSetup(new InboxController(service)).build();
        mvc.perform(get("/api/agents/reviewer/console/inbox/events").param("unread_only", "true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.events[0].id").value("event-1"));
    }
}
