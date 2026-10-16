package io.github.ddxpromax.webcoder.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Majo-compatible local backup signing.  An archive is authenticated with an
 * installation-local HMAC key; a signature from a different installation must
 * be explicitly trusted as a foreign backup before it can be imported/restored.
 */
final class BackupArchiveTrust {
    private static final String PREFIX = "hmac-sha256-v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupArchiveTrust() {
    }

    static void signAndFinalize(
        Path unsignedArchive,
        Path target,
        Map<String, Object> metadata,
        ObjectMapper mapper,
        Path keyFile
    ) throws IOException {
        Map<String, Object> signed = new LinkedHashMap<>(metadata);
        signed.remove("signature");
        try {
            signed.put("signature", signature(unsignedArchive, signed, mapper, keyFile));
        } catch (Exception exception) {
            throw new IOException("Could not sign backup archive", exception);
        }
        rewriteMetadata(unsignedArchive, target, signed, mapper);
        Files.deleteIfExists(unsignedArchive);
    }

    static boolean verifies(
        Path archive,
        Map<String, Object> metadata,
        ObjectMapper mapper,
        Path keyFile
    ) {
        Object actual = metadata.get("signature");
        if (!(actual instanceof String value) || value.isBlank()) {
            return false;
        }
        try {
            return constantTimeEquals(
                signature(archive, metadata, mapper, keyFile), value
            );
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isZip(Path archive) {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            return zip.entries().hasMoreElements();
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String signature(
        Path archive,
        Map<String, Object> metadata,
        ObjectMapper mapper,
        Path keyFile
    ) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key(keyFile), "HmacSHA256"));
        mac.update("META\0".getBytes(StandardCharsets.UTF_8));
        mac.update(mapper.writeValueAsBytes(canonicalMetadata(metadata)));
        mac.update((byte) 0);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<? extends ZipEntry> entries = java.util.Collections.list(zip.entries());
            entries.sort(Comparator.comparing(ZipEntry::getName));
            for (ZipEntry entry : entries) {
                if (entry.isDirectory() || "meta.json".equals(entry.getName())) {
                    continue;
                }
                mac.update("ENTRY\0".getBytes(StandardCharsets.UTF_8));
                mac.update(entry.getName().getBytes(StandardCharsets.UTF_8));
                mac.update((byte) 0);
                long size = 0;
                try (InputStream input = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[8192];
                    for (int read; (read = input.read(buffer)) != -1;) {
                        size += read;
                        mac.update(buffer, 0, read);
                    }
                }
                mac.update((byte) 0);
                for (int index = 7; index >= 0; index--) {
                    mac.update((byte) (size >>> (index * 8)));
                }
                mac.update((byte) 0);
            }
        }
        return PREFIX + toHex(mac.doFinal());
    }

    private static Map<String, Object> canonicalMetadata(Map<String, Object> metadata) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("id", metadata.get("id"));
        canonical.put("name", metadata.get("name"));
        canonical.put("description", String.valueOf(metadata.getOrDefault("description", "")));
        canonical.put("created_at", metadata.get("created_at"));
        canonical.put("version", metadata.getOrDefault("version", "1"));
        Map<String, Object> scope = asMap(metadata.get("scope"));
        Map<String, Object> canonicalScope = new TreeMap<>();
        canonicalScope.put("include_agents", Boolean.TRUE.equals(scope.get("include_agents")));
        canonicalScope.put("include_global_config", Boolean.TRUE.equals(scope.get("include_global_config")));
        canonicalScope.put("include_secrets", Boolean.TRUE.equals(scope.get("include_secrets")));
        canonicalScope.put("include_skill_pool", Boolean.TRUE.equals(scope.get("include_skill_pool")));
        canonical.put("scope", canonicalScope);
        canonical.put("agent_count", metadata.getOrDefault("agent_count", 0));
        canonical.put("majo_version", metadata.getOrDefault("majo_version", ""));
        canonical.put("system_info", new TreeMap<>(asMap(metadata.get("system_info"))));
        canonical.put("accepted_via_trust", Boolean.TRUE.equals(metadata.get("accepted_via_trust")));
        return canonical;
    }

    private static void rewriteMetadata(
        Path source,
        Path target,
        Map<String, Object> metadata,
        ObjectMapper mapper
    ) throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            output.putNextEntry(new ZipEntry("meta.json"));
            output.write(mapper.writeValueAsBytes(metadata));
            output.closeEntry();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if ("meta.json".equals(entry.getName())) {
                    continue;
                }
                output.putNextEntry(new ZipEntry(entry.getName()));
                if (!entry.isDirectory()) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        input.transferTo(output);
                    }
                }
                output.closeEntry();
            }
        }
    }

    private static synchronized byte[] key(Path keyFile) throws IOException {
        if (Files.isRegularFile(keyFile)) {
            return Files.readAllBytes(keyFile);
        }
        Files.createDirectories(keyFile.getParent());
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        try {
            Files.write(keyFile, value);
        } catch (IOException exception) {
            if (Files.isRegularFile(keyFile)) {
                return Files.readAllBytes(keyFile);
            }
            throw exception;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : Map.of();
    }

    private static boolean constantTimeEquals(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) {
            return false;
        }
        int difference = 0;
        for (int index = 0; index < a.length; index++) {
            difference |= a[index] ^ b[index];
        }
        return difference == 0;
    }

    private static String toHex(byte[] value) {
        StringBuilder output = new StringBuilder(value.length * 2);
        for (byte item : value) {
            output.append(Character.forDigit((item >>> 4) & 0xf, 16));
            output.append(Character.forDigit(item & 0xf, 16));
        }
        return output.toString();
    }
}
