package io.github.ddxpromax.webcoder.message;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.AgentStore;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import io.github.ddxpromax.webcoder.inbox.InboxService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MessagesControllerTests {
    @Test void sendsConsoleMessagesAsInboxEvents() throws Exception {Path root=Files.createTempDirectory("majo-message-");try{AgentStore agents=new AgentStore(new MajoWorkingDirectory(root.toString()),new JsonFileStore(new ObjectMapper()));InboxService inbox=org.mockito.Mockito.mock(InboxService.class);var mvc=MockMvcBuilders.standaloneSetup(new MessagesController(inbox,agents)).build();mvc.perform(post("/api/messages/send").header("X-Agent-Id","default").contentType("application/json").content("{\"channel\":\"console\",\"text\":\"Hello\",\"target_session\":\"s1\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));verify(inbox).appendEvent(eq("default"),eq("console"),eq("s1"),eq("message"),eq("success"),eq("Hello"),eq("Hello"),eq("info"),any());}finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}}
}
