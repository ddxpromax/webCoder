package io.github.ddxpromax.webcoder.access;

import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Persisted Majo channel ACL: mutually-exclusive allow, deny, and pending states. */
@Component
public class AccessControlStore {
    private final JsonFileStore files;
    private final java.nio.file.Path path;
    private final Object lock = new Object();
    private Map<String, Object> data;

    public AccessControlStore(MajoWorkingDirectory working, JsonFileStore files) {
        this.files = files;
        this.path = working.resolve("access_control.json");
        this.data = files.read(path, Map.of());
    }

    public boolean isWhitelisted(String channel, String userId) {
        synchronized (lock) { return users(acl(channel), "whitelist").containsKey(userId); }
    }
    public boolean isBlacklisted(String channel, String userId) {
        synchronized (lock) { return users(acl(channel), "blacklist").containsKey(userId); }
    }
    public boolean hasData(String channel) {
        synchronized (lock) {
            if (!data.containsKey(channel)) return false;
            Map<String, Object> acl = acl(channel);
            return !users(acl, "whitelist").isEmpty() || !users(acl, "blacklist").isEmpty() || !pending(acl).isEmpty();
        }
    }
    public Map<String, Object> getAcl(String channel) {
        synchronized (lock) { return copyAcl(acl(channel)); }
    }
    public Map<String, Object> getAll() {
        synchronized (lock) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (String channel : data.keySet()) if (hasData(channel)) result.put(channel, copyAcl(acl(channel)));
            return result;
        }
    }
    public List<Map<String, Object>> allPending() {
        synchronized (lock) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object value : data.values()) if (value instanceof Map<?, ?> raw) {
                for (Map<String, Object> item : pending(map(raw))) result.add(new LinkedHashMap<>(item));
            }
            result.sort((left, right) -> Double.compare(number(right.get("timestamp")), number(left.get("timestamp"))));
            return result;
        }
    }
    public void addToWhitelist(String channel, String userId, String remark, String username) {
        synchronized (lock) { put(channel, userId, remark, username, "whitelist"); }
    }
    public void addToBlacklist(String channel, String userId, String remark, String username) {
        synchronized (lock) { put(channel, userId, remark, username, "blacklist"); }
    }
    public void removeFromWhitelist(String channel, String userId) {
        synchronized (lock) { users(acl(channel), "whitelist").remove(userId); save(); }
    }
    public void removeFromBlacklist(String channel, String userId) {
        synchronized (lock) { users(acl(channel), "blacklist").remove(userId); save(); }
    }
    public void addPending(String channel, String userId, String message, String username) {
        synchronized (lock) {
            Map<String, Object> acl = acl(channel);
            if (users(acl, "whitelist").containsKey(userId) || users(acl, "blacklist").containsKey(userId)
                || pending(acl).stream().anyMatch(item -> userId.equals(text(item.get("user_id"))))) return;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("user_id", userId); item.put("channel", channel); item.put("timestamp", Instant.now().toEpochMilli() / 1000.0);
            item.put("first_message", text(message).substring(0, Math.min(200, text(message).length())));
            item.put("remark", ""); item.put("username", text(username));
            pending(acl).add(item); save();
        }
    }
    public boolean approvePending(String channel, String userId, String remark) {
        synchronized (lock) { return resolvePending(channel, userId, remark, "whitelist"); }
    }
    public boolean denyPending(String channel, String userId, String remark) {
        synchronized (lock) { return resolvePending(channel, userId, remark, "blacklist"); }
    }
    public boolean dismissPending(String channel, String userId) {
        synchronized (lock) { boolean removed = pending(acl(channel)).removeIf(item -> userId.equals(text(item.get("user_id")))); if (removed) save(); return removed; }
    }
    public boolean updatePendingRemark(String channel, String userId, String remark) {
        synchronized (lock) {
            for (Map<String, Object> item : pending(acl(channel))) if (userId.equals(text(item.get("user_id")))) {
                item.put("remark", text(remark)); save(); return true;
            }
        }
        return false;
    }
    public boolean updateRemark(String channel, String userId, String remark) { return updateUser(channel, userId, "remark", remark); }
    public boolean updateUsername(String channel, String userId, String username) {
        boolean changed = updateUser(channel, userId, "username", username);
        synchronized (lock) {
            for (Map<String, Object> item : pending(acl(channel))) if (userId.equals(text(item.get("user_id")))) {
                item.put("username", text(username)); changed = true;
            }
        }
        if (changed) synchronized (lock) { save(); }
        return changed;
    }

    private boolean resolvePending(String channel, String userId, String remark, String target) {
        Map<String, Object> acl = acl(channel);
        Map<String, Object> found = null;
        for (Map<String, Object> item : pending(acl)) if (userId.equals(text(item.get("user_id")))) { found = item; break; }
        String actualRemark = text(remark).isBlank() && found != null ? text(found.get("remark")) : text(remark);
        String username = found == null ? "" : text(found.get("username"));
        pending(acl).removeIf(item -> userId.equals(text(item.get("user_id"))));
        users(acl, target).put(userId, user(actualRemark, username));
        users(acl, target.equals("whitelist") ? "blacklist" : "whitelist").remove(userId);
        save(); return true;
    }
    private void put(String channel, String userId, String remark, String username, String target) {
        Map<String, Object> acl = acl(channel); Map<String, Object> existing = map(users(acl, target).get(userId));
        users(acl, target).put(userId, user(text(remark).isBlank() ? text(existing.get("remark")) : remark,
            text(username).isBlank() ? text(existing.get("username")) : username));
        users(acl, target.equals("whitelist") ? "blacklist" : "whitelist").remove(userId);
        pending(acl).removeIf(item -> userId.equals(text(item.get("user_id")))); save();
    }
    private boolean updateUser(String channel, String userId, String field, String value) {
        synchronized (lock) {
            boolean changed = false;
            for (String list : List.of("whitelist", "blacklist")) if (users(acl(channel), list).containsKey(userId)) {
                Map<String, Object> item = map(users(acl(channel), list).get(userId)); item.put(field, text(value)); users(acl(channel), list).put(userId, item); changed = true;
            }
            if (changed) save(); return changed;
        }
    }
    private Map<String, Object> acl(String channel) {
        Map<String, Object> result = map(data.get(channel));
        result.put("whitelist", new LinkedHashMap<>(users(result, "whitelist")));
        result.put("blacklist", new LinkedHashMap<>(users(result, "blacklist")));
        result.put("pending", new ArrayList<>(pending(result)));
        data.put(channel, result); return result;
    }
    @SuppressWarnings("unchecked") private Map<String, Object> users(Map<String, Object> acl, String key) { Object value = acl.get(key); Map<String, Object> result = value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>(); acl.put(key, result); return result; }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> pending(Map<String, Object> acl) { Object value = acl.get("pending"); List<Map<String, Object>> result = new ArrayList<>(); if (value instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> map) result.add(map(map)); acl.put("pending", result); return result; }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>(); }
    private Map<String, Object> user(String remark, String username) { return new LinkedHashMap<>(Map.of("remark", text(remark), "username", text(username))); }
    private Map<String, Object> copyAcl(Map<String, Object> source) { return new LinkedHashMap<>(Map.of("whitelist", users(source, "whitelist"), "blacklist", users(source, "blacklist"), "pending", pending(source))); }
    private void save() { files.writeAtomic(path, new LinkedHashMap<>(data)); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private double number(Object value) { return value instanceof Number number ? number.doubleValue() : 0; }
}
