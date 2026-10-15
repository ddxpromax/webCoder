package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Majo current-time and user-timezone tools. */
public class TimeTools {
    private static final DateTimeFormatter FORMAT =
        DateTimeFormatter.ofPattern(
            "yyyy-MM-dd HH:mm:ss z (EEEE)"
        );
    private final AgentStore agents;

    public TimeTools(AgentStore agents) {
        this.agents = agents;
    }

    @Tool(
        name = "get_current_time",
        description = "Get the current date and time in an IANA timezone",
        readOnly = true,
        concurrencySafe = true
    )
    public String getCurrentTime(
        @ToolParam(
            name = "timezone",
            description = "Optional IANA timezone, such as Asia/Shanghai",
            required = false
        ) String timezone
    ) {
        return ZonedDateTime.now(resolveZone(timezone))
            .format(FORMAT);
    }

    @Tool(
        name = "set_user_timezone",
        description = "Save the user's IANA timezone"
    )
    public String setUserTimezone(
        @ToolParam(
            name = "timezone",
            description = "IANA timezone name"
        ) String timezone
    ) {
        if (timezone == null || timezone.isBlank()) {
            return "timezone must not be empty";
        }
        try {
            ZoneId.of(timezone);
        } catch (Exception exception) {
            return "Invalid timezone '" + timezone
                + "'. Use an IANA name such as Asia/Shanghai.";
        }
        agents.updateRoot("user_timezone", timezone);
        return "User timezone set to " + timezone + ".";
    }

    ZoneId resolveZone(String explicit) {
        ZoneId chosen = parseZone(explicit);
        if (chosen != null) {
            return chosen;
        }
        Object saved = agents.loadConfig()
            .get("user_timezone");
        chosen = parseZone(saved == null ? null
            : String.valueOf(saved));
        return chosen == null ? ZoneId.systemDefault() : chosen;
    }

    private static ZoneId parseZone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(value);
        } catch (Exception ignored) {
            return null;
        }
    }
}
