package io.github.ddxpromax.webcoder.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Majo cron payload validation and five-field crontab normalization. */
public final class CronModels {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> DOW = Map.of("0", "SUN", "1", "MON", "2", "TUE", "3", "WED", "4", "THU", "5", "FRI", "6", "SAT", "7", "SUN");
    private CronModels() {}

    public static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }
    public static String string(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
    public static boolean bool(Object value, boolean fallback) { return value instanceof Boolean b ? b : fallback; }
    public static int integer(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
    public static Map<String, Object> schedule(Map<String, Object> spec) { return map(spec.get("schedule")); }
    public static Map<String, Object> dispatch(Map<String, Object> spec) { return map(spec.get("dispatch")); }
    public static Map<String, Object> runtime(Map<String, Object> spec) { return map(spec.get("runtime")); }

    public static String normalizeCron(String expression) {
        String[] fields = expression == null ? new String[0] : expression.trim().split("\\s+");
        if (fields.length == 3) fields = new String[] {"0", "0", fields[0], fields[1], fields[2]};
        if (fields.length == 4) fields = new String[] {"0", fields[0], fields[1], fields[2], fields[3]};
        if (fields.length != 5) throw new IllegalArgumentException("cron must have 5 fields (or 4/3 fields that can be normalized); seconds not supported");
        fields[4] = normalizeDow(fields[4]);
        return String.join(" ", fields);
    }

    private static String normalizeDow(String value) {
        List<String> groups = new ArrayList<>();
        for (String group : value.split(",")) {
            String suffix = ""; String core = group;
            int slash = group.indexOf('/');
            if (slash >= 0) { core = group.substring(0, slash); suffix = group.substring(slash); }
            int dash = core.indexOf('-');
            groups.add(dash < 0 ? DOW.getOrDefault(core, core) + suffix : DOW.getOrDefault(core.substring(0, dash), core.substring(0, dash)) + "-" + DOW.getOrDefault(core.substring(dash + 1), core.substring(dash + 1)) + suffix);
        }
        return String.join(",", groups);
    }

    public static void validate(Map<String, Object> spec) {
        if (string(spec.get("name"), "").isBlank()) throw new IllegalArgumentException("name is required");
        Map<String, Object> schedule = schedule(spec);
        String type = string(schedule.get("type"), "cron"); schedule.put("type", type);
        if ("cron".equals(type)) {
            schedule.put("cron", normalizeCron(string(schedule.get("cron"), "")));
            schedule.put("run_at", null); schedule.put("repeat_every_days", null); schedule.put("repeat_end_type", null); schedule.put("repeat_until", null); schedule.put("repeat_count", null);
        } else if ("once".equals(type)) {
            String runAt = string(schedule.get("run_at"), "");
            if (parseInstant(runAt) == null) throw new IllegalArgumentException("schedule.type is once but run_at is missing or invalid");
            schedule.put("cron", null);
            Object repeat = schedule.get("repeat_every_days");
            if (repeat != null && integer(repeat, 0) <= 0) throw new IllegalArgumentException("repeat_every_days must be positive");
            if (repeat != null) validateRepeat(schedule, runAt);
        } else throw new IllegalArgumentException("schedule.type must be 'cron' or 'once'");
        schedule.putIfAbsent("timezone", "UTC"); spec.put("schedule", schedule);

        Map<String, Object> dispatch = dispatch(spec); dispatch.putIfAbsent("type", "channel"); dispatch.putIfAbsent("channel", "console"); dispatch.putIfAbsent("mode", "stream"); dispatch.putIfAbsent("silent", false); dispatch.putIfAbsent("meta", new LinkedHashMap<>());
        Map<String, Object> target = map(dispatch.get("target")); target.putIfAbsent("user_id", ""); target.putIfAbsent("session_id", ""); dispatch.put("target", target); spec.put("dispatch", dispatch);
        Map<String, Object> runtime = runtime(spec); runtime.putIfAbsent("max_concurrency", 1); runtime.putIfAbsent("timeout_seconds", 120); runtime.putIfAbsent("misfire_grace_seconds", 600); runtime.putIfAbsent("share_session", true); runtime.putIfAbsent("tool_safety", false); spec.put("runtime", runtime);
        String taskType = string(spec.get("task_type"), "agent"); spec.put("task_type", taskType);
        if ("text".equals(taskType)) { if (string(spec.get("text"), "").isBlank()) throw new IllegalArgumentException("task_type is text but text is empty"); if (bool(dispatch.get("silent"), false)) throw new IllegalArgumentException("silent delivery is only supported for agent tasks"); spec.put("request", null); }
        else if ("agent".equals(taskType)) { Map<String, Object> request = map(spec.get("request")); if (request.isEmpty()) throw new IllegalArgumentException("task_type is agent but request is missing"); request.put("user_id", target.get("user_id")); request.put("session_id", target.get("session_id")); spec.put("request", request); }
        else throw new IllegalArgumentException("task_type must be 'text' or 'agent'");
        spec.putIfAbsent("enabled", true); spec.putIfAbsent("meta", new LinkedHashMap<>()); spec.putIfAbsent("save_result_to_inbox", !("text".equals(taskType) && "cron".equals(type)));
    }

    private static void validateRepeat(Map<String, Object> schedule, String runAt) {
        String end = string(schedule.get("repeat_end_type"), "never"); schedule.put("repeat_end_type", end);
        if ("until".equals(end)) { if (parseInstant(string(schedule.get("repeat_until"), "")) == null || parseInstant(string(schedule.get("repeat_until"), "")).compareTo(parseInstant(runAt)) <= 0) throw new IllegalArgumentException("repeat_until must be later than run_at (deadline must be after execution time)"); schedule.put("repeat_count", null); }
        else if ("count".equals(end)) { if (integer(schedule.get("repeat_count"), 0) < 1) throw new IllegalArgumentException("repeat_end_type is count but repeat_count is missing"); schedule.put("repeat_until", null); }
        else if ("never".equals(end)) { schedule.put("repeat_until", null); schedule.put("repeat_count", null); }
        else throw new IllegalArgumentException("invalid repeat_end_type: " + end);
    }

    public static Instant parseInstant(String value) { try { return Instant.parse(value); } catch (Exception ignored) {} try { return OffsetDateTime.parse(value).toInstant(); } catch (Exception ignored) {} return null; }
    public static String formatInstant(Instant instant, String zone) { try { return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.ofInstant(instant, ZoneId.of(zone))); } catch (Exception ignored) { return DateTimeFormatter.ISO_INSTANT.format(instant); } }
    @SuppressWarnings("unchecked") public static Map<String, Object> copy(Map<String, Object> source) { try { return MAPPER.convertValue(MAPPER.valueToTree(source), Map.class); } catch (Exception e) { return new LinkedHashMap<>(source); } }
}
