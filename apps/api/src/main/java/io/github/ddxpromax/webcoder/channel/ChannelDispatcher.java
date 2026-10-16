package io.github.ddxpromax.webcoder.channel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.github.ddxpromax.webcoder.access.AccessControlStore;
import io.github.ddxpromax.webcoder.agent.AgentStore;
import io.github.ddxpromax.webcoder.agent.HarnessAgentFactory;
import io.github.ddxpromax.webcoder.agent.MemoryCommandService;
import io.github.ddxpromax.webcoder.agent.StreamTextCollector;
import io.github.ddxpromax.webcoder.chat.ChatService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Routes Majo channel input through ACL, memory commands, and the agent loop. */
@Service
public class ChannelDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ChannelDispatcher.class);
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_REPLY_CHARS = 12_000;
    private static final String SYSTEM_PROMPT = "You are a professional coding assistant communicating through a messaging channel. Answer clearly and concisely.";
    private final HarnessAgentFactory agents;
    private final AccessControlStore access;
    private final ChatService chats;
    private final MemoryCommandService memoryCommands;
    private final LastContactStore contacts;

    public ChannelDispatcher(HarnessAgentFactory agents, AccessControlStore access, ChatService chats,
                             MemoryCommandService memoryCommands, LastContactStore contacts) {
        this.agents = agents;
        this.access = access;
        this.chats = chats;
        this.memoryCommands = memoryCommands;
        this.contacts = contacts;
    }

    public void dispatch(Map<String, Object> config, ChannelMessage message,
                         BiConsumer<String, String> reply) {
        String denied = accessCheck(config, message);
        if (denied != null) {
            if (!denied.isBlank()) reply.accept(message.replyTo(), denied);
            return;
        }
        if (message.text().isBlank()) return;
        contacts.record(message.channelId(), message.replyTo(), message.senderId());
        try {
            String text = message.text().strip();
            String response = (text.equalsIgnoreCase("/memory") || text.toLowerCase().startsWith("/memory "))
                ? runMemoryCommand(message, text) : runAgent(message);
            reply.accept(message.replyTo(), response);
        } catch (Exception exception) {
            log.warn("Channel {} failed to handle inbound message", message.channelId(), exception);
            reply.accept(message.replyTo(), "Unable to process the message: " + safeMessage(exception));
        }
    }

    String accessCheck(Map<String, Object> config, ChannelMessage message) {
        String channel = message.channelId();
        String sender = message.senderId();
        if (access.isBlacklisted(channel, sender)) return "";
        if (access.hasData(channel) && !access.isWhitelisted(channel, sender)) {
            access.addPending(channel, sender, message.text(), message.senderName());
            return "Your access request is awaiting administrator approval.";
        }
        String policy = text(config, message.groupId() == null ? "dm_policy" : "group_policy", "open");
        if (!"allowlist".equalsIgnoreCase(policy)) return null;
        Object raw = config == null ? null : config.get("allow_from");
        boolean allowed = raw instanceof List<?> entries && entries.stream()
            .anyMatch(entry -> sender.equals(String.valueOf(entry)));
        return allowed ? null : "You are not permitted to use this service.";
    }

    private String runMemoryCommand(ChannelMessage message, String command) {
        String args = command.length() == "/memory".length() ? "" : command.substring("/memory".length());
        String response = memoryCommands.execute(AgentStore.DEFAULT_AGENT_ID, args);
        saveExchange(message, command, response, "");
        return response;
    }

    private String runAgent(ChannelMessage message) {
        String agentId = AgentStore.DEFAULT_AGENT_ID;
        String sessionId = "ch-" + message.channelId() + "-" + Integer.toHexString(message.identity().hashCode());
        String chatId = chats.getOrCreateBySession(agentId, sessionId, message.text()).getId();
        chats.setStatus(chatId, "running");
        OpenAIChatModel model = agents.modelFor(agentId, HarnessAgentFactory.ModelFallback.NONE);
        if (model == null) {
            chats.setStatus(chatId, "idle");
            return "No model is configured. Configure a model in the console first.";
        }
        HarnessAgent agent = agents.builder(agentId, SYSTEM_PROMPT, agents.workspaceFor(agentId), model).build();
        StreamTextCollector collected = new StreamTextCollector();
        try {
            RuntimeContext context = RuntimeContext.builder().sessionId(sessionId).userId(message.senderId()).build();
            agent.streamEvents(new UserMessage(message.text()), context).doOnNext(collected::accept).blockLast(RUN_TIMEOUT);
        } finally {
            agents.close(agent);
            chats.setStatus(chatId, "idle");
        }
        String response = collected.text().trim();
        if (response.isBlank()) response = "(No response was generated.)";
        if (response.length() > MAX_REPLY_CHARS) response = response.substring(0, MAX_REPLY_CHARS) + "\n...[response truncated]";
        saveExchange(message, message.text(), response, collected.thinking());
        return response;
    }

    private void saveExchange(ChannelMessage message, String prompt, String response, String thinking) {
        String sessionId = "ch-" + message.channelId() + "-" + Integer.toHexString(message.identity().hashCode());
        String chatId = chats.getOrCreateBySession(AgentStore.DEFAULT_AGENT_ID, sessionId, prompt).getId();
        Map<String, String> user = new LinkedHashMap<>(); user.put("role", "user"); user.put("content", prompt);
        Map<String, String> assistant = new LinkedHashMap<>(); assistant.put("role", "assistant"); assistant.put("content", response);
        if (thinking != null && !thinking.isBlank()) assistant.put("thinking", thinking);
        chats.saveMessages(chatId, List.of(user, assistant));
    }

    private static String text(Map<String, Object> config, String key, String fallback) {
        Object value = config == null ? null : config.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? "unexpected error" : exception.getMessage();
    }
}
