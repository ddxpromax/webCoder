package io.github.ddxpromax.webcoder.agent;

import io.github.ddxpromax.webcoder.chat.ChatEntity;
import io.github.ddxpromax.webcoder.chat.ChatService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Majo-compatible operational conversation statistics. */
@RestController
@RequestMapping("/api")
public class AgentStatsController {
    private final ChatService chats;
    private final TokenUsageService tokens;
    public AgentStatsController(ChatService chats, TokenUsageService tokens) { this.chats = chats; this.tokens = tokens; }

    @GetMapping("/agent-stats")
    public Map<String, Object> stats(@RequestParam(required = false) String start_date, @RequestParam(required = false) String end_date) {
        LocalDate end = parse(end_date, LocalDate.now()), start = parse(start_date, end.minusDays(30)); if (start.isAfter(end)) { LocalDate swap = start; start = end; end = swap; }
        Map<String, Map<String, Object>> days = new LinkedHashMap<>(); for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) days.put(date.toString(), day(date));
        Map<String, Map<String, Object>> channels = new LinkedHashMap<>(); Set<String> sessions = new HashSet<>(); int users = 0, assistants = 0, tools = 0;
        for (ChatEntity chat : chats.list()) {
            String channel = chat.getChannel() == null || chat.getChannel().isBlank() ? "console" : chat.getChannel(); Map<String, Object> channelStat = channels.computeIfAbsent(channel, key -> channel(key));
            LocalDate created = chat.getCreatedAt() == null ? null : chat.getCreatedAt().toLocalDate(); if (created != null && days.containsKey(created.toString())) increment(days.get(created.toString()), "chats");
            for (var message : chats.getMessages(chat.getId())) { if (message.getCreatedAt() == null) continue; Map<String, Object> daily = days.get(message.getCreatedAt().toLocalDate().toString()); if (daily == null) continue; String role = message.getRole(); if ("user".equals(role)) { increment(daily, "user_messages"); increment(channelStat, "user_messages"); users++; sessions.add(chat.getId()); } else if ("assistant".equals(role)) { increment(daily, "assistant_messages"); increment(channelStat, "assistant_messages"); assistants++; } if (message.getToolCalls() != null && !message.getToolCalls().isBlank()) { increment(daily, "tool_calls"); tools++; } }
            increment(channelStat, "session_count");
        }
        for (Map<String, Object> item : days.values()) item.put("total_messages", number(item, "user_messages") + number(item, "assistant_messages"));
        for (Map<String, Object> item : channels.values()) item.put("total_messages", number(item, "user_messages") + number(item, "assistant_messages"));
        Map<String, Object> token = tokens.summary(start.toString(), end.toString(), null, null);
        Map<String, Object> result = new LinkedHashMap<>(); result.put("start_date", start.toString()); result.put("end_date", end.toString()); result.put("total_active_sessions", sessions.size()); result.put("total_messages", users + assistants); result.put("total_user_messages", users); result.put("total_assistant_messages", assistants); result.put("total_tool_calls", tools); result.put("total_prompt_tokens", token.get("total_prompt_tokens")); result.put("total_completion_tokens", token.get("total_completion_tokens")); result.put("total_llm_calls", token.get("total_calls")); result.put("by_date", new ArrayList<>(days.values())); result.put("channel_stats", new ArrayList<>(channels.values())); return result;
    }
    private static Map<String, Object> day(LocalDate value) { Map<String, Object> result = new LinkedHashMap<>(); result.put("date", value.toString()); for (String key : List.of("chats", "user_messages", "assistant_messages", "tool_calls", "active_sessions", "total_messages")) result.put(key, 0); return result; }
    private static Map<String, Object> channel(String name) { Map<String, Object> result = new LinkedHashMap<>(); result.put("channel", name); for (String key : List.of("session_count", "user_messages", "assistant_messages", "total_messages")) result.put(key, 0); return result; }
    private static void increment(Map<String, Object> value, String key) { value.put(key, number(value, key) + 1); }
    private static int number(Map<String, Object> value, String key) { return ((Number) value.getOrDefault(key, 0)).intValue(); }
    private static LocalDate parse(String value, LocalDate fallback) { return value == null || value.isBlank() ? fallback : LocalDate.parse(value); }
}
